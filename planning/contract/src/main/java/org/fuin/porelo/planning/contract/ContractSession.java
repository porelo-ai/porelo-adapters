package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.fuin.porelo.planning.api.InstalledPlanning;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.jspecify.annotations.Nullable;

final class ContractSession {
    static final int BOUND_SECONDS = 15;
    final Path root;
    final Path directory;
    final Path temporary;
    final Path runtime;
    final Canary canary;
    final Fixture fixture;
    final Board board;
    final InstalledPlanning client;
    final ExecutorService workers =
            Executors.newCachedThreadPool(
                    task -> {
                        final Thread thread = new Thread(task, "planning-contract");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final List<VarlinkClient> connections = new ArrayList<>();
    private final Set<String> secrets = new LinkedHashSet<>();
    private final ProcessSamples processes;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private Future<?> sampling;

    ContractSession(Path root, Fixture fixture, Class<?> main, Map<String, String> properties)
            throws Exception {
        this(root, fixture, main, properties, Board.standard());
    }

    ContractSession(
            Path root, Fixture fixture, Class<?> main, Map<String, String> properties, Board board)
            throws Exception {
        this.root = root;
        this.fixture = fixture;
        this.board = board;
        directory = Files.createDirectory(root.resolve("directory"));
        temporary = Files.createDirectory(root.resolve("temporary"));
        runtime = Files.createDirectory(root.resolve("runtime"));
        processes = new ProcessSamples(root.resolve("pid"));
        final AtomicBoolean abandoned = new AtomicBoolean();
        final AtomicReference<InstalledPlanning> starting = new AtomicReference<>();
        Canary prepared = null;
        try {
            final Path storyDecoy = root.resolve("story-decoy");
            final Path issueDecoy = root.resolve("issue-decoy");
            fixture.seed(board, directory);
            fixture.decoy(storyDecoy, false, board);
            fixture.decoy(issueDecoy, true, board);
            canary =
                    new Canary(
                            root.resolve("home"),
                            Map.of(
                                    "canary.md",
                                    Files.readString(storyDecoy),
                                    "issue.md",
                                    Files.readString(issueDecoy)));
            prepared = canary;
            Files.delete(storyDecoy);
            Files.delete(issueDecoy);
            secrets.add(fixture.token());
            fixture.observeProcesses(processes::sample);
            sampling =
                    sampler.scheduleWithFixedDelay(processes::sample, 0, 5, TimeUnit.MILLISECONDS);
            final Path executable =
                    PlanningExecutable.write(root, main, canary.home(), temporary, properties);
            client =
                    within(
                            "start adapter",
                            () -> {
                                final InstalledPlanning opened =
                                        new InstalledPlanning(executable, runtime, directory);
                                starting.set(opened);
                                if (abandoned.get()) opened.close();
                                return opened;
                            });
        } catch (Exception | Error failure) {
            abandoned.set(true);
            final InstalledPlanning opened = starting.get();
            if (opened != null) cleanup(failure, opened::close);
            sampler.shutdownNow();
            cleanup(failure, processes::sample);
            cleanup(failure, processes::killSurvivors);
            cleanup(failure, fixture::close);
            workers.shutdownNow();
            cleanup(
                    failure,
                    () ->
                            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS))
                                    .as("failed startup leaves no contract callers")
                                    .isTrue());
            cleanup(
                    failure,
                    () ->
                            assertThat(sampler.awaitTermination(5, TimeUnit.SECONDS))
                                    .as("failed startup leaves no sampler")
                                    .isTrue());
            if (prepared != null) cleanup(failure, prepared::close);
            throw failure;
        }
    }

    <T> Future<T> submit(Callable<T> call) {
        return workers.submit(call);
    }

    <T> T within(String asked, Callable<T> call) throws Exception {
        return await(asked, submit(call));
    }

