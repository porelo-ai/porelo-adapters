package org.fuin.porelo.build.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.Target;
import org.junit.jupiter.api.Test;

class GitHubReaderTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static final String API = "https://forge.example";

    private static final Target TARGET =
            new Target("git@github.com:porelo-ai/porelo.git", API, "secret-token");

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    /**
     * What the fake GitHub answers, by the address asked; and what was asked, with which headers.
     */
    private final Map<String, Http.Response> answers = new LinkedHashMap<>();

    private final List<Map.Entry<URI, Map<String, String>>> asked = new ArrayList<>();

    /** How much of each answer's end was asked to be kept, by the address asked. */
    private final Map<String, Integer> tails = new LinkedHashMap<>();

    private GitHubReader reader() {
        return new GitHubReader(
                (uri, headers, tail) -> {
                    asked.add(Map.entry(uri, headers));
                    tails.put(uri.toString(), tail);
                    final Http.Response answer = answers.get(uri.toString());
                    if (answer == null) {
                        throw new IOException("nothing at " + uri);
                    }
                    return answer;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void answer(final String path, final int status, final String body) {
        answers.put(
                path.startsWith("http") ? path : API + path,
                new Http.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8)));
    }

    private void runs(final String json) {
        answer(
                "/repos/porelo-ai/porelo/actions/runs?head_sha=" + COMMIT + "&per_page=100&page=1",
                200,
                "{\"workflow_runs\":" + json + "}");
    }

    @Test
    void aCommitWhoseRunsAllSucceededIsASuccessAskedWithTheToken() {
        runs(
                "[{\"id\":1,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                        + "{\"id\":2,\"name\":\"Docs\",\"status\":\"completed\",\"conclusion\":\"skipped\"}]");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/1/jobs?per_page=100&page=1",
                200,
                "{\"jobs\":[{\"id\":10,\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"}]}");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/2/jobs?per_page=100&page=1",
                200,
                "{\"jobs\":[]}");

        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .as("a success, with its jobs and without their logs")
                .isEqualTo(
                        new Build(
                                Build.SUCCESS,
                                List.of(new Build.Job("Build / unit", "success")),
                                ""));
        assertThat(asked.get(0).getValue())
                .as("GitHub's API is asked with the token")
                .containsEntry("Authorization", "Bearer secret-token");
    }

    @Test
    void aRunStillGoingMakesTheCommitRunningOrQueued() {
        runs(
                "[{\"id\":1,\"status\":\"completed\",\"conclusion\":\"success\"},{\"id\":2,\"status\":\"in_progress\"}]");
        final Build running = reader().look(TARGET, COMMIT, Build.Logs.ALL);
        assertThat(running.verdict()).as("a run in progress").isEqualTo(Build.RUNNING);
        assertThat(running.jobs())
                .as("no jobs listed while it runs: a question per run every round")
                .isEmpty();
        assertThat(asked).as("only the runs were asked for").hasSize(1);

        runs("[{\"id\":2,\"status\":\"queued\"}]");
        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE).verdict())
                .as("only queued runs")
                .isEqualTo(Build.QUEUED);
    }

    @Test
    void aFailedRunNamesItsFailedJobAndHandsTheLogsTailFetchedWithoutTheToken() {
        runs(
                "[{\"id\":7,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"failure\"},"
                        + "{\"id\":8,\"name\":\"Checks\",\"status\":\"in_progress\"}]");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/7/jobs?per_page=100&page=1",
                200,
                "{\"jobs\":[{\"id\":70,\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                        + "{\"id\":71,\"name\":\"Leg - ubuntu\",\"status\":\"completed\","
                        + "\"conclusion\":\"failure\"}]}");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/8/jobs?per_page=100&page=1",
                200,
                "{\"jobs\":[{\"id\":80,\"name\":\"lint\",\"status\":\"in_progress\",\"conclusion\":null}]}");
        answers.put(
                API + "/repos/porelo-ai/porelo/actions/jobs/71/logs",
                new Http.Response(
                        302,
                        Map.of("location", List.of("https://blobs.example/log?sig=x")),
                        new byte[0]));
        final String log = "x".repeat(Build.TAIL) + "\nERROR the test failed here\n";
        answer("https://blobs.example/log?sig=x", 200, log);

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(build.verdict())
                .as("a failure outweighs a run still going")
                .isEqualTo(Build.FAILURE);
        assertThat(build.jobs())
                .extracting(Build.Job::name, Build.Job::result)
                .as("every job, named as GitHub spells workflow and job")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Build / unit", "success"),
                        org.assertj.core.groups.Tuple.tuple("Build / Leg - ubuntu", "failure"),
                        org.assertj.core.groups.Tuple.tuple("Checks / lint", "in_progress"));
        assertThat(build.jobs().get(0).log()).as("no log of a job that did not fail").isNull();
        assertThat(build.jobs().get(1).logText())
                .as("the end of the failed job's log")
                .endsWith("ERROR the test failed here\n")
                .hasSize(Build.TAIL);
        assertThat(tails)
                .as("a log is read through for its end, never held whole")
                .containsEntry(API + "/repos/porelo-ai/porelo/actions/jobs/71/logs", Build.TAIL)
                .containsEntry("https://blobs.example/log?sig=x", Build.TAIL);
        assertThat(asked)
                .as("the log's host is asked once")
                .filteredOn(each -> each.getKey().getHost().equals("blobs.example"))
                .singleElement()
                .satisfies(
                        each ->
                                assertThat(each.getValue())
                                        .as("the token stays with GitHub")
                                        .isEmpty());
    }

    @Test
    void everyJobsLogOnceTheVerdictIsFinalWhenAllAreWanted() {
        runs("[{\"id\":1,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"success\"}]");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/1/jobs?per_page=100&page=1",
                200,
                "{\"jobs\":[{\"id\":10,\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                        + "{\"id\":11,\"name\":\"docs\",\"status\":\"completed\",\"conclusion\":\"skipped\"}]}");
        answer("/repos/porelo-ai/porelo/actions/jobs/10/logs", 200, "unit ok\n");
        answer("/repos/porelo-ai/porelo/actions/jobs/11/logs", 404, "{}");

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.ALL);

        assertThat(build.verdict()).as("every run succeeded").isEqualTo(Build.SUCCESS);
        assertThat(build.jobs())
                .extracting(Build.Job::name, Build.Job::result, Build.Job::logText)
                .as("each job, with the log GitHub gave and without one where it gave none")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Build / unit", "success", "unit ok\n"),
                        org.assertj.core.groups.Tuple.tuple("Build / docs", "skipped", ""));
        asked.clear();
        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE).jobs())
                .as("a success's jobs all the same, without their logs")
                .extracting(Build.Job::log)
                .containsOnlyNulls()
                .hasSize(2);
        assertThat(asked).as("the runs and their jobs, and no log").hasSize(2);
    }

    @Test
    void aCancelledRunIsCancelled() {
        runs("[{\"id\":1,\"status\":\"completed\",\"conclusion\":\"cancelled\"}]");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/1/jobs?per_page=100&page=1",
                200,
                "{\"jobs\":[]}");

        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE).verdict())
                .as("a cancelled run and none that failed")
                .isEqualTo(Build.CANCELLED);
    }

    @Test
    void aCommitWithoutABuildIsUnknownAndNoRefusal() {
        runs("[]");

        final Build missing = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(missing.verdict()).as("no run of the commit").isEqualTo(Build.UNKNOWN);
        assertThat(missing.detail())
                .as("says which commit and where")
                .contains("no build of 0123456789ab at porelo-ai/porelo");
    }

    @Test
    void aRateLimitIsRefusedWithHowLongItLastsAndARefusedCredentialAsSuch() {
        answers.put(
                API
                        + "/repos/porelo-ai/porelo/actions/runs?head_sha="
                        + COMMIT
                        + "&per_page=100&page=1",
                new Http.Response(
                        403,
                        Map.of(
                                "x-ratelimit-remaining",
                                List.of("0"),
                                "x-ratelimit-reset",
                                List.of(String.valueOf(NOW.getEpochSecond() + 90))),
                        new byte[0]));
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("the limit is waited out")
                                    .isEqualTo(BuildRefused.Reason.RATE_LIMITED);
                            assertThat(refused.retryAfterSeconds())
                                    .as("until the reset GitHub named")
                                    .isEqualTo(90);
                            assertThat(refused.detail()).as("says when").contains("resets at");
                        });

        answer(
                "/repos/porelo-ai/porelo/actions/runs?head_sha=" + COMMIT + "&per_page=100&page=1",
                401,
                "{}");
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("a 401 is a credential GitHub does not take")
                                    .isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED);
                            assertThat(refused.detail())
                                    .as("says what may be wrong with it")
                                    .contains("401")
                                    .contains("expired");
                        });

        // Every 403 was said to be a missing Actions scope, and a person changed a token that had
        // it; the head is asked of the refs, which need Contents too.
        answer(
                "/repos/porelo-ai/porelo/git/ref/heads/fix-login",
                403,
                "{\"message\":\"Resource not accessible by personal access token\"}");
        assertThatThrownBy(() -> reader().head(TARGET, "fix-login"))
                .as("GitHub's own words and what was asked")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("a 403 that is no rate limit")
                                    .isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED);
                            assertThat(refused.detail())
                                    .as("GitHub's words, what was asked, what a reader needs")
                                    .contains("Resource not accessible by personal access token")
                                    .contains("/git/ref/heads/fix-login")
                                    .contains("Actions: Read and Contents: Read")
                                    .doesNotContain("lacks the scope");
                        });
    }

    @Test
    void aGitHubThatDoesNotAnswerIsUnreachable() {
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("nothing answered")
                                    .isEqualTo(BuildRefused.Reason.UNREACHABLE);
                            assertThat(refused.detail())
                                    .as("says so")
                                    .startsWith("GitHub did not answer");
                        });
    }

    @Test
    void anythingButAFullShaIsNoQuestionToGitHub() {
        assertThat(reader().look(TARGET, "main", Build.Logs.FAILURE).verdict())
                .as("a branch name is not a commit")
                .isEqualTo(Build.UNKNOWN);
        assertThat(asked).as("nothing was asked of GitHub").isEmpty();
    }

    @Test
    void theHeadOfABranchIsTheCommitItsRefNames() {
        answer(
                "/repos/porelo-ai/porelo/git/ref/heads/fix-login",
                200,
                "{\"object\":{\"sha\":\"" + COMMIT + "\"}}");

        assertThat(reader().head(TARGET, "fix-login")).as("the ref's object").isEqualTo(COMMIT);
    }

    @Test
    void aPushGitHubHasNotBuiltYetIsPendingNeverTheVerdictOfTheCommitBefore() {

        // Right after a push the newest run is the previous commit's; its verdict must never be
        // said of the new one.
        final String pushed = "f".repeat(40);
        answer(
                "/repos/porelo-ai/porelo/git/ref/heads/fix-login",
                200,
                "{\"object\":{\"sha\":\"" + pushed + "\"}}");
        runs("[{\"id\":1,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"success\"}]");
        answer(
                "/repos/porelo-ai/porelo/actions/runs?head_sha=" + pushed + "&per_page=100&page=1",
                200,
                "{\"workflow_runs\":[]}");

        assertThat(reader().head(TARGET, "fix-login"))
                .as("the branch's tip, not the last commit built")
                .isEqualTo(pushed);
        assertThat(reader().look(TARGET, pushed, Build.Logs.FAILURE).verdict())
                .as("no run yet")
                .isEqualTo(Build.UNKNOWN);
    }

    @Test
    void aBranchGitHubLacksIsNoHeadButARepositoryItDoesNotShowIsRefused() {
        answer("/repos/porelo-ai/porelo/git/ref/heads/gone", 404, "{}");
        answer("/repos/porelo-ai/porelo", 200, "{}");
        assertThat(reader().head(TARGET, "gone"))
                .as("no such branch, in a repository GitHub shows")
                .isEmpty();

        answer("/repos/porelo-ai/porelo", 404, "{}");
        assertThatThrownBy(() -> reader().head(TARGET, "gone"))
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("a repository GitHub does not show")
                                        .isEqualTo(BuildRefused.Reason.NO_SUCH_REPOSITORY));
    }

    @Test
    void readsTheRepositoryFromEveryWayAProjectFileWritesAnUpstream() {
        for (final String upstream :
                List.of(
                        "git@github.com:porelo-ai/porelo.git",
                        "git@github.com:porelo-ai/porelo",
                        "https://github.com/porelo-ai/porelo.git",
                        "https://github.com/porelo-ai/porelo/",
                        "ssh://git@github.com/porelo-ai/porelo.git",
                        "ssh://git@github.com:22/porelo-ai/porelo.git")) {
            assertThat(GitHubReader.repository(new Target(upstream, "", "t")))
                    .as(upstream)
                    .isEqualTo(new GitHubReader.Repository("porelo-ai", "porelo"));
        }
        assertThatThrownBy(() -> GitHubReader.repository(new Target("github.com", "", "t")))
                .as("a host alone names no repository")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUpstreamWhoseOwnerOrNameIsADotSegmentIsNoRepositoryAndNothingIsAsked() {
        // "/repos/../user" is another API path than a repository's, asked with the token.
        for (final String upstream :
                List.of(
                        "git@github.com:../user",
                        "https://github.com/porelo-ai/..",
                        "https://github.com/porelo-ai/...git",
                        "git@github.com:./porelo")) {
            assertThatThrownBy(() -> reader().head(new Target(upstream, API, "t"), "main"))
                    .as(upstream)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(asked).as("nothing was sent with the token").isEmpty();
    }

    @Test
    void everyPageOfACommitsRunsIsReadSoAFailedRunOnTheSecondIsTheVerdict() {
        final String path = "/repos/porelo-ai/porelo/actions/runs?head_sha=" + COMMIT;
        final StringBuilder first = new StringBuilder();
        for (int id = 1; id <= 100; id++) {
            first.append(id == 1 ? "" : ",")
                    .append("{\"id\":")
                    .append(id)
                    .append(",\"name\":\"W")
                    .append(id)
                    .append("\",\"status\":\"completed\",\"conclusion\":\"success\"}");
            answer(
                    "/repos/porelo-ai/porelo/actions/runs/" + id + "/jobs?per_page=100&page=1",
                    200,
                    "{\"total_count\":0,\"jobs\":[]}");
        }
        answer(
                path + "&per_page=100&page=1",
                200,
                "{\"total_count\":101,\"workflow_runs\":[" + first + "]}");
        answer(
                path + "&per_page=100&page=2",
                200,
                "{\"total_count\":101,\"workflow_runs\":[{\"id\":101,\"name\":\"Late\","
                        + "\"status\":\"completed\",\"conclusion\":\"failure\"}]}");
        answer(
                "/repos/porelo-ai/porelo/actions/runs/101/jobs?per_page=100&page=1",
                200,
                "{\"total_count\":1,\"jobs\":[{\"id\":1010,\"name\":\"unit\","
                        + "\"status\":\"completed\",\"conclusion\":\"failure\"}]}");
        answer("/repos/porelo-ai/porelo/actions/jobs/1010/logs", 200, "it failed\n");

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(build.verdict())
                .as("the 101st run failed; a success read from the first page alone is wrong")
                .isEqualTo(Build.FAILURE);
        assertThat(build.jobs())
                .extracting(Build.Job::name, Build.Job::logText)
                .as("the failed job, with its log")
                .contains(org.assertj.core.groups.Tuple.tuple("Late / unit", "it failed\n"));
    }

    @Test
    void moreRunsThanGitHubListsAreNoVerdict() {
        final String path = "/repos/porelo-ai/porelo/actions/runs?head_sha=" + COMMIT;
        for (int page = 1; page <= 10; page++) {
            final StringBuilder runs = new StringBuilder();
            for (int each = 0; each < 100; each++) {
                runs.append(each == 0 ? "" : ",")
                        .append("{\"id\":")
                        .append(page * 1000 + each)
                        .append(",\"status\":\"completed\",\"conclusion\":\"success\"}");
                answer(
                        "/repos/porelo-ai/porelo/actions/runs/"
                                + (page * 1000 + each)
                                + "/jobs?per_page=100&page=1",
                        200,
                        "{\"total_count\":0,\"jobs\":[]}");
            }
            answer(
                    path + "&per_page=100&page=" + page,
                    200,
                    "{\"total_count\":1001,\"workflow_runs\":[" + runs + "]}");
        }

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(build.verdict())
                .as("a run left unread can be the one that failed")
                .isEqualTo(Build.UNKNOWN);
        assertThat(build.detail())
                .as("says why")
                .contains("more runs of 0123456789ab at porelo-ai/porelo than GitHub lists");
    }

    @Test
    void aFailedJobBeyondTheMostJobsHandedOverIsKeptWithItsLog() {
        runs(
                "[{\"id\":1,\"name\":\"Matrix\",\"status\":\"completed\",\"conclusion\":\"failure\"}]");
        final StringBuilder jobs = new StringBuilder();
        for (int id = 1; id <= Build.MOST_JOBS + 10; id++) {
            jobs.append(id == 1 ? "" : ",")
                    .append("{\"id\":")
                    .append(id)
                    .append(",\"name\":\"leg ")
                    .append(id)
                    .append("\",\"status\":\"completed\",\"conclusion\":\"")
                    .append(id == Build.MOST_JOBS + 10 ? "failure" : "success")
                    .append("\"}");
        }
        answer(
                "/repos/porelo-ai/porelo/actions/runs/1/jobs?per_page=100&page=1",
                200,
                "{\"total_count\":" + (Build.MOST_JOBS + 10) + ",\"jobs\":[" + jobs + "]}");
        answer(
                "/repos/porelo-ai/porelo/actions/jobs/" + (Build.MOST_JOBS + 10) + "/logs",
                200,
                "the last leg failed\n");

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(build.jobs()).as("no more than the most handed over").hasSize(Build.MOST_JOBS);
        assertThat(build.jobs().getLast())
                .as("the failed job is kept, last as GitHub lists it, with its log")
                .extracting(Build.Job::name, Build.Job::result, Build.Job::logText)
                .containsExactly(
                        "Matrix / leg " + (Build.MOST_JOBS + 10),
                        "failure",
                        "the last leg failed\n");
        assertThat(build.detail()).as("a job failed, and is named").isEmpty();
    }

    @Test
    void aSecondaryRateLimitIsWaitedOutNeverReportedAsARefusedCredential() {
        answers.put(
                API
                        + "/repos/porelo-ai/porelo/actions/runs?head_sha="
                        + COMMIT
                        + "&per_page=100&page=1",
                new Http.Response(
                        403,
                        Map.of("retry-after", List.of("30")),
                        "{\"message\":\"You have exceeded a secondary rate limit.\"}"
                                .getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .as("a 403 with retry-after")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("a right token must not be sent to be replaced")
                                    .isEqualTo(BuildRefused.Reason.RATE_LIMITED);
                            assertThat(refused.retryAfterSeconds())
                                    .as("as long as GitHub asked")
                                    .isEqualTo(30);
                        });

        answer(
                "/repos/porelo-ai/porelo/actions/runs?head_sha=" + COMMIT + "&per_page=100&page=1",
                403,
                "{\"message\":\"You have exceeded a secondary rate limit. Please wait a few"
                        + " minutes before you try again.\"}");
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .as("a 403 that says so without a header")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("waited out")
                                    .isEqualTo(BuildRefused.Reason.RATE_LIMITED);
                            assertThat(refused.retryAfterSeconds())
                                    .as("at least a minute, as GitHub says to wait")
                                    .isEqualTo(60);
                        });
    }

    @Test
    void aRedirectFromTheApiIsRefusedNeverReadAsNoBuildOrNoBranch() {
        // GitHub answers 301 for a repository that was renamed or moved.
        final String moved =
                "{\"message\":\"Moved Permanently\",\"url\":\"https://forge.example/repositories/1\"}";
        answers.put(
                API
                        + "/repos/porelo-ai/porelo/actions/runs?head_sha="
                        + COMMIT
                        + "&per_page=100&page=1",
                new Http.Response(
                        301,
                        Map.of("location", List.of(API + "/repositories/1/actions/runs")),
                        moved.getBytes(StandardCharsets.UTF_8)));
        answers.put(
                API + "/repos/porelo-ai/porelo/git/ref/heads/fix-login",
                new Http.Response(
                        301,
                        Map.of(
                                "location",
                                List.of(API + "/repositories/1/git/ref/heads/fix-login")),
                        moved.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .as("a moved repository's build")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("the project file names an old address")
                                    .isEqualTo(BuildRefused.Reason.NO_SUCH_REPOSITORY);
                            assertThat(refused.detail()).as("says so").contains("(301)");
                        });
        assertThatThrownBy(() -> reader().head(TARGET, "fix-login"))
                .as("a moved repository's branch")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("the project file names an old address")
                                        .isEqualTo(BuildRefused.Reason.NO_SUCH_REPOSITORY));
        assertThat(asked)
                .as("the redirect is not followed with the token")
                .extracting(each -> each.getKey().getPath())
                .noneMatch(path -> path.startsWith("/repositories/"));
    }

    @Test
    void anAnswerThatIsNotJsonIsAForgeThatDidNotAnswerNeverAFailedReader() {
        answer(
                "/repos/porelo-ai/porelo/actions/runs?head_sha=" + COMMIT + "&per_page=100&page=1",
                200,
                "<html><body>Bad gateway</body></html>");
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE))
                .as("a 200 that is a proxy's page")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("the forge, not the installation")
                                        .isEqualTo(BuildRefused.Reason.UNREACHABLE));

        answer("/repos/porelo-ai/porelo/git/ref/heads/fix-login", 403, "<html>Forbidden</html>");
        assertThatThrownBy(() -> reader().head(TARGET, "fix-login"))
                .as("a 403 that is a page")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("still a refused credential")
                                    .isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED);
                            assertThat(refused.detail())
                                    .as("without words of GitHub's")
                                    .contains("no reason given");
                        });
    }

    @Test
    void asksGitHubsOwnApiWhenTheProjectNamesNone() {
        answer(
                "https://api.github.com/repos/porelo-ai/porelo/git/ref/heads/main",
                200,
                "{\"object\":{\"sha\":\"" + COMMIT + "\"}}");

        assertThat(reader().head(new Target(TARGET.upstream(), "", "t"), "main"))
                .as("asked of api.github.com")
                .isEqualTo(COMMIT);
    }
}
