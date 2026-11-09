package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class GitProcesses implements AutoCloseable {
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Path home;
    private final Map<Process, Set<ProcessHandle>> running = new HashMap<>();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService tracker = Executors.newSingleThreadScheduledExecutor();
    private boolean closed;

    GitProcesses(final Path home) {
        this.home = home;
        // Retain handles while Git is alive, before an exiting parent can orphan a pipe holder.
        tracker.scheduleWithFixedDelay(this::captureAll, 0, 10, TimeUnit.MILLISECONDS);
    }

    static void isolate(final ProcessBuilder builder, final Path home) {
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        environment.put("HOME", home.toAbsolutePath().toString());
        environment.put("XDG_CONFIG_HOME", home.resolve(".config").toAbsolutePath().toString());
        environment.put("LC_ALL", "C");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_AUTHOR_NAME", "Contract Fixture");
        environment.put("GIT_AUTHOR_EMAIL", "fixture@example.org");
        environment.put("GIT_COMMITTER_NAME", "Contract Fixture");
        environment.put("GIT_COMMITTER_EMAIL", "fixture@example.org");
    }

    synchronized Process start(final ProcessBuilder builder) throws IOException {
        if (closed) throw new IOException("Asked to start a Git process after its owner closed");
        final Process process = builder.start();
        running.put(process, new HashSet<>(Set.of(process.toHandle())));
        capture(process);
        return process;
    }

    synchronized void capture(final Process process) {
        final Set<ProcessHandle> handles = running.get(process);
        if (handles != null) process.descendants().forEach(handles::add);
    }

    private synchronized void captureAll() {
        for (final Process process : running.keySet()) capture(process);
    }

    synchronized void finished(final Process process) {
        running.remove(process);
    }

    String git(final Path directory, final String... arguments) throws IOException {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        isolate(builder, home);
        return run(builder, TIMEOUT);
    }

    String run(final ProcessBuilder builder, final Duration timeout) throws IOException {
        final List<String> command = builder.command();
        final Process process;
        final Future<byte[]> output;
        synchronized (this) {
            process = start(builder);
            output = readers.submit(() -> process.getInputStream().readAllBytes());
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new IOException(
                        "Expected "
                                + command
                                + " to finish within "
                                + timeout
                                + "; got a timeout: "
                                + text(output));
            }
            final String text = text(output);
            if (process.exitValue() != 0) {
                throw new IOException(
                        "Expected "
                                + command
                                + " to exit 0; got "
                                + process.exitValue()
                                + ": "
                                + text);
            }
            return text;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + command, ex);
        } finally {
            try {
                terminate(process);
                finished(process);
            } finally {
                output.cancel(true);
            }
        }
    }

    private static String text(final Future<byte[]> output)
            throws IOException, InterruptedException {
        try {
            return new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (ExecutionException ex) {
            throw new IOException("Expected Git output; got a failed output reader", ex.getCause());
        } catch (TimeoutException ex) {
            throw new IOException(
                    "Expected Git output within five seconds; got a blocked output reader", ex);
        }
    }

    void terminate(final Process process) throws IOException {
        final List<ProcessHandle> handles;
        synchronized (this) {
            capture(process);
            handles = List.copyOf(running.getOrDefault(process, Set.of(process.toHandle())));
        }
        handles.stream()
                .filter(handle -> handle.pid() != process.pid())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            final CompletableFuture<?>[] exits =
                    handles.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(exits).get(5, TimeUnit.SECONDS);
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while stopping Git process tree " + process.pid(), ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IOException(
                    "Expected stopped Git process tree; still alive: "
                            + handles.stream()
                                    .filter(ProcessHandle::isAlive)
                                    .map(ProcessHandle::pid)
                                    .toList(),
                    ex);
        }
    }

    /** Stops owned processes and their output readers within bounded waits. */
    @Override
    public void close() throws IOException {
        final List<Process> processes;
        synchronized (this) {
            if (closed) return;
            closed = true;
            captureAll();
            processes = List.copyOf(running.keySet());
        }
        tracker.shutdownNow();
        IOException failure = null;
        for (final Process process : processes) {
            try {
                terminate(process);
                finished(process);
            } catch (IOException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        readers.shutdownNow();
        try {
            if (!readers.awaitTermination(5, TimeUnit.SECONDS)
                    || !tracker.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException(
                        "Expected stopped Git output readers and tracker; some remain running");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping Git processes", ex);
        }
        if (failure != null) throw failure;
    }
}
