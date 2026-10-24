package org.fuin.porelo.build.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.build.http.Http;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForgejoReaderTest {
    private static final String API = "https://forge.example/api/v1";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String REPO = "/repos/core/probe";
    private static final String RUNS = REPO + "/actions/runs?head_sha=" + SHA + "&limit=50&page=1";
    private static final String JOBS = REPO + "/actions/runs/1/jobs";
    private static final String LOG = REPO + "/actions/jobs/1/logs";
    private static final Target TARGET =
            new Target("git@forge.example:core/probe.git", API, "test-token");
    private final Map<String, Http.Response> answers = new LinkedHashMap<>();
    private final List<URI> requests = new ArrayList<>();
    private final ForgejoReader reader =
            new ForgejoReader(
                    (uri, headers, tail) -> {
                        requests.add(uri);
                        assertThat(headers.get("Authorization"))
                                .as("every API request uses Forgejo token authorization")
                                .isEqualTo("token " + TARGET.token());
                        assertThat(uri.getScheme() + "://" + uri.getAuthority())
                                .as("the token stays at the API origin")
                                .isEqualTo("https://forge.example");
                        final Http.Response response = answers.get(uri.toString());
                        if (response == null)
                            throw new IOException("No fixture for " + uri.getPath());
                        return response;
                    });

    private void answer(final String path, final int status, final Object body) {
        answers.put(
                API + path,
                new Http.Response(
                        status, Map.of(), Json.write(body).getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> run(final int id, final String status) {
        return Map.of("id", id, "workflow_id", "check.yml", "status", status);
    }

    private void runs(final String... statuses) {
        final List<Map<String, Object>> runs = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) runs.add(run(i + 1, statuses[i]));
        answer(RUNS, 200, Map.of("total_count", runs.size(), "workflow_runs", runs));
    }

    private void jobs(final String status) {
        answer(JOBS, 200, List.of(Map.of("id", 1, "name", "test", "status", status)));
    }

    private Build build(final Build.Logs logs) {
        return reader.build(TARGET, SHA, logs);
    }

    @Test
    void aJobThatNeverExecutedHasNoLog() {
        // A missing log must not stop polling a visible repository as if it were absent.
        runs("cancelled");
        jobs("cancelled");
        answer(LOG, 404, Map.of("message", "job has not been executed yet"));
        assertThat(build(Build.Logs.ALL))
                .as("unexecuted jobs retain their result without a log")
                .isEqualTo(
                        new Build(
                                Build.CANCELLED,
                                List.of(new Build.Job("check.yml / test", "cancelled")),
                                ""));
    }

    @Test
    void anEmptyApiIsAWrongCallBeforeAnyRequest() {
        // Choosing an implicit forge would send a credential to the wrong server.
        final Target empty = new Target(TARGET.upstream(), "", TARGET.token());
        assertThatThrownBy(() -> reader.head(empty, "main"))
                .as("Forgejo has no public default API")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forgejo has no public default");
        assertThatThrownBy(() -> reader.build(empty, SHA, Build.Logs.NONE))
                .as("build calls require the API too")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forgejo has no public default");
        assertThat(requests).as("no credential was sent").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308})
    void aRedirectNeverTakesTheTokenOutsideTheApiOrigin(final int status) {
        // The destination is controlled by the HTTP response and must never receive the token.
        runs("failure");
        jobs("failure");
        answers.put(
                API + LOG,
                new Http.Response(
                        status,
                        Map.of("location", List.of("https://elsewhere.example/stolen")),
                        new byte[0]));
        assertThatThrownBy(() -> build(Build.Logs.FAILURE))
                .as("redirects are repository refusals")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .as("the project points at a moved API")
                                        .isEqualTo(BuildRefused.Reason.NO_SUCH_REPOSITORY));
        assertThat(requests)
                .as("only the three API URLs were requested")
                .containsExactly(
                        URI.create(API + RUNS), URI.create(API + JOBS), URI.create(API + LOG));
    }

    @Test
    void theHeadComesFromTheBranchCommitId() {
        // Using Actions to find a head would miss a push without a workflow.
        answer(REPO + "/branches/topic/fix", 200, Map.of("commit", Map.of("id", SHA)));
        assertThat(reader.head(TARGET, "topic/fix"))
                .as("the branch's current commit")
                .isEqualTo(SHA);
    }

    @Test
    void anAbsentBranchIsConfirmedAgainstTheRepository() {
        // A branch and an invisible repository both return 404 but need different answers.
        answer(REPO + "/branches/absent", 404, Map.of("message", "The target couldn't be found."));
        answer(REPO, 200, Map.of());
        assertThat(reader.head(TARGET, "absent"))
                .as("the repository exists but its branch does not")
                .isEmpty();
        assertThat(requests)
                .as("the ambiguity was resolved")
                .containsExactly(
                        URI.create(API + REPO + "/branches/absent"), URI.create(API + REPO));
    }

    @ParameterizedTest
    @CsvSource({
        "waiting,queued",
        "blocked,queued",
        "running,running",
        "success,success",
        "skipped,success",
        "cancelled,cancelled",
        "failure,failure"
    })
    void eachSupportedStatusHasItsVerdict(final String status, final String verdict) {
        // Misclassified states can release or strand a task.
        runs(status);
        jobs(status);
        assertThat(build(Build.Logs.NONE).verdict())
                .as("Forgejo's single status %s", status)
                .isEqualTo(verdict);
    }

    @ParameterizedTest
    @CsvSource({
        "waiting,running,running",
        "cancelled,waiting,queued",
        "cancelled,running,running",
        "failure,waiting,failure",
        "failure,cancelled,failure"
    })
    void failurePrecedesOutstandingAndOutstandingPrecedesCancellation(
            final String first, final String second, final String verdict) {
        // A pending sibling cannot conceal an already failed workflow.
        runs(first, second);
        jobs(first);
        answer(
                REPO + "/actions/runs/2/jobs",
                200,
                List.of(Map.of("id", 2, "name", "other", "status", second)));
        assertThat(build(Build.Logs.NONE).verdict()).as("aggregate precedence").isEqualTo(verdict);
    }

    @Test
    void unknownStatesCannotBecomeSuccess() {
        // Future Forgejo statuses must not silently grant a successful verdict.
        runs("future_state");
        final Build unknown = build(Build.Logs.NONE);
        assertThat(unknown.verdict())
                .as("an unfamiliar status cannot grant success")
                .isEqualTo(Build.UNKNOWN);
        assertThat(unknown.detail())
                .as("the caller can identify the unfamiliar value")
                .contains("future_state");
    }

    @Test
    void noRunsCannotInheritAnotherCommitsBuild() {
        // A commit with no workflows must remain unknown.
        runs();
        assertThat(build(Build.Logs.ALL).verdict())
                .as("no build means no verdict")
                .isEqualTo(Build.UNKNOWN);
    }

    @Test
    void logsNoneRequestsNoLogAndFailureRequestsOnlyFailedLogs() {
        // Unrequested logs expose output and waste bandwidth.
        runs("failure");
        answer(
                JOBS,
                200,
                List.of(
                        Map.of("id", 2, "name", "green", "status", "success"),
                        Map.of("id", 1, "name", "red", "status", "failure")));
        answer(LOG, 200, "unused");
        final Build without = build(Build.Logs.NONE);
        assertThat(without.jobs())
                .as("both jobs, neither log")
                .containsExactly(
                        new Build.Job("check.yml / green", "success"),
                        new Build.Job("check.yml / red", "failure"));
        assertThat(requests).as("NONE listed runs and jobs only").hasSize(2);
        answers.put(
                API + LOG,
                new Http.Response(200, Map.of(), "failure end\n".getBytes(StandardCharsets.UTF_8)));
        final Build failure = build(Build.Logs.FAILURE);
        assertThat(failure.jobs().get(1).logText())
                .as("only the failed log")
                .isEqualTo("failure end\n");
        assertThat(requests).as("one extra log download").hasSize(5);
    }

    @Test
    void allLogsWaitsForTheFinalVerdict() {
        // Pending runs cannot expose successful logs before the policy permits them.
        runs("failure", "running");
        jobs("failure");
        answer(
                REPO + "/actions/runs/2/jobs",
                200,
                List.of(Map.of("id", 2, "name", "green", "status", "success")));
        answers.put(API + LOG, new Http.Response(200, Map.of(), new byte[] {42}));
        assertThat(build(Build.Logs.ALL).jobs())
                .as("only failure logs while another run is outstanding")
                .containsExactly(
                        new Build.Job("check.yml / test", "failure", new byte[] {42}),
                        new Build.Job("check.yml / green", "success"));
    }

    @Test
    void runPaginationUsesTotalCountEvenForAShortPage() {
        // Ignoring later pages can hide the failed workflow.
        answer(RUNS, 200, Map.of("total_count", 2, "workflow_runs", List.of(run(1, "success"))));
        answer(
                RUNS.replace("page=1", "page=2"),
                200,
                Map.of("total_count", 2, "workflow_runs", List.of(run(2, "failure"))));
        jobs("success");
        answer(
                REPO + "/actions/runs/2/jobs",
                200,
                List.of(Map.of("id", 2, "name", "last", "status", "failure")));
        assertThat(build(Build.Logs.NONE).verdict())
                .as("the failure is on page two")
                .isEqualTo(Build.FAILURE);
        assertThat(requests)
                .as("both run pages were read")
                .contains(URI.create(API + RUNS.replace("page=1", "page=2")));
    }

    @Test
    void incompleteRunPaginationFailsClosed() {
        // An omitted page cannot authorize a successful verdict.
        answer(RUNS, 200, Map.of("total_count", 2, "workflow_runs", List.of(run(1, "success"))));
        answer(
                RUNS.replace("page=1", "page=2"),
                200,
                Map.of("total_count", 2, "workflow_runs", List.of()));
        assertThatThrownBy(() -> build(Build.Logs.NONE))
                .as("missing run entries are no answer")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .as("incomplete response")
                                        .isEqualTo(BuildRefused.Reason.UNREACHABLE));
    }

    @Test
    void aFailureBeyondFiftyJobsIsKeptAndLogsShareTheirBudget() {
        // Truncation cannot discard the only failure or overflow the socket message.
        runs("failure");
        final List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 1; i <= 60; i++)
            many.add(
                    Map.of("id", i, "name", "leg " + i, "status", i == 60 ? "failure" : "success"));
        answer(JOBS, 200, many);
        final byte[] large = new byte[3 * 1024 * 1024];
        Arrays.fill(large, (byte) 'x');
        large[large.length - 1] = '!';
        for (int i = 1; i <= 60; i++)
            answers.put(
                    API + REPO + "/actions/jobs/" + i + "/logs",
                    new Http.Response(200, Map.of(), large));
        final Build result = build(Build.Logs.ALL);
        assertThat(result.jobs()).as("fifty jobs retain the last failure in order").hasSize(50);
        assertThat(result.jobs().getLast().name())
                .as("the failure survives")
                .isEqualTo("check.yml / leg 60");
        for (final Build.Job job : result.jobs()) {
            assertThat(job.log())
                    .as("equal share of the total log budget")
                    .hasSize(Build.MOST_LOG_BYTES / 50)
                    .endsWith((byte) '!');
        }
    }

    @ParameterizedTest
    @CsvSource({
        "401,CREDENTIAL_REFUSED",
        "403,CREDENTIAL_REFUSED",
        "404,NO_SUCH_REPOSITORY",
        "302,NO_SUCH_REPOSITORY",
        "500,UNREACHABLE"
    })
    void refusalsKeepTheirMeaning(final int status, final BuildRefused.Reason reason) {
        // Wrong refusal types make Porelo retry or replace credentials incorrectly.
        answer(RUNS, status, Map.of("message", "refused"));
        assertThatThrownBy(() -> build(Build.Logs.NONE))
                .as("HTTP %s refusal", status)
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e -> assertThat(e.reason()).as("the refusal type").isEqualTo(reason));
    }

    @Test
    void retryAfterIsPreserved() {
        // A rate limit must delay the next poll rather than revoke the credential.
        answers.put(
                API + RUNS,
                new Http.Response(429, Map.of("retry-after", List.of("30")), new byte[0]));
        assertThatThrownBy(() -> build(Build.Logs.NONE))
                .as("the server's retry delay")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e -> {
                            assertThat(e.reason())
                                    .as("rate limit")
                                    .isEqualTo(BuildRefused.Reason.RATE_LIMITED);
                            assertThat(e.retryAfterSeconds())
                                    .as("seconds before retry")
                                    .isEqualTo(30);
                        });
    }

    @Test
    void nonJsonAndTransportFailuresAreUnreachable() {
        // A proxy page or a lost connection must never become a build verdict.
        answers.put(
                API + RUNS,
                new Http.Response(
                        200, Map.of(), "<html>proxy</html>".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> build(Build.Logs.NONE))
                .as("non JSON is unreachable")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .as("proxy page")
                                        .isEqualTo(BuildRefused.Reason.UNREACHABLE));
        answers.clear();
        assertThatThrownBy(() -> build(Build.Logs.NONE))
                .as("I/O is unreachable")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .as("no response")
                                        .isEqualTo(BuildRefused.Reason.UNREACHABLE));
    }

    @Test
    void aNonJsonRepositoryProbeIsUnreachable() {
        // A proxy page cannot confirm branch absence and stop watching a valid branch.
        answer(REPO + "/branches/absent", 404, Map.of("message", "The target couldn't be found."));
        answers.put(
                API + REPO,
                new Http.Response(
                        200, Map.of(), "<html>proxy</html>".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> reader.head(TARGET, "absent"))
                .as("a repository probe still needs a JSON response")
                .isInstanceOfSatisfying(
                        BuildRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .as("the probe did not answer")
                                        .isEqualTo(BuildRefused.Reason.UNREACHABLE));
    }
}
