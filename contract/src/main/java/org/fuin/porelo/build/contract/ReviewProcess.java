package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fuin.porelo.build.api.ReviewProtocol;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.jspecify.annotations.Nullable;

/** Keeps raw replies observable while starting the same installed executable as Porelo. */
final class ReviewProcess {

    private ReviewProcess() {}

    static void run(
            final Path executable,
            final String forge,
            final Path scratch,
            final ReviewCase test,
            final RecordedForge server)
            throws Exception {
        final Path socket = scratch.resolve("s").toAbsolutePath();
        final ProcessBuilder builder =
                new ProcessBuilder(executable.toString(), "serve", socket.toString());
        builder.environment()
                .put("XDG_CONFIG_HOME", scratch.resolve("config").toAbsolutePath().toString());
        final Process process = builder.start();
        final AtomicBoolean stopping = new AtomicBoolean();
        final CompletableFuture<String> ready = new CompletableFuture<>();
        final FutureTask<String> output =
                new FutureTask<>(() -> output(process.getInputStream(), ready, stopping));
        final FutureTask<String> errors =
                new FutureTask<>(() -> output(process.getErrorStream(), null, stopping));
        Thread.ofVirtual().start(output);
        Thread.ofVirtual().start(errors);
        try {
            assertThat(ready.get(machineMillis(), TimeUnit.MILLISECONDS))
                    .as("the adapter announces its listening socket")
                    .isEqualTo("ready " + socket);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> described =
                        client.call(ReviewProtocol.DESCRIBE, Map.of());
                assertThat(described)
                        .as("the installed executable speaks this forge's Review1")
                        .containsOnlyKeys("protocolVersion", "forge");
                assertThat(described.get("protocolVersion")).as("Review1 version").isEqualTo(1L);
                assertThat(described.get("forge")).as("Review1 forge identity").isEqualTo(forge);
                test.expected().compare(test, client, server);
            }
        } finally {
            stopping.set(true);
            process.destroy();
            if (!process.waitFor(machineMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor();
            }
            ReviewExpectation.secretFree(
                    output.get(machineMillis(), TimeUnit.MILLISECONDS), server.token());
            ReviewExpectation.secretFree(
                    errors.get(machineMillis(), TimeUnit.MILLISECONDS), server.token());
        }
    }

    private static long machineMillis() {
        return LimitFactors.DEFAULT.machines(Duration.ofSeconds(30)).toMillis();
    }

    private static String output(
            final InputStream stream,
            final @Nullable CompletableFuture<String> ready,
            final AtomicBoolean stopping)
            throws IOException {
        final StringBuilder text = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            if (ready != null) {
                final String first = reader.readLine();
                ready.complete(first == null ? "" : first);
                text.append(first == null ? "" : first);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                text.append('\n').append(line);
            }
            return text.toString();
        } catch (IOException ex) {
            if (stopping.get()) {
                return text.toString();
            }
            if (ready != null) {
                ready.completeExceptionally(ex);
            }
            throw ex;
        }
    }
}
