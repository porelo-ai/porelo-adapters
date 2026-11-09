package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class GitHttpServerTest {
    private static final String REPOSITORY = "stories.git";
    @TempDir Path directory;

    @Test
    void anAcceptedCredentialCanCloneAndPushRealCommits() throws Exception {
        // A fake transport success could hide an adapter that never fetches or publishes its
        // commit.
        final Path root = directory.resolve("forge");
        final String token = UUID.randomUUID().toString();
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitClient git = new GitClient(directory.resolve("client"))) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(token);
            final Path clone = directory.resolve("clone");

            git.run(directory, token, "clone", userUrl(server), clone.toString());
            assertThat(Files.readString(clone.resolve("story.md")))
                    .as("clone receives the remote's committed file")
                    .isEqualTo("Ready\n");
            Files.writeString(clone.resolve("story.md"), "Done\n");
            git.run(clone, null, "add", "story.md");
            git.run(clone, null, "commit", "-m", "Complete story");
            final String commit = git.run(clone, null, "rev-parse", "HEAD").strip();
            git.run(clone, token, "push", "origin", "HEAD:main");

            assertThat(repositories.head(REPOSITORY))
                    .as("push changes the bare remote's main ref")
                    .isEqualTo(commit);
            assertThat(repositories.file(REPOSITORY, "story.md"))
                    .as("push changes the remote's actual file")
                    .isEqualTo("Done\n");
            assertThat(server.requests())
                    .as("Git uses both real smart HTTP services")
                    .extracting(GitHttpServer.Request::path)
                    .anyMatch(path -> path.endsWith("/git-upload-pack"))
                    .anyMatch(path -> path.endsWith("/git-receive-pack"));
            assertThat(server.requests().stream().filter(GitHttpServer.Request::accepted).toList())
                    .as("every accepted Git request uses this call's credential")
                    .isNotEmpty()
                    .allMatch(request -> token.equals(request.token()));
            server.verify();
        }
    }

    // Harm: post-call scans miss Git helpers that exit before an RPC returns.
    @Test
    void samplesTheRequestingProcessTreeBeforeReplying() throws Exception {
        final String token = UUID.randomUUID().toString();
        final Path pid = directory.resolve("pid");
        Files.writeString(pid, Long.toString(ProcessHandle.current().pid()));
        final ProcessSamples samples = new ProcessSamples(pid);
        try (GitRepositories repositories = new GitRepositories(directory.resolve("forge"));
                GitHttpServer server = new GitHttpServer(directory.resolve("forge"));
                GitClient git = new GitClient(directory.resolve("client"))) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(token);
            server.observeProcesses(samples::sample);
            git.run(
                    directory,
                    token,
                    "clone",
                    userUrl(server),
                    directory.resolve("clone").toString());
            assertThatThrownBy(() -> samples.noSecret(token))
                    .as("in-flight sampling retains the ended Git client's credential environment")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("captured child environment");
            server.verify();
        }
    }

    // Harm: refusing a fixed repository can miss a partial write when an adapter pushes in reverse.
    @Test
    void theSecondPushIsRefusedRegardlessOfRepositoryOrder() throws Exception {
        final Path root = directory.resolve("forge");
        final String token = UUID.randomUUID().toString();
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitClient git = new GitClient(directory.resolve("client"))) {
            server.accept(token);
            final Map<String, String> original = new java.util.LinkedHashMap<>();
            for (final String name : List.of("one.git", "two.git")) {
                repositories.create(name, Map.of("issue.md", "Queued\n"));
                original.put(name, repositories.head(name));
                final Path clone = directory.resolve(name);
                git.run(
                        directory,
                        token,
                        "clone",
                        server.uri(name).toString().replace("http://", "http://fixture@"),
                        clone.toString());
                Files.writeString(clone.resolve("issue.md"), "Closed\n");
                git.run(clone, null, "add", "issue.md");
                git.run(clone, null, "commit", "-m", "Change issue state");
            }
            server.refuseSecondPush();
            git.run(directory.resolve("two.git"), token, "push", "origin", "HEAD:main");
            assertThatThrownBy(
                            () ->
                                    git.run(
                                            directory.resolve("one.git"),
                                            token,
                                            "push",
                                            "origin",
                                            "HEAD:main"))
                    .as("the second publication is refused even when one.git is pushed second")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("403");
            assertThat(repositories.head("one.git"))
                    .as("refused second remote keeps its original commit")
                    .isEqualTo(original.get("one.git"));
            assertThat(repositories.file("two.git", "issue.md"))
                    .as("the first push really changed its remote")
                    .isEqualTo("Closed\n");
            git.run(
                    directory.resolve("two.git"),
                    token,
                    "push",
                    "--force",
                    "origin",
                    original.get("two.git") + ":refs/heads/main");
            assertThat(repositories.head("two.git"))
                    .as("the failure control permits restoring the first remote")
                    .isEqualTo(original.get("two.git"));
            server.verify();
        }
    }

    @Test
    void missingAndRefusedCredentialsAreChallengedAndRecorded() throws Exception {
        // An unauthenticated or wrong-token request must not bypass the credential contract.
        final String accepted = UUID.randomUUID().toString();
        final String refused = UUID.randomUUID().toString();
        try (GitHttpServer server = new GitHttpServer(directory.resolve("forge"));
                HttpClient client = httpClient()) {
            server.accept(accepted);
            final HttpResponse<Void> missing = get(client, server, null);
            final HttpResponse<Void> wrong = get(client, server, refused);

            assertThat(missing.statusCode())
                    .as("a missing credential gets a Basic challenge")
                    .isEqualTo(401);
            assertThat(wrong.statusCode())
                    .as("a refused credential gets a Basic challenge")
                    .isEqualTo(401);
            assertThat(missing.headers().firstValue("WWW-Authenticate").orElse(""))
                    .as("a Git client can recognize the authentication scheme")
                    .startsWith("Basic ");
            assertThat(server.requests()).as("both refused requests remain observable").hasSize(2);
            assertThat(server.requests().getFirst().token())
                    .as("a missing credential is recorded as absent")
                    .isNull();
            assertThat(server.requests().getFirst().authorizationPresent())
                    .as("absence is distinct from an invalid header")
                    .isFalse();
            assertThat(server.requests().getLast().token())
                    .as("the refused password remains available for per-call checks")
                    .isEqualTo(refused);
            assertThat(server.requests())
                    .as("neither request was accepted")
                    .noneMatch(GitHttpServer.Request::accepted);
            assertThat(server.requests())
                    .as("repository, method, and path are recorded before refusal")
                    .allSatisfy(
                            request -> {
                                assertThat(request.repository())
                                        .as("the request names its bare repository")
                                        .isEqualTo(REPOSITORY);
                                assertThat(request.method())
                                        .as("discovery uses GET")
                                        .isEqualTo("GET");
                                assertThat(request.path())
                                        .as("the raw discovery query is retained")
                                        .isEqualTo(
                                                "/stories.git/info/refs?service=git-upload-pack");
                            });
        }
    }

    @Test
    void changingTheAcceptedTokenIsObservedOnTheNextRequest() throws Exception {
        // Reusing a previous call's credential must not make a later refused call succeed.
        final String first = UUID.randomUUID().toString();
        final String second = UUID.randomUUID().toString();
        final Path root = directory.resolve("forge");
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                HttpClient client = httpClient()) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(first);
            assertThat(get(client, server, first).statusCode())
                    .as("the first call's token is accepted")
                    .isEqualTo(200);
            assertThat(get(client, server, second).statusCode())
                    .as("a second call cannot borrow the first token")
                    .isEqualTo(401);
            server.refuseAll();
            assertThat(get(client, server, first).statusCode())
                    .as("revoking the first token takes effect immediately")
                    .isEqualTo(401);
            server.accept(second);
            assertThat(get(client, server, second).statusCode())
                    .as("replacement credentials can be accepted at runtime")
                    .isEqualTo(200);

            assertThat(server.requests())
                    .as("the four decisions retain each request's acceptance")
                    .extracting(GitHttpServer.Request::accepted)
                    .containsExactly(true, false, false, true);
            assertThat(server.requests().stream().map(GitHttpServer.Request::token).toList())
                    .as("each call is recorded with the credential it supplied")
                    .containsExactly(first, second, first, second);
            assertThat(server.requests().toString())
                    .as("routine request diagnostics omit credential values")
                    .doesNotContain(first, second);
            server.verify();
        }
    }

    @Test
    void malformedAndDuplicateAuthorizationCannotAuthorizeARequest() throws Exception {
        // Ambiguous authentication headers must not hide a second credential from the suite.
        final String token = UUID.randomUUID().toString();
        try (GitHttpServer server = new GitHttpServer(directory.resolve("forge"));
                HttpClient client = httpClient()) {
            server.accept(token);
            for (final List<String> headers :
                    List.of(List.of("Basic not-base64!"), List.of(basic(token), basic(token)))) {
                final HttpRequest.Builder builder =
                        HttpRequest.newBuilder(server.uri(REPOSITORY))
                                .timeout(Duration.ofSeconds(5));
                headers.forEach(value -> builder.header("Authorization", value));
                assertThat(
                                client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
                                        .statusCode())
                        .as("a malformed or duplicate credential is refused")
                        .isEqualTo(401);
            }
            assertThat(server.requests())
                    .as("invalid Authorization is recorded without inventing a password")
                    .hasSize(2)
                    .allMatch(GitHttpServer.Request::authorizationPresent)
                    .noneMatch(GitHttpServer.Request::accepted)
                    .allMatch(request -> request.token() == null);
        }
    }

    @Test
    void gitFollowsARedirectWithoutSendingAuthorizationToTheOtherHost() throws Exception {
        // A redirect must not lend the forge credential to a different host.
        final String token = UUID.randomUUID().toString();
        final Path root = directory.resolve("forge");
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitHttpServer other = GitHttpServer.redirectTarget(directory.resolve("other"));
                GitClient git = new GitClient(directory.resolve("client"))) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(token);
            server.redirect(REPOSITORY, other);

            assertThatThrownBy(() -> git.run(directory, token, "ls-remote", userUrl(server)))
                    .as("the redirect recorder refuses without asking Git for another login")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("403");
            assertThat(server.origin().getHost())
                    .as("the authenticated forge has its own host")
                    .isEqualTo("127.0.0.1");
            assertThat(other.origin().getHost())
                    .as("the target changes the host as well as the port")
                    .isEqualTo("127.0.0.2");
            assertThat(server.requests())
                    .as("Git authenticated before following the redirect")
                    .anyMatch(GitHttpServer.Request::accepted);
            assertThat(other.requests())
                    .as("Git actually followed the redirect to the other listener")
                    .hasSize(1)
                    .noneMatch(GitHttpServer.Request::authorizationPresent);
            assertThat(other.requests().getFirst().path())
                    .as("the redirect preserves Git's path and query")
                    .isEqualTo("/stories.git/info/refs?service=git-upload-pack");
        }
    }

    @Test
    void aStalledRepositoryTimesOutAndCanStillBeClosedPromptly() throws Exception {
        // A blocked forge must not hang Git or leave server workers alive after the fixture closes.
        final String token = UUID.randomUUID().toString();
        final Path root = directory.resolve("forge");
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitClient git = new GitClient(directory.resolve("client"))) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(token);
            server.stall(REPOSITORY);

            assertThatThrownBy(
                            () ->
                                    git.run(
                                            directory,
                                            token,
                                            "-c",
                                            "http.lowSpeedLimit=1",
                                            "-c",
                                            "http.lowSpeedTime=1",
                                            "ls-remote",
                                            userUrl(server)))
                    .as("Git's short low-speed timeout ends a stalled request")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Operation too slow");
            assertThat(server.requests())
                    .as("the timeout exercised an authenticated stalled request")
                    .anyMatch(GitHttpServer.Request::accepted);
            final CompletableFuture<Void> closed =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    server.close();
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            });
            closed.get(3, TimeUnit.SECONDS);
            assertThat(closed)
                    .as("close releases the stalled handler within a bounded wait")
                    .isCompleted();
        }
    }

    @Test
    void aRefusedPushLeavesTheRemoteCommitAndFileUnchanged() throws Exception {
        // A refusal must not accidentally publish the commit whose atomicity the suite checks.
        final String token = UUID.randomUUID().toString();
        final Path root = directory.resolve("forge");
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitClient git = new GitClient(directory.resolve("client"))) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            final String before = repositories.head(REPOSITORY);
            server.accept(token);
            final Path clone = directory.resolve("clone");
            git.run(directory, token, "clone", userUrl(server), clone.toString());
            Files.writeString(clone.resolve("story.md"), "Done\n");
            git.run(clone, null, "add", "story.md");
            git.run(clone, null, "commit", "-m", "Complete story");
            server.refusePushes(REPOSITORY);

            assertThatThrownBy(() -> git.run(clone, token, "push", "origin", "HEAD:main"))
                    .as("the actual receive-pack POST is refused")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("403");
            assertThat(repositories.head(REPOSITORY))
                    .as("a refused push cannot advance main")
                    .isEqualTo(before);
            assertThat(repositories.file(REPOSITORY, "story.md"))
                    .as("a refused push cannot alter the remote content")
                    .isEqualTo("Ready\n");
            assertThat(server.requests())
                    .as("refusal occurs after discovery, at the actual push")
                    .anyMatch(
                            request ->
                                    request.method().equals("POST")
                                            && request.path().endsWith("/git-receive-pack"));
            server.verify();
        }
    }

    // Harm: refusing a concurrent second push before the first CGI finishes races rollback.
    @Test
    void waitsForTheFirstBackendBeforeRefusingAConcurrentSecondPush() throws Exception {
        final Path root = directory.resolve("forge");
        final String token = UUID.randomUUID().toString();
        final Path started = directory.resolve("first-started");
        final Path release = directory.resolve("release-first");
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitClient git = new GitClient(directory.resolve("client"));
                var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            server.accept(token);
            for (final String name : List.of("one.git", "two.git")) {
                repositories.create(name, Map.of("issue.md", "Queued\n"));
                final Path clone = directory.resolve(name);
                git.run(
                        directory,
                        token,
                        "clone",
                        server.uri(name).toString().replace("http://", "http://fixture@"),
                        clone.toString());
                Files.writeString(clone.resolve("issue.md"), "Closed\n");
                git.run(clone, null, "add", "issue.md");
                git.run(clone, null, "commit", "-m", "Change issue state");
            }
            final Path hook =
                    Files.createDirectories(root.resolve("one.git/hooks")).resolve("pre-receive");
            Files.writeString(
                    hook,
                    "#!/bin/sh\ntouch "
                            + shellQuote(started)
                            + "\nwhile [ ! -e "
                            + shellQuote(release)
                            + " ]; do sleep 0.01; done\n");
            Files.setPosixFilePermissions(
                    hook, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            server.refuseSecondPush();
            final var first =
                    callers.submit(
                            () ->
                                    git.run(
                                            directory.resolve("one.git"),
                                            token,
                                            "push",
                                            "origin",
                                            "HEAD:main"));
            try {
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(started) && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(started)
                        .as("the first push is blocked inside its real receive hook")
                        .exists();
                final var second =
                        callers.submit(
                                () -> {
                                    try {
                                        git.run(
                                                directory.resolve("two.git"),
                                                token,
                                                "push",
                                                "origin",
                                                "HEAD:main");
                                        return "accepted";
                                    } catch (IOException refusal) {
                                        return "refused";
                                    }
                                });
                final long arrivalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (server.requests().stream()
                                        .filter(
                                                r ->
                                                        r.method().equals("POST")
                                                                && r.path()
                                                                        .endsWith(
                                                                                "/git-receive-pack"))
                                        .count()
                                < 2
                        && System.nanoTime() < arrivalDeadline) Thread.sleep(10);
                assertThat(
                                server.requests().stream()
                                        .filter(
                                                r ->
                                                        r.method().equals("POST")
                                                                && r.path()
                                                                        .endsWith(
                                                                                "/git-receive-pack"))
                                        .count())
                        .as("both pushes reached the server before testing the ordering")
                        .isEqualTo(2);
                assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                        .as("second refusal waits while the first backend is still blocked")
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                Files.writeString(release, "continue");
                first.get(5, TimeUnit.SECONDS);
                assertThat(second.get(5, TimeUnit.SECONDS))
                        .as("second publication is refused after the first completes")
                        .isEqualTo("refused");
                assertThat(repositories.file("one.git", "issue.md"))
                        .as("first remote is final before rollback can begin")
                        .isEqualTo("Closed\n");
                assertThat(repositories.file("two.git", "issue.md"))
                        .as("second remote receives no update")
                        .isEqualTo("Queued\n");
                server.verify();
            } finally {
                Files.writeString(release, "continue");
            }
        }
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    @Test
    void aLargePushDoesNotDeadlockTheBackendPipes() throws Exception {
        // Buffering one CGI pipe before draining the other can hang a large push indefinitely.
        final String token = UUID.randomUUID().toString();
        final Path root = directory.resolve("forge");
        final byte[] bytes = new byte[2 * 1024 * 1024];
        new Random(37).nextBytes(bytes);
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                GitClient git = new GitClient(directory.resolve("client"))) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(token);
            final Path clone = directory.resolve("clone");
            git.run(directory, token, "clone", userUrl(server), clone.toString());
            Files.write(clone.resolve("large.bin"), bytes);
            git.run(clone, null, "add", "large.bin");
            git.run(clone, null, "commit", "-m", "Add large fixture");
            git.run(clone, token, "-c", "http.postBuffer=1024", "push", "origin", "HEAD:main");
            assertThat(repositories.head(REPOSITORY))
                    .as("the complete large pack reaches the remote")
                    .isEqualTo(git.run(clone, null, "rev-parse", "HEAD").strip());
            server.verify();
        }
    }

    @Test
    void aGzippedRpcReachesGitWithItsContentEncoding() throws Exception {
        // Dropping the CGI encoding makes real compressed Git requests fail or hang.
        final String token = UUID.randomUUID().toString();
        final Path root = directory.resolve("forge");
        try (GitRepositories repositories = new GitRepositories(root);
                GitHttpServer server = new GitHttpServer(root);
                HttpClient client = httpClient()) {
            repositories.create(REPOSITORY, Map.of("story.md", "Ready\n"));
            server.accept(token);
            final String want = "want " + repositories.head(REPOSITORY) + "\n";
            final byte[] request =
                    ("%04x".formatted(want.getBytes(StandardCharsets.UTF_8).length + 4)
                                    + want
                                    + "00000009done\n")
                            .getBytes(StandardCharsets.UTF_8);
            final java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(request);
            }
            final HttpResponse<byte[]> response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(server.uri(REPOSITORY) + "/git-upload-pack"))
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Authorization", basic(token))
                                    .header("Content-Type", "application/x-git-upload-pack-request")
                                    .header("Content-Encoding", "gzip")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofByteArray(
                                                    compressed.toByteArray()))
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode())
                    .as("Git accepts a correctly encoded upload-pack RPC")
                    .isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.ISO_8859_1))
                    .as("the gzip body was decoded by Git and produced a pack")
                    .contains("PACK");
            server.verify();
        }
    }

    private static HttpResponse<Void> get(
            final HttpClient client, final GitHttpServer server, final @Nullable String token)
            throws Exception {
        final HttpRequest.Builder builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        server.uri(REPOSITORY)
                                                + "/info/refs?service=git-upload-pack"))
                        .timeout(Duration.ofSeconds(5));
        if (token != null) builder.header("Authorization", basic(token));
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String basic(final String token) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString(("fixture:" + token).getBytes(StandardCharsets.UTF_8));
    }

    private static String userUrl(final GitHttpServer server) {
        return server.uri(REPOSITORY).toString().replace("http://", "http://fixture@");
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(
                        new ProxySelector() {
                            /** Keeps loopback traffic independent of machine proxy settings. */
                            @Override
                            public List<Proxy> select(final URI uri) {
                                return List.of(Proxy.NO_PROXY);
                            }

                            /** Makes a failed loopback connection visible to the test. */
                            @Override
                            public void connectFailed(
                                    final URI uri,
                                    final SocketAddress address,
                                    final IOException failure) {
                                throw new java.io.UncheckedIOException(
                                        "Expected a direct loopback HTTP connection; got a failure"
                                                + " at "
                                                + address,
                                        failure);
                            }
                        })
                .build();
    }
}
