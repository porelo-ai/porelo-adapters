package org.fuin.sokar.build.github;

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
import org.fuin.sokar.build.api.Build;
import org.fuin.sokar.build.api.BuildRefused;
import org.fuin.sokar.build.api.Target;
import org.junit.jupiter.api.Test;

class GitHubReaderTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static final String API = "https://forge.example";

    private static final Target TARGET = new Target("git@github.com:sokar-ai/sokar.git", API, "secret-token");

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    /** What the fake GitHub answers, by the address asked; and what was asked, with which headers. */
    private final Map<String, Http.Response> answers = new LinkedHashMap<>();

    private final List<Map.Entry<URI, Map<String, String>>> asked = new ArrayList<>();

    private GitHubReader reader() {
        return new GitHubReader((uri, headers) -> {
            asked.add(Map.entry(uri, headers));
            final Http.Response answer = answers.get(uri.toString());
            if (answer == null) {
                throw new IOException("nothing at " + uri);
            }
            return answer;
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void answer(final String path, final int status, final String body) {
        answers.put(path.startsWith("http") ? path : API + path,
                new Http.Response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8)));
    }

    private void runs(final String json) {
        answer("/repos/sokar-ai/sokar/actions/runs?head_sha=" + COMMIT + "&per_page=50", 200,
                "{\"workflow_runs\":" + json + "}");
    }

    @Test
    void aCommitWhoseRunsAllSucceededIsASuccessAskedWithTheToken() {
        runs("[{\"id\":1,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                + "{\"id\":2,\"name\":\"Docs\",\"status\":\"completed\",\"conclusion\":\"skipped\"}]");
        answer("/repos/sokar-ai/sokar/actions/runs/1/jobs?per_page=100", 200,
                "{\"jobs\":[{\"id\":10,\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"}]}");
        answer("/repos/sokar-ai/sokar/actions/runs/2/jobs?per_page=100", 200, "{\"jobs\":[]}");

        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE)).isEqualTo(new Build(Build.SUCCESS,
                List.of(new Build.Job("Build / unit", "success")), ""));
        assertThat(asked.get(0).getValue()).containsEntry("Authorization", "Bearer secret-token");
    }

    @Test
    void aRunStillGoingMakesTheCommitRunningOrQueued() {
        runs("[{\"id\":1,\"status\":\"completed\",\"conclusion\":\"success\"},{\"id\":2,\"status\":\"in_progress\"}]");
        final Build running = reader().look(TARGET, COMMIT, Build.Logs.ALL);
        assertThat(running.verdict()).isEqualTo(Build.RUNNING);
        assertThat(running.jobs()).as("no jobs listed while it runs: a question per run every round").isEmpty();
        assertThat(asked).as("only the runs were asked for").hasSize(1);

        runs("[{\"id\":2,\"status\":\"queued\"}]");
        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE).verdict()).isEqualTo(Build.QUEUED);
    }

    @Test
    void aFailedRunNamesItsFailedJobAndHandsTheLogsTailFetchedWithoutTheToken() {
        runs("[{\"id\":7,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"failure\"},"
                + "{\"id\":8,\"name\":\"Checks\",\"status\":\"in_progress\"}]");
        answer("/repos/sokar-ai/sokar/actions/runs/7/jobs?per_page=100", 200,
                "{\"jobs\":[{\"id\":70,\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                        + "{\"id\":71,\"name\":\"Leg - ubuntu\",\"status\":\"completed\","
                        + "\"conclusion\":\"failure\"}]}");
        answer("/repos/sokar-ai/sokar/actions/runs/8/jobs?per_page=100", 200,
                "{\"jobs\":[{\"id\":80,\"name\":\"lint\",\"status\":\"in_progress\",\"conclusion\":null}]}");
        answers.put(API + "/repos/sokar-ai/sokar/actions/jobs/71/logs", new Http.Response(302,
                Map.of("location", List.of("https://blobs.example/log?sig=x")), new byte[0]));
        final String log = "x".repeat(Build.TAIL) + "\nERROR the test failed here\n";
        answer("https://blobs.example/log?sig=x", 200, log);

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(build.verdict()).as("a failure outweighs a run still going").isEqualTo(Build.FAILURE);
        assertThat(build.jobs()).extracting(Build.Job::name, Build.Job::result)
                .as("every job, named as GitHub spells workflow and job").containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Build / unit", "success"),
                        org.assertj.core.groups.Tuple.tuple("Build / Leg - ubuntu", "failure"),
                        org.assertj.core.groups.Tuple.tuple("Checks / lint", "in_progress"));
        assertThat(build.jobs().get(0).log()).as("no log of a job that did not fail").isNull();
        assertThat(build.jobs().get(1).logText()).endsWith("ERROR the test failed here\n").hasSize(Build.TAIL);
        assertThat(asked).filteredOn(each -> each.getKey().getHost().equals("blobs.example"))
                .singleElement().satisfies(each -> assertThat(each.getValue())
                        .as("the token stays with GitHub").isEmpty());
    }

    @Test
    void everyJobsLogOnceTheVerdictIsFinalWhenAllAreWanted() {
        runs("[{\"id\":1,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"success\"}]");
        answer("/repos/sokar-ai/sokar/actions/runs/1/jobs?per_page=100", 200,
                "{\"jobs\":[{\"id\":10,\"name\":\"unit\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                        + "{\"id\":11,\"name\":\"docs\",\"status\":\"completed\",\"conclusion\":\"skipped\"}]}");
        answer("/repos/sokar-ai/sokar/actions/jobs/10/logs", 200, "unit ok\n");
        answer("/repos/sokar-ai/sokar/actions/jobs/11/logs", 404, "{}");

        final Build build = reader().look(TARGET, COMMIT, Build.Logs.ALL);

        assertThat(build.verdict()).isEqualTo(Build.SUCCESS);
        assertThat(build.jobs()).extracting(Build.Job::name, Build.Job::result, Build.Job::logText)
                .as("each job, with the log GitHub gave and without one where it gave none")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Build / unit", "success", "unit ok\n"),
                        org.assertj.core.groups.Tuple.tuple("Build / docs", "skipped", ""));
        asked.clear();
        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE).jobs())
                .as("a success's jobs all the same, without their logs").extracting(Build.Job::log).containsOnlyNulls()
                .hasSize(2);
        assertThat(asked).as("the runs and their jobs, and no log").hasSize(2);
    }

    @Test
    void aCancelledRunIsCancelled() {
        runs("[{\"id\":1,\"status\":\"completed\",\"conclusion\":\"cancelled\"}]");
        answer("/repos/sokar-ai/sokar/actions/runs/1/jobs?per_page=100", 200, "{\"jobs\":[]}");

        assertThat(reader().look(TARGET, COMMIT, Build.Logs.FAILURE).verdict()).isEqualTo(Build.CANCELLED);
    }

    @Test
    void aCommitWithoutABuildIsUnknownAndNoRefusal() {
        runs("[]");

        final Build missing = reader().look(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(missing.verdict()).isEqualTo(Build.UNKNOWN);
        assertThat(missing.detail()).contains("no build of 0123456789ab at sokar-ai/sokar");
    }

    @Test
    void aRateLimitIsRefusedWithHowLongItLastsAndARefusedCredentialAsSuch() {
        answers.put(API + "/repos/sokar-ai/sokar/actions/runs?head_sha=" + COMMIT + "&per_page=50",
                new Http.Response(403, Map.of("x-ratelimit-remaining", List.of("0"),
                        "x-ratelimit-reset", List.of(String.valueOf(NOW.getEpochSecond() + 90))), new byte[0]));
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE)).isInstanceOfSatisfying(BuildRefused.class, refused -> {
            assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.RATE_LIMITED);
            assertThat(refused.retryAfterSeconds()).as("until the reset GitHub named").isEqualTo(90);
            assertThat(refused.detail()).contains("resets at");
        });

        answer("/repos/sokar-ai/sokar/actions/runs?head_sha=" + COMMIT + "&per_page=50", 401, "{}");
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE)).isInstanceOfSatisfying(BuildRefused.class, refused -> {
            assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED);
            assertThat(refused.detail()).contains("401").contains("expired");
        });

        // Every 403 was said to be a missing Actions scope, and a person changed a token that had
        // it; the head is asked of the refs, which need Contents too.
        answer("/repos/sokar-ai/sokar/git/ref/heads/fix-login", 403,
                "{\"message\":\"Resource not accessible by personal access token\"}");
        assertThatThrownBy(() -> reader().head(TARGET, "fix-login")).as("GitHub's own words and what was asked")
                .isInstanceOfSatisfying(BuildRefused.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED);
                    assertThat(refused.detail()).contains("Resource not accessible by personal access token")
                            .contains("/git/ref/heads/fix-login").contains("Actions: Read and Contents: Read")
                            .doesNotContain("lacks the scope");
                });
    }

    @Test
    void aGitHubThatDoesNotAnswerIsUnreachable() {
        assertThatThrownBy(() -> reader().look(TARGET, COMMIT, Build.Logs.FAILURE)).isInstanceOfSatisfying(BuildRefused.class,
                refused -> {
                    assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.UNREACHABLE);
                    assertThat(refused.detail()).startsWith("GitHub did not answer");
                });
    }

    @Test
    void anythingButAFullShaIsNoQuestionToGitHub() {
        assertThat(reader().look(TARGET, "main", Build.Logs.FAILURE).verdict()).isEqualTo(Build.UNKNOWN);
        assertThat(asked).isEmpty();
    }

    @Test
    void theHeadOfABranchIsTheCommitItsRefNames() {
        answer("/repos/sokar-ai/sokar/git/ref/heads/fix-login", 200, "{\"object\":{\"sha\":\"" + COMMIT + "\"}}");

        assertThat(reader().head(TARGET, "fix-login")).isEqualTo(COMMIT);
    }

    @Test
    void aPushGitHubHasNotBuiltYetIsPendingNeverTheVerdictOfTheCommitBefore() {

        // Right after a push the newest run is the previous commit's; its verdict must
        // never be said of the new one.
        final String pushed = "f".repeat(40);
        answer("/repos/sokar-ai/sokar/git/ref/heads/fix-login", 200, "{\"object\":{\"sha\":\"" + pushed + "\"}}");
        runs("[{\"id\":1,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"success\"}]");
        answer("/repos/sokar-ai/sokar/actions/runs?head_sha=" + pushed + "&per_page=50", 200, "{\"workflow_runs\":[]}");

        assertThat(reader().head(TARGET, "fix-login")).as("the branch's tip, not the last commit built").isEqualTo(pushed);
        assertThat(reader().look(TARGET, pushed, Build.Logs.FAILURE).verdict()).as("no run yet")
                .isEqualTo(Build.UNKNOWN);
    }

    @Test
    void aBranchGitHubLacksIsNoHeadButARepositoryItDoesNotShowIsRefused() {
        answer("/repos/sokar-ai/sokar/git/ref/heads/gone", 404, "{}");
        answer("/repos/sokar-ai/sokar", 200, "{}");
        assertThat(reader().head(TARGET, "gone")).isEmpty();

        answer("/repos/sokar-ai/sokar", 404, "{}");
        assertThatThrownBy(() -> reader().head(TARGET, "gone")).isInstanceOfSatisfying(BuildRefused.class,
                refused -> assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.NO_SUCH_REPOSITORY));
    }

    @Test
    void readsTheRepositoryFromEveryWayAProjectFileWritesAnUpstream() {
        for (final String upstream : List.of("git@github.com:sokar-ai/sokar.git", "git@github.com:sokar-ai/sokar",
                "https://github.com/sokar-ai/sokar.git", "https://github.com/sokar-ai/sokar/",
                "ssh://git@github.com/sokar-ai/sokar.git", "ssh://git@github.com:22/sokar-ai/sokar.git")) {
            assertThat(GitHubReader.repository(new Target(upstream, "", "t")))
                    .as(upstream).isEqualTo(new GitHubReader.Repository("sokar-ai", "sokar"));
        }
        assertThatThrownBy(() -> GitHubReader.repository(new Target("github.com", "", "t")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void asksGitHubsOwnApiWhenTheProjectNamesNone() {
        answer("https://api.github.com/repos/sokar-ai/sokar/git/ref/heads/main", 200,
                "{\"object\":{\"sha\":\"" + COMMIT + "\"}}");

        assertThat(reader().head(new Target(TARGET.upstream(), "", "t"), "main")).isEqualTo(COMMIT);
    }
}
