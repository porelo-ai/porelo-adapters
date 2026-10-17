package org.fuin.porelo.build.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One HTTP GET and its answer: the seam a test fakes GitHub at. */
@FunctionalInterface
public interface Http {

    /**
     * Sends a GET and follows no redirect.
     *
     * @param uri Where.
     * @param headers What to send; empty for a redirect's target, which must never see the token.
     * @return The answer.
     * @throws IOException When nothing answered.
     */
    Response get(URI uri, Map<String, String> headers) throws IOException;

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
        return (uri, headers) -> {
            final HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET();
            headers.forEach(request::header);
            try {
                final HttpResponse<byte[]> answer =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                final Map<String, List<String>> named = new LinkedHashMap<>();
                answer.headers()
                        .map()
                        .forEach(
                                (name, values) -> named.put(name.toLowerCase(Locale.ROOT), values));
                return new Response(answer.statusCode(), named, answer.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ex);
            }
        };
    }
}
