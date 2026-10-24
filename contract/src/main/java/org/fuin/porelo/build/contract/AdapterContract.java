package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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

    /**
     * Configures an adapter's contract tests.
     *
     * @param main The executable adapter's main class.
     * @param forge Its Describe identity.
     * @param recordings Directory containing one subdirectory for every suite case.
     */
    protected AdapterContract(final Class<?> main, final String forge, final Path recordings) {
        this.main = main;
        this.forge = forge;
        this.recordings = recordings;
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
                new RecordedForge(recordings.resolve(test.name()), UUID.randomUUID().toString())) {
            try (InstalledBuildReader reader =
                    new InstalledBuildReader(
                            JavaExecutable.write(scratch, main), forge, scratch.resolve("s"))) {
                final Target target =
                        new Target(
                                "git@forge.example:" + test.repository() + ".git",
                                server.api(),
                                server.token());
                try {
                    compare(test, reader, target);
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
            final ContractCase test, final InstalledBuildReader reader, final Target target) {
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
                                        .as("%s retry-after", test.name())
                                        .isEqualTo(expected.retryAfter());
                            });
        } else if (test.expected() instanceof ContractCase.LoggedAnswer expected) {
            compareRecordedLog(test, reader, target, expected);
        } else if (test.expected() instanceof ContractCase.Answer expected) {
            assertThat(test.question().ask(reader, target))
                    .as("%s answer", test.name())
                    .isEqualTo(expected.value());
        }
    }

    private static void compareRecordedLog(
            final ContractCase test,
            final InstalledBuildReader reader,
            final Target target,
            final ContractCase.LoggedAnswer expected) {
        final Object answer = test.question().ask(reader, target);
        assertThat(answer).as("%s build answer", test.name()).isInstanceOf(Build.class);
        final Build actual = (Build) answer;
        assertThat(actual.jobs())
                .as("%s recorded jobs", test.name())
                .hasSize(expected.value().jobs().size());
        final byte[] log = actual.jobs().get(2).log();
        assertThat(log)
                .as("%s failed job's last 64 KiB", test.name())
                .isNotNull()
                .hasSize(Build.TAIL);
        if (log == null) {
            throw new AssertionError("The failed job has no log");
        }
        try {
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(log)))
                    .as("%s recorded log tail SHA-256", test.name())
                    .isEqualTo(expected.sha256());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required to verify recorded log bytes", ex);
        }
        final List<Build.Job> jobs = new ArrayList<>(expected.value().jobs());
        final Build.Job failed = jobs.get(2);
        jobs.set(2, new Build.Job(failed.name(), failed.result(), log));
        assertThat(actual)
                .as("%s answer", test.name())
                .isEqualTo(new Build(expected.value().verdict(), jobs, expected.value().detail()));
    }
}
