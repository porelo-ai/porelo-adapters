package org.fuin.porelo.build.http;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;

/**
 * Holds every request of one review call to the call's end: each send waits for its whole answer,
 * body included, no longer than the call has left, and once the end has passed nothing more is
 * sent. A send refused for that throws {@link HttpConnectTimeoutException}, so a forge client
 * counts it as never sent; one cut off at the end is aborted and throws {@link
 * HttpTimeoutException}, so a write that may have reached the forge is of unknown outcome.
 */
public final class CallDeadline extends HttpClient {

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
    public CallDeadline(HttpClient http, Clock clock, Instant end) {
        this.http = http;
        this.clock = clock;
        this.end = end;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        final Duration left = left();
        final HttpRequest bounded = within(request, left);
        // The JDK's request timeout ends once the headers arrive; the body is bounded here.
        final FutureTask<HttpResponse<T>> exchange =
                new FutureTask<>(() -> http.send(bounded, handler));
        Thread.ofVirtual().name("review-request").start(exchange);
        try {
            return exchange.get(left.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            if (!exchange.cancel(true)) {
                // The answer came as the call ended; a write the forge took is not made unknown.
                return finished(exchange);
            }
            throw new HttpTimeoutException(
                    "the review call's deadline passed while the forge answered");
        } catch (InterruptedException ex) {
            exchange.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            throw thrown(ex.getCause());
        }
    }

    private static <T> HttpResponse<T> finished(FutureTask<HttpResponse<T>> exchange)
            throws IOException, InterruptedException {
        try {
            return exchange.get();
        } catch (ExecutionException ex) {
            throw thrown(ex.getCause());
        }
    }

    /** What the exchange threw, as {@code send} itself would have thrown it. */
    private static IOException thrown(@Nullable Throwable cause) throws InterruptedException {
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException("the review request failed", cause);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.sendAsync(within(request, left()), handler);
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
            return http.sendAsync(within(request, left()), handler, push);
        } catch (HttpConnectTimeoutException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** The request with the smaller of its own timeout and what the call has left. */
    private static HttpRequest within(HttpRequest request, Duration left)
            throws HttpConnectTimeoutException {
        if (left.isNegative() || left.isZero()) {
            throw new HttpConnectTimeoutException("the review call's deadline passed; not sent");
        }
        final Duration own = request.timeout().orElse(left);
        return HttpRequest.newBuilder(request, (name, value) -> true)
                .timeout(own.compareTo(left) <= 0 ? own : left)
                .build();
    }

    private Duration left() {
        return Duration.between(clock.instant(), end);
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
