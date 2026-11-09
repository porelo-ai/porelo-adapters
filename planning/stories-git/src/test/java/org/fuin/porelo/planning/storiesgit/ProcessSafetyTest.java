package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProcessSafetyTest {
    @TempDir Path root;

    // Harm: concurrent adapters can corrupt a clone or remove another call's credential pipe.
    @Test
    void refusesASecondAdapterBeforeItTouchesTheStore() throws Exception {
        final StoriesGit first = new StoriesGit();
        first.initialize(root);
        try {
            Files.createDirectory(root.resolve("tmp/credential-active"));
            final StoriesGit second = new StoriesGit();
            final Throwable failure = catchThrowable(() -> second.initialize(root));
            assertThat(failure)
                    .as("one adapter exclusively owns the directory")
                    .isInstanceOf(PlanningRefused.class);
            assertThat(Files.isDirectory(root.resolve("tmp/credential-active")))
                    .as("a refused owner cannot sweep an active credential transport")
                    .isTrue();
        } finally {
            close(first);
        }
        final StoriesGit replacement = new StoriesGit();
        replacement.initialize(root);
        close(replacement);
    }

    // Harm: a clone installed before publication must not make the next snapshot unusable.
    @Test
    void verifiesACloneThatAppearsBeforeStagingIsInstalled() throws Exception {
        final Path source = Files.createDirectory(root.resolve("source.git"));
        final ProcessBuilder initializer =
                new ProcessBuilder("/usr/bin/git", "init", "--bare", "--quiet", source.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        initializer.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        initializer.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process init = initializer.start();
        try {
            assertThat(init.waitFor(5, TimeUnit.SECONDS))
                    .as("the local bare repository is created")
                    .isTrue();
            assertThat(init.exitValue()).as("the bare repository is usable").isZero();
        } finally {
            if (init.isAlive()) init.destroyForcibly().waitFor();
        }
        final Path program =
                script(
                        """
                        #!/bin/sh
                        set -eu
                        for argument do
                            if [ "$argument" = clone ]; then
                                for argument do staging=$argument; done
                                final="$PWD/one.git-or-worktree"
                                /usr/bin/git clone --quiet -- "$PWD/../source.git" "$staging" 2>/dev/null
                                /usr/bin/git -C "$staging" remote set-url origin https://example.org/one.git
                                /usr/bin/git clone --quiet -- "$PWD/../source.git" "$final" 2>/dev/null
                                /usr/bin/git -C "$final" remote set-url origin https://example.org/one.git
                                exit 0
                            fi
                        done
                        exec /usr/bin/git "$@"
                        """);
        final GitStore store = new GitStore(root, program, Duration.ofSeconds(5));
        try {
            store.begin();
            final Path clone = store.clone("one", "https://example.org/one.git", "");
            assertThat(Files.isDirectory(clone.resolve(".git")))
                    .as("the existing clone is verified and retained")
                    .isTrue();
            try (var entries = Files.list(root.resolve("repositories"))) {
                assertThat(entries.map(path -> path.getFileName().toString()).toList())
                        .as("the losing staging directory is removed")
                        .containsExactly("one.git-or-worktree");
            }
        } finally {
            close(store);
        }
    }

    // Harm: crash leftovers accumulate and may preserve abandoned credential transports.
    @Test
    void sweepsOnlyStaleCredentialAndPendingCloneEntriesAtStartup() throws Exception {
        Files.createDirectories(root.resolve("tmp/credential-old"));
        Files.writeString(root.resolve("tmp/credential-old/password"), "no credential bytes\n");
        Files.createDirectories(root.resolve("repositories/one.pending-old"));
        Files.createDirectories(root.resolve("repositories/one.git-or-worktree"));
        Files.createDirectories(root.resolve("tmp/keep"));
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(root);
        try {
            assertThat(Files.exists(root.resolve("tmp/credential-old")))
                    .as("startup removes stale credential transports under the lock")
                    .isFalse();
            assertThat(Files.exists(root.resolve("repositories/one.pending-old")))
                    .as("startup removes abandoned clone staging")
                    .isFalse();
            assertThat(Files.isDirectory(root.resolve("repositories/one.git-or-worktree")))
                    .as("completed clones are retained")
                    .isTrue();
            assertThat(Files.isDirectory(root.resolve("tmp/keep")))
                    .as("unrelated files are retained")
                    .isTrue();
        } finally {
            close(adapter);
        }
    }

    // Harm: killing the adapter must not leave Git or askpass hanging on an abandoned FIFO.
    @Test
    void boundsGitAndAskpassAfterTheAdapterIsKilled() throws Exception {
        final Path program =
                script(
                        "#!/bin/sh\nset -eu\nprintf '%s' \"$$\" > git-pid\n"
                                + "while [ ! -f release ]; do sleep 0.01; done\n"
                                + "\"$GIT_ASKPASS\" 'Password for remote:' &\n"
                                + "printf '%s' \"$!\" > askpass-pid\nwait\n");
        final Process owner =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                ProcessSafetyTest.class.getName(),
                                root.toString(),
                                program.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        final List<ProcessHandle> children = new ArrayList<>();
        try {
            await(() -> Files.exists(root.resolve("git-pid")), Duration.ofSeconds(5));
            children.addAll(owner.descendants().toList());
            final long gitPid = Long.parseLong(Files.readString(root.resolve("git-pid")));
            owner.destroyForcibly();
            assertThat(owner.waitFor(2, TimeUnit.SECONDS))
                    .as("the adapter owner was killed")
                    .isTrue();
            Files.createFile(root.resolve("release"));
            await(() -> Files.exists(root.resolve("askpass-pid")), Duration.ofSeconds(1));
            final long askpassPid = Long.parseLong(Files.readString(root.resolve("askpass-pid")));
            ProcessHandle.of(askpassPid).ifPresent(children::add);
            await(() -> !running(gitPid), Duration.ofSeconds(4));
            assertThat(running(gitPid))
                    .as("an independent watchdog terminates Git and askpass after owner death")
                    .isFalse();
            assertThat(running(askpassPid))
                    .as("the independent watchdog also kills the blocked askpass descendant")
                    .isFalse();
        } finally {
            children.forEach(child -> child.destroyForcibly());
            owner.destroyForcibly();
            owner.waitFor(2, TimeUnit.SECONDS);
        }
    }

    // Harm: five-second network bounds reject otherwise usable repositories.
    @Test
    void givesClonesSixtySecondsAndLocalCallsFiveSeconds() throws Exception {
        final Path program = script("#!/bin/sh\ntr '\\000' '\\n' < /proc/$PPID/cmdline\n");
        final GitStore store = productionBounds(program);
        try {
            store.begin();
            assertThat(store.git(root, "", "clone", "probe"))
                    .as("the actual clone watchdog has the network budget")
                    .contains("/usr/bin/timeout\n", "--kill-after=1s\n", "60s\n");
            assertThat(store.git(root, "", "rev-parse", "HEAD"))
                    .as("the actual local watchdog keeps the short budget")
                    .contains("5s\n");
        } finally {
            close(store);
        }
    }

    // Harm: many cold clones exhaust a short snapshot budget, while calls must finish before 120 s.
    @Test
    void allowsSnapshotsPastSixtySecondsAndCapsTheRemainingProcessBudget() throws Exception {
        final Path program = script("#!/bin/sh\ntr '\\000' '\\n' < /proc/$PPID/cmdline\n");
        final GitStore store = productionBounds(program);
        try {
            store.begin();
            final var deadline = GitStore.class.getDeclaredField("deadline");
            deadline.setAccessible(true);
            deadline.setLong(store, deadline.getLong(store) - TimeUnit.SECONDS.toNanos(61));
            final String output = store.git(root, "", "clone", "probe");
            final double bound =
                    output.lines()
                            .filter(line -> line.matches("[0-9.]+s"))
                            .mapToDouble(
                                    line ->
                                            Double.parseDouble(
                                                    line.substring(0, line.length() - 1)))
                            .findFirst()
                            .orElseThrow();
            assertThat(bound)
                    .as("after 61 seconds the snapshot has at most 49 seconds left")
                    .isGreaterThan(45)
                    .isLessThanOrEqualTo(49);
        } finally {
            close(store);
        }
    }

    // Harm: URL digits must not blame a valid credential, and throttling needs its own refusal.
    @ParameterizedTest
    @MethodSource("failures")
    void classifiesHttpStatusesWithoutMatchingDigitsInUrls(String diagnostic, Reason reason)
            throws Exception {
        final Path directory = Files.createTempDirectory(root, "failure-");
        final Path program = directory.resolve("program");
        Files.writeString(program, "#!/bin/sh\ncat >&2 <<'END'\n" + diagnostic + "\nEND\nexit 1\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        final GitStore store = new GitStore(directory, program, Duration.ofSeconds(2));
        try {
            store.begin();
            final Throwable failure = catchThrowable(() -> store.git(directory, "", "probe"));
            assertThat(failure)
                    .as("failed Git calls are typed refusals")
                    .isInstanceOf(PlanningRefused.class);
            assertThat(((PlanningRefused) failure).reason())
                    .as("the diagnostic's status or transport failure determines the reason")
                    .isEqualTo(reason);
            assertThat(((PlanningRefused) failure).parameters().toString())
                    .as("remote diagnostics are never returned")
                    .doesNotContain("example.org");
        } finally {
            close(store);
        }
    }

    private static java.util.stream.Stream<Arguments> failures() {
        final Map<String, Reason> cases =
                Map.of(
                        "fatal: unable to access 'https://example.org:40123/repo403.git/': Failed"
                                + " to connect",
                        Reason.UNREACHABLE,
                        "fatal: unable to access 'https://example.org/repo401.git/': The requested"
                                + " URL returned error: 404",
                        Reason.NO_SUCH_STORE,
                        "fatal: unable to access 'https://example.org/repo.git/': The requested URL"
                                + " returned error: 429",
                        Reason.RATE_LIMITED,
                        "fatal: Too many requests",
                        Reason.RATE_LIMITED,
                        "fatal: The requested URL returned error: 403",
                        Reason.CREDENTIAL_REFUSED);
        return cases.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    public static void main(String[] args) {
        final GitStore store =
                new GitStore(Path.of(args[0]), Path.of(args[1]), Duration.ofMillis(500));
        store.begin();
        store.git(Path.of(args[0]), "call-credential", "probe");
    }

    private GitStore productionBounds(Path program) throws Exception {
        try {
            return GitStore.class
                    .getDeclaredConstructor(Path.class, Path.class)
                    .newInstance(root, program);
        } catch (NoSuchMethodException absent) {
            return new GitStore(root, program, Duration.ofSeconds(5));
        }
    }

    private Path script(String text) throws Exception {
        final Path program = root.resolve("program");
        Files.writeString(program, text);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }

    private static void close(Object value) throws Exception {
        if (value instanceof AutoCloseable closeable) closeable.close();
    }

    private static boolean running(long pid) throws Exception {
        final Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.exists(stat)) return false;
        return !Files.readString(stat).matches(".*\\) [ZX] .*\\R?");
    }

    private static void await(CheckedBoolean condition, Duration bound) throws Exception {
        final long deadline = System.nanoTime() + bound.toNanos();
        while (!condition.get() && System.nanoTime() < deadline) Thread.sleep(10);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
