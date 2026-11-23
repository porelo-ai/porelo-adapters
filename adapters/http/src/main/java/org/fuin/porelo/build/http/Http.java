package org.fuin.porelo.build.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/** One HTTP GET, or a POST, and its answer: the transport shared by forge adapters. */
@FunctionalInterface
public interface Http {

    /** How long a connection may take to open, before {@code limits.services}. */
    Duration CONNECT = Duration.ofSeconds(15);

    /** How long one request may wait for its answer's headers, before {@code limits.services}. */
    Duration REQUEST = Duration.ofSeconds(60);

    /**
     * Sends a GET and follows no redirect.
     *
     * @param uri Where.
     * @param headers What to send; empty for a redirect's target, which must never see the token.
     * @param tail The most of the body's end that is kept, read through without holding the rest; 0
     *     for the whole body.
     * @return The answer.
     * @throws IOException When nothing answered.
     */
    Response get(URI uri, Map<String, String> headers, int tail) throws IOException;

    /**
     * Sends a POST of a JSON body and follows no redirect.
     *
     * @param uri Where.
     * @param headers What to send.
     * @param json The body, sent as {@code application/json}.
     * @return The answer, its whole body.
     * @throws IOException When nothing answered.
     * @throws UnsupportedOperationException When this transport only reads.
     */
    default Response post(final URI uri, final Map<String, String> headers, final String json)
            throws IOException {
        throw new UnsupportedOperationException("this transport only reads");
    }

    /**
     * An HTTP answer.
     *
     * @param status Its status code.
     * @param headers Its headers, names in lower case.
     * @param body Its body.
     */
    record Response(int status, Map<String, List<String>> headers, byte[] body) {

        /**
         * Returns a header's first value.
         *
         * @param name Lower case.
         * @return It, or "".
         */
        public String header(final String name) {
            final List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? "" : values.get(0);
        }
    }

    /**
     * Returns a GET transport trusting public roots and the account's configured authorities.
     *
     * @param limits The time factors, whose {@code services} scales each deadline.
     * @return It.
     */
    static Http overNetwork(final LimitFactors limits) {
        return overNetwork(TrustedCertificates.file(System.getenv()), limits);
    }

    /**
     * Returns a GET transport with additional authorities, no redirects, and a deadline on each
     * GET.
     *
     * @param certificates The account's PEM certificates; a missing regular file adds no authority.
     * @param limits The time factors, whose {@code services} scales each deadline.
     * @return The transport.
     * @throws IllegalStateException When the certificates cannot be read or contain no certificate.
     */
    static Http overNetwork(final Path certificates, final LimitFactors limits) {
        final Duration request = limits.services(REQUEST);
        final HttpClient client =
                HttpClient.newBuilder()
                        .sslContext(TrustedCertificates.context(certificates))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(limits.services(CONNECT))
                        .build();
        return new Http() {
            @Override
            public Response get(final URI uri, final Map<String, String> headers, final int tail)
                    throws IOException {
                return send(
                        client,
                        HttpRequest.newBuilder(uri).timeout(request).GET(),
                        headers,
                        tail,
                        request);
            }

            @Override
            public Response post(
                    final URI uri, final Map<String, String> headers, final String json)
                    throws IOException {
                return send(
                        client,
                        HttpRequest.newBuilder(uri)
                                .timeout(request)
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                json, java.nio.charset.StandardCharsets.UTF_8)),
                        headers,
                        0,
                        request);
            }
        };
    }

    private static Response send(
            final HttpClient client,
            final HttpRequest.Builder request,
            final Map<String, String> headers,
            final int tail,
            final Duration reading)
            throws IOException {
        headers.forEach(request::header);
        try {
            final HttpResponse<InputStream> answer =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            final Map<String, List<String>> named = new LinkedHashMap<>();
            answer.headers()
                    .map()
                    .forEach((name, values) -> named.put(name.toLowerCase(Locale.ROOT), values));
            try (InputStream body = answer.body()) {
                return new Response(answer.statusCode(), named, within(reading, body, tail));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        }
    }

    /**
     * Reads a body, closing it at the deadline so a trickling forge cannot hold the read.
     *
     * @param reading The deadline, counted from the headers' arrival.
     * @param body The body.
     * @param tail The most of its end that is kept, or 0 for all of it.
     * @return What was read.
     * @throws HttpTimeoutException When the body did not end within the deadline.
     * @throws IOException When the stream fails.
     */
    private static byte[] within(final Duration reading, final InputStream body, final int tail)
            throws IOException {
        final AtomicBoolean settled = new AtomicBoolean();
        CompletableFuture.runAsync(
                () -> {
                    if (settled.compareAndSet(false, true)) {
                        try {
                            body.close();
                        } catch (IOException ignored) {
                            // The read that the close cuts off reports the deadline instead.
                        }
                    }
                },
                CompletableFuture.delayedExecutor(reading.toNanos(), TimeUnit.NANOSECONDS));
        final byte[] read;
        try {
            read = tail > 0 ? tail(body, tail) : body.readAllBytes();
        } catch (IOException ex) {
            if (!settled.compareAndSet(false, true)) {
                throw late(reading, ex);
            }
            throw ex;
        }
        if (!settled.compareAndSet(false, true)) {
            throw late(reading, null);
        }
        return read;
    }

    private static HttpTimeoutException late(
            final Duration reading, final @Nullable IOException cause) {
        final HttpTimeoutException late =
                new HttpTimeoutException(
                        "the answer's body did not end within " + reading.toSeconds() + " s");
        if (cause != null) {
            late.initCause(cause);
        }
        return late;
    }

    /**
     * Returns the last bytes of a stream, holding at most twice that many while it reads.
     *
     * @param in What is read to its end.
     * @param most How many of the last bytes are kept.
     * @return Them.
     * @throws IOException When the stream fails.
     */
    private static byte[] tail(final InputStream in, final int most) throws IOException {
        final byte[] window = new byte[2 * most];
        int length = 0;
        int read;
        while ((read = in.read(window, length, window.length - length)) >= 0) {
            length += read;
            if (length == window.length) {
                System.arraycopy(window, most, window, 0, most);
                length = most;
            }
        }
        return Arrays.copyOfRange(window, Math.max(0, length - most), length);
    }
}
