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
import org.fuin.porelo.build.http.Http;
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

    private GitHubReader reader() {
        return new GitHubReader(
                (uri, headers, tail) -> {
                    asked.add(Map.entry(uri, headers));
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

    @Test
    void aPrimaryRateLimitUsesItsResetAndAForbiddenCredentialKeepsGitHubsWords() {
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
        assertThatThrownBy(() -> reader().build(TARGET, COMMIT, Build.Logs.FAILURE))
                .as("the primary limit keeps GitHub's reset time")
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

        // A guessed scope can send a valid credential for replacement; preserve GitHub's words.
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
    void anythingButAFullShaIsNoQuestionToGitHub() {
        assertThat(reader().build(TARGET, "main", Build.Logs.FAILURE).verdict())
                .as("a branch name is not a commit")
                .isEqualTo(Build.UNKNOWN);
        assertThat(asked).as("nothing was asked of GitHub").isEmpty();
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

        final Build build = reader().build(TARGET, COMMIT, Build.Logs.FAILURE);

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

        final Build build = reader().build(TARGET, COMMIT, Build.Logs.FAILURE);

        assertThat(build.verdict())
                .as("a run left unread can be the one that failed")
                .isEqualTo(Build.UNKNOWN);
        assertThat(build.detail())
                .as("says why")
                .contains("more runs of 0123456789ab at porelo-ai/porelo than GitHub lists");
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

        assertThatThrownBy(() -> reader().build(TARGET, COMMIT, Build.Logs.FAILURE))
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
        assertThatThrownBy(() -> reader().build(TARGET, COMMIT, Build.Logs.FAILURE))
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
