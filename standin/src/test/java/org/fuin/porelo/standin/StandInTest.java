package org.fuin.porelo.standin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandInTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TOKEN = "ghp_standinTestTokenNeverShown0123456789";

    @TempDir Path tmp;

    private VarlinkServer server;

    private VarlinkClient client;

    private final ByteArrayOutputStream log = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        final Path dir =
                Files.createDirectory(
                        tmp.resolve("run"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        server =
                Main.start(
                        new String[] {
                            "--socket",
                            dir.resolve("porelod.sock").toString(),
                            "--state",
                            tmp.resolve("state").toString(),
                            "--fixtures"
                        },
                        Map.of(),
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                        URI.create("https://api.github.invalid/"),
                        new PrintStream(log, true, StandardCharsets.UTF_8));
        Thread.ofVirtual().start(server);
        client = new VarlinkClient(server.socketPath());
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
        server.close();
    }

    private Map<String, Object> call(String method, Map<String, Object> parameters) {
        final Map<String, Object> all = new HashMap<>(parameters);
        all.put("project", StandIn.PROJECT);
        return client.call(StandIn.INTERFACE + "." + method, all);
    }

    private String errorOf(String method, Map<String, Object> parameters) {
        try {
            call(method, parameters);
        } catch (VarlinkException ex) {
            return String.valueOf(ex.getErrorName());
        }
        throw new AssertionError(method + " answered where an error was expected");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> push(String name) {
        final List<Map<String, Object>> pending =
                (List<Map<String, Object>>) call("Pending", Map.of()).get("pending");
        return pending.stream().filter(p -> name.equals(p.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private String run(String push, String runName) {
        final Map<String, Object> review = (Map<String, Object>) push(push).get("review");
        final List<Map<String, Object>> runs = (List<Map<String, Object>>) review.get("runs");
        return (String)
                runs.stream()
                        .filter(r -> runName.equals(r.get("name")))
                        .findFirst()
                        .orElseThrow()
                        .get("run");
    }

    private String submission(String push, String path, int line, String extraProperties) {
        final Map<String, Object> p = push(push);
        final String base = (String) call("Review", Map.of("name", push)).get("base");
        return """
               {"version":"2.1.0","properties":{"porelo/profileVersion":"1"},
                "runs":[{"tool":{"driver":{"name":"review display"}},
                  "versionControlProvenance":[{"revisionId":"%s"}],"columnKind":"unicodeCodePoints",
                  "properties":{"porelo/baseRevisionId":"%s"},
                  "results":[{"kind":"review","level":"none","message":{"text":"A comment here."},
                    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"%s"},"region":{"startLine":%d,"endLine":%d}}}],
                    "properties":{"porelo/anchorKind":"line","porelo/diffSide":"new"%s}}]}]}
               """
                .formatted(p.get("commit"), base, path, line, line, extraProperties);
    }

    @Test
    void pendingListsEveryFixturePushWithItsLevelMarkedAsTheStandInsValue() {
        final Map<String, Object> push = push("fixture-e0");

        assertThat(push.get("sourceLevel")).as("the fixture's level").isEqualTo("E0");
        assertThat(((Number) push.get("approvalsNeeded")).intValue()).as("two for E0").isEqualTo(2);
        assertThat((String) push.get("standIn"))
                .as("marked as the stand-in's")
                .contains("stand-in");
        assertThat((String) push.get("fetch"))
                .as("a local fetch from the stand-in's clone")
                .startsWith("git fetch /");
    }

    // Harm: a page that does not end where it says, or a cursor that reads another run.
    @Test
    void aRunOfMoreThanOnePageIsReadPageByPageAndEndsWithoutACursor() throws Exception {
        final String run = run("fixture-paging", "fixture-scanner");
        int results = 0;
        String cursor = null;
        int pages = 0;
        do {
            final Map<String, Object> parameters =
                    new HashMap<>(Map.of("name", "fixture-paging", "run", run));
            if (cursor != null) {
                parameters.put("cursor", cursor);
            }
            final Map<String, Object> page = call("Comments", parameters);
            results += JSON.readTree((String) page.get("sarif")).at("/runs/0/results").size();
            cursor = (String) page.get("cursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).as("230 results in pages of 100").isEqualTo(3);
        assertThat(results).as("every result once").isEqualTo(230);
    }

    @Test
    void aCursorOfAnotherRunOfTheSamePushIsRefused() throws Exception {
        final String paging = run("fixture-paging", "fixture-scanner");
        final String cursor =
                (String)
                        call("Comments", Map.of("name", "fixture-paging", "run", paging))
                                .get("cursor");
        call(
                "Comment",
                Map.of(
                        "name",
                        "fixture-paging",
                        "sarif",
                        submission("fixture-paging", "src/Many.java", 1, "")));
        final String local = run("fixture-paging", "people here");

        assertThat(
                        errorOf(
                                "Comments",
                                Map.of("name", "fixture-paging", "run", local, "cursor", cursor)))
                .as("a cursor is bound to its run")
                .endsWith(".CursorRefused");
    }

    @Test
    void aPageCarriesTheDaemonsIdsAndTheStandInsLevels() throws Exception {
        final String run = run("fixture-threads", "github.com");
        final JsonNode results =
                JSON.readTree(
                                (String)
                                        call(
                                                        "Comments",
                                                        Map.of(
                                                                "name",
                                                                "fixture-threads",
                                                                "run",
                                                                run))
                                                .get("sarif"))
                        .at("/runs/0/results");

        for (final JsonNode result : results) {
            assertThat(result.path("guid").asText())
                    .as("every result has a guid")
                    .matches("[0-9a-f-]{36}");
            assertThat(result.at("/properties/porelo~1sourceLevel").asText())
                    .as("a forge comment is E0 or E1")
                    .isIn("E0", "E1");
        }
        final JsonNode reply = results.get(4);
        assertThat(reply.at("/properties/porelo~1replyTo").asText())
                .as("a reply names its parent's guid")
                .isEqualTo(results.get(3).path("guid").asText());
    }

    // Harm: a client's comment claims an authority only the daemon gives.
    @Test
    void aCommentClaimingASourceLevelIsRefusedAsForbidden() {
        final String sarif =
                submission("fixture-anchor", "src/Notes.java", 1, ",\"porelo/sourceLevel\":\"I1\"");

        assertThat(errorOf("Comment", Map.of("name", "fixture-anchor", "sarif", sarif)))
                .as("a D field from a client")
                .endsWith(".ForbiddenField");
    }

    @Test
    void aCommentBeyondTheFilesLastLineIsRefusedAsOutsideTheBlob() throws Exception {
        final String file = headFile("fixture-anchor");

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        submission("fixture-anchor", file, 6, ""))))
                .as("a five-line file has no line 6")
                .endsWith(".AnchorOutsideBlob");
    }

    @Test
    void aCommentOnTheFileIsStoredAsTheCallersI1CommentInANewThread() throws Exception {
        final String file = headFile("fixture-anchor");

        final Map<String, Object> answer =
                call(
                        "Comment",
                        Map.of(
                                "name",
                                "fixture-anchor",
                                "sarif",
                                submission("fixture-anchor", file, 5, "")));

        final JsonNode result = JSON.readTree((String) answer.get("sarif")).at("/runs/0/results/0");
        assertThat(result.at("/properties/porelo~1sourceLevel").asText())
                .as("a local person")
                .isEqualTo("I1");
        assertThat(result.at("/properties/porelo~1threadId").asText())
                .as("its own thread")
                .isEqualTo(answer.get("thread"));
        assertThat(answer).as("nothing went to a forge").doesNotContainKey("sentToForge");
    }

    @Test
    void aReplyToAResultThePushDoesNotHaveIsRefused() throws Exception {
        final String file = headFile("fixture-anchor");
        final String sarif = submission("fixture-anchor", file, 5, "");

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        sarif,
                                        "replyTo",
                                        "6f1c2a8e-0000-4000-8000-000000000000")))
                .as("a forged parent")
                .endsWith(".NoSuchResult");
    }

    @Test
    void theSameIdWithAnotherDocumentIsRefused() throws Exception {
        final String file = headFile("fixture-anchor");
        call(
                "Comment",
                Map.of(
                        "name",
                        "fixture-anchor",
                        "sarif",
                        submission("fixture-anchor", file, 5, ""),
                        "id",
                        "once"));

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        submission("fixture-anchor", file, 4, ""),
                                        "id",
                                        "once")))
                .endsWith(".IdReused");
    }

    @Test
    void aToForgeCommentOnAFixtureThatPretendsItsPullRequestMovedIsRefused() throws Exception {
        final String sarif = submission("fixture-moved", headFile("fixture-moved"), 1, "");

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of("name", "fixture-moved", "sarif", sarif, "toForge", true)))
                .endsWith(".PullRequestMoved");
    }

    @Test
    void resolvingAThreadShowsItResolvedAndReopeningOpensIt() throws Exception {
        final String run = run("fixture-threads", "github.com");
        final String thread =
                JSON.readTree(
                                (String)
                                        call(
                                                        "Comments",
                                                        Map.of(
                                                                "name",
                                                                "fixture-threads",
                                                                "run",
                                                                run))
                                                .get("sarif"))
                        .at("/runs/0/results/6/properties/porelo~1threadId")
                        .asText();

        assertThat(
                        call("ResolveThread", Map.of("name", "fixture-threads", "thread", thread))
                                .get("state"))
                .isEqualTo("resolved");
        assertThat(
                        call(
                                        "ResolveThread",
                                        Map.of(
                                                "name",
                                                "fixture-threads",
                                                "thread",
                                                thread,
                                                "reopen",
                                                true))
                                .get("state"))
                .isEqualTo("open");
        assertThat(errorOf("ResolveThread", Map.of("name", "fixture-threads", "thread", "nope")))
                .endsWith(".NoSuchThread");
    }

    // Harm: work leaves without review.
    @Test
    void approveForwardsNothingAndSaysSo() {
        final Map<String, Object> answer = call("Approve", Map.of("name", "fixture-threads"));

        assertThat((String) answer.get("forwarded")).as("its answer says so").startsWith("nothing");
        assertThat(
                        errorOf(
                                "Approve",
                                Map.of(
                                        "name",
                                        "fixture-moved",
                                        "commit",
                                        push("fixture-moved").get("commit"))))
                .as("the fixture that pretends to have moved")
                .endsWith(".MovedSinceReview");
    }

    @Test
    void aMethodTheStandInDoesNotServeFailsNamingTheStandIn() {
        assertThatThrownBy(() -> call("Projects", Map.of()))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName()).endsWith(".Failed");
                            assertThat(String.valueOf(ex.getParameters())).contains("stand-in");
                        });
    }

    // Harm: the token leaks through an answer or a log line.
    @Test
    void anAnswerThatWouldCarryTheTokenIsNotSentAndNotLogged() throws Exception {
        final Pushes pushes = new Pushes();
        final Git git = Git.open(tmp.resolve("leak.git"));
        final String commit = git.commitTree(Map.of("a", "a\n"), null, "a");
        pushes.add(
                new Push(
                        "leaky",
                        "subject " + TOKEN,
                        "2026-01-01T00:00:00Z",
                        null,
                        "E1",
                        "C1",
                        Map.of(),
                        1,
                        null,
                        null,
                        "",
                        commit,
                        commit));
        final Path dir =
                Files.createDirectory(
                        tmp.resolve("leak"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final StandIn standIn =
                new StandIn(
                        pushes,
                        git,
                        null,
                        List.of(),
                        TOKEN,
                        Clock.systemUTC(),
                        new PrintStream(log, true, StandardCharsets.UTF_8));
        try (VarlinkServer leaky =
                        standIn.register(
                                new VarlinkServer(dir.resolve("s.sock"), StandIn.INTERFACE));
                VarlinkClient leakyClient = new VarlinkClient(leaky.socketPath())) {
            Thread.ofVirtual().start(leaky);

            assertThatThrownBy(
                            () ->
                                    leakyClient.call(
                                            StandIn.INTERFACE + ".Pending",
                                            Map.of("project", StandIn.PROJECT)))
                    .as("the answer is refused")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(String.valueOf(ex.getParameters()))
                                            .doesNotContain(TOKEN));
        }
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("no log line carries it")
                .doesNotContain(TOKEN);
    }

    @Test
    void aSocketDirectoryOthersCanReadIsRefused() throws Exception {
        final Path open =
                Files.createDirectory(
                        tmp.resolve("open"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxr-xr-x")));

        assertThatThrownBy(
                        () ->
                                Main.start(
                                        new String[] {
                                            "--socket",
                                            open.resolve("s.sock").toString(),
                                            "--state",
                                            tmp.resolve("s2").toString(),
                                            "--fixtures"
                                        },
                                        Map.of(),
                                        HttpClient.newHttpClient(),
                                        URI.create("https://api.github.invalid/"),
                                        new PrintStream(log, true, StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0700");
    }

    @Test
    void aPullRequestWithoutATokenInTheEnvironmentIsRefused() throws Exception {
        final Path dir =
                Files.createDirectory(
                        tmp.resolve("t"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));

        assertThatThrownBy(
                        () ->
                                Main.start(
                                        new String[] {
                                            "--socket",
                                            dir.resolve("s.sock").toString(),
                                            "--state",
                                            tmp.resolve("s3").toString(),
                                            "--repository",
                                            "example-org/example-repo",
                                            "--pull",
                                            "17"
                                        },
                                        Map.of(),
                                        HttpClient.newBuilder()
                                                .followRedirects(HttpClient.Redirect.NEVER)
                                                .build(),
                                        URI.create("https://api.github.invalid/"),
                                        new PrintStream(log, true, StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Main.TOKEN_VARIABLE);
    }

    private String headFile(String push) throws Exception {
        final Map<String, Object> review = call("Review", Map.of("name", push));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> files = (List<Map<String, Object>>) review.get("files");
        if (!files.isEmpty()) {
            return (String) files.get(0).get("path");
        }
        final ObjectNode any = (ObjectNode) JSON.readTree("{}");
        throw new AssertionError("no changed file in " + push + any);
    }
}
