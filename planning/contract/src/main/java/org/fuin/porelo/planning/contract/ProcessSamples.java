package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** Retains observations before short-lived helpers disappear and before children are orphaned. */
final class ProcessSamples {
    private final Path pidFile;
    private final Set<ProcessHandle> processes = new HashSet<>();
    private @Nullable ProcessHandle adapter;
    private final Set<String> environments = new LinkedHashSet<>();
    private final Set<String> arguments = new LinkedHashSet<>();
    private final Set<String> failures = new LinkedHashSet<>();

    ProcessSamples(Path pidFile) {
        this.pidFile = pidFile;
    }

    synchronized void sample() {
        if (!Files.exists(pidFile)) return;
        try {
            final String pid = Files.readString(pidFile).strip();
            // The launcher can be replacing the describe pid with the serving pid.
            if (pid.isEmpty()) return;
            adapter = ProcessHandle.of(Long.parseLong(pid)).orElse(null);
            if (adapter != null) {
                processes.add(adapter);
                try (var descendants = adapter.descendants()) {
                    processes.addAll(descendants.toList());
                }
            }
            // Retained children may acquire descendants even after the original parent exits.
            for (final ProcessHandle process : java.util.List.copyOf(processes)) {
                if (process.isAlive())
                    try (var descendants = process.descendants()) {
                        processes.addAll(descendants.toList());
                    }
            }
            for (final ProcessHandle process : processes) {
                if (!process.isAlive()) continue;
                capture(process, "environ", environments);
                capture(process, "cmdline", arguments);
            }
        } catch (IOException | NumberFormatException ex) {
            failures.add("Cannot inspect adapter pid");
        }
    }

    private void capture(ProcessHandle process, String file, Set<String> observations) {
        final Path entry = Path.of("/proc", Long.toString(process.pid()), file);
        @Nullable IOException last = null;
        // A process that is exiting or inside execve refuses its proc entries for a moment.
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                observations.add(new String(Files.readAllBytes(entry), StandardCharsets.UTF_8));
                return;
            } catch (IOException ex) {
                last = ex;
                if (!running(process)) return;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (running(process))
            failures.add(
                    "Cannot inspect live process "
                            + file
                            + ": "
                            + (last == null ? "interrupted" : last.getClass().getSimpleName()));
    }

    synchronized void noSecret(String token) {
        assertThat(failures).as("every live adapter process must be inspectable").isEmpty();
        assertThat(environments.stream().anyMatch(value -> SecretScan.contains(token, value)))
                .as("captured child environment contains no credential")
                .isFalse();
        assertThat(arguments.stream().anyMatch(value -> SecretScan.contains(token, value)))
                .as("captured child argument list contains no credential")
                .isFalse();
    }

    void stopAdapter() throws Exception {
        final ProcessHandle parent;
        synchronized (this) {
            parent = adapter;
        }
        if (parent == null || !parent.isAlive()) return;
        // Give a launcher its shutdown hook so it can reap its native executable before forced
        // close.
        parent.destroy();
        try {
            parent.onExit().get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException stillRunning) {
            // InstalledPlanning.close performs the final forced stop within the caller's bound.
        }
    }

    void awaitStopped() throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!survivors().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(survivors())
                .as("adapter and every observed descendant must exit at shutdown")
                .isEmpty();
    }

    private synchronized Set<Long> survivors() {
        final Set<Long> result = new HashSet<>();
        processes.stream().filter(ProcessSamples::running).forEach(p -> result.add(p.pid()));
        return result;
    }

    private static boolean running(ProcessHandle process) {
        if (!process.isAlive()) return false;
        try {
            // A reparented zombie has terminated even if the host has not reaped its pid yet.
            return Files.readString(Path.of("/proc", Long.toString(process.pid()), "status"))
                    .lines()
                    .noneMatch(line -> line.startsWith("State:") && line.contains("Z (zombie)"));
        } catch (IOException exited) {
            return process.isAlive();
        }
    }

    synchronized void killSurvivors() throws Exception {
        processes.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        awaitStopped();
    }
}
