package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class GitProcessesTest {
    @TempDir Path directory;

    @Test
    void closingTheOwnerStopsAChildThatOutlivesItsParentAndRetainsItsPipe() throws Exception {
        // An orphaned pipe holder can hang fixture teardown and escape process cleanup.
        final Path home = Files.createDirectory(directory.resolve("home"));
        final Path childPid = directory.resolve("child-pid");
        final Path release = directory.resolve("release");
        ProcessHandle child = null;
        try (GitProcesses processes = new GitProcesses(home);
                WatchService changes = directory.getFileSystem().newWatchService()) {
            directory.register(
                    changes,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            final ProcessBuilder builder =
                    new ProcessBuilder(
                                    "/bin/sh",
                                    "-c",
                                    """
                                    sleep 300 &
                                    child=$!
                                    printf '%s\n' "$child" > "$1"
                                    while [ ! -e "$2" ]; do sleep 0.01; done
                                    """,
                                    "fixture-parent",
                                    childPid.toString(),
                                    release.toString())
                            .directory(directory.toFile());
            GitProcesses.isolate(builder, home);
            final Process parent = processes.start(builder);
            awaitFile(childPid, changes);
            final long pid = Long.parseLong(Files.readString(childPid).strip());
            child =
                    parent.descendants()
                            .filter(handle -> handle.pid() == pid)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "Expected the reported PID to be an owned"
                                                            + " child; got "
                                                            + pid));
            assertThat(child.isAlive())
                    .as("a real child retains the parent's output pipe")
                    .isTrue();
            processes.capture(parent);
            Files.createFile(release);
            assertThat(parent.waitFor(5, TimeUnit.SECONDS))
                    .as("the immediate parent exits before teardown")
                    .isTrue();
            assertThat(child.isAlive())
                    .as("the retained child outlives its immediate parent")
                    .isTrue();

            processes.close();

            assertThat(child.isAlive())
                    .as("close waits until the retained descendant has stopped")
                    .isFalse();
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    @Test
    void aProcessDeadlineFailsLoudAndCleansUpTheCommand() throws Exception {
        // A helper that never exits must not hang a fixture or leave a command running.
        final Path home = Files.createDirectory(directory.resolve("home"));
        try (GitProcesses processes = new GitProcesses(home)) {
            final ProcessBuilder builder =
                    new ProcessBuilder("/bin/sh", "-c", "exec sleep 300")
                            .directory(directory.toFile());
            GitProcesses.isolate(builder, home);

            assertThatThrownBy(() -> processes.run(builder, Duration.ofMillis(200)))
                    .as("the bounded process wait fails with the requested command and timeout")
                    .isInstanceOf(IOException.class)
                    .hasMessageContainingAll("sleep 300", "timeout");
        }
    }

    private static void awaitFile(final Path file, final WatchService changes) throws Exception {
        while (!Files.exists(file) || Files.size(file) == 0) {
            final WatchKey key = changes.poll(5, TimeUnit.SECONDS);
            if (key == null)
                throw new IOException(
                        "Expected a child PID file within five seconds; got no event");
            key.pollEvents();
            if (!key.reset())
                throw new IOException("Expected a valid child PID watch; got a closed directory");
        }
    }
}
