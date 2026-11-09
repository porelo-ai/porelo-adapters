package org.fuin.porelo.planning.contract;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** A loopback smart HTTP forge backed by Git, with per-request credentials and failure controls. */
public final class GitHttpServer implements AutoCloseable {

    /**
     * One arrival, including requests refused before Git runs. Tokens are omitted from diagnostics.
     *
     * @param repository Bare repository name, or an empty string for an invalid path.
     * @param method HTTP method.
     * @param path Raw path and query.
     * @param token Basic password, or null when no single valid Basic credential was supplied.
     * @param authorizationPresent Whether any Authorization header arrived, even if malformed.
     * @param accepted Whether the token was accepted at arrival.
     */
    public record Request(
            String repository,
            String method,
            String path,
            @Nullable String token,
            boolean authorizationPresent,
            boolean accepted) {
        /** Returns a diagnostic that cannot disclose the credential. */
        @Override
        public String toString() {
            return method
                    + " "
                    + path
                    + " (authorization="
                    + authorizationPresent
                    + ", accepted="
                    + accepted
                    + ")";
        }
    }

    private record Credential(String user, String token) {}

    private record CgiHeaders(int status, Map<String, List<String>> values) {}

    private final Path root;
    private final Path scratch;
    private final Path backend;
    private final GitProcesses processes;
    private final HttpServer server;
    private final String host;
    private final boolean redirectTarget;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
    private volatile Set<String> tokens = Set.of();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final Set<String> refusedPushes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pushesUntilRefusal = new AtomicInteger(-1);
    private final Set<String> stalled = ConcurrentHashMap.newKeySet();
    private final Map<String, URI> redirects = new ConcurrentHashMap<>();
    private final Set<HttpExchange> exchanges = new HashSet<>();
    private final CountDownLatch closing = new CountDownLatch(1);
    private boolean closed;

    /**
     * Starts an authenticated forge on 127.0.0.1 and an ephemeral port, refusing every token
     * initially.
     *
     * @param root Directory containing only fixture bare repositories and server scratch files.
     * @throws IOException If Git's HTTP backend or the listener cannot be started.
     */
    public GitHttpServer(final Path root) throws IOException {
        this(root, "127.0.0.1", false);
    }

    private GitHttpServer(final Path directory, final String host, final boolean redirectTarget)
            throws IOException {
        root = Files.createDirectories(directory).toRealPath();
        scratch = Files.createTempDirectory(root, ".http-");
        processes = new GitProcesses(Files.createDirectory(scratch.resolve("home")));
        this.host = host;
        this.redirectTarget = redirectTarget;
        try {
            final String execPath = processes.git(scratch, "--exec-path").strip();
            backend = Path.of(execPath).resolve("git-http-backend");
            if (!backend.isAbsolute()
                    || !Files.isRegularFile(backend)
                    || !Files.isExecutable(backend)) {
                throw new IOException(
                        "Expected executable git-http-backend under git --exec-path; got "
                                + backend);
            }
            server = HttpServer.create(new InetSocketAddress(host, 0), 0);
            server.setExecutor(workers);
            server.createContext("/", this::serve);
            server.start();
        } catch (IOException | RuntimeException ex) {
            workers.shutdownNow();
            timeouts.shutdownNow();
            try {
                processes.close();
                FixturePaths.delete(scratch);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    /**
     * Starts a recorder on the other loopback host, 127.0.0.2.
     *
     * @param root Its own fixture directory.
     * @return A server that records every request and answers 403 without challenging for
     *     credentials.
     * @throws IOException If the backend or listener cannot be started.
     */
    public static GitHttpServer redirectTarget(final Path root) throws IOException {
        // A challenge here would measure a new login rather than credentials carried by a redirect.
        return new GitHttpServer(root, "127.0.0.2", true);
    }

    /** {@return the directory in which fixture bare repositories are served} */
    public Path root() {
        return root;
    }

    /** {@return this server's HTTP origin, with no credential} */
    public URI origin() {
        return URI.create("http://" + host + ":" + server.getAddress().getPort());
    }

    /**
     * Returns a repository's credential-free clone URL.
     *
     * @param repository Simple bare repository name ending in .git.
     * @return Its loopback URL.
     */
    public URI uri(final String repository) {
        return origin().resolve("/" + FixturePaths.repository(repository));
    }

    /**
     * Replaces the set of accepted tokens atomically for subsequent requests.
     *
     * @param accepted Passwords accepted by Basic authentication; none may be empty.
     */
    public void accept(final String... accepted) {
        final Set<String> replacements = new HashSet<>();
        for (final String token : accepted) {
            if (token.isEmpty() || token.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "Expected nonempty tokens without control characters");
            }
            replacements.add(token);
        }
        tokens = Set.copyOf(replacements);
    }

    /** Refuses every credential on subsequent requests. */
    public void refuseAll() {
        tokens = Set.of();
    }

    /**
     * Refuses receive-pack POSTs while allowing discovery and reads.
     *
     * @param repository Bare repository whose pushes must fail with HTTP 403.
     */
    public void refusePushes(final String repository) {
        refusedPushes.add(FixturePaths.repository(repository));
    }

    /**
     * Refuses the second subsequent receive-pack POST and later pushes to that repository. The
     * first repository remains writable so an adapter can undo its first publication.
     */
    public void refuseSecondPush() {
        if (!pushesUntilRefusal.compareAndSet(-1, 2))
            throw new IllegalStateException(
                    "Expected a fresh second-push control; got an already enabled control");
    }

    /**
     * Holds authenticated requests without a response until the server closes.
     *
     * @param repository Bare repository to stall.
     */
    public void stall(final String repository) {
        stalled.add(FixturePaths.repository(repository));
    }

    /**
     * Redirects every authenticated request for a repository to the other loopback host.
     *
     * @param repository Bare repository to redirect.
     * @param target Recorder created by {@link #redirectTarget(Path)}.
     */
    public void redirect(final String repository, final GitHttpServer target) {
        if (!target.redirectTarget || !target.host.equals("127.0.0.2")) {
            throw new IllegalArgumentException(
                    "Expected a redirect recorder on 127.0.0.2; got " + target.origin());
        }
        redirects.put(FixturePaths.repository(repository), target.origin());
    }

    /** {@return an immutable arrival-ordered snapshot, including unauthenticated requests} */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /**
     * Refuses to let a backend or CGI failure disappear inside an HTTP handler.
     *
     * @throws IOException If a handler encountered an unexpected backend failure.
     */
    public void verify() throws IOException {
        if (!failures.isEmpty())
            throw new IOException("Expected successful Git CGI handling; got " + failures);
    }

    private void serve(final HttpExchange exchange) throws IOException {
        synchronized (this) {
            if (closed) {
                exchange.close();
                return;
            }
            exchanges.add(exchange);
        }
        try (exchange) {
            final List<String> authorization =
                    exchange.getRequestHeaders().getOrDefault("Authorization", List.of());
            final Credential credential = credential(authorization);
            final boolean accepted = credential != null && tokens.contains(credential.token());
            final String repository = repository(exchange.getRequestURI().getRawPath());
            requests.add(
                    new Request(
                            repository,
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().toASCIIString(),
                            credential == null ? null : credential.token(),
                            !authorization.isEmpty(),
                            accepted));
            if (redirectTarget) {
                exchange.sendResponseHeaders(403, -1);
            } else if (!accepted || credential == null) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"contract\"");
                exchange.sendResponseHeaders(401, -1);
            } else if (repository.isEmpty()
                    || !Files.isDirectory(root.resolve(repository))
                    || Files.isSymbolicLink(root.resolve(repository))) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                authenticated(exchange, repository, credential);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while serving a Git request", ex);
        } finally {
            synchronized (this) {
                exchanges.remove(exchange);
            }
        }
    }

