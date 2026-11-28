package org.fuin.porelo.build.forgejo.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** A Forgejo that answers what a test arranged, for the client the review code calls. */
final class ReplayedForgejo {

    private ReplayedForgejo() {}

    /** One arranged answer: its status and JSON body. */
    record Reply(int status, String json) {}

    /** Answers the arranged replies in order, keeps each request, and fails on one more. */
    static final class RecordingHttpClient extends HttpClient {

        private final List<Reply> replies;

        final List<HttpRequest> requests = new ArrayList<>();

        RecordingHttpClient(Reply... replies) {
            this.replies = List.of(replies);
        }

        /** Fails immediately on a request not explicitly arranged by the test. */
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            assertThat(requests.size())
                    .as("no unexpected request, especially a repeated create")
                    .isLessThan(replies.size());
            final Reply reply = replies.get(requests.size());
            requests.add(request);
            final HttpResponse.BodySubscriber<T> subscriber =
                    handler.apply(new Answer<>(request, "", reply.status()));
            subscriber.onSubscribe(
                    new Flow.Subscription() {
                        /** The small arranged answer is delivered synchronously. */
                        @Override
                        public void request(long count) {}

                        /** There is no live exchange to cancel. */
                        @Override
                        public void cancel() {}
                    });
            subscriber.onNext(
                    List.of(ByteBuffer.wrap(reply.json().getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            return new Answer<>(
                    request, subscriber.getBody().toCompletableFuture().join(), reply.status());
        }

        /** Async requests are never part of the arranged exchange. */
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("Unexpected async request");
        }

        /** Async requests are never part of the arranged exchange. */
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) {
            throw new AssertionError("Unexpected async request");
        }

        /** No cookies affect these isolated exchanges. */
        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        /** No connection is opened. */
        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        /** Redirects must not introduce an unarranged exchange. */
        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        /** No proxy affects these isolated exchanges. */
        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        /** TLS is never used by this client. */
        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        /** TLS is never used by this client. */
        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        /** No external authentication affects these isolated exchanges. */
        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        /** HTTP version has no effect on the arranged answers. */
        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        /** Answers are delivered synchronously. */
        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record Answer<T>(HttpRequest request, T body, int statusCode)
            implements HttpResponse<T>, HttpResponse.ResponseInfo {

        /** No transport headers affect the arranged answer. */
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        /** Redirects would require an unarranged exchange. */
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        /** No TLS connection is opened. */
        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        /** The arranged answer belongs to the requested URI. */
        @Override
        public URI uri() {
            return request.uri();
        }

        /** HTTP version has no effect on the arranged answer. */
        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
