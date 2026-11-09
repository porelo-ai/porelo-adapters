package org.fuin.porelo.planning.storiesgit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;

/** Used under the adapter monitor; processes have independent credentials and bounded lifetimes. */
final class GitStore implements AutoCloseable {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CLONE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(110);
    private static final long CLEANUP_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_OUTPUT = 64 * 1024;
    private static final String ASKPASS =
            """
            #!/bin/sh
            case "$1" in
              *Username*) printf '%s\\n' x-access-token ;;
              *Password*)
                IFS= read -r password < "$PORELO_GIT_CREDENTIAL_PIPE" || exit 1
                # Restore the line for a later prompt in this same Git call, without exporting it.
                printf '%s\\n' "$password" > "$PORELO_GIT_CREDENTIAL_PIPE" || exit 1
                printf '%s\\n' "$password" ;;

              *) exit 1 ;;
            esac
            """;
    private final Path directory;
    private final Path repositories;
    private final Path helper;
    private final Path executable;
    private final Duration timeout;
    private final Duration cloneTimeout;
    private final StoreLock lock;
    private final @org.jspecify.annotations.Nullable Path certificates;
    private final Map<String, Path> clones = new TreeMap<>();
    private long deadline;

    GitStore(Path directory) {
        this(directory, Path.of("/usr/bin/git"));
    }

    GitStore(Path directory, Path executable) {
        this(directory, executable, CALL_TIMEOUT, CLONE_TIMEOUT);
    }

    GitStore(Path directory, Path executable, Duration timeout) {
        this(directory, executable, timeout, timeout);
    }

