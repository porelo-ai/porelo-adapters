package org.fuin.porelo.build.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgejoRecorderTest {
    @TempDir Path directory;

    @Test
    void aCutSummaryCannotBecomeRecordedFailedTests() throws Exception {
        // A converter must not turn a partial log into evidence of a complete test result block.
        final Path capture = captures("cut-summary");
        final Path log;
        try (var files = Files.list(capture)) {
            log =
                    files.filter(
                                    file ->
                                            file.getFileName()
                                                            .toString()
                                                            .contains("-log-failed-tests-")
                                                    && file.toString().endsWith(".body"))
                            .findFirst()
                            .orElseThrow();
        }
        final String text = Files.readString(log);
        final int closing = text.indexOf("Tests run:", text.lastIndexOf("[INFO] Results:"));
        Files.writeString(log, text.substring(0, closing + "Tests run:".length()));
        assertThat(convert(capture))
                .as("the converter requires a complete seven-test summary")
                .isNotZero();
        assertThat(Files.readString(capture.resolve("conversion-output")))
                .as("the refusal reports that the cut block supplies no tests")
                .contains("Expected the seven echoed failed tests; got []");
    }

    @Test
    void anUnexpectedSecondFailedJobCannotOverwriteTheFirstJobsEvidence() throws Exception {
        // Accepting another job with the same log filename would replay the wrong diagnostic bytes.
        final Path capture = captures("two-failed-jobs");
        final Path jobs;
        try (var files = Files.list(capture)) {
            jobs =
                    files.filter(
                                    file ->
                                            file.getFileName()
                                                            .toString()
                                                            .contains("-jobs-failed-tests-")
                                                    && file.toString().endsWith(".body"))
                            .findFirst()
                            .orElseThrow();
        }
        final List<?> original = (List<?>) Json.parseExact(Files.readString(jobs));
        final List<Object> two = new java.util.ArrayList<>(original);
        final Map<String, Object> second = new java.util.LinkedHashMap<>();
        object(original.getFirst()).forEach((key, value) -> second.put((String) key, value));
        final long id = ((Number) second.get("id")).longValue() + 1;
        second.put("id", id);
        second.put("name", "another failed job");
        two.add(second);
        Files.writeString(jobs, Json.write(two));
        final String label = "log-failed-tests-" + id;
        Files.writeString(
                capture.resolve("99-" + label + ".body"),
                "another diagnostic\n"
                        + Files.readString(
                                Path.of(
                                        "src/test/resources/recordings/failed-tests/failed-job.txt")));
        Files.writeString(
                capture.resolve("99-" + label + ".headers"), "Content-Type: text/plain\n");
        Files.writeString(capture.resolve("99-" + label + ".status"), "200\n");
        Files.writeString(
                capture.resolve("index.txt"),
                label + " 200 /api/v1/repos/core/maven-failure/actions/jobs/" + id + "/logs\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThat(convert(capture))
                .as("the controlled capture refuses a second failed job")
                .isNotZero();
        assertThat(Files.readString(capture.resolve("conversion-output")))
                .as("the refusal identifies the unexpected job set")
                .contains("Expected exactly one failed job");
    }

    @Test
    void refusingAPreexistingPodNeverRemovesIt() throws Exception {
        // A capture must not destroy another run's pod when it refuses to start.
        final String calls = run("exists) exit 0;; create) exit 42;;");
        assertThat(calls).as("refusing an existing pod performs no removal").doesNotContain("rm");
    }

    @Test
    void failingToCreateAPodNeverRemovesAPodItDoesNotOwn() throws Exception {
        // A name collision during creation must not delete the pod that won the race.
        final String calls = run("exists) exit 1;; create) exit 42;;");
        assertThat(calls).as("failed creation cannot authorize removal").doesNotContain("rm");
    }

    @Test
    void existingOutputIsRefusedBeforeAnyPodIsTouched() throws Exception {
        // A second capture must not append to or mix with a previous run's evidence.
        final Path output = Files.createDirectory(directory.resolve("capture"));
        final Path marker = output.resolve("evidence");
        Files.writeString(marker, "existing evidence");
        assertThat(run("exists) exit 0;;"))
                .as("nonempty output is refused before any pod command")
                .isEmpty();
        assertThat(Files.readString(marker))
                .as("existing evidence remains intact")
                .isEqualTo("existing evidence");
    }

    @Test
    void failedCleanupNamesTheOwnedPodAndFailsTheCapture() throws Exception {
        // An unreported leftover can block later captures or destroy unrelated work during cleanup.
        final String id = "a".repeat(64);
        final String calls =
                run("exists) exit 1;; create) echo " + id + "; exit 0;; rm) exit 17;;");
        assertThat(calls)
                .as("cleanup removes only the pod identity returned by successful creation")
                .contains("pod rm -f " + id)
                .doesNotContain("pod rm -f core-forgejo-rec");
        assertThat(Files.readString(directory.resolve("output")))
                .as("a cleanup failure reports the exact leftover")
                .contains("LEFTOVER: could not remove owned pod " + id);
    }

    @Test
    void capturedResponsesBecomeRecordingsWithoutReplacingTheirValues() throws Exception {
        // A converter that invents IDs, triggers, pages or tests would invalidate the contract
        // evidence.
        final Path capture = captures("valid");
        assertThat(convert(capture))
                .as("complete captured data produces both recording directories")
                .isZero();
        final Map<?, ?> triggers =
                object(
                        Json.parse(
                                Files.readString(
                                        capture.resolve("recordings/triggers/expect.json"))));
        final List<?> runs = (List<?>) triggers.get("runs");
        final Map<?, ?> sourceRuns =
                object(
                        Json.parse(
                                (String)
                                        object(
                                                        Json.parse(
                                                                Files.readString(
                                                                        Path.of(
                                                                                "src/test/resources/recordings/triggers/01.json"))))
                                                .get("body")));
        assertThat(runs.stream().map(run -> (String) object(run).get("event")))
                .as("conversion preserves the two observed trigger values in their captured order")
                .containsExactlyElementsOf(
                        ((List<?>) sourceRuns.get("workflow_runs"))
                                .stream().map(run -> (String) object(run).get("event")).toList());
        final Map<?, ?> failed =
                object(
                        Json.parse(
                                Files.readString(
                                        capture.resolve("recordings/failed-tests/expect.json"))));
        final Map<?, ?> job = object(((List<?>) failed.get("jobs")).getFirst());
        final Map<?, ?> sourceExpected =
                object(
                        Json.parse(
                                Files.readString(
                                        Path.of(
                                                "src/test/resources/recordings/failed-tests/expect.json"))));
        final Map<?, ?> sourceJob = object(((List<?>) sourceExpected.get("jobs")).getFirst());
        assertThat(job.get("run"))
                .as("the failed job names its captured run id")
                .isEqualTo(sourceJob.get("run"));
        assertThat(job.get("url"))
                .as("the job page uses the captured run page and array index")
                .isEqualTo(sourceJob.get("url"));
        assertThat((List<?>) job.get("failedTests"))
                .as("conversion reads the seven real error entries")
                .hasSize(7);
        assertThat(Files.readAllBytes(capture.resolve("recordings/failed-tests/failed-job.txt")))
                .as("the converter preserves the log bytes supplied by the forge")
                .isEqualTo(
                        Files.readAllBytes(
                                Path.of(
                                        "src/test/resources/recordings/failed-tests/failed-job.txt")));
    }

    @Test
    void aCaptureWithAnIncompatibleWebRootFailsInsteadOfInventingHttps() throws Exception {
        // Replacing an observed HTTP page with an invented HTTPS page would fabricate evidence.
        final Path capture = captures("invalid-root");
        final Path runs = capture.resolve("01-runs-triggers.body");
        Files.writeString(
                runs,
                Files.readString(runs).replace("https://forge.example", "http://forge.example"));
        assertThat(convert(capture)).as("an incompatible measured ROOT_URL is refused").isNotZero();
        assertThat(Files.readString(capture.resolve("conversion-output")))
                .as("the failure names the observed root mismatch")
                .contains("Expected the configured HTTPS web host");
    }

    private Path captures(final String name) throws Exception {
        final Path capture = Files.createDirectory(directory.resolve(name));
        int number = 0;
        final StringBuilder index = new StringBuilder();
        for (final String kind : List.of("triggers", "failed-tests")) {
            final Path source = Path.of("src/test/resources/recordings", kind);
            try (var files = Files.list(source)) {
                for (final Path file :
                        files.filter(path -> path.getFileName().toString().matches("[0-9]+\\.json"))
                                .sorted()
                                .toList()) {
                    final Map<?, ?> exchange = object(Json.parse(Files.readString(file)));
                    final String path = (String) exchange.get("path");
                    final String label;
                    if (path.contains("/runs?")) label = "runs-" + kind;
                    else if (path.endsWith("/jobs"))
                        label = "jobs-" + kind + "-" + path.split("/runs/")[1].split("/")[0];
                    else label = "log-" + kind + "-" + path.split("/jobs/")[1].split("/")[0];
                    final String prefix = "%02d-%s".formatted(++number, label);
                    final String repo = kind.equals("triggers") ? "triggers" : "maven-failure";
                    index.append(label)
                            .append(" 200 ")
                            .append("/api/v1")
                            .append(
                                    path.replace(
                                            "/repos/porelo-ai/porelo-adapters/",
                                            "/repos/core/" + repo + "/"))
                            .append('\n');
                    final byte[] body =
                            exchange.containsKey("bodyFile")
                                    ? Files.readAllBytes(
                                            source.resolve((String) exchange.get("bodyFile")))
                                    : ((String) exchange.get("body"))
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    Files.write(capture.resolve(prefix + ".body"), body);
                    Files.writeString(
                            capture.resolve(prefix + ".headers"),
                            "HTTP/1.1 200 OK\nContent-Type: application/json\n");
                    Files.writeString(capture.resolve(prefix + ".status"), "200\n");
                }
            }
        }
        Files.writeString(capture.resolve("index.txt"), index);
        return capture;
    }

    private int convert(final Path capture) throws Exception {
        final String script = Files.readString(Path.of("src/test/record/record-forgejo.sh"));
        final String marker = "python3 -I - \"$OUT\" <<'CONVERT'\n";
        final int start = script.indexOf(marker) + marker.length();
        final String converter = script.substring(start, script.indexOf("\nCONVERT\n", start));
        final ProcessBuilder command =
                new ProcessBuilder("python3", "-I", "-c", converter, capture.toString());
        command.environment().put("CAPTURE_DAY", "2000-01-01");
        command.redirectErrorStream(true)
                .redirectOutput(capture.resolve("conversion-output").toFile());
        final Process child = command.start();
        try {
            assertThat(child.waitFor(10, TimeUnit.SECONDS))
                    .as("conversion of local captured responses finishes promptly")
                    .isTrue();
            return child.exitValue();
        } finally {
            child.destroyForcibly();
        }
    }

    private static Map<?, ?> object(final Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new AssertionError("The recording value must be an object");
    }

    private String run(final String behavior) throws Exception {
        final Path commands = directory.resolve("commands");
        final Path fake = directory.resolve("podman");
        Files.writeString(
                fake,
                "#!/bin/bash\nprintf '%s\\n' \"$*\" >>\"$RECORDER_CALLS\"\ncase \"${2:-}\" in "
                        + behavior
                        + " esac\nexit 42\n");
        assertThat(fake.toFile().setExecutable(true))
                .as("the fake podman can exercise ownership failures")
                .isTrue();
        final ProcessBuilder process =
                new ProcessBuilder(
                        "bash",
                        "src/test/record/record-forgejo.sh",
                        directory.resolve("capture").toString());
        process.environment()
                .put("PATH", directory + ":" + process.environment().getOrDefault("PATH", ""));
        process.environment().put("RECORDER_CALLS", commands.toString());
        process.redirectErrorStream(true).redirectOutput(directory.resolve("output").toFile());
        final Process child = process.start();
        try {
            assertThat(child.waitFor(10, TimeUnit.SECONDS))
                    .as("refusal does not wait for a forge or runner")
                    .isTrue();
            assertThat(child.exitValue())
                    .as("a capture that did not create its pod fails")
                    .isNotZero();
            return Files.exists(commands) ? Files.readString(commands) : "";
        } finally {
            child.destroyForcibly();
        }
    }
}
