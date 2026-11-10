package org.fuin.porelo.build.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.build.http.Http;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RunDetailsTest {
    private static final String SHA = "a".repeat(40);
    private static final String REPO = "/repos/owner/repository";
    private static final String API = "https://forge.example";
    private static final Target TARGET =
            new Target("https://forge.example/owner/repository", API, "test-token");
    private final List<URI> requests = new ArrayList<>();
    private final Map<String, Http.Response> answers = new LinkedHashMap<>();
    private final GitHubReader reader =
            new GitHubReader(
                    (uri, headers, tail) -> {
                        requests.add(uri);
                        final Http.Response answer =
                                answers.get(uri.toString().substring(API.length()));
                        if (answer == null)
                            throw new AssertionError("No answer for " + uri.getPath());
                        return answer;
                    },
                    Clock.systemUTC());

    private void answer(final String path, final Object body) {
        answers.put(
                path,
                new Http.Response(
                        200, Map.of(), Json.write(body).getBytes(StandardCharsets.UTF_8)));
    }

    private void runs(final List<Map<String, @Nullable Object>> runs) {
        answer(
                REPO + "/actions/runs?head_sha=" + SHA + "&per_page=100&page=1",
                Map.of("total_count", runs.size(), "workflow_runs", runs));
    }

    private static Map<String, @Nullable Object> run(final Object id, final String result) {
        final Map<String, @Nullable Object> run = new LinkedHashMap<>();
        run.put("id", id);
        run.put("name", "Build");
        run.put("status", result.equals("queued") ? "queued" : "completed");
        run.put("conclusion", result.equals("queued") ? null : result);
        run.put("event", "push");
        run.put("html_url", "https://forge.example/owner/repository/actions/runs/9");
        return run;
    }

    private void jobs(final String id, final List<Map<String, Object>> jobs) {
        answer(
                REPO + "/actions/runs/" + id + "/jobs" + "?per_page=100&page=1",
                Map.of("total_count", jobs.size(), "jobs", jobs));
    }

    private static Map<String, Object> job(final Object id, final String result) {
        final Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", id);
        job.put("name", "unit");
        job.put("status", "completed");
        job.put("conclusion", result);
        job.put("html_url", "https://forge.example/owner/repository/actions/runs/9/job/10");
        return job;
    }

    @Test
    void everyQueuedRunIsNamedBeforeAnyJobCanBeRequested() {
        // An empty or partial run list hides outstanding work from the person waiting on a build.
        final Map<String, @Nullable Object> first = run(1, "queued");
        final Map<String, @Nullable Object> second = run(2, "queued");
        second.put("event", "pull_request");
        runs(List.of(first, second));
        final Build build = reader.build(TARGET, SHA, Build.Logs.ALL);
        assertThat(build.verdict()).as("both runs are still queued").isEqualTo(Build.QUEUED);
        assertThat(build.runs())
                .as("both outstanding runs retain their distinct triggers and ids")
                .containsExactly(
                        new Build.Run("Build", "queued", "push", "1"),
                        new Build.Run("Build", "queued", "pull_request", "2"));
        assertThat(build.jobs()).as("jobs are withheld while the entire build is queued").isEmpty();
        assertThat(requests).as("queued builds require no job requests").hasSize(1);
    }

    @Test
    void invalidRunIdsNeverNameOrRequestAnotherRunsJobs() {
        // Rounding an external id can attach another run's jobs to the build.
        for (final Object id : List.of(0, -1, 1.5, 1e30, "12", true)) {
            runs(List.of(run(id, "success")));
            final int before = requests.size();
            final Build build = reader.build(TARGET, SHA, Build.Logs.NONE);
            assertThat(build.runs().getFirst().id()).as("invalid run id %s is absent", id).isNull();
            assertThat(requests.subList(before, requests.size()))
                    .as("invalid ids permit only the run-list request")
                    .hasSize(1);
        }
        final Map<String, @Nullable Object> absent = run(1, "success");
        absent.remove("id");
        runs(List.of(absent));
        assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).runs().getFirst().id())
                .as("a missing id cannot name a run")
                .isNull();
    }

    @Test
    void positiveIntegralIdsAreFormattedWithoutFloatingPointRounding() {
        // A rounded large id requests a different build and attributes its result to this one.
        for (final long id : List.of(1L, 9007199254740993L, Long.MAX_VALUE)) {
            runs(List.of(run(id, "success")));
            jobs(Long.toString(id), List.of(job(10, "success")));
            final Build build = reader.build(TARGET, SHA, Build.Logs.NONE);
            assertThat(build.runs().getFirst().id())
                    .as("positive integer ids keep all their digits")
                    .isEqualTo(Long.toString(id));
            assertThat(build.jobs().getFirst().run())
                    .as("the job belongs to the exact listed run")
                    .isEqualTo(Long.toString(id));
        }
    }

    @Test
    void onlyOnePrintableShortStringCanNameATrigger() {
        // Control text or invented trigger values can forge a line in the build status.
        for (final Object event :
                List.of(
                        "",
                        "  ",
                        "push\nschedule",
                        "x".repeat(65),
                        42,
                        true,
                        "push\u2028schedule",
                        "push\u200bschedule")) {
            final Map<String, @Nullable Object> run = run(1, "queued");
            run.put("event", event);
            runs(List.of(run));
            assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).runs().getFirst().event())
                    .as("invalid event %s is absent", event)
                    .isNull();
        }
        final Map<String, @Nullable Object> run = run(1, "queued");
        run.put("event", null);
        runs(List.of(run));
        assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).runs().getFirst().event())
                .as("an absent trigger stays absent")
                .isNull();
        run.put("event", "PUSH");
        runs(List.of(run));
        assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).runs().getFirst().event())
                .as("the forge's valid trigger is lower case")
                .isEqualTo("push");
    }

    @Test
    void aRunListedTwiceIsNamedAndAskedForItsJobsOnlyOnce() {
        // Duplicate runs would report jobs and failures twice.
        runs(List.of(run(1, "success"), run(1, "success")));
        jobs("1", List.of(job(10, "success")));
        final Build build = reader.build(TARGET, SHA, Build.Logs.NONE);
        assertThat(build.runs()).as("the same run is named once").hasSize(1);
        assertThat(build.jobs()).as("the same run's jobs are listed once").hasSize(1);
        assertThat(requests).as("one list and one jobs request are enough").hasSize(2);
    }

    @Test
    void failedRunsBeyondTheReplyLimitStillHaveTheirJobsReadWithinTheRequestLimit() {
        // A large run list must neither flood the forge nor conceal a late failed run.
        final List<Map<String, @Nullable Object>> runs = new ArrayList<>();
        for (int id = 1; id <= 60; id++) {
            runs.add(run(id, id == 60 ? "failure" : "success"));
            jobs(Integer.toString(id), List.of(job(id, id == 60 ? "failure" : "success")));
        }
        runs(runs);
        final Build build = reader.build(TARGET, SHA, Build.Logs.NONE);
        final List<String> jobPaths =
                requests.stream().map(URI::getPath).filter(path -> path.endsWith("/jobs")).toList();
        assertThat(jobPaths)
                .as("only fifty runs may fan out to job lists")
                .hasSize(Build.MOST_RUNS);
        assertThat(jobPaths.getFirst())
                .as("a failure is queried before successful runs")
                .endsWith("/60/jobs");
        final Build.Job failed =
                build.jobs().stream()
                        .filter(job -> job.result().equals("failure"))
                        .findFirst()
                        .orElseThrow();
        assertThat(failed.run())
                .as("a run beyond the fifty named runs cannot leave a dangling association")
                .isNull();
        assertThat(build.runs())
                .as("only the first fifty runs in forge order are named")
                .hasSize(Build.MOST_RUNS);
    }

    @Test
    void everyJobBranchKeepsItsRunAndItsOwnForgePage() {
        // Missing associations or links make identically named jobs impossible to distinguish.
        runs(List.of(run(1, "failure")));
        jobs("1", List.of(job(10, "failure"), job(11, "success")));
        answers.put(
                REPO + "/actions/jobs/10/logs",
                new Http.Response(
                        200,
                        Map.of(),
                        "[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test.failed:3\n[INFO] Tests run: 1\n"
                                .getBytes(StandardCharsets.UTF_8)));
        answers.put(
                REPO + "/actions/jobs/11/logs",
                new Http.Response(
                        200,
                        Map.of(),
                        "[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test.notFailed:3\n[INFO] Tests run: 1\n"
                                .getBytes(StandardCharsets.UTF_8)));
        for (final Build.Logs logs : Build.Logs.values()) {
            final Build build = reader.build(TARGET, SHA, logs);
            assertThat(build.jobs())
                    .as("both red and green jobs keep their association")
                    .allSatisfy(
                            each ->
                                    assertThat(each.run())
                                            .as("the listing run owns every job")
                                            .isEqualTo("1"));
            assertThat(build.jobs().getFirst().url())
                    .as("the job page is retained even without a log")
                    .isEqualTo("https://forge.example/owner/repository/actions/runs/9/job/10");
            assertThat(build.jobs().getFirst().failedTests())
                    .as("only requested failure logs identify tests")
                    .isEqualTo(logs == Build.Logs.NONE ? null : List.of("Test.failed"));
            assertThat(build.jobs().getLast().failedTests())
                    .as("a successful job never identifies failed tests, even under all logs")
                    .isNull();
        }
    }

    @Test
    void malformedOrForeignPagesNeverBecomeJobLinks() {
        // A forge response must not send the person to a credential-bearing or foreign page.
        for (final String url :
                List.of(
                        "http://forge.example/runs/1",
                        "https://other.example/runs/1",
                        "https://user@forge.example/runs/1",
                        "https://forge.example/a b",
                        "https://forge.example/" + "x".repeat(2048))) {
            final Map<String, @Nullable Object> run = run(1, "success");
            final Map<String, Object> job = job(10, "success");
            job.put("html_url", url);
            runs(List.of(run));
            jobs("1", List.of(job));
            assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).jobs().getFirst().url())
                    .as("the invalid or foreign job page is omitted")
                    .isNull();
        }
    }

    @Test
    void unfamiliarConclusionsNeverGrantSuccessAndCannotHideAFailure() {
        // A false green verdict can release a task whose checks need intervention.
        for (final String conclusion : List.of("action_required", "stale", "<none>")) {
            final Map<String, @Nullable Object> unknown = run(1, conclusion);
            if (conclusion.equals("<none>")) unknown.put("conclusion", null);
            runs(List.of(unknown));
            final Build build = reader.build(TARGET, SHA, Build.Logs.NONE);
            assertThat(build.verdict())
                    .as("only known green conclusions grant success")
                    .isEqualTo(Build.UNKNOWN);
            assertThat(build.detail())
                    .as("the unfamiliar conclusion is named")
                    .contains(conclusion);
            runs(List.of(unknown, run(2, "failure")));
            jobs("1", List.of());
            jobs("2", List.of(job(10, "failure")));
            assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).verdict())
                    .as("an unknown sibling cannot conceal a failed run")
                    .isEqualTo(Build.FAILURE);
        }
    }

    @Test
    void anUnfamiliarOrMissingRunStatusIsUnknownNeverRunning() {
        // A run reported running forever keeps a caller waiting out its deadline instead of acting.
        for (final String status : List.of("stale", "failure", "<none>")) {
            final Map<String, @Nullable Object> odd = run(1, "success");
            odd.put("status", status.equals("<none>") ? null : status);
            odd.put("conclusion", null);
            runs(List.of(odd));
            final Build build = reader.build(TARGET, SHA, Build.Logs.NONE);
            assertThat(build.verdict())
                    .as("status %s is not a state the reader knows", status)
                    .isEqualTo(Build.UNKNOWN);
            assertThat(build.detail()).as("the unfamiliar status is named").contains(status);
            runs(List.of(odd, run(2, "failure")));
            jobs("1", List.of());
            jobs("2", List.of(job(10, "failure")));
            assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).verdict())
                    .as("an unfamiliar status cannot conceal a failed run")
                    .isEqualTo(Build.FAILURE);
        }
    }

    @Test
    void onlyTheKnownGreenAndFailedConclusionsReceiveTheirVerdicts() {
        // New forge conclusions must not accidentally pass through a permissive verdict table.
        for (final String result :
                List.of(
                        "success",
                        "skipped",
                        "neutral",
                        "cancelled",
                        "failure",
                        "timed_out",
                        "startup_failure")) {
            runs(List.of(run(1, result)));
            jobs("1", List.of());
            final String expected =
                    List.of("success", "skipped", "neutral").contains(result)
                            ? Build.SUCCESS
                            : result.equals("cancelled") ? Build.CANCELLED : Build.FAILURE;
            assertThat(reader.build(TARGET, SHA, Build.Logs.NONE).verdict())
                    .as("GitHub's documented conclusion %s has its explicit verdict", result)
                    .isEqualTo(expected);
        }
    }

    @Test
    void aJobNamingAnotherRunIsNeverAttributedOrDownloaded() {
        // A mismatched run_id must not attach another run's result and log to this build.
        runs(List.of(run(1, "failure")));
        final Map<String, Object> other = job(10, "failure");
        other.put("run_id", 2);
        jobs("1", List.of(other));
        assertThat(reader.build(TARGET, SHA, Build.Logs.ALL).jobs())
                .as("a foreign run's job is rejected before its log is requested")
                .isEmpty();
        assertThat(requests).as("only runs and the job list were read").hasSize(2);
    }

    @Test
    void invalidJobIdsNeverDownloadAnotherJobsLog() {
        // Rounding a job id could disclose and summarize the wrong job's log.
        runs(List.of(run(1, "failure")));
        for (final Object id : List.of(0, -1, 12.5, 1e30, "12")) {
            jobs("1", List.of(job(id, "failure")));
            final int before = requests.size();
            assertThat(reader.build(TARGET, SHA, Build.Logs.ALL).jobs().getFirst().log())
                    .as("invalid job ids leave the listed job without a downloaded log")
                    .isNull();
            assertThat(requests.subList(before, requests.size()))
                    .as("an invalid id permits no log request")
                    .hasSize(2);
        }
    }
}
