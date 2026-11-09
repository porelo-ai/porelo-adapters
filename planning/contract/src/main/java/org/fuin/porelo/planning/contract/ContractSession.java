package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.fuin.porelo.planning.api.InstalledPlanning;
import org.fuin.porelo.wire.varlink.VarlinkClient;

final class ContractSession {
    static final int BOUND_SECONDS = 15;
    final Path root;
    final Path directory;
    final Path temporary;
    final Path runtime;
    final Canary canary;
    final Fixture fixture;
    final Board board = Board.standard();
    final InstalledPlanning client;
    final ExecutorService workers =
            Executors.newCachedThreadPool(
                    task -> {
                        final Thread thread = new Thread(task, "planning-contract");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final List<VarlinkClient> connections = new ArrayList<>();

    ContractSession(Path root, Fixture fixture, Class<?> main, Map<String, String> properties)
            throws Exception {
        this.root = root;
        this.fixture = fixture;
        directory = Files.createDirectory(root.resolve("directory"));
        temporary = Files.createDirectory(root.resolve("temporary"));
        runtime = Files.createDirectory(root.resolve("runtime"));
        canary = new Canary(root.resolve("home"));
        final AtomicBoolean abandoned = new AtomicBoolean();
        final AtomicReference<InstalledPlanning> starting = new AtomicReference<>();
        try {
            fixture.seed(board, directory);
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
        } catch (Exception | Error ex) {
            abandoned.set(true);
            final InstalledPlanning opened = starting.get();
            if (opened != null) opened.close();
            workers.shutdownNow();
            canary.close();
            fixture.close();
            throw ex;
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

    void noSecret(Object answer, String token) throws Exception {
        // Assert only the match, because an assertion rendering a bad answer would leak the token
        // again.
        assertThat(String.valueOf(answer).contains(token))
                .as("answer or refusal must not disclose the call credential")
                .isFalse();
        assertThat(
                        SecretScan.files(
                                token,
                                directory,
                                canary.home(),
                                temporary,
                                root.resolve("working")))
                .as(
                        "no credential may persist in the adapter directory, home, temporary or"
                                + " working directory")
                .isEmpty();
        assertThat(Files.readString(root.resolve("output")).contains(token))
                .as("adapter stdout and stderr must not disclose the call credential")
                .isFalse();
        final long pid = Long.parseLong(Files.readString(root.resolve("pid")));
        final ProcessHandle process =
                ProcessHandle.of(pid)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Expected a live adapter; got no process"));
        final List<ProcessHandle> observed = new ArrayList<>(List.of(process));
        try (Stream<ProcessHandle> descendants = process.descendants()) {
            observed.addAll(descendants.toList());
        }
        for (final ProcessHandle child : observed) {
            final Path environment = Path.of("/proc", Long.toString(child.pid()), "environ");
            if (Files.exists(environment)) {
                final Map<String, String> values = new java.util.LinkedHashMap<>();
                for (final String entry :
                        new String(
                                        Files.readAllBytes(environment),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .split("\u0000")) {
                    final int equals = entry.indexOf('=');
                    if (equals > 0)
                        values.put(entry.substring(0, equals), entry.substring(equals + 1));
                }
                assertThat(SecretScan.environment(token, values))
                        .as("observed child environment contains no credential")
                        .isEmpty();
            }
            assertThat(
                            Arrays.toString(child.info().arguments().orElse(new String[0]))
                                    .contains(token))
                    .as("observed child argument list contains no credential")
                    .isFalse();
        }
        for (final Map<String, String> environment : fixture.childEnvironments())
            assertThat(SecretScan.environment(token, environment))
                    .as("captured helper environment contains no credential")
                    .isEmpty();
    }

    void close() throws Exception {
        Exception failure = null;
        try {
            // Close sockets before joining callers so a stuck receive cannot prolong cleanup.
            for (final VarlinkClient connection : connections) connection.close();
            within(
                    "close installed adapter",
                    () -> {
                        client.close();
                        return null;
                    });
            assertThat(
                            SecretScan.files(
                                    fixture.token(),
                                    directory,
                                    canary.home(),
                                    temporary,
                                    root.resolve("working")))
                    .as("shutdown must not persist the credential")
                    .isEmpty();
            assertThat(Files.readString(root.resolve("output")).contains(fixture.token()))
                    .as("shutdown output must not disclose the credential")
                    .isFalse();
        } catch (Exception ex) {
            failure = ex;
        } finally {
            workers.shutdownNow();
            try {
                fixture.close();
                fixture.verify();
                canary.verifyUntouched();
            } finally {
                canary.close();
            }
        }
        if (failure != null) throw failure;
    }
}
