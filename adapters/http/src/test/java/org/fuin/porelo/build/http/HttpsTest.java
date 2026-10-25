package org.fuin.porelo.build.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards access to a local forge without trusting an unrelated certificate authority. */
class HttpsTest {

    @TempDir static Path directory;

    private static HttpsServer server;

    @BeforeAll
    static void serveWithAFreshCertificate() throws Exception {
        final SSLContext context = new TestCertificates(directory).serverContext();
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext(
                "/verdict",
                exchange -> {
                    final byte[] body = "verdict".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void theConfiguredAuthorityLetsTheLocalForgeAnswer() throws Exception {
        // Refusing the account's authority prevents a build verdict from reaching the task.
        final Http.Response answer =
                Http.overNetwork(directory.resolve("ca.pem")).get(verdict(), Map.of(), 0);

        assertThat(answer.status()).as("the local forge answers over trusted TLS").isEqualTo(200);
        assertThat(new String(answer.body(), StandardCharsets.UTF_8))
                .as("the answer is read through the shared HTTP transport")
                .isEqualTo("verdict");
    }

    @Test
    void aMissingFileDoesNotTrustTheLocalForge() {
        assertThatThrownBy(
                        () ->
                                Http.overNetwork(directory.resolve("absent.pem"))
                                        .get(verdict(), Map.of(), 0))
                .as("a private forge is refused until its authority is configured")
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void aDirectoryDoesNotAddAnyAuthority() {
        assertThatThrownBy(() -> Http.overNetwork(directory).get(verdict(), Map.of(), 0))
                .as("only a regular certificate file may add trust")
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void anUnrelatedAuthorityDoesNotTrustTheLocalForge() throws Exception {
        assertThatThrownBy(
                        () ->
                                Http.overNetwork(directory.resolve("other.pem"))
                                        .get(verdict(), Map.of(), 0))
                .as("adding one authority must not trust certificates signed by another")
                .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void everyAuthorityInTheBundleCanAuthenticateTheForge() throws Exception {
        final Path bundle = directory.resolve("bundle.pem");
        Files.writeString(
                bundle,
                Files.readString(directory.resolve("other.pem"))
                        + Files.readString(directory.resolve("ca.pem")));

        assertThat(Http.overNetwork(bundle).get(verdict(), Map.of(), 0).status())
                .as("the CA is trusted even when it is not the bundle's first certificate")
                .isEqualTo(200);
    }

    private static URI verdict() {
        return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/verdict");
    }
}