    private GitStore(Path directory, Path executable, Duration timeout, Duration cloneTimeout) {
        this.directory = directory;
        this.executable = executable;
        this.timeout = timeout;
        this.cloneTimeout = cloneTimeout;
        repositories = directory.resolve("repositories");
        helper = directory.resolve("git-askpass");
        lock = new StoreLock(directory);
        try {
            for (final String child : List.of("repositories", "home", "tmp", "template")) {
                final Path path = directory.resolve(child);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                    Files.createDirectory(
                            path,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
                if (Files.isSymbolicLink(path)
                        || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().startsWith(directory))
                    throw Refusals.failed(
                            "Expected private store directory "
                                    + child
                                    + "; got a link or non-directory");
            }
            sweep(directory.resolve("tmp"), "credential-*");
            sweep(repositories, "*.pending-*");
            sweep(directory, "planning.pending-*");
            sweep(directory.resolve("tmp"), "askpass-*");
            sweep(directory.resolve("tmp"), "certificates-*");
            certificates = GitCertificates.prepare(directory, System.getenv());
            if (!Files.exists(helper, LinkOption.NOFOLLOW_LINKS)) {
                final Path pending =
                        Files.createTempFile(
                                directory.resolve("tmp"),
                                "askpass-",
                                ".sh",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")));
                try {
                    Files.writeString(pending, ASKPASS, StandardCharsets.UTF_8);
                    Files.move(pending, helper, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(pending);
                }
            }
            if (!new SafeFiles(directory).read("git-askpass").equals(ASKPASS))
                throw Refusals.failed(
                        "Expected credential-free askpass helper; got unexpected helper text");
            Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException ex) {
            lock.close();
            throw Refusals.failed(
                    "Expected writable adapter storage; got a directory or helper creation"
                            + " failure");
        } catch (RuntimeException ex) {
            lock.close();
            throw ex;
        }
    }

    void begin() {
        deadline = System.nanoTime() + SNAPSHOT_TIMEOUT.toNanos();
        clones.clear();
    }

    void checkDeadline() {
        if (System.nanoTime() - deadline >= 0)
            throw Refusals.store(
                    Reason.UNREACHABLE,
                    "Expected the Git snapshot within 110 seconds; got a timeout");
    }

    Path planning(String url, String token) {
        return cloneAt("planning", directory.resolve("planning"), url, token);
    }

    Path clone(String key, String url, String token) {
        Settings.repositoryKey(key);
        return cloneAt(
                "repositories/" + key, repositories.resolve(key + ".git-or-worktree"), url, token);
    }

    private Path cloneAt(String key, Path clone, String url, String token) {
        final Path parent = clone.getParent();
        if (parent == null)
            throw Refusals.failed("Expected a clone inside adapter storage; got " + key);
        if (!Files.exists(clone, LinkOption.NOFOLLOW_LINKS)) {
            Path staging = null;
            try {
                staging = Files.createTempDirectory(parent, clone.getFileName() + ".pending-");
                git(
                        parent,
                        token,
                        "clone",
                        "--quiet",
                        "--depth=1",
                        "--no-recurse-submodules",
                        "--template=" + directory.resolve("template"),
                        "--",
                        url,
                        staging.toString());
                try {
                    Files.move(staging, clone, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException appeared) {
                    if (!Files.exists(clone, LinkOption.NOFOLLOW_LINKS)) throw appeared;
                }
            } catch (IOException ex) {
                throw Refusals.failed(
                        "Expected an isolated clone for "
                                + key
                                + "; got a clone installation failure");
            } finally {
                if (staging != null && Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
                    remove(staging);
            }
        }
        if (Files.isSymbolicLink(clone)
                || !Files.isDirectory(clone, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(clone.resolve(".git"))
                || !Files.isDirectory(clone.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
            throw Refusals.failed(
                    "Expected a regular owned clone for " + key + "; got an unsafe clone");
        final String origin = git(clone, "", "remote", "get-url", "origin").strip();
        if (!origin.equals(url))
            throw Refusals.invalid(
                    "Expected the configured repository for the stored clone; got another origin");
        clones.put(key, clone);
        return clone;
    }

    String revision() {
        final StringBuilder heads = new StringBuilder();
        for (final var clone : clones.entrySet()) {
            final String key = clone.getKey();
            final String head = git(clone.getValue(), "", "rev-parse", "--verify", "HEAD").strip();
            if (!head.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                throw Refusals.failed(
                        "Expected a full clone head for " + key + "; got another git answer");
            heads.append(key).append('=').append(head).append('\n');
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(heads.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    String git(Path work, String token, String... arguments) {
        final long remaining = deadline - System.nanoTime() - CLEANUP_NANOS;
        if (remaining <= 0)
            throw Refusals.store(
                    Reason.UNREACHABLE,
                    "Expected the Git snapshot within 110 seconds; got a timeout");
        final Duration bound =
                arguments.length > 0 && arguments[0].equals("clone") ? cloneTimeout : timeout;
        long budget = Math.min(remaining, bound.toNanos());
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "/usr/bin/timeout",
                                "--kill-after=1s",
                                seconds(budget),
                                executable.toString(),
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "core.fsmonitor=false",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "http.followRedirects=false",
                                "-c",
                                "protocol.allow=never",
                                "-c",
                                "protocol.http.allow=always",
                                "-c",
                                "protocol.https.allow=always",
                                "-c",
                                "maintenance.auto=false",
                                "-c",
                                "gc.auto=0"));
        if (certificates != null) command.addAll(List.of("-c", "http.sslCAInfo=" + certificates));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(
                Map.of(
                        "PATH",
                        "/usr/bin:/bin",
                        "HOME",
                        directory.resolve("home").toString(),
                        "TMPDIR",
                        directory.resolve("tmp").toString(),
                        "XDG_CONFIG_HOME",
                        directory.resolve("home").toString(),
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "GIT_TERMINAL_PROMPT",
                        "0",
                        "GIT_ASKPASS",
                        helper.toString(),
                        "LC_ALL",
                        "C"));
        Process process = null;
        try (CredentialPipe credential =
                token.isEmpty()
                        ? null
                        : new CredentialPipe(directory.resolve("tmp"), token, environment)) {
            if (credential != null)
                environment.put("PORELO_GIT_CREDENTIAL_PIPE", credential.path().toString());
            budget = Math.min(budget, deadline - System.nanoTime() - CLEANUP_NANOS);
            if (budget <= 0)
                throw Refusals.store(
                        Reason.UNREACHABLE,
                        "Expected the Git snapshot within 110 seconds; got a timeout");
            builder.command().set(2, seconds(budget));
            process = builder.start();
            process.getOutputStream().close();
            final Capture output = new Capture(process.getInputStream());
            final Capture error = new Capture(process.getErrorStream());
            final Thread stdout = Thread.ofVirtual().start(output);
            final Thread stderr = Thread.ofVirtual().start(error);
            try {
                if (!process.waitFor(budget + TimeUnit.SECONDS.toNanos(1), TimeUnit.NANOSECONDS))
                    throw Refusals.store(
                            Reason.UNREACHABLE,
                            "Expected Git to answer within "
                                    + TimeUnit.NANOSECONDS.toMillis(budget)
                                    + " milliseconds; got a timeout");
                stdout.join(1000);
                stderr.join(1000);
                if (stdout.isAlive() || stderr.isAlive() || output.failed || error.failed)
                    throw Refusals.failed(
                            "Expected complete Git output; got an unreadable or unfinished stream");
                if (process.exitValue() == 124 || process.exitValue() == 137)
                    throw Refusals.store(
                            Reason.UNREACHABLE, "Expected bounded Git completion; got a timeout");
                if (process.exitValue() != 0) throw gitFailure(error.text());
                if (output.overflow)
                    throw Refusals.failed(
                            "Expected bounded Git output; got more than " + MAX_OUTPUT + " bytes");
                return output.text();
            } finally {
                // Kill helpers too: a timed-out HTTP process must not outlive the call's token.
                stop(process);
                stdout.join(1000);
                stderr.join(1000);
            }
        } catch (IOException ex) {
            throw Refusals.failed(
                    "Expected /usr/bin/git to execute; got a process or stream failure");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw Refusals.store(
                    Reason.UNREACHABLE, "Expected Git to finish; got an interrupted call");
        } finally {
            if (process != null) stop(process);
            environment.clear();
        }
    }

    private static String seconds(long nanos) {
        return java.math.BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString() + "s";
    }

    private static org.fuin.porelo.planning.api.PlanningRefused gitFailure(String diagnostic) {
        // Remote output is used only for classification, never copied into an error or log.
        final String text =
                diagnostic.toLowerCase(Locale.ROOT).replaceAll("https?://[^\\s'\"]+", "[url]");
        if (text.contains("returned error: 429") || text.contains("too many requests"))
            return Refusals.store(
                    Reason.RATE_LIMITED, "Expected repository capacity; got too many requests");
        if (text.contains("authentication failed")
                || text.contains("returned error: 401")
                || text.contains("returned error: 403")
                || text.contains("could not read password"))
            return Refusals.store(
                    Reason.CREDENTIAL_REFUSED,
                    "Expected repository access with this call's credential; got an authentication"
                            + " refusal");
        if (text.contains("not found")
                || text.contains("returned error: 404")
                || text.contains("does not appear to be a git repository"))
            return Refusals.store(
                    Reason.NO_SUCH_STORE,
                    "Expected an accessible Git repository; got an absent or hidden store");
        if (text.contains("could not resolve")
                || text.contains("failed to connect")
                || text.contains("timed out")
                || text.contains("ssl")
                || text.contains("redirect")
                || text.contains("connection"))
            return Refusals.store(
                    Reason.UNREACHABLE,
                    "Expected a repository response; got a transport failure or refused redirect");
        return Refusals.failed("Expected Git to finish successfully; got a nonzero exit status");
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // The process is already being terminated; diagnostics are never logged.
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS))
                throw Refusals.failed(
                        "Expected terminated Git process; got a live process after forced"
                                + " shutdown");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void remove(Path staging) {
        try (var paths = Files.walk(staging)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        } catch (IOException ex) {
            throw Refusals.failed(
                    "Expected temporary clone cleanup; got leftover " + staging.getFileName());
        }
    }

    private static void sweep(Path parent, String pattern) throws IOException {
        try (var entries = Files.newDirectoryStream(parent, pattern)) {
            for (final Path entry : entries) remove(entry);
        }
    }

    @Override
    public void close() {
        lock.close();
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean failed;
        private boolean overflow;

        Capture(InputStream input) {
            this.input = input;
        }

        /** Drains output without allowing a remote to fill a pipe or retain unbounded text. */
        @Override
        public void run() {
            try (input) {
                final byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    final int accepted = Math.min(count, MAX_OUTPUT - bytes.size());
                    bytes.write(chunk, 0, accepted);
                    if (accepted < count) overflow = true;
                }
            } catch (IOException ex) {
                failed = true;
            }
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
