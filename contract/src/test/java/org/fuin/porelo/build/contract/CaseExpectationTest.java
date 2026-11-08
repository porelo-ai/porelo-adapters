package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaseExpectationTest {
    @TempDir Path directory;

    @Test
    void anInventedHeadCannotAuthorizeAStaleBuild() throws Exception {
        // An invented head could make an adapter attribute a stale build to the current push.
        final String sha = "a".repeat(40);
        write("01.json", Map.of("head", sha));
        Files.writeString(
                directory.resolve("expect.json"),
                Json.write(Map.of("head", "b".repeat(40), "headFrom", "01.json#/head")));
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            assertThatThrownBy(
                            () -> CaseExpectation.read(directory, test("head"), forge.exchanges()))
                    .as("a SHA absent from the recorded body is rejected")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expect.json head must appear in a recorded body");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "result", "order", "omission", "log"})
    void fabricatedJobEvidenceCannotWeakenTheContract(final String damage) throws Exception {
        // Fabricated names, results, order, or logs could conceal a failed or missing job.
        write(
                "01.json",
                Map.of(
                        "jobs",
                        List.of(
                                Map.of("name", "first", "result", "success"),
                                Map.of("name", "second", "result", "failure"))));
        final List<Map<String, Object>> jobs = new ArrayList<>();
        jobs.add(job("first", "success", 0));
        jobs.add(job("second", "failure", 1));
        final String message;
        switch (damage) {
            case "name" -> {
                jobs.getFirst().put("name", "invented");
                message = "job name must appear";
            }
            case "result" -> {
                jobs.getLast().put("result", "success");
                message = "job result must appear";
            }
            case "order" -> {
                java.util.Collections.reverse(jobs);
                message = "every recorded job in forge order";
            }
            case "omission" -> {
                jobs.removeLast();
                message = "every recorded job in forge order";
            }
            case "log" -> {
                jobs.getLast().put("log", "../../missing.json");
                message = "names no recorded exchange";
            }
            default -> throw new AssertionError(damage);
        }
        Files.writeString(
                directory.resolve("expect.json"),
                Json.write(Map.of("jobs", jobs, "runs", List.of())));
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            assertThatThrownBy(
                            () ->
                                    CaseExpectation.read(
                                            directory, test("failed"), forge.exchanges()))
                    .as("fabricated %s evidence is rejected", damage)
                    .hasMessageContaining(message);
        }
    }

    @Test
    void recordedRunsAreTakenWithTheirStateWhileOutstanding() throws Exception {
        // A guard that refuses everything would pass the fabrication cases as well.
        writeRuns();
        writeExpectedRuns(runs());
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            final CaseExpectation expectation =
                    CaseExpectation.read(directory, test("failed"), forge.exchanges());

            assertThat(expectation.runs())
                    .as("each recorded run with its conclusion, or its state while it has none")
                    .containsExactly(
                            new CaseExpectation.Run("Build", "failure"),
                            new CaseExpectation.Run("Lint", "in_progress"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "name",
                "result",
                "order",
                "omission",
                "state of a finished run",
                "result of another run",
                "name from another field"
            })
    void fabricatedRunEvidenceCannotWeakenTheContract(final String damage) throws Exception {
        // Invented, reordered or missing runs would let a reader hide a workflow from Porelo.
        writeRuns();
        final List<Map<String, Object>> runs = runs();
        final String message;
        switch (damage) {
            case "name" -> {
                runs.getFirst().put("name", "invented");
                message = "run name must appear";
            }
            case "result" -> {
                runs.getFirst().put("result", "success");
                message = "run result must appear";
            }
            case "order" -> {
                java.util.Collections.reverse(runs);
                message = "every recorded run in forge order";
            }
            case "omission" -> {
                runs.removeLast();
                message = "every recorded run in forge order";
            }
            case "state of a finished run" -> {
                runs.getFirst().put("result", "completed");
                runs.getFirst().put("resultFrom", RUNS + "/0/status");
                message = "finished run's result is its conclusion";
            }
            case "result of another run" -> {
                runs.getFirst().put("result", "in_progress");
                runs.getFirst().put("resultFrom", RUNS + "/1/status");
                message = "same recorded run";
            }
            case "name from another field" -> {
                runs.getFirst().put("name", "completed");
                runs.getFirst().put("nameFrom", RUNS + "/0/status");
                message = "Run name evidence must name one of";
            }
            default -> throw new AssertionError(damage);
        }
        writeExpectedRuns(runs);
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            assertThatThrownBy(
                            () ->
                                    CaseExpectation.read(
                                            directory, test("failed"), forge.exchanges()))
                    .as("fabricated %s evidence is rejected", damage)
                    .hasMessageContaining(message);
        }
    }

    @Test
    void anUnknownAnswerExpectsNoRuns() throws Exception {
        // Runs beside an unknown verdict would show workflows Porelo cannot judge.
        writeRuns();
        writeExpectedRuns(runs());
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            assertThatThrownBy(
                            () ->
                                    CaseExpectation.read(
                                            directory, test("no-build"), forge.exchanges()))
                    .as("an unknown answer's expectation names no runs")
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("expect.json fields must be [jobs]");
        }
    }

    @Test
    void aRetryDelayMustComeFromTheResponse() throws Exception {
        // A forged short delay could repeat requests while the forge still refuses them.
        Files.writeString(
                directory.resolve("01.json"),
                Json.write(
                        Map.of(
                                "method",
                                "GET",
                                "path",
                                "/limit",
                                "status",
                                429,
                                "headers",
                                Map.of("Retry-After", "17"),
                                "body",
                                "")));
        Files.writeString(directory.resolve("expect.json"), "{\"retryAfter\":1}");
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            assertThatThrownBy(
                            () ->
                                    CaseExpectation.read(
                                            directory, test("rate-limited"), forge.exchanges()))
                    .as("the recorded retry delay is mandatory")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must match the recorded header");
        }
    }

    @Test
    void verdictRulesCannotBeSuppliedByAnAdapter() throws Exception {
        // Letting fixture data choose its verdict would make the mapping check circular.
        write("01.json", Map.of());
        Files.writeString(
                directory.resolve("expect.json"), "{\"jobs\":[],\"verdict\":\"success\"}");
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token")) {
            assertThatThrownBy(
                            () ->
                                    CaseExpectation.read(
                                            directory, test("failed"), forge.exchanges()))
                    .as("only recording-derived values are accepted")
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("expect.json fields must be");
        }
    }

    private void write(final String file, final Object body) throws Exception {
        Files.writeString(
                directory.resolve(file),
                Json.write(
                        Map.of(
                                "method",
                                "GET",
                                "path",
                                "/data",
                                "status",
                                200,
                                "headers",
                                Map.of(),
                                "body",
                                Json.write(body))));
    }

    private static final String RUNS = "01.json#/workflow_runs";

    private void writeRuns() throws Exception {
        final Map<String, Object> lint = new java.util.LinkedHashMap<>();
        lint.put("id", 2);
        lint.put("name", "Lint");
        lint.put("status", "in_progress");
        lint.put("conclusion", null);
        final Map<String, Object> body =
                Map.of(
                        "total_count",
                        2,
                        "workflow_runs",
                        List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "name",
                                        "Build",
                                        "status",
                                        "completed",
                                        "conclusion",
                                        "failure"),
                                lint));
        Files.writeString(
                directory.resolve("01.json"),
                Json.write(
                        Map.of(
                                "method",
                                "GET",
                                "path",
                                "/repos/o/r/actions/runs?head_sha=" + "a".repeat(40),
                                "status",
                                200,
                                "headers",
                                Map.of(),
                                "body",
                                Json.write(body))));
    }

    private void writeExpectedRuns(final List<Map<String, Object>> runs) throws Exception {
        Files.writeString(
                directory.resolve("expect.json"),
                Json.write(Map.of("jobs", List.of(), "runs", runs)));
    }

    private static List<Map<String, Object>> runs() {
        final List<Map<String, Object>> runs = new ArrayList<>();
        runs.add(run("Build", "failure", RUNS + "/0/name", RUNS + "/0/conclusion"));
        runs.add(run("Lint", "in_progress", RUNS + "/1/name", RUNS + "/1/status"));
        return runs;
    }

    private static Map<String, Object> run(
            final String name,
            final String result,
            final String nameFrom,
            final String resultFrom) {
        return new java.util.LinkedHashMap<>(
                Map.of(
                        "name",
                        name,
                        "result",
                        result,
                        "nameFrom",
                        nameFrom,
                        "resultFrom",
                        resultFrom));
    }

    private static Map<String, Object> job(
            final String name, final String result, final int index) {
        return new java.util.LinkedHashMap<>(
                Map.of(
                        "name",
                        name,
                        "result",
                        result,
                        "nameFrom",
                        List.of("01.json#/jobs/" + index + "/name"),
                        "resultFrom",
                        "01.json#/jobs/" + index + "/result"));
    }

    private static ContractCase test(final String name) {
        return ContractCase.all().stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
