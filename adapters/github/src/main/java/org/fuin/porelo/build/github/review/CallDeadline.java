package org.fuin.porelo.build.github.review;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Holds every request of one review call to the call's end: each send waits for its answer no
 * longer than the call has left, and once the end has passed nothing more is sent. A send refused
 * for that throws {@link HttpConnectTimeoutException}, so the GitHub client counts it as never sent
 * and a write is not taken for one that may have reached GitHub.
 */
final class CallDeadline extends HttpClient {

    private final HttpClient http;

    private final Clock clock;

    private final Instant end;

    /**
     * Constructor.
     *
     * @param http The client the requests go through.
     * @param clock What time it is.
     * @param end When the call ends.
     */
    CallDeadline(HttpClient http, Clock clock, Instant end) {
        this.http = http;
        this.clock = clock;
        this.end = end;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        return http.send(within(request), handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.sendAsync(within(request), handler);
        } catch (HttpConnectTimeoutException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        try {
            return http.sendAsync(within(request), handler, push);
        } catch (HttpConnectTimeoutException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** The request with the smaller of its own timeout and what the call has left. */
    private HttpRequest within(HttpRequest request) throws HttpConnectTimeoutException {
        final Duration left = Duration.between(clock.instant(), end);
        if (left.isNegative() || left.isZero()) {
            throw new HttpConnectTimeoutException("the review call's deadline passed; not sent");
        }
        final Duration own = request.timeout().orElse(left);
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .timeout(own.compareTo(left) <= 0 ? own : left)
                .build();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return http.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return http.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return http.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return http.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return http.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return http.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return http.authenticator();
    }

    @Override
    public Version version() {
        return http.version();
    }

    @Override
    public Optional<Executor> executor() {
        return http.executor();
    }
}
