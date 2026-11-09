package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A hostile Git home whose hooks, credential helper, and fsmonitor leave an observable marker. */
public final class Canary implements AutoCloseable {
    private final Path home;
    private final Path marker;
    private final WatchService writes;
    private final List<String> changes = new ArrayList<>();

    /**
     * Creates a fresh home with Git commands that touch an initially absent canary file.
     *
     * @param directory A nonexistent directory owned by this test.
     * @throws IOException If the home, executable hooks, or write watcher cannot be created.
     */
    public Canary(final Path directory) throws IOException {
        home = Files.createDirectory(directory).toRealPath();
        marker = home.resolve("canary");
        final Path hooks = Files.createDirectory(home.resolve("hooks"));
        for (final String name : List.of("pre-commit", "post-checkout", "pre-push")) {
            executable(
                    hooks.resolve(name), "#!/bin/sh\ntouch -- " + quote(marker.toString()) + "\n");
        }
        final Path helper = home.resolve("credential-helper");
        executable(helper, "#!/bin/sh\ntouch -- " + quote(marker.toString()) + "\n");
        final Path monitor = home.resolve("fsmonitor");
        executable(
                monitor,
                "#!/bin/sh\ntouch -- " + quote(marker.toString()) + "\nprintf 'fixture\\000'\n");
        Files.writeString(
                home.resolve(".gitconfig"),
                "[core]\n\thooksPath = "
                        + config(hooks)
                        + "\n\tfsmonitor = "
                        + configText(quote(monitor.toString()))
                        + "\n\tfsmonitorHookVersion = 2\n[credential]\n\thelper = "
                        + configCommand(helper)
                        + "\n");
        writes = home.getFileSystem().newWatchService();
        try {
            home.register(
                    writes,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            hooks.register(
                    writes,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException | RuntimeException ex) {
            writes.close();
            throw ex;
        }
    }

    /** {@return the hostile home directory to pass to the adapter under test} */
    public Path home() {
        return home;
    }

    /** {@return the absent marker, also suitable as a containment-test symbolic link target} */
    public Path marker() {
        return marker;
    }

    /**
     * Gives a process this home and its XDG configuration directory without changing its Git
     * guards.
     *
     * @param process Builder to configure before starting the process.
     * @return The same builder for use by the caller.
     */
    public ProcessBuilder applyTo(final ProcessBuilder process) {
        process.environment().put("HOME", home.toString());
        process.environment().put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        return process;
    }

    /**
     * Asserts that the marker is absent and no write, creation, or deletion was observed in the
     * home. Access times cannot reliably establish whether a file was read.
     *
     * @throws IOException If the watcher becomes unusable.
     */
    public synchronized void verifyUntouched() throws IOException {
        WatchKey key;
        try {
            key = writes.poll(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while observing canary writes", ex);
        }
        while (key != null) {
            for (final WatchEvent<?> event : key.pollEvents()) {
                changes.add(
                        event.kind().name() + " in " + key.watchable() + ": " + event.context());
            }
            if (!key.reset())
                throw new IOException(
                        "Expected an active canary write watcher; got an invalid watch key");
            key = writes.poll();
        }
        assertThat(Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
                .as("host Git configuration and containment attempts must not create the canary")
                .isFalse();
        assertThat(changes)
                .as("the hostile home must receive no writes, including a canary later deleted")
                .isEmpty();
    }

    private static void executable(final Path path, final String script) throws IOException {
        Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(path, script);
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String config(final Path path) {
        return configText(path.toString());
    }

    private static String configText(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String configCommand(final Path path) {
        return configText("!" + quote(path.toString()));
    }

    /**
     * Closes the watcher; the test owner removes the home after its processes have stopped.
     *
     * @throws IOException If the watcher cannot be closed.
     */
    @Override
    public void close() throws IOException {
        writes.close();
    }
}
