package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A remote read probe for fault proofs that need real stalls or cross-host redirects. */
final class RemoteFileFixture extends FileFixture {
    private final HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    private final java.util.concurrent.ExecutorService workers =
            Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean redirect = new AtomicBoolean();
    private final AtomicBoolean stalled = new AtomicBoolean();
    private final AtomicBoolean authorization = new AtomicBoolean();
    private final AtomicInteger redirectsSent = new AtomicInteger();

    RemoteFileFixture() throws IOException {
        source.setExecutor(workers);
        target.setExecutor(workers);
        target.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        if (exchange.getRequestHeaders().containsKey("Authorization"))
                            authorization.set(true);
                        exchange.sendResponseHeaders(403, -1);
                    }
                });
        source.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        if (stalled.get()) {
                            try {
                                closed.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        } else if (redirect.get()) {
                            exchange.getResponseHeaders()
                                    .set(
                                            "Location",
                                            "http://127.0.0.2:"
                                                    + target.getAddress().getPort()
                                                    + "/board");
                            exchange.sendResponseHeaders(307, -1);
                            redirectsSent.incrementAndGet();
                        } else exchange.sendResponseHeaders(200, -1);
                    }
                });
        source.start();
        target.start();
    }

    @Override
    public Map<String, Object> settings() {
        final Map<String, Object> settings = new LinkedHashMap<>(super.settings());
        settings.put("remote", "http://127.0.0.1:" + source.getAddress().getPort() + "/board");
        return Map.copyOf(settings);
    }

    @Override
    public Set<Ability> abilities() {
        final Set<Ability> abilities = new HashSet<>(super.abilities());
        abilities.add(Ability.stall);
        abilities.add(Ability.redirect);
        return Set.copyOf(abilities);
    }

    @Override
    public void stall() {
        stalled.set(true);
    }

    @Override
    public void redirect() {
        redirect.set(true);
    }

    @Override
    public void verifyRedirect() {
        synchronized (redirectsSent) {
            assertThat(redirectsSent.get())
                    .as("source must actually issue a cross-host redirect")
                    .isPositive();
        }
        assertThat(authorization.get())
                .as("Authorization must never follow a cross-host redirect")
                .isFalse();
    }

    @Override
    public void close() throws IOException {
        closed.countDown();
        source.stop(0);
        target.stop(0);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS))
                throw new IOException("Remote probe workers remain alive");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping remote probe", ex);
        }
    }
}