    private void authenticated(
            final HttpExchange exchange, final String repository, final Credential credential)
            throws IOException, InterruptedException {
        final boolean push =
                exchange.getRequestMethod().equals("POST")
                        && exchange.getRequestURI().getRawPath().endsWith("/git-receive-pack");
        if (push && pushesUntilRefusal.getAndUpdate(left -> left > 0 ? left - 1 : left) == 1)
            refusedPushes.add(repository);
        final URI redirect = redirects.get(repository);
        if (redirect != null) {
            exchange.getResponseHeaders()
                    .set("Location", redirect + exchange.getRequestURI().toASCIIString());
            exchange.sendResponseHeaders(307, -1);
        } else if (stalled.contains(repository)) {
            closing.await();
        } else if (refusedPushes.contains(repository) && push) {
            exchange.sendResponseHeaders(403, -1);
        } else {
            backend(exchange, credential);
        }
    }

    private void backend(final HttpExchange exchange, final Credential credential)
            throws IOException, InterruptedException {
        final ProcessBuilder builder =
                new ProcessBuilder(backend.toString()).directory(scratch.toFile());
        GitProcesses.isolate(builder, scratch.resolve("home"));
        final Map<String, String> environment = builder.environment();
        environment.put("GIT_PROJECT_ROOT", root.toString());
        environment.put("GIT_HTTP_EXPORT_ALL", "1");
        environment.put("PATH_INFO", exchange.getRequestURI().getRawPath());
        environment.put("REQUEST_METHOD", exchange.getRequestMethod());
        environment.put(
                "QUERY_STRING",
                exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : exchange.getRequestURI().getRawQuery());
        for (final String name :
                List.of("Content-Type", "Content-Length", "Content-Encoding", "Git-Protocol")) {
            final String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null)
                environment.put(
                        switch (name) {
                            case "Content-Type" -> "CONTENT_TYPE";
                            case "Content-Length" -> "CONTENT_LENGTH";
                            case "Content-Encoding" -> "HTTP_CONTENT_ENCODING";
                            default -> "HTTP_GIT_PROTOCOL";
                        },
                        value);
        }
        environment.put("REMOTE_USER", credential.user());
        environment.put("REMOTE_ADDR", exchange.getRemoteAddress().getAddress().getHostAddress());
        final Process process = processes.start(builder);
        final Future<?> deadline =
                timeouts.schedule(
                        () -> {
                            failures.add("Git HTTP backend exceeded " + GitProcesses.TIMEOUT);
                            try {
                                processes.terminate(process);
                            } catch (IOException ex) {
                                failures.add(
                                        "Git backend timeout cleanup failed: " + ex.getMessage());
                            } finally {
                                exchange.close();
                            }
                        },
                        GitProcesses.TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS);
        final Future<Long> input =
                workers.submit(
                        () -> {
                            try (InputStream body = exchange.getRequestBody();
                                    OutputStream stdin = process.getOutputStream()) {
                                return body.transferTo(stdin);
                            }
                        });
        final Future<byte[]> errors = workers.submit(() -> process.getErrorStream().readAllBytes());
        try (InputStream stdout = process.getInputStream()) {
            final CgiHeaders headers = headers(stdout);
            headers.values()
                    .forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
            exchange.sendResponseHeaders(headers.status(), 0);
            stdout.transferTo(exchange.getResponseBody());
            await(input);
            if (!process.waitFor(GitProcesses.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Expected Git HTTP backend to exit; got a timeout");
            }
            final String error = new String(await(errors), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException(
                        "Expected Git HTTP backend exit 0; got "
                                + process.exitValue()
                                + ": "
                                + error);
            }
        } catch (IOException ex) {
            if (closing.getCount() != 0)
                failures.add(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage());
            throw ex;
        } finally {
            deadline.cancel(false);
            try {
                processes.terminate(process);
                processes.finished(process);
            } finally {
                input.cancel(true);
                errors.cancel(true);
            }
        }
    }