    static <T> T await(String asked, Future<T> answer) throws Exception {
        try {
            return answer.get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            answer.cancel(true);
            throw new AssertionError(
                    "Expected " + asked + " within " + BOUND_SECONDS + " seconds; got a timeout");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Error error) throw error;
            if (ex.getCause() instanceof Exception exception) throw exception;
            throw new AssertionError(
                    "Expected " + asked + "; got an unrecognized failure", ex.getCause());
        }
    }

    VarlinkClient connection() throws Exception {
        // InstalledPlanning owns the process; separate connections prevent its call lock from
        // hiding adapter races.
        try (Stream<Path> paths = Files.walk(runtime)) {
            final List<Path> sockets =
                    paths.filter(p -> p.getFileName().toString().equals("s")).toList();
            assertThat(sockets)
                    .as("one installed adapter socket in the suite-owned runtime")
                    .hasSize(1);
            final VarlinkClient connection =
                    within(
                            "connect independent caller",
                            () -> new VarlinkClient(sockets.getFirst()));
            connections.add(connection);
            return connection;
        }
    }

    void rememberSecret(String token) {
        secrets.add(token);
    }

    void noSecrets(Object answer) throws Exception {
        for (final String secret : secrets) noSecret(answer, secret);
    }

    void noSecret(Object answer, String token) throws Exception {
        // Only a boolean or redacted names may reach assertion diagnostics.
        assertThat(
                        discloses(
                                answer,
                                token,
                                java.util.Collections.newSetFromMap(
                                        new java.util.IdentityHashMap<>())))
                .as("answer or refusal must not disclose the call credential")
                .isFalse();
        assertThat(
                        SecretScan.files(
                                token,
                                directory,
                                canary.home(),
                                temporary,
                                runtime,
                                root.resolve("working")))
                .as("no credential may persist in adapter files, names or links")
                .isEmpty();
        assertThat(SecretScan.contains(token, Files.readString(root.resolve("output"))))
                .as("adapter stdout and stderr must not disclose the call credential")
                .isFalse();
        processes.sample();
        if (!sampler.isShutdown())
            assertThat(sampling)
                    .as("background process sampling remains active throughout calls")
                    .isNotDone();
        processes.noSecret(token);
        for (final Map<String, String> environment : fixture.childEnvironments())
            assertThat(SecretScan.environment(token, environment))
                    .as("captured helper environment contains no credential")
                    .isEmpty();
    }

    private static boolean discloses(@Nullable Object answer, String token, Set<Object> seen) {
        if (answer == null || !seen.add(answer)) return false;
        if (SecretScan.contains(token, String.valueOf(answer))) return true;
        if (answer instanceof org.fuin.porelo.planning.api.PlanningRefused refusal
                && discloses(refusal.parameters(), token, seen)) return true;
        if (answer instanceof org.fuin.porelo.planning.api.PlanningException refusal
                && discloses(refusal.parameters(), token, seen)) return true;
        if (answer instanceof org.fuin.porelo.wire.varlink.VarlinkException refusal
                && discloses(refusal.getParameters(), token, seen)) return true;
        if (answer instanceof Throwable failure) {
            if (discloses(failure.getCause(), token, seen)) return true;
            for (final Throwable suppressed : failure.getSuppressed())
                if (discloses(suppressed, token, seen)) return true;
        }
        if (answer instanceof Iterable<?> entries)
            for (final Object entry : entries) if (discloses(entry, token, seen)) return true;
        return false;
    }

    void close() throws Exception {
        @Nullable Throwable failure = null;
        // Each cleanup runs even after an assertion fails; later failures preserve its cause.
        for (final VarlinkClient connection : connections)
            failure = cleanup(failure, connection::close);
        processes.sample();
        failure =
                cleanup(
                        failure,
                        () ->
                                within(
                                        "close installed adapter",
                                        () -> {
                                            try {
                                                processes.stopAdapter();
                                            } finally {
                                                client.close();
                                            }
                                            return null;
                                        }));
        failure = cleanup(failure, processes::awaitStopped);
        failure = cleanup(failure, processes::killSurvivors);
        // Releasing remote stalls must precede joining callers that may be waiting on them.
        failure = cleanup(failure, fixture::close);
        workers.shutdownNow();
        failure =
                cleanup(
                        failure,
                        () ->
                                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS))
                                        .as("contract callers stop after fixture shutdown")
                                        .isTrue());
        sampler.shutdownNow();
        failure =
                cleanup(
                        failure,
                        () ->
                                assertThat(sampler.awaitTermination(5, TimeUnit.SECONDS))
                                        .as("process sampler stops after shutdown")
                                        .isTrue());
        failure = cleanup(failure, fixture::verify);
        failure = cleanup(failure, () -> noSecrets("shutdown"));
        failure = cleanup(failure, canary::verifyUntouched);
        failure = cleanup(failure, canary::close);
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        if (failure != null) throw new AssertionError("Unexpected shutdown failure", failure);
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws Throwable;
    }

    private static @Nullable Throwable cleanup(@Nullable Throwable primary, Cleanup action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (primary == null) return failure;
            if (primary != failure) primary.addSuppressed(failure);
        }
        return primary;
    }
}
