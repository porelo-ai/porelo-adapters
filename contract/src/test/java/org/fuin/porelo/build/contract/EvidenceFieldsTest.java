package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceFieldsTest {
    @TempDir Path directory;

    @Test
    void aTruncatedClosingLineCannotEvidenceFailedTests() throws Exception {
        // Fixture evidence must not approve a summary whose closing line was cut in transit.
        final Path original = recordings("github").resolve("failed-tests");
        try (Stream<Path> files = Files.list(original)) {
            for (final Path file : files.toList())
                Files.copy(file, directory.resolve(file.getFileName()));
        }
        final Path log = directory.resolve("failed-job.txt");
        final String text = Files.readString(log);
        final int results = text.lastIndexOf("[INFO] Results:");
        final int closing = text.indexOf("Tests run:", results);
        assertThat(closing)
                .as("the captured summary contains a closing line to truncate")
                .isGreaterThan(results);
        Files.writeString(log, text.substring(0, closing + "Tests run:".length()));
        assertThatThrownBy(
                        () ->
                                CaseExpectation.read(
                                        directory,
                                        test("failed-tests"),
                                        RecordedForge.read(directory),
                                        "github.com"))
                .as("a cut closing line cannot support any expected failed test")
                .isInstanceOf(AssertionError.class);
    }

    @TestFactory
    Stream<DynamicTest> everyRecordingSuppliesEvidenceForItsExpectedFields() {
        // A malformed expectation can make a correct adapter fail or conceal a wrong answer.
        return Stream.of("github", "forgejo")
                .flatMap(
                        forge ->
                                ContractCase.all().stream()
                                        .map(
                                                test ->
                                                        DynamicTest.dynamicTest(
                                                                forge
                                                                        + " supplies evidence for "
                                                                        + test.name(),
                                                                () -> {
                                                                    final Path path =
                                                                            recordings(forge)
                                                                                    .resolve(
                                                                                            test
                                                                                                    .name());
                                                                    final CaseExpectation expected =
                                                                            CaseExpectation.read(
                                                                                    path,
                                                                                    test,
                                                                                    RecordedForge
                                                                                            .read(
                                                                                                    path),
                                                                                    forge.equals(
                                                                                                    "github")
                                                                                            ? "github.com"
                                                                                            : "forge.example");
                                                                    if (test.name()
                                                                            .equals("triggers")) {
                                                                        assertThat(
                                                                                        expected
                                                                                                .runs()
                                                                                                .stream()
                                                                                                .map(
                                                                                                        CaseExpectation
                                                                                                                        .Run
                                                                                                                ::event))
                                                                                .as(
                                                                                        "both captured"
                                                                                                + " triggers"
                                                                                                + " remain"
                                                                                                + " distinguishable")
                                                                                .containsExactlyInAnyOrder(
                                                                                        "push",
                                                                                        forge
                                                                                                        .equals(
                                                                                                                "github")
                                                                                                ? "schedule"
                                                                                                : "pull_request");
                                                                        assertThat(expected.jobs())
                                                                                .as(
                                                                                        "the trigger"
                                                                                                + " case"
                                                                                                + " contains"
                                                                                                + " jobs"
                                                                                                + " to associate")
                                                                                .isNotEmpty()
                                                                                .allSatisfy(
                                                                                        job ->
                                                                                                assertThat(
                                                                                                                job
                                                                                                                        .run())
                                                                                                        .as(
                                                                                                                "every"
                                                                                                                        + " trigger-case"
                                                                                                                        + " job belongs"
                                                                                                                        + " to a"
                                                                                                                        + " run")
                                                                                                        .isNotNull());
                                                                    }
                                                                    if (test.name()
                                                                            .equals(
                                                                                    "failed-tests")) {
                                                                        assertThat(
                                                                                        expected.jobs()
                                                                                                .getFirst()
                                                                                                .failedTests())
                                                                                .as(
                                                                                        "the real"
                                                                                                + " Maven"
                                                                                                + " summary"
                                                                                                + " names"
                                                                                                + " exactly"
                                                                                                + " seven"
                                                                                                + " errors")
                                                                                .hasSize(7);
                                                                        assertThat(
                                                                                        expected.jobs()
                                                                                                .getFirst()
                                                                                                .url())
                                                                                .as(
                                                                                        "the failed"
                                                                                                + " job has"
                                                                                                + " an evidenced"
                                                                                                + " page")
                                                                                .isNotNull();
                                                                    }
                                                                })));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "event",
                "id",
                "run",
                "url",
                "failedTests",
                "failedTestsFrom",
                "runFrom",
                "urlFrom",
                "missing eventFrom",
                "omitted event",
                "omitted id",
                "omitted run",
                "omitted tests",
                "omitted url"
            })
    void inventedOptionalFieldsCannotWeakenTheContract(final String damage) throws Exception {
        // Forged metadata could pass a reader that attributes a failure to another run or test.
        final Path original = recordings("github").resolve("failed-tests");
        try (Stream<Path> files = Files.list(original)) {
            for (final Path file : files.toList())
                Files.copy(file, directory.resolve(file.getFileName()));
        }
        final Map<?, ?> parsed =
                (Map<?, ?>) Json.parse(Files.readString(directory.resolve("expect.json")));
        if (parsed == null) throw new AssertionError("The fixture must be an object");
        final Map<String, Object> expected = copy(parsed);
        final List<Map<String, Object>> jobs = objects(expected.get("jobs"));
        final List<Map<String, Object>> runs = objects(expected.get("runs"));
        switch (damage) {
            case "event" -> runs.getFirst().put("event", "schedule");
            case "id" -> runs.getFirst().put("id", "9007199254740993");
            case "run" -> jobs.getFirst().put("run", "1");
            case "url" -> jobs.getFirst().put("url", "https://other.example/log");
            case "failedTests" -> {
                final List<Object> names =
                        new ArrayList<>((List<?>) jobs.getFirst().get("failedTests"));
                names.set(0, "Invented.test");
                jobs.getFirst().put("failedTests", names);
            }
            case "failedTestsFrom" -> {
                final List<String> refs = new ArrayList<>();
                for (final Object ignored : (List<?>) jobs.getFirst().get("failedTests"))
                    refs.add("04.json#line=1");
                jobs.getFirst().put("failedTestsFrom", refs);
            }
            case "runFrom" -> jobs.getFirst().put("runFrom", "02.json#/jobs/0/id");
            case "urlFrom" -> jobs.getFirst().put("urlFrom", "01.json#/workflow_runs/0/name");
            case "missing eventFrom" -> runs.getFirst().remove("eventFrom");
            case "omitted event" -> {
                runs.getFirst().remove("event");
                runs.getFirst().remove("eventFrom");
            }
            case "omitted id" -> {
                runs.getFirst().remove("id");
                runs.getFirst().remove("idFrom");
            }
            case "omitted run" -> {
                jobs.getFirst().remove("run");
                jobs.getFirst().remove("runFrom");
            }
            case "omitted tests" -> {
                jobs.getFirst().remove("failedTests");
                jobs.getFirst().remove("failedTestsFrom");
            }
            case "omitted url" -> {
                jobs.getFirst().remove("url");
                jobs.getFirst().remove("urlFrom");
            }
            default -> throw new AssertionError(damage);
        }
        expected.put("jobs", jobs);
        expected.put("runs", runs);
        Files.writeString(directory.resolve("expect.json"), Json.write(expected));
        assertThatThrownBy(
                        () ->
                                CaseExpectation.read(
                                        directory,
                                        test("failed-tests"),
                                        RecordedForge.read(directory),
                                        "github.com"))
                .as("fabricated %s evidence is rejected", damage)
                .isInstanceOfAny(AssertionError.class, java.io.IOException.class);
    }

    @Test
    void aJobListPathCannotClaimTheJobsOfAnotherRun() throws Exception {
        // A matching id elsewhere in the response must not authorize a foreign job association.
        final Path original = recordings("github").resolve("triggers");
        try (Stream<Path> files = Files.list(original)) {
            for (final Path file : files.toList())
                Files.copy(file, directory.resolve(file.getFileName()));
        }
        final Path jobList = directory.resolve("02.json");
        final String body = Files.readString(jobList);
        Files.writeString(jobList, body.replace("/runs/41298055478/jobs", "/runs/1/jobs"));
        assertThatThrownBy(
                        () ->
                                CaseExpectation.read(
                                        directory,
                                        test("triggers"),
                                        RecordedForge.read(directory),
                                        "github.com"))
                .as("the listing path is required evidence of the job's run")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("jobs request ties each job");
    }

    private static Path recordings(final String forge) {
        return Path.of("..", "adapters", forge, "src", "test", "resources", "recordings");
    }

    private static ContractCase test(final String name) {
        return ContractCase.all().stream()
                .filter(test -> test.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> copy(final Map<?, ?> source) {
        final Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put((String) key, value));
        return result;
    }

    private static List<Map<String, Object>> objects(final Object source) {
        return ((List<?>) source).stream().map(each -> copy((Map<?, ?>) each)).toList();
    }
}
