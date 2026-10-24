package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.InstalledBuildReader;
import org.fuin.porelo.build.api.Target;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** The Build2 socket contract; adapters supply a main class, forge identity, and recordings. */
public abstract class AdapterContract {

    private final Class<?> main;
    private final String forge;
    private final Path recordings;
    private final String authorization;

    /**
     * Configures an adapter's contract tests.
     *
     * @param main The executable adapter's main class.
     * @param forge Its Describe identity.
     * @param recordings Directory containing one subdirectory for every suite case.
     * @param authorization Exact API authorization scheme, such as Bearer or token.
     */
    protected AdapterContract(
            final Class<?> main,
            final String forge,
            final Path recordings,
            final String authorization) {
        this.main = main;
        this.forge = forge;
        this.recordings = recordings;
        this.authorization = authorization;
    }

    /**
     * Exercises every shared case through a fresh installed adapter and loopback forge.
     *
     * @return One named dynamic test per contract case.
     */
    @TestFactory
    public final Stream<DynamicTest> everyAdapterAnswersTheBuildContract() {
        // A wrong verdict or refusal sends a task down the wrong path; tokens must stay at the API.
        return ContractCase.all().stream()
                .map(each -> DynamicTest.dynamicTest(each.name(), () -> run(each)));
    }

    private void run(final ContractCase test) throws Exception {
        Files.createDirectories(Path.of("target"));
        final Path scratch = Files.createTempDirectory(Path.of("target"), "c-");
        try (RecordedForge server =
                new RecordedForge(
                        recordings.resolve(test.name()),
                        UUID.randomUUID().toString(),
                        authorization)) {
            final CaseExpectation expected =
                    CaseExpectation.read(recordings.resolve(test.name()), test, server.exchanges());
            try (InstalledBuildReader reader =
                    new InstalledBuildReader(
                            JavaExecutable.write(scratch, main), forge, scratch.resolve("s"))) {
                final Target target =
                        new Target(
                                "git@forge.example:" + test.repository() + ".git",
                                server.api(),
                                server.token());
                try {
                    compare(test, expected, reader, target);
                } finally {
                    server.verify();
                }
            }
        } finally {
            try (Stream<Path> paths = Files.walk(scratch)) {
                for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void compare(
            final ContractCase test,
            final CaseExpectation values,
            final InstalledBuildReader reader,
            final Target target) {
        if (test.expected() instanceof ContractCase.Refusal expected) {
            assertThatThrownBy(() -> test.question().ask(reader, target))
                    .as("%s refusal", test.name())
                    .isInstanceOfSatisfying(
                            BuildRefused.class,
                            refused -> {
                                assertThat(refused.reason())
                                        .as("%s refusal reason", test.name())
                                        .isEqualTo(expected.reason());
                                assertThat(refused.retryAfterSeconds())
                                        .as("%s recorded retry-after", test.name())
                                        .isEqualTo(values.retryAfter());
                            });
            return;
        }
        final Object answer = test.question().ask(reader, target);
        if (test.expected() instanceof ContractCase.HeadAnswer) {
            assertThat(answer).as("%s recorded head", test.name()).isEqualTo(values.head());
            return;
        }
        assertThat(answer).as("%s build answer", test.name()).isInstanceOf(Build.class);
        final Build actual = (Build) answer;
        final ContractCase.Verdict verdict = (ContractCase.Verdict) test.expected();
        assertThat(actual.verdict()).as("%s verdict", test.name()).isEqualTo(verdict.value());
        if (List.of(Build.QUEUED, Build.RUNNING, Build.UNKNOWN).contains(verdict.value())) {
            assertThat(values.jobs())
                    .as("outstanding or unknown recordings have no expected jobs")
                    .isEmpty();
            assertThat(actual.jobs())
                    .as("%s has no jobs before a final verdict", test.name())
                    .isEmpty();
            return;
        }
        compareJobs(test, values, actual);
    }

    private static void compareJobs(
            final ContractCase test, final CaseExpectation values, final Build actual) {
        final List<CaseExpectation.Job> retained = values.retainedJobs();
        assertThat(values.jobs()).as("%s recorded finished jobs", test.name()).isNotEmpty();
        assertThat(actual.jobs())
                .as("%s retained job count", test.name())
                .hasSize(retained.size())
                .hasSizeLessThanOrEqualTo(Build.MOST_JOBS);
        final Build.Logs policy = ((ContractCase.Commit) test.question()).logs();
        final List<byte[]> logs = new ArrayList<>();
        long bytes = 0;
        int count = 0;
        for (final CaseExpectation.Job job : retained) {
            final boolean wanted =
                    policy != Build.Logs.NONE
                            && (policy == Build.Logs.ALL ? job.finished() : job.failed());
            final byte[] body = job.log();
            if (wanted) {
                assertThat(body).as("%s recorded log for %s", test.name(), job.name()).isNotNull();
                if (body == null) throw new AssertionError("Missing recorded log: " + job.name());
                final byte[] tail = CaseExpectation.tail(body, Build.TAIL);
                logs.add(tail);
                bytes += tail.length;
                count++;
            } else {
                assertThat(body).as("%s has no log fixture under %s", job.name(), policy).isNull();
                logs.add(new byte[0]);
            }
        }
        final int share = bytes > Build.MOST_LOG_BYTES ? Build.MOST_LOG_BYTES / count : Build.TAIL;
        long returnedBytes = 0;
        for (int index = 0; index < retained.size(); index++) {
            final CaseExpectation.Job expected = retained.get(index);
            final Build.Job job = actual.jobs().get(index);
            assertThat(job.name())
                    .as("%s job %s in forge order", test.name(), index)
                    .isEqualTo(expected.name());
            assertThat(job.result())
                    .as("%s result of %s", test.name(), expected.name())
                    .isEqualTo(expected.result());
            final byte[] log = job.log();
            if (expected.log() == null) {
                assertThat(log)
                        .as("%s no unwanted log for %s", test.name(), expected.name())
                        .isNull();
            } else {
                assertThat(log)
                        .as("%s exact recorded log end for %s", test.name(), expected.name())
                        .isNotNull()
                        .hasSizeLessThanOrEqualTo(Build.TAIL)
                        .isEqualTo(CaseExpectation.tail(logs.get(index), share));
                if (log != null) returnedBytes += log.length;
            }
        }
        assertThat(returnedBytes)
                .as("%s total log budget", test.name())
                .isLessThanOrEqualTo(Build.MOST_LOG_BYTES);
        if (test.name().equals("log-tail")) {
            assertThat(
                            retained.stream()
                                    .anyMatch(
                                            job ->
                                                    job.log() != null
                                                            && job.log().length > Build.TAIL))
                    .as("the log-tail recording crosses the tail limit")
                    .isTrue();
        }
        if (test.name().equals("many-jobs")) {
            assertThat(values.jobs().size())
                    .as("the matrix recording crosses the job limit")
                    .isGreaterThan(Build.MOST_JOBS);
            assertThat(values.jobs().subList(Build.MOST_JOBS, values.jobs().size()))
                    .as("a failure lies beyond the job limit")
                    .anyMatch(CaseExpectation.Job::failed);
        }
        if (test.name().equals("shared-logs")) {
            assertThat(bytes)
                    .as("the recorded tails cross the shared budget")
                    .isGreaterThan(Build.MOST_LOG_BYTES);
        }
    }
}