    private static <T> T await(final Future<T> result) throws IOException, InterruptedException {
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            throw new IOException(
                    "Expected completed CGI stream; got a failed stream", ex.getCause());
        } catch (TimeoutException ex) {
            throw new IOException(
                    "Expected completed CGI stream within five seconds; got a timeout", ex);
        }
    }

    private static CgiHeaders headers(final InputStream stdout) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() < 64 * 1024) {
            final int next = stdout.read();
            if (next == -1)
                throw new IOException("Expected CGI headers followed by a blank line; got EOF");
            bytes.write(next);
            if (next == '\n' && previous == '\n') break;
            if (next != '\r') previous = next;
        }
        if (bytes.size() == 64 * 1024)
            throw new IOException("Expected CGI headers below 64 KiB; got a larger header block");
        int status = 200;
        final Map<String, List<String>> values = new LinkedHashMap<>();
        for (final String line : bytes.toString(StandardCharsets.ISO_8859_1).split("\r?\n")) {
            if (line.isEmpty()) continue;
            final int colon = line.indexOf(':');
            if (colon < 1)
                throw new IOException("Expected a CGI header name and value; got " + line);
            final String name = line.substring(0, colon);
            final String value = line.substring(colon + 1).strip();
            if (!name.matches("[A-Za-z0-9-]+") || value.indexOf('\r') >= 0) {
                throw new IOException("Expected a valid CGI header; got " + name);
            }
            if (name.equalsIgnoreCase("Status")) {
                if (!value.matches("[1-5][0-9]{2}( .*)?"))
                    throw new IOException("Expected CGI Status code; got " + value);
                status = Integer.parseInt(value.substring(0, 3));
            } else if (!name.equalsIgnoreCase("Content-Length")
                    && !name.equalsIgnoreCase("Transfer-Encoding")
                    && !name.equalsIgnoreCase("Connection")) {
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        return new CgiHeaders(status, values);
    }

    private static String repository(final String path) {
        final int slash = path.indexOf('/', 1);
        final String name = path.substring(1, slash < 0 ? path.length() : slash);
        if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.git") || name.contains("..")) return "";
        final String suffix = slash < 0 ? "" : path.substring(slash);
        return Set.of("/info/refs", "/git-upload-pack", "/git-receive-pack").contains(suffix)
                ? name
                : "";
    }

    private static @Nullable Credential credential(final List<String> authorization) {
        if (authorization.size() != 1
                || !authorization.getFirst().regionMatches(true, 0, "Basic ", 0, 6)) return null;
        final String decoded;
        try {
            decoded =
                    new String(
                            Base64.getDecoder().decode(authorization.getFirst().substring(6)),
                            StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        final int colon = decoded.indexOf(':');
        if (colon < 1 || decoded.chars().anyMatch(Character::isISOControl)) return null;
        return new Credential(decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    /**
     * Stops arrivals, releases stalls, closes exchanges, and kills running Git backend processes.
     *
     * @throws IOException If processes, workers, or owned scratch files cannot be cleaned up.
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing.countDown();
            server.stop(0);
            exchanges.forEach(HttpExchange::close);
        }
        timeouts.shutdownNow();
        workers.shutdownNow();
        processes.close();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)
                    || !timeouts.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("Expected stopped Git HTTP workers; some remain running");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing Git HTTP workers", ex);
        }
        FixturePaths.delete(scratch);
    }
}
