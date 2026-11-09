package org.fuin.porelo.planning.storiesgit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the built native executable inside the contract's isolated process and output recorder. */
public final class NativePlanningLauncher {
    private NativePlanningLauncher() {}

    /**
     * Forwards describe or serve to the native image, retaining both output streams for scanning.
     */
    public static void main(String[] args) throws Exception {
        final Path executable = Path.of(System.getProperty("stories.git.executable"));
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw new IOException(
                    "Expected built native planning executable; got a missing or non-executable"
                            + " file");
        final List<String> command = new ArrayList<>(List.of(executable.toString()));
        command.addAll(List.of(args));
        final Process process = new ProcessBuilder(command).start();
        final Thread cleanup = new Thread(() -> stop(process), "native-planning-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        final AtomicReference<IOException> failure = new AtomicReference<>();
        final Thread stdout =
                Thread.ofVirtual().start(() -> copy(process.getInputStream(), System.out, failure));
        final Thread stderr =
                Thread.ofVirtual().start(() -> copy(process.getErrorStream(), System.err, failure));
        try {
            final int exit = process.waitFor();
            stdout.join(1000);
            stderr.join(1000);
            if (stdout.isAlive() || stderr.isAlive() || failure.get() != null)
                throw new IOException(
                        "Expected complete native adapter output; got an unfinished or failed"
                                + " stream");
            if (exit != 0) throw new IOException("Expected native adapter exit 0; got " + exit);
        } finally {
            stop(process);
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
    }

    private static void copy(
            InputStream input, OutputStream output, AtomicReference<IOException> failure) {
        try (input) {
            input.transferTo(output);
            output.flush();
        } catch (IOException ex) {
            failure.compareAndSet(null, ex);
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
