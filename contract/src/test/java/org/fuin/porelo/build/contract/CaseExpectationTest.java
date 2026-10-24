package org.fuin.porelo.build.contract;

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
        Files.writeString(directory.resolve("expect.json"), Json.write(Map.of("jobs", jobs)));
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
