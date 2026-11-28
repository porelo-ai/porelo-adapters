package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewRecordingTest {

    @TempDir static Path scratch;

    private SSLContext serverTls;
    private SSLContext clientTls;
    private Path directory;

    @BeforeAll
    void trustsOnlyTheCasesGeneratedAuthority() throws Exception {
        final Path certificates = Files.createDirectory(scratch.resolve("certificates"));
        final Path configuration = scratch.resolve("config");
        serverTls = new ReviewCertificates(certificates).create(configuration);
        final KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream in =
                Files.newInputStream(configuration.resolve("porelo/ca-certificates.pem"))) {
            trust.setCertificateEntry(
                    "case", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        final TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX");
        managers.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, managers.getTrustManagers(), null);
    }

    @BeforeEach
    void createsAnIndependentRecordingDirectory() throws IOException {
        directory = Files.createTempDirectory(scratch, "case-");
    }

    @Test
    void reorderedJsonFieldsStillNameTheSameWrite() throws Exception {
        write(200);
        try (RecordedForge forge = forge();
                HttpClient client = client()) {
            assertThat(
                            post(
                                            client,
                                            forge,
                                            "{\"variables\":{\"id\":\"target\"},\"query\":\"resolve\"}")
                                    .statusCode())
                    .as("JSON member order does not change the requested mutation")
                    .isEqualTo(200);
            forge.verify();
            assertThat(forge.api())
                    .as("review calls use an Enterprise REST base on TLS")
                    .startsWith("https://127.0.0.1:")
                    .endsWith("/api/v3");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"query\":\"resolve\",\"variables\":{\"id\":\"another-thread\"}}",
                "{\"query\":\"unresolve\",\"variables\":{\"id\":\"target\"}}",
                "not JSON",
                ""
            })
    void aDifferentOrUnreadableBodyCannotPassAsTheRecordedWrite(final String body)
            throws Exception {
        write(200);
        try (RecordedForge forge = forge();
                HttpClient client = client()) {
            assertThat(post(client, forge, body).statusCode())
                    .as("the replay refuses a different write before serving success")
                    .isEqualTo(400);
            assertThatThrownBy(forge::verify)
                    .as("a wrong write must fail the contract")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Request body differs");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"method", "path"})
    void theRightBodyCannotAuthorizeAnotherMethodOrPath(final String changed) throws Exception {
        write(200);
        try (RecordedForge forge = forge();
                HttpClient client = client()) {
            final HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            forge.api()
                                                    + (changed.equals("path")
                                                            ? "/another-write"
                                                            : "/write")))
                            .header("Authorization", "Bearer test-token")
                            .method(
                                    changed.equals("method") ? "PUT" : "POST",
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"query\":\"resolve\",\"variables\":{\"id\":\"target\"}}"))
                            .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as("a method or path mismatch is unrecorded traffic")
                    .isEqualTo(404);
            assertThatThrownBy(forge::verify)
                    .as("only the exact recorded destination is authorized")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Unrecorded request");
        }
    }

    @Test
    void aHeldWritePermitsNoRequestsAtAll() throws Exception {
        try (RecordedForge forge = forge();
                HttpClient client = client()) {
            forge.verify();
            assertThat(post(client, forge, "{}").statusCode())
                    .as("zero exchanges is a prohibition on traffic")
                    .isEqualTo(404);
            assertThatThrownBy(forge::verify)
                    .as("a refused write cannot secretly call the forge")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Unrecorded request");
        }
    }

    @Test
    void aLostWriteAnswerDoesNotPermitASecondAttempt() throws Exception {
        write(0);
        try (RecordedForge forge = forge();
                HttpClient client = client()) {
            final String body = "{\"query\":\"resolve\",\"variables\":{\"id\":\"target\"}}";
            assertThatThrownBy(() -> post(client, forge, body))
                    .as("the request was received but its answer was lost")
                    .isInstanceOf(IOException.class);
            forge.verify();
            assertThat(post(client, forge, body).statusCode())
                    .as("the lost answer is never replayable for a write")
                    .isEqualTo(404);
            assertThatThrownBy(forge::verify)
                    .as("a duplicate create or mutation fails the suite")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Unrecorded request");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aForbiddenRedirectIsObservedEvenWhenAuthorizationWasStripped(final boolean credential)
            throws Exception {
        Files.writeString(
                directory.resolve("01.json"),
                """
                {"method":"GET","path":"/api/v3/read","status":302,
                 "headers":{"location":"{{other}}/elsewhere"},"body":""}
                """);
        try (RecordedForge forge = forge();
                HttpClient client = client()) {
            final HttpResponse<String> redirect =
                    client.send(
                            HttpRequest.newBuilder(URI.create(forge.api() + "/read"))
                                    .header("Authorization", "Bearer test-token")
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            final URI other = URI.create(redirect.headers().firstValue("location").orElseThrow());
            assertThat(other.getHost())
                    .as("a distinct numeric loopback host avoids DNS fallback")
                    .isEqualTo("127.0.0.2");
            final HttpRequest.Builder follow = HttpRequest.newBuilder(other);
            if (credential) {
                follow.header("Authorization", "Bearer test-token");
            }
            assertThat(
                            client.send(follow.build(), HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .as("the trusted redirect sink receives the forbidden follow")
                    .isEqualTo(404);
            assertThatThrownBy(forge::verify)
                    .as("dropping the header still does not permit a follow")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("redirect to another host");
        }
    }

    private RecordedForge forge() throws IOException {
        return new RecordedForge(directory, "test-token", "Bearer", serverTls);
    }

    private HttpClient client() {
        return HttpClient.newBuilder().sslContext(clientTls).build();
    }

    private void write(final int status) throws IOException {
        Files.writeString(
                directory.resolve("01.json"),
                """
                {"method":"POST","path":"/api/v3/write","status":%d,"headers":{},"body":"{}",
                 "requestBody":{"query":"resolve","variables":{"id":"target"}}}
                """
                        .formatted(status));
    }

    private static HttpResponse<String> post(
            final HttpClient client, final RecordedForge forge, final String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(forge.api() + "/write"))
                        .header("Authorization", "Bearer test-token")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
