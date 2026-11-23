package org.fuin.porelo.build.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the GET the reader sends over the network, against a stand-in on 127.0.0.1. */
class HttpTest {

    /** A log many times the tail handed over, as a long job writes one. */
    private static final int LOG = 64 * Build.TAIL;

    private static final byte[] END =
            "\nERROR the test failed here\n".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final java.util.concurrent.ExecutorService handlers =
            java.util.concurrent.Executors.newCachedThreadPool();
    private Http http;
    private final AtomicInteger silentAsked = new AtomicInteger();

    @TempDir Path directory;

    @BeforeEach
    void serve() throws Exception {
        http = Http.overNetwork(directory.resolve("absent.pem"), LimitFactors.DEFAULT);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/log",
                exchange -> {
                    exchange.sendResponseHeaders(200, LOG);
                    try (OutputStream out = exchange.getResponseBody()) {
                        final byte[] line = new byte[LOG - END.length];
                        Arrays.fill(line, (byte) 'x');
                        out.write(line);
                        out.write(END);
                    }
                });
        server.createContext(
                "/silent",
                exchange -> {
                    silentAsked.incrementAndGet();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.createContext(
                "/trickle",
                exchange -> {
                    exchange.sendResponseHeaders(200, 1_000_000);
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (int i = 0; i < 1_000_000; i++) {
                            out.write('x');
                            out.flush();
                            Thread.sleep(20_000);
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private URI log() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/log");
    }

    @Test
    void aLogIsReadThroughKeepingOnlyItsEnd() throws Exception {
        // A job's log can be hundreds of megabytes, of which 64 KiB are handed over; held whole, a
        // large one ends the reader on the host, and its commit never gets a verdict.
        final Http.Response answer = http.get(log(), Map.of(), Build.TAIL);

        assertThat(answer.status()).as("the stand-in answered").isEqualTo(200);
        assertThat(answer.body()).as("only the end is kept").hasSize(Build.TAIL);
        assertThat(new String(answer.body(), StandardCharsets.UTF_8))
                .as("and it is the end")
                .endsWith("ERROR the test failed here\n");
    }

    @Test
    void aSilentForgeIsGivenUpAtTheDeadlineTimesTheServicesFactor() throws Exception {
        // A slow network is met by the factor: too short a deadline refuses every answer, too long
        // a one holds the reader on a forge that never answers.
        final Http quick =
                Http.overNetwork(
                        directory.resolve("absent.pem"), LimitFactors.parse("services=0.1"));
        final URI silent =
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/silent");
        final long start = System.nanoTime();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(20),
                () ->
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                        () -> quick.get(silent, Map.of(), 0))
                                .as("the silent forge is given up")
                                .isInstanceOf(java.net.http.HttpTimeoutException.class));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .as("after 0.1 times 60 s, not before")
                .isBetween(java.time.Duration.ofMillis(5_500), java.time.Duration.ofSeconds(15));
    }

    @Test
    void aSilentForgeIsGivenUpOnAPostAtTheDeadlineTimesTheServicesFactor() throws Exception {
        // Opening a pull request waits on the same forge as a read, and must end as soon.
        final Http quick =
                Http.overNetwork(
                        directory.resolve("absent.pem"), LimitFactors.parse("services=0.1"));
        final URI silent =
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/silent");
        final long start = System.nanoTime();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(20),
                () ->
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                        () -> quick.post(silent, Map.of(), "{}"))
                                .as("the silent forge is given up")
                                .isInstanceOf(java.net.http.HttpTimeoutException.class));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .as("after 0.1 times 60 s, not before")
                .isBetween(java.time.Duration.ofMillis(5_500), java.time.Duration.ofSeconds(15));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, Build.TAIL})
    void aTricklingBodyIsGivenUpAtTheDeadlineTimesTheServicesFactor(final int tail)
            throws Exception {
        // A byte every 20 s holds a read past the 6 s deadline, which must cut it off, not await
        // it.
        final Http quick =
                Http.overNetwork(
                        directory.resolve("absent.pem"), LimitFactors.parse("services=0.1"));
        final URI trickle =
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/trickle");
        final long start = System.nanoTime();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(30),
                () ->
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                        () -> quick.get(trickle, Map.of(), tail))
                                .as("the trickling body is given up")
                                .isInstanceOf(java.net.http.HttpTimeoutException.class)
                                .hasMessageContaining("body did not end within 6 s")
                                .hasCauseInstanceOf(java.io.IOException.class));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .as("after 0.1 times 60 s, not before")
                .isBetween(java.time.Duration.ofMillis(5_500), java.time.Duration.ofSeconds(15));
    }

    // Harm: a call that waits past Porelo's own bound loses the forge's words to a bare timeout.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/silent", "/trickle"})
    void aRequestWaitsNoLongerThanItsCallHasLeft(final String path) throws Exception {
        final long end = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        final Http call = http.within(() -> java.time.Duration.ofNanos(end - System.nanoTime()));
        final URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        final long start = System.nanoTime();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(30),
                () ->
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                        () -> call.get(uri, Map.of(), 0))
                                .as("the request is given up when the call's time runs out")
                                .isInstanceOf(java.net.http.HttpTimeoutException.class)
                                .hasMessageContaining("the call's time ran out"));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .as("after the 2 s the call had left, not the request's own 60 s")
                .isBetween(java.time.Duration.ofMillis(1_500), java.time.Duration.ofSeconds(15));
    }

    // Harm: a request sent after its call's time ran out keeps the forge busy for nothing.
    @Test
    void noRequestIsSentOnceItsCallHasNoTimeLeft() {
        final Http call = http.within(() -> java.time.Duration.ZERO);
        final URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/silent");

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(30),
                () ->
                        org.assertj.core.api.Assertions.assertThatThrownBy(
                                        () -> call.get(uri, Map.of(), 0))
                                .as("the request is refused before it is sent")
                                .isInstanceOf(java.net.http.HttpTimeoutException.class)
                                .hasMessageContaining("the call's time ran out"));
        assertThat(silentAsked.get()).as("the forge was not asked").isZero();
    }

    @Test
    void anAnswerAskedWholeIsWhole() throws Exception {
        assertThat(http.get(log(), Map.of(), 0).body())
                .as("an API answer is read whole, since JSON cut at its start is none")
                .hasSize(LOG);
    }

    @Test
    void redirectsNeverForwardCredentialsToAnotherOrigin() throws Exception {
        // Automatic redirects would leak a forge token to a response-controlled destination.
        final AtomicInteger foreignRequests = new AtomicInteger();
        final HttpServer foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        foreign.createContext(
                "/stolen",
                exchange -> {
                    foreignRequests.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        foreign.start();
        try {
            server.createContext(
                    "/redirect",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .set(
                                        "Location",
                                        "http://127.0.0.1:"
                                                + foreign.getAddress().getPort()
                                                + "/stolen");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            final URI redirect =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            final Http.Response response =
                    http.get(redirect, Map.of("Authorization", "token test-only"), 0);
            assertThat(foreignRequests.get())
                    .as("no request or credential reaches the foreign origin")
                    .isZero();
            assertThat(response.status())
                    .as("the adapter decides what the redirect means")
                    .isEqualTo(302);
        } finally {
            foreign.stop(0);
        }
    }
}
