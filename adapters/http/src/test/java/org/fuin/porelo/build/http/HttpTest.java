package org.fuin.porelo.build.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.build.api.Build;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the GET the reader sends over the network, against a stand-in on 127.0.0.1. */
class HttpTest {

    /** A log many times the tail handed over, as a long job writes one. */
    private static final int LOG = 64 * Build.TAIL;

    private static final byte[] END =
            "\nERROR the test failed here\n".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    @BeforeEach
    void serve() throws Exception {
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
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI log() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/log");
    }

    @Test
    void aLogIsReadThroughKeepingOnlyItsEnd() throws Exception {
        // A job's log can be hundreds of megabytes, of which 64 KiB are handed over; held whole, a
        // large one ends the reader on the host, and its commit never gets a verdict.
        final Http.Response answer = Http.overNetwork().get(log(), Map.of(), Build.TAIL);

        assertThat(answer.status()).as("the stand-in answered").isEqualTo(200);
        assertThat(answer.body()).as("only the end is kept").hasSize(Build.TAIL);
        assertThat(new String(answer.body(), StandardCharsets.UTF_8))
                .as("and it is the end")
                .endsWith("ERROR the test failed here\n");
    }

    @Test
    void anAnswerAskedWholeIsWhole() throws Exception {
        assertThat(Http.overNetwork().get(log(), Map.of(), 0).body())
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
                    Http.overNetwork().get(redirect, Map.of("Authorization", "token test-only"), 0);
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
