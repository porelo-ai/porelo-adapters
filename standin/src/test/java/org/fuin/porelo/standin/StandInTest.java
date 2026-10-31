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
                .as("the same id with another document")
                .endsWith(".IdReused");
    }

    @Test
    void aToForgeCommentOnAFixtureThatPretendsItsPullRequestMovedIsRefused() throws Exception {
        final String sarif = submission("fixture-moved", headFile("fixture-moved"), 1, "");

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of("name", "fixture-moved", "sarif", sarif, "toForge", true)))
                .as("the fixture's pull request moved")
                .endsWith(".PullRequestMoved");
    }

    @Test
    void resolvingAThreadShowsItResolvedAndReopeningOpensIt() throws Exception {
        final String run = run("fixture-threads", "github.com");
        final String thread = stateOfResult6(run).path("porelo/threadId").asText();
        final int open = openThreads("fixture-threads");

        call("ResolveThread", Map.of("name", "fixture-threads", "thread", thread));

        assertThat(stateOfResult6(run).path("porelo/threadState").asText())
                .as("Comments shows it resolved")
                .isEqualTo("resolved");
        assertThat(openThreads("fixture-threads"))
                .as("Pending counts one open thread less")
                .isEqualTo(open - 1);

        call("ResolveThread", Map.of("name", "fixture-threads", "thread", thread, "reopen", true));

        assertThat(stateOfResult6(run).path("porelo/threadState").asText())
                .as("reopened")
                .isEqualTo("open");
        assertThat(openThreads("fixture-threads")).as("counted open again").isEqualTo(open);
    }

    private JsonNode stateOfResult6(String run) throws Exception {
        return JSON.readTree(
                        (String)
                                call("Comments", Map.of("name", "fixture-threads", "run", run))
                                        .get("sarif"))
                .at("/runs/0/results/6/properties");
    }

    @SuppressWarnings("unchecked")
    private int openThreads(String push) {
        return ((Number) ((Map<String, Object>) push(push).get("review")).get("openThreads"))
                .intValue();
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
        assertThatThrownBy(() -> call("Start", Map.of()))
                .as("a method the stand-in does not serve")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("the interface's Failed")
                                    .endsWith(".Failed");
                            assertThat(String.valueOf(ex.getParameters()))
                                    .as("naming the stand-in")
                                    .contains("stand-in");
                        });
    }

    // Harm: the token leaks through an answer or a log line.
    @Test
    void anAnswerThatWouldCarryTheTokenIsNotSentAndNotLogged() throws Exception {
        final Pushes pushes = new Pushes();
        final Git git = Git.open(tmp.resolve("leak.git"));
        final String commit = git.commitTree(Map.of("a", "a\n"), List.of(), "a");
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
                .as("a socket directory others may read")
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
                .as("no token to read the pull request with")
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
        throw new AssertionError("no changed file in " + push);
    }

    // Harm: a suggestion sent as a reply lands on the parent's lines, which "Commit suggestion"
    // would overwrite instead of the lines the person chose.
    @Test
    void aSuggestionIsNeverSentAsAReplyAndAPlainAnswerToAReviewCommentIs() throws Exception {
        final ObjectNode parent =
                (ObjectNode)
                        JSON.readTree(
                                """
                                {"properties":{"porelo/nativeCommentKind":"review",
                                  "porelo/nativeCommentId":"42","porelo/nativeReplyTo":"41"}}
                                """);

        assertThat(StandIn.forgeParent(parent, true))
                .as("a suggestion goes as a new comment at its own anchor")
                .isNull();
        assertThat(StandIn.forgeParent(parent, false))
                .as("a plain answer joins the thread's root")
                .isEqualTo(41L);
    }

    // Harm: a client's comment claims a field only the daemon or an adapter writes.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                ",\"porelo/authorDisplayName\":\"Alice\"",
                ",\"porelo/outdated\":true",
                ",\"porelo/outdatedReason\":\"fileGone\"",
                ",\"porelo/originalPath\":\"src/Notes.java\""
            })
    void aCommentClaimingAnotherColumnsFieldIsRefusedAsForbidden(String property) {
        final String sarif = submission("fixture-anchor", "src/Notes.java", 1, property);

        assertThat(errorOf("Comment", Map.of("name", "fixture-anchor", "sarif", sarif)))
                .as("a field whose column names no person")
                .endsWith(".ForbiddenField");
    }

    @Test
    void aProfileVersionThatIsNoStringIsRefused() {
        final String sarif =
                submission("fixture-anchor", "src/Notes.java", 1, "")
                        .replace("\"porelo/profileVersion\":\"1\"", "\"porelo/profileVersion\":1");

        assertThat(errorOf("Comment", Map.of("name", "fixture-anchor", "sarif", sarif)))
                .as("never coerced")
                .endsWith(".ProfileViolation");
    }

    private static Push pullRequestPush(String commit) {
        return new Push(
                "pr-1",
                "s",
                "2026-01-01T00:00:00Z",
                1L,
                "E1",
                "C3",
                Map.of(),
                2,
                null,
                null,
                "",
                commit,
                "0".repeat(40));
    }

    private static ObjectNode shell() throws Exception {
        return (ObjectNode) JSON.readTree("{\"runs\":[{}]}");
    }

    private static ObjectNode comment(String kind, String id) throws Exception {
        return (ObjectNode)
                JSON.readTree(
                        """
                        {"properties":{"porelo/nativeCommentKind":"%s","porelo/nativeCommentId":"%s"}}
                        """
                                .formatted(kind, id));
    }

    // Harm: a cursor taken before the pull request moved reads a page of another commit's run.
    @Test
    void aCursorOfAnEarlierCommitIsRefused() throws Exception {
        final Push push = pullRequestPush("a".repeat(40));
        final List<ObjectNode> results = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(comment("review", Integer.toString(i + 1)));
        }
        final Push.Run run = push.putRun("github.com", "forge", 1, shell(), results, List.of());
        final String cursor = StandIn.cursor(push, run, 1);

        push.commit = "b".repeat(40);

        assertThatThrownBy(() -> StandIn.offset(cursor, push, run))
                .as("the cursor names the commit it was read at")
                .isInstanceOf(VarlinkException.class);
    }

    // Harm: two comments with one guid make a reply or a resolve land on the wrong one.
    @Test
    void anIssueCommentAndAReviewCommentWithTheSameIdGetTwoGuids() throws Exception {
        final Push push = pullRequestPush("a".repeat(40));
        final Push.Run run =
                push.putRun(
                        "github.com",
                        "forge",
                        1,
                        shell(),
                        List.of(comment("issue", "10"), comment("review", "10")),
                        List.of());

        assertThat(run.results.get(0).path("guid").asText())
                .as("GitHub numbers the two kinds apart")
                .isNotEqualTo(run.results.get(1).path("guid").asText());
    }

    private static String git(String mirror, String... args) throws Exception {
        final List<String> command =
                new java.util.ArrayList<>(List.of("git", "--git-dir=" + mirror));
        command.addAll(List.of(args));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git " + String.join(" ", args) + ": " + out).isZero();
        return out.strip();
    }

    // Harm: the review display takes the base as the merge base with the mirror's HEAD; a wrong
    // or missing one shows a person another diff than the one they approve.
    @Test
    @SuppressWarnings("unchecked")
    void theMirrorsHeadGivesEveryPushItsOwnBase() throws Exception {
        final Map<String, Object> answer = call("Pending", Map.of());
        final String mirror = (String) answer.get("mirror");

        assertThat(git(mirror, "symbolic-ref", "HEAD"))
                .as("HEAD names the default branch")
                .startsWith("refs/heads/");
        for (final Map<String, Object> push : (List<Map<String, Object>>) answer.get("pending")) {
            final String name = (String) push.get("name");
            assertThat(git(mirror, "merge-base", "HEAD", "refs/porelo/incoming/" + name))
                    .as("the merge base of " + name)
                    .isEqualTo(call("Review", Map.of("name", name)).get("base"));
        }
    }

    // Harm: without Projects the review display finds no project and shows no push at all.
    @Test
    @SuppressWarnings("unchecked")
    void projectsNamesTheStandInsProjectWithItsRepositoryAndPendingCount() {
        final int pending = ((List<Object>) call("Pending", Map.of()).get("pending")).size();

        final Map<String, Object> project =
                ((List<Map<String, Object>>)
                                client.call(StandIn.INTERFACE + ".Projects", Map.of())
                                        .get("projects"))
                        .get(0);

        assertThat(project.get("name")).as("the project").isEqualTo(StandIn.PROJECT);
        assertThat(((Number) project.get("pending")).intValue())
                .as("its pushes")
                .isEqualTo(pending);
        final Map<String, Object> repository =
                ((List<Map<String, Object>>) project.get("repositories")).get(0);
        assertThat(repository.get("name")).as("its repository").isEqualTo(StandIn.REPOSITORY);
        assertThat(repository.get("own")).as("its own").isEqualTo(true);
        assertThat(((Number) repository.get("pending")).intValue())
                .as("the same pushes")
                .isEqualTo(pending);
    }

    // Harm: one id sent twice at once makes two comments, and with toForge two forge writes.
    @Test
    void oneIdCalledAtOnceFromSeveralConnectionsMakesOneComment() throws Exception {
        final String sarif = submission("fixture-anchor", headFile("fixture-anchor"), 1, "");
        final Map<String, Object> parameters =
                Map.of(
                        "project",
                        StandIn.PROJECT,
                        "name",
                        "fixture-anchor",
                        "sarif",
                        sarif,
                        "id",
                        "once");
        final List<java.util.concurrent.Future<Object>> answers = new java.util.ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                answers.add(
                        executor.submit(
                                () -> {
                                    try (VarlinkClient own =
                                            new VarlinkClient(server.socketPath())) {
                                        start.await();
                                        return own.call(StandIn.INTERFACE + ".Comment", parameters)
                                                .get("guid");
                                    }
                                }));
            }
            start.countDown();
        }
        final java.util.Set<Object> guids = new java.util.HashSet<>();
        for (final var answer : answers) {
            guids.add(answer.get());
        }

        assertThat(guids).as("every call answers the one comment").hasSize(1);
    }

    // Harm: a flag of the wrong type is read as false, so a comment meant for the pull request
    // silently stays here, or a reopen resolves.
    @Test
    void aParameterOfTheWrongTypeIsRefusedNotReadAsAbsent() throws Exception {
        final String sarif = submission("fixture-anchor", headFile("fixture-anchor"), 1, "");

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        sarif,
                                        "toForge",
                                        "true")))
                .as("toForge as a string")
                .isEqualTo("org.varlink.service.InvalidParameter");
        assertThat(
                        errorOf(
                                "Comments",
                                Map.of(
                                        "name",
                                        "fixture-threads",
                                        "run",
                                        run("fixture-threads", "github.com"),
                                        "round",
                                        "1")))
                .as("round as a string")
                .isEqualTo("org.varlink.service.InvalidParameter");
    }

    // Harm: a path git quotes is no path of the push, so a comment on it can never be anchored.
    @Test
    @SuppressWarnings("unchecked")
    void aPathGitWouldQuoteIsAnsweredAsItIs() {
        final List<Map<String, Object>> files =
                (List<Map<String, Object>>)
                        call("Review", Map.of("name", "fixture-unicode-path")).get("files");

        assertThat(files.stream().map(f -> f.get("path")).toList())
                .as("unquoted")
                .containsExactlyInAnyOrder("docs/\"a\".md", "docs/Über.md");
    }

    // Harm: a client checking the stored comment against the profile refuses it.
    @Test
    void aStoredCommentCarriesTheRunsStamps() throws Exception {
        final String sarif = submission("fixture-anchor", headFile("fixture-anchor"), 1, "");

        final JsonNode stored =
                JSON.readTree(
                        (String)
                                call("Comment", Map.of("name", "fixture-anchor", "sarif", sarif))
                                        .get("sarif"));

        assertThat(stored.at("/runs/0/automationDetails/guid").asText())
                .as("the run's guid")
                .isNotEmpty();
        assertThat(stored.at("/runs/0/properties/porelo~1reviewRound").asText())
                .as("the round")
                .isNotEmpty();
    }

    // Harm: the mirror holds a private repository's objects; other local accounts must not read
    // them.
    @Test
    void theMirrorIsReadableByItsOwnerOnly() throws Exception {
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(tmp.resolve("state/mirror.git"))))
                .as("whatever the umask")
                .isEqualTo("rwx------");
    }

    // Harm: a stream that skips the guard sends the token to the caller.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"Projects", "WatchProjects", "Comment"})
    void aProjectsAnswerThatWouldCarryTheTokenIsNotSent(String method) throws Exception {
        final Pushes pushes = new Pushes();
        // The mirror's path is in the answer; here it holds the token.
        final Git git = Git.open(tmp.resolve("state-" + TOKEN + ".git"));
        final String commit = git.commitTree(Map.of("a", "a\n"), List.of(), "a");
        pushes.add(
                new Push(
                        "p",
                        "s",
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

            // A refused document names the field it refuses; here the field is the token.
            final Map<String, Object> parameters =
                    "Comment".equals(method)
                            ? Map.of(
                                    "project",
                                    StandIn.PROJECT,
                                    "name",
                                    "p",
                                    "sarif",
                                    "{\"" + TOKEN + "\":1}")
                            : Map.of();
            assertThatThrownBy(() -> leakyClient.call(StandIn.INTERFACE + "." + method, parameters))
                    .as("the answer is refused")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(String.valueOf(ex.getParameters()))
                                            .as("its error holds no token")
                                            .doesNotContain(TOKEN));
        }
    }

    // Harm: a retry with the same id but a forge write asked for answers as if it had been sent.
    @Test
    void theSameIdWithAnotherReplyToIsReused() throws Exception {
        final String sarif = submission("fixture-anchor", headFile("fixture-anchor"), 1, "");
        final Map<String, Object> first =
                call("Comment", Map.of("name", "fixture-anchor", "sarif", sarif, "id", "k1"));

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        sarif,
                                        "id",
                                        "k1",
                                        "replyTo",
                                        first.get("guid"))))
                .as("another call under the same id")
                .endsWith(".IdReused");
    }

    // Harm: a stored comment the profile refuses is served to every reader.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "driver:{}",
                "driver:{\"name\":7}",
                "message:{\"text\":\"x\",\"markdown\":{\"a\":1}}",
                "region:{\"startLine\":1,\"endLine\":1,\"startColumn\":-5}",
                "region:{\"startLine\":1,\"endLine\":1,\"endColumn\":[false]}"
            })
    void aCommentWithAFieldOfTheWrongShapeIsRefused(String change) throws Exception {
        final String[] at = change.split(":", 2);
        final ObjectNode document =
                (ObjectNode)
                        JSON.readTree(
                                submission("fixture-anchor", headFile("fixture-anchor"), 1, ""));
        final JsonNode value = JSON.readTree(at[1]);
        switch (at[0]) {
            case "driver" -> ((ObjectNode) document.at("/runs/0/tool")).set("driver", value);
            case "message" -> ((ObjectNode) document.at("/runs/0/results/0")).set("message", value);
            default ->
                    ((ObjectNode) document.at("/runs/0/results/0/locations/0/physicalLocation"))
                            .set("region", value);
        }

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        JSON.writeValueAsString(document))))
                .as("refused, not stored")
                .endsWith(".ProfileViolation");
    }

    @Test
    void aCommentClaimingADraftIsRefusedAsForbidden() throws Exception {
        final String sarif =
                submission("fixture-anchor", "src/Notes.java", 1, ",\"porelo/draft\":true");

        assertThat(errorOf("Comment", Map.of("name", "fixture-anchor", "sarif", sarif)))
                .as("an adapter's field")
                .endsWith(".ForbiddenField");
    }

    // Harm: with pull requests stacked on one another no default branch gives each its base, so
    // a review display would show one of them another diff; the stand-in must say so, not serve it.
    @Test
    void stackedPullRequestsAreRefusedNotServedWithAWrongBase() throws Exception {
        final Pushes pushes = new Pushes();
        final Git git = Git.open(tmp.resolve("stacked.git"));
        final String b1 = git.commitTree(Map.of("a", "1\n"), List.of(), "b1");
        final String h1 = git.commitTree(Map.of("a", "2\n"), List.of(b1), "h1");
        final String h2 = git.commitTree(Map.of("a", "3\n"), List.of(h1), "h2");
        git.updateRef("refs/porelo/incoming/one", h1);
        git.updateRef("refs/porelo/incoming/two", h2);
        pushes.add(
                new Push(
                        "one",
                        "s",
                        "2026-01-01T00:00:00Z",
                        null,
                        "E1",
                        "C1",
                        Map.of(),
                        1,
                        null,
                        null,
                        "",
                        h1,
                        b1));
        pushes.add(
                new Push(
                        "two",
                        "s",
                        "2026-01-01T00:00:00Z",
                        null,
                        "E1",
                        "C1",
                        Map.of(),
                        1,
                        null,
                        null,
                        "",
                        h2,
                        h1));
        final Path dir =
                Files.createDirectory(
                        tmp.resolve("stacked"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final StandIn standIn =
                new StandIn(
                        pushes,
                        git,
                        null,
                        List.of(),
                        null,
                        Clock.systemUTC(),
                        new PrintStream(log, true, StandardCharsets.UTF_8));
        try (VarlinkServer stacked =
                        standIn.register(
                                new VarlinkServer(dir.resolve("s.sock"), StandIn.INTERFACE));
                VarlinkClient stackedClient = new VarlinkClient(stacked.socketPath())) {
            Thread.ofVirtual().start(stacked);

            assertThatThrownBy(
                            () ->
                                    stackedClient.call(
                                            StandIn.INTERFACE + ".Pending",
                                            Map.of("project", StandIn.PROJECT)))
                    .as("refused, naming why")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            ex ->
                                    assertThat(String.valueOf(ex.getParameters()))
                                            .as("the push whose base it cannot give")
                                            .contains("one"));
            assertThatThrownBy(
                            () ->
                                    stackedClient.call(
                                            StandIn.INTERFACE + ".Review",
                                            Map.of("project", StandIn.PROJECT, "name", "one")))
                    .as("a review is refused too, never served with a wrong base")
                    .isInstanceOf(VarlinkException.class);
            assertThat(git.read("for-each-ref", "refs/heads"))
                    .as("no default branch is left that gives push one a wrong base")
                    .isBlank();
        }
    }

    // Harm: a call asking for no parent is answered with another call's comment, or the reverse.
    @Test
    void theSameIdWithReplyToTheTextNullIsReused() throws Exception {
        final String sarif = submission("fixture-anchor", headFile("fixture-anchor"), 1, "");
        call("Comment", Map.of("name", "fixture-anchor", "sarif", sarif, "id", "k1"));

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        sarif,
                                        "id",
                                        "k1",
                                        "replyTo",
                                        "null")))
                .as("a parent named null is not no parent")
                .endsWith(".IdReused");
    }

    // Harm: a retry asking for the forge write is answered as if it had been sent.
    @Test
    void theSameIdWithToForgeAskedIsReused() throws Exception {
        final String sarif = submission("fixture-anchor", headFile("fixture-anchor"), 1, "");
        call("Comment", Map.of("name", "fixture-anchor", "sarif", sarif, "id", "k1"));

        assertThat(
                        errorOf(
                                "Comment",
                                Map.of(
                                        "name",
                                        "fixture-anchor",
                                        "sarif",
                                        sarif,
                                        "id",
                                        "k1",
                                        "toForge",
                                        true)))
                .as("the forge write is part of the call")
                .endsWith(".IdReused");
    }
}
