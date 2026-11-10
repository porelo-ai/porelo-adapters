package org.fuin.porelo.build.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FailedTestsTest {
    @Test
    void anIncompleteClosingLineNeverCompletesAResultBlock() {
        // A tail cut inside the summary must not publish tests from an incomplete block.
        final String block = "[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test.failed:1\n";
        for (final String closing :
                List.of(
                        "[INFO] Tests run:",
                        "[INFO] Tests run: 1",
                        "[INFO] Tests run:\n",
                        "[INFO] Tests run: one\n")) {
            assertThat(read(block + closing))
                    .as("an unfinished or invalid summary line supplies no tests")
                    .isNull();
        }
    }

    @Test
    void theRealSurefireErrorBlockNamesExactlyItsSevenFailedMethods() throws Exception {
        // A summary that drops or guesses real methods sends the repair to the wrong test.
        assertThat(
                        FailedTests.read(
                                Files.readAllBytes(
                                        Path.of("src/test/resources/surefire-errors.txt"))))
                .as("the complete captured summary identifies the seven methods as printed")
                .containsExactly(
                        "StandInReplayTest.aCommentSentToGitHubAndAReadAtOnceBothFinish",
                        "StandInReplayTest.aCommentSentToGitHubIsOneResultThatGitHubsReplyJoins",
                        "StandInReplayTest.aCommentWhoseLineDiffersFromItsThreadIsHeldAndTheRunPartial",
                        "StandInReplayTest.aForgedReplyToIsRefusedAndNothingIsWritten",
                        "StandInReplayTest.aReplyToASentReplyGoesToItsRootOnGitHub",
                        "StandInReplayTest.aSentCommentThatGitHubReadsBackOddlyIsNotHeld",
                        "StandInReplayTest.pendingReadsGitHubAgainAfterItsPullRequestsWereStacked");
    }

    @Test
    void aCompleteResultBlockNamesFailuresAndErrorsInOrder() {
        // A missing failed test leaves a task without the failure it must repair.
        assertThat(
                        read(
                                """
                                [ERROR] Before.notAnEntry:1
                                [INFO] Results:
                                [ERROR] Failures:
                                [ERROR]   FirstTest.fails:12->helper:3 expected true
                                [ERROR] Errors:
                                [ERROR]   SecondTest.errors:23 » IllegalState broken
                                [INFO] Tests run: 2, Failures: 1, Errors: 1
                                [ERROR] After.notAnEntry:1
                                """))
                .as("only entries inside the completed summary identify failed tests")
                .containsExactly("FirstTest.fails", "SecondTest.errors");
    }

    @Test
    void aCutOffBlockAndAnEntryWithoutItsBlockNameNoTests() {
        // A truncated log must not turn a partial diagnostic into an asserted test failure.
        assertThat(read("[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test.cut:1\n"))
                .as("an unterminated result block supplies no tests")
                .isNull();
        assertThat(read("[ERROR] Errors:\n[ERROR]   Test.cut:1\n[INFO] Tests run: 1\n"))
                .as("a tail starting after Results supplies no tests")
                .isNull();
        assertThat(
                        read(
                                "[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test.cut:1\n"
                                        + "[INFO] Results:\n[INFO] Tests run: 0\n"))
                .as("a new block cannot complete the previous cut block")
                .isNull();
    }

    @Test
    void flakesAndContinuationLinesNeverBecomeFailedTests() {
        // Flaky retries and stack traces must not send a task to repair a test that passed.
        assertThat(
                        read(
                                """
                                [INFO] Results:
                                [ERROR] Failures:
                                [ERROR]   Test.failed:1 » Assertion failure
                                    Continuation.looksLikeATest:2
                                [ERROR]     Indented.continuation:3
                                [WARNING] Flakes:
                                [WARNING]   Test.flaky:4
                                [ERROR]   Test.alsoFlaky:5
                                [INFO] Tests run: 3, Failures: 1, Errors: 0, Flakes: 2
                                """))
                .as("only direct entries in failure and error groups are failed tests")
                .containsExactly("Test.failed");
    }

    @Test
    void aSummaryLineWithoutItsMavenPrefixIsOnlyAContinuation() {
        // An assertion message can print "Tests run:" or "Results:"; read as structure, it closes
        // a cut-off block or drops a real failure.
        assertThat(
                        read(
                                """
                                [INFO] Results:
                                [ERROR] Failures:
                                [ERROR]   Suite.cutOff:1 » AssertionError expected:
                                Tests run: 1, Failures: 0
                                """))
                .as("an unprefixed closing line never completes a cut-off block")
                .isNull();
        assertThat(
                        read(
                                """
                                [INFO] Results:
                                [ERROR] Failures:
                                [ERROR]   First.fails:10 » AssertionError
                                Results:
                                Flakes:
                                [ERROR]   Second.fails:20 » AssertionError
                                [ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
                                """))
                .as("unprefixed continuation lines neither restart nor end the failure group")
                .containsExactly("First.fails", "Second.fails");
        assertThat(read("Results:\nFailures:\n[ERROR]   Fake.test:1\nTests run: 1\n"))
                .as("output without Maven's framing names no test")
                .isNull();
    }

    @Test
    void realDisplayNamesAndTheirControlCharacterContinuationsNameNoTests() throws Exception {
        // Guessing a Java method from a display name can identify an unrelated test.
        for (final String name :
                List.of("display-name-failure.txt", "display-name-continuation.txt")) {
            assertThat(FailedTests.read(Files.readAllBytes(Path.of("src/test/resources", name))))
                    .as("the real display-name summary %s supplies no class and method", name)
                    .isNull();
        }
    }

    @Test
    void oversizedLinesAreSkippedBeforeTheirContentsAreRead() {
        // An unbounded diagnostic line must not consume excessive parsing work or invent a test.
        assertThat(
                        read(
                                "[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test."
                                        + "x".repeat(10_000)
                                        + ":1\n[ERROR]   Test.kept:2\n[INFO] Tests run: 2\n"))
                .as("a ten-kilobyte entry is skipped without losing the next valid entry")
                .containsExactly("Test.kept");
    }

    @Test
    void timestampsAnsiCodesAndCarriageReturnsDoNotHideTheSummary() {
        // Forge decoration must not hide failed tests or reach the displayed names.
        assertThat(
                        read(
                                "2026-11-01T08:32:49.7731970Z \u001b[32m[INFO]"
                                        + " Results:\u001b[0m\r\n"
                                        + "2026-11-01T08:32:49Z [ERROR] Errors:\r\n"
                                        + "2026-11-01T08:32:49Z [ERROR]  "
                                        + " \u001b[31mTest.failed:2\u001b[0m » Error\r\n"
                                        + "2026-11-01T08:32:49Z [INFO] Tests run: 1\r\n"))
                .as("decoration is removed before recognizing result lines")
                .containsExactly("Test.failed");
    }

    @Test
    void testNamesAreBoundedPrintableAndDeduplicatedAcrossWholeBlocks() {
        // Duplicate or oversized names can crowd the real failures out of a bounded reply.
        final String entries =
                IntStream.range(0, 60)
                        .mapToObj(i -> "[ERROR]   Test.method" + i + ":1\n")
                        .collect(java.util.stream.Collectors.joining());
        assertThat(
                        read(
                                "[INFO] Results:\n[ERROR] Errors:\n[ERROR]   Test.method0:1\n"
                                        + "[ERROR]   Test."
                                        + "x".repeat(200)
                                        + ":1\n"
                                        + "[ERROR]   Test.bad\u0001name:1\n"
                                        + "[ERROR]   Test.display name:1\n"
                                        + entries
                                        + "[INFO] Tests run: 60\n"))
                .as("the first fifty distinct valid names fit the published reply bounds")
                .containsExactlyElementsOf(
                        IntStream.range(0, 50).mapToObj(i -> "Test.method" + i).toList());
        assertThat(
                        read(
                                "[INFO] Results:\n"
                                        + "[ERROR] Errors:\n"
                                        + "[ERROR]   Test.same:1\n"
                                        + "[INFO] Tests run: 1\n"
                                        + "[INFO] Results:\n"
                                        + "[ERROR] Failures:\n"
                                        + "[ERROR]   Test.same:2\n"
                                        + "[ERROR]   Test.other:3\n"
                                        + "[INFO] Tests run: 2\n"))
                .as("completed blocks share ordered deduplication")
                .containsExactly("Test.same", "Test.other");
    }

    private static @org.jspecify.annotations.Nullable List<String> read(final String log) {
        return FailedTests.read(log.getBytes(StandardCharsets.UTF_8));
    }
}
