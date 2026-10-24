package org.fuin.porelo.build.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One HTTP GET and its answer: the transport shared by forge adapters. */
@FunctionalInterface
public interface Http {

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
     * Returns the ordinary way a GET is sent: no redirect followed, a deadline on each.
     *
     * @return It.
     */
    static Http overNetwork() {
        final HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
        return (uri, headers, tail) -> {
            final HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET();
            headers.forEach(request::header);
            try {
                final HttpResponse<InputStream> answer =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                final Map<String, List<String>> named = new LinkedHashMap<>();
                answer.headers()
                        .map()
                        .forEach(
                                (name, values) -> named.put(name.toLowerCase(Locale.ROOT), values));
                try (InputStream body = answer.body()) {
                    return new Response(
                            answer.statusCode(),
                            named,
                            tail > 0 ? tail(body, tail) : body.readAllBytes());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        };
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
