package org.fuin.porelo.standin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The stand-in over its socket, reading and writing GitHub as the forge clients recorded it. */
class StandInReplayTest {

    private static final String TOKEN = "ghp_standinReplayTokenNeverShown012345678";

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;

    private final ByteArrayOutputStream log = new ByteArrayOutputStream();

    private GitHubReplay github;

    private VarlinkServer server;

    private VarlinkClient client;

    /** Serves the given pull requests of the replay. */
    private void start(List<Long> pulls) throws Exception {
        final Git mirror = github.mirror(tmp.resolve("state").resolve("mirror.git"));
        final StandIn standIn =
                new StandIn(
                        new Pushes(),
                        mirror,
                        github.source(mirror, TOKEN),
                        pulls,
                        TOKEN,
                        github.clock,
                        new PrintStream(log, true, StandardCharsets.UTF_8));
        final Path dir =
                Files.createDirectory(
                        tmp.resolve("run"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        server = standIn.register(new VarlinkServer(dir.resolve("s.sock"), StandIn.INTERFACE));
        Thread.ofVirtual().start(server);
        client = new VarlinkClient(server.socketPath());
    }

    private void startOnPullSix() throws Exception {
        github = new GitHubReplay(tmp.resolve("remote.git"));
        github.pull(6, List.of());
        start(List.of(6L));
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("no log line holds the token")
                .doesNotContain(TOKEN);
    }

    private Map<String, Object> call(String method, Map<String, Object> parameters) {
        return call(client, method, parameters);
    }

    private static Map<String, Object> call(
            VarlinkClient on, String method, Map<String, Object> parameters) {
        final Map<String, Object> all = new HashMap<>(parameters);
        all.put("project", StandIn.PROJECT);
        final Map<String, Object> answer = on.call(StandIn.INTERFACE + "." + method, all);
        assertThat(String.valueOf(answer)).as("no answer holds the token").doesNotContain(TOKEN);
        return answer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> push(String name) {
        final List<Map<String, Object>> pending =
                (List<Map<String, Object>>) call("Pending", Map.of()).get("pending");
        return pending.stream().filter(p -> name.equals(p.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private String forgeRun(String push) {
        final Map<String, Object> review = (Map<String, Object>) push(push).get("review");
        return (String)
                ((List<Map<String, Object>>) review.get("runs"))
                        .stream()
                                .filter(r -> "github.com".equals(r.get("name")))
                                .findFirst()
                                .orElseThrow()
                                .get("run");
    }

    private String submission(String push) {
        // The pushes are known once a call has read GitHub.
        final Object commit = push(push).get("commit");
        final Map<String, Object> review = call("Review", Map.of("name", push));
        return """
               {"version":"2.1.0","properties":{"porelo/profileVersion":"1"},
                "runs":[{"tool":{"driver":{"name":"review display"}},
                  "versionControlProvenance":[{"revisionId":"%s"}],"columnKind":"unicodeCodePoints",
                  "properties":{"porelo/baseRevisionId":"%s"},
                  "results":[{"kind":"review","level":"none","message":{"text":"A comment here."},
                    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"%s"},"region":{"startLine":2,"endLine":2}}}],
                    "properties":{"porelo/anchorKind":"line","porelo/diffSide":"new"}}]}]}
               """
                .formatted(commit, review.get("base"), GitHubReplay.PATH);
    }

    // Harm: a comment shown on another line than GitHub's thread misleads the reviewer.
    @Test
    void aCommentWhoseLineDiffersFromItsThreadIsHeldAndTheRunPartial() throws Exception {
        github = new GitHubReplay(tmp.resolve("remote.git"));
        final GitHubReplay.Pull pull = github.pull(6, List.of());
        pull.comments = GitHubReplay.changed(pull.comments, 1, "line", 2);
        start(List.of(6L));

        final Map<String, Object> page =
                call("Comments", Map.of("name", "pr-6", "run", forgeRun("pr-6")));

        assertThat(String.valueOf(page.get("held")))
                .as("the reply is held, the reason naming the line")
                .contains("4431405381")
                .contains("line");
        final JsonNode sarif = JSON.readTree((String) page.get("sarif"));
        assertThat(sarif.at("/runs/0/properties/porelo~1completeness").asText())
                .as("a run with a held item is partial")
                .isEqualTo("partial");
        assertThat(sarif.at("/runs/0/results").toString())
                .as("the held reply is left out")
                .doesNotContain("Fixture reply");
    }

    // Harm: a reply to a result that is not there is written to GitHub anyway.
    @Test
    void aForgedReplyToIsRefusedAndNothingIsWritten() throws Exception {
        startOnPullSix();
        final String sarif = submission("pr-6");

        assertThatThrownBy(
                        () ->
                                call(
                                        "Comment",
                                        Map.of(
                                                "name",
                                                "pr-6",
                                                "sarif",
                                                sarif,
                                                "toForge",
                                                true,
                                                "replyTo",
                                                "00000000-0000-4000-8000-000000000000")))
                .as("refused, naming the result")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("no such result")
                                        .endsWith(".NoSuchResult"));
        assertThat(github.writes()).as("no write reached GitHub").isEmpty();
    }

    // Harm: a written comment and a read at once wait on each other for good.
    @Test
    void aCommentSentToGitHubAndAReadAtOnceBothFinish() throws Exception {
        startOnPullSix();
        final String sarif = submission("pr-6");
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch reading = new CountDownLatch(1);
        github.seen =
                request -> {
                    if ("POST".equals(request.method())
                            && request.uri().getPath().endsWith("/pulls/6/comments")) {
                        writing.countDown();
                        // The write waits until a read holds the forge, as a slow GitHub lets it.
                        await(reading);
                    } else if (writing.getCount() == 0
                            && "GET".equals(request.method())
                            && request.uri().getPath().endsWith("/pulls/6")) {
                        reading.countDown();
                    }
                };
        final CompletableFuture<Map<String, Object>> comment =
                CompletableFuture.supplyAsync(
                        () ->
                                call(
                                        "Comment",
                                        Map.of("name", "pr-6", "sarif", sarif, "toForge", true)));
        await(writing);
        github.clock.advance(Duration.ofMinutes(1));
        try (VarlinkClient second = new VarlinkClient(server.socketPath())) {
            final CompletableFuture<Map<String, Object>> pending =
                    CompletableFuture.supplyAsync(() -> call(second, "Pending", Map.of()));

            assertThat(pending.get(20, TimeUnit.SECONDS))
                    .as("the read finishes")
                    .containsKey("pending");
            assertThat(comment.get(20, TimeUnit.SECONDS))
                    .as("the comment finishes, sent")
                    .containsKey("sentToForge");
        }
    }

    // Harm: once GitHub's pull requests were stacked, the stand-in never reads them again.
    @Test
    void pendingReadsGitHubAgainAfterItsPullRequestsWereStacked() throws Exception {
        github = new GitHubReplay(tmp.resolve("remote.git"));
        final String m0 = github.remote.commitTree(Map.of("README", "0\n"), List.of(), "m0");
        final String m1 = github.remote.commitTree(Map.of("README", "1\n"), List.of(m0), "m1");
        final String six = github.remote.commitTree(Map.of("README", "6\n"), List.of(m1), "six");
        final String seven =
                github.remote.commitTree(Map.of("README", "7\n"), List.of(m1), "seven");
        github.put(6, six, m1).empty();
        // Seven's base is m0, yet its history holds six's base m1: stacked.
        github.put(7, seven, m0).empty();
        start(List.of(6L, 7L));
        assertThatThrownBy(() -> call("Pending", Map.of()))
                .as("stacked pull requests are refused")
                .isInstanceOf(VarlinkException.class);

        final String rebased =
                github.remote.commitTree(Map.of("README", "7\n"), List.of(m0), "seven again");
        github.put(7, rebased, m0).empty();
        github.clock.advance(Duration.ofMinutes(1));

        assertThat(call("Pending", Map.of()))
                .as("the rebased pull request is read and served")
                .containsKey("pending");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Waited 20 s for the other call");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
