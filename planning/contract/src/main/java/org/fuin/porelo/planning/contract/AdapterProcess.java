package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Launches a test main while retaining its output for credential checks before the client discards
 * it.
 */
public final class AdapterProcess {
    private AdapterProcess() {}

    /**
     * Invokes the adapter main; arguments are output file, pid file, main class, then adapter
     * arguments. The launcher has already replaced the inherited environment and working directory.
     *
     * @param args Launcher-owned paths and adapter entry point.
     * @throws Exception If the entry point or its output cannot be opened.
     */
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        final OutputStream log =
                Files.newOutputStream(
                        Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.setOut(
                new PrintStream(new CopyOutput(System.out, log), true, StandardCharsets.UTF_8));
        System.setErr(
                new PrintStream(new CopyOutput(System.err, log), true, StandardCharsets.UTF_8));
        try {
            Class.forName(args[2])
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) Arrays.copyOfRange(args, 3, args.length));
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Error error) throw error;
            if (ex.getCause() instanceof Exception exception) throw exception;
            throw ex;
        } finally {
            log.flush();
        }
    }

    private static final class CopyOutput extends OutputStream {
        private final OutputStream original;
        private final OutputStream log;

        private CopyOutput(OutputStream original, OutputStream log) {
            this.original = original;
            this.log = log;
        }

        @Override
        public void write(int value) throws IOException {
            synchronized (log) {
                log.write(value);
                original.write(value);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            synchronized (log) {
                log.write(bytes, offset, length);
                original.write(bytes, offset, length);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (log) {
                log.flush();
                original.flush();
            }
        }
    }
}
