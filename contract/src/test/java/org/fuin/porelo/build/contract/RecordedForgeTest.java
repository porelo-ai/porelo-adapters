package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecordedForgeTest {

    private Path directory;

    @BeforeEach
    void createsScratchInsideTarget() throws Exception {
        Files.createDirectories(Path.of("target"));
        directory = Files.createTempDirectory(Path.of("target"), "forge-");
    }

    @AfterEach
    void removesItsScratch() throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void generatedJobsKeepTheFailureBeyondFifty() throws Exception {
        // Dropping a generated matrix leg could let a broken failed-job retention guard pass.
        Files.writeString(
                directory.resolve("01.json"),
                """
                {"method":"GET","path":"/jobs","status":200,"headers":{},
                 "bodyGenerated":{"kind":"github-jobs","count":60,"lastConclusion":"failure",
                  "template":{"id":1,"name":"leg {{id}}","status":"completed","conclusion":"success"}}}
                """);
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "Bearer");
                HttpClient client = HttpClient.newHttpClient()) {
            final Map<?, ?> body =
                    (Map<?, ?>) Json.parse(get(client, forge.api() + "/jobs").body());
            final List<?> jobs = (List<?>) body.get("jobs");
            assertThat(body.get("total_count"))
                    .as("matrix size advertised to the adapter")
                    .isEqualTo(60.0);
            assertThat(jobs).as("all matrix legs reach the adapter").hasSize(60);
            assertThat(jobs.getFirst())
                    .as("first job retains the real reduced job shape")
                    .isEqualTo(
                            Map.of(
                                    "id",
                                    1.0,
                                    "name",
                                    "leg 1",
                                    "status",
                                    "completed",
                                    "conclusion",
                                    "success"));
            assertThat(jobs.getLast())
                    .as("failure is beyond the fifty-job boundary")
                    .isEqualTo(
                            Map.of(
                                    "id",
                                    60.0,
                                    "name",
                                    "leg 60",
                                    "status",
                                    "completed",
                                    "conclusion",
                                    "failure"));
            forge.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 257})
    void unboundedOrEmptyGeneratedTrafficIsRefused(final int count) throws Exception {
        // Empty traffic bypasses guards, and unchecked counts can exhaust the test process.
        final Path file = directory.resolve("01.json");
        Files.writeString(
                file,
                """
                {"repeat":%d,"exchanges":[
                 {"method":"GET","path":"/log","status":200,"headers":{},"bodyBytes":4}]}
                """
                        .formatted(count));
        assertThatThrownBy(
                        () -> {
                            try (RecordedForge forge =
                                    new RecordedForge(directory, "test-token", "Bearer")) {
                                forge.verify();
                            }
                        })
                .as("repeat count is bounded before allocation")
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("repeat must be between 1 and 256; got " + count);
        Files.writeString(
                file,
                """
                {"method":"GET","path":"/jobs","status":200,"headers":{},
                 "bodyGenerated":{"kind":"github-jobs","count":%d,
                  "template":{"id":1,"name":"leg {{id}}","status":"completed","conclusion":"success"}}}
                """
                        .formatted(count));
        assertThatThrownBy(
                        () -> {
                            try (RecordedForge forge =
                                    new RecordedForge(directory, "test-token", "Bearer")) {
                                forge.verify();
                            }
                        })
                .as("job count is bounded before allocation")
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("count must be between 1 and 256; got " + count);
    }

    @Test
    void repeatedLogsRequireEveryRedirectAndBlobRequest() throws Exception {
        // An omitted repeat must not turn a large reply or token-boundary guard into an empty test.
        Files.writeString(
                directory.resolve("01.json"),
                """
                {"repeat":2,"exchanges":[
                 {"method":"GET","path":"/jobs/{{id}}/logs","status":302,
                  "headers":{"location":"{{blob}}/logs/{{id}}"},"body":""},
                 {"method":"GET","path":"/logs/{{id}}","host":"blob","status":200,
                  "headers":{"content-type":"text/plain"},"bodyBytes":65555}]}
                """);
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "Bearer");
                HttpClient client = HttpClient.newHttpClient()) {
            for (int id = 1; id <= 2; id++) {
                final HttpResponse<String> redirect =
                        get(client, forge.api() + "/jobs/" + id + "/logs");
                assertThat(redirect.statusCode()).as("each log starts at the API").isEqualTo(302);
                final String location = redirect.headers().firstValue("location").orElseThrow();
                assertThat(location).as("each redirect names its own blob").endsWith("/logs/" + id);
                final HttpResponse<byte[]> blob =
                        client.send(
                                HttpRequest.newBuilder(URI.create(location)).build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                assertThat(blob.body())
                        .as("every generated log crosses the tail boundary")
                        .isEqualTo(ContractCase.generatedTail(65555));
                if (id == 1) {
                    assertThatThrownBy(forge::verify)
                            .as("the remaining repeat is mandatory")
                            .isInstanceOf(AssertionError.class)
                            .hasMessageContaining("all recorded exchanges were requested");
                }
            }
            forge.verify();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authorizationIsExactAndStaysAtTheApiOrigin(final boolean external) throws Exception {
        // A missing API credential or a credential sent to another origin must fail the suite.
        Files.writeString(
                directory.resolve("01.json"),
                Json.write(
                        Map.of(
                                "method",
                                "GET",
                                "path",
                                "/known",
                                "host",
                                external ? "blob" : "api",
                                "status",
                                200,
                                "headers",
                                Map.of(),
                                "body",
                                "ok")));
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "token");
                HttpClient client = HttpClient.newHttpClient()) {
            final String url =
                    external ? forge.api().replace("127.0.0.1", "localhost") : forge.api();
            final HttpResponse<String> response = get(client, url + "/known");
            assertThat(response.statusCode()).as("the recorded request was served").isEqualTo(200);
            assertThatThrownBy(forge::verify)
                    .as(
                            "the authorization boundary rejects %s",
                            external ? "a leaked credential" : "a wrong scheme")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining(
                            external
                                    ? "Authorization left API origin: GET /known"
                                    : "API token missing or wrong: GET /known");
        }
    }

    @Test
    void aSecondAuthorizationHeaderCannotBypassTheExactHeaderGuard() throws Exception {
        // A correct first header must not hide an additional credential sent to the forge.
        Files.writeString(
                directory.resolve("01.json"),
                """
                {"method":"GET","path":"/known","status":200,"headers":{},"body":"ok"}
                """);
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "Bearer");
                HttpClient client = HttpClient.newHttpClient()) {
            client.send(
                    HttpRequest.newBuilder(URI.create(forge.api() + "/known"))
                            .header("Authorization", "Bearer test-token")
                            .header("Authorization", "token test-token")
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThatThrownBy(forge::verify)
                    .as("the API accepts exactly one matching authorization header")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("API token missing or wrong: GET /known");
        }
    }

    private static HttpResponse<String> get(final HttpClient client, final String uri)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(uri))
                        .header("Authorization", "Bearer test-token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aMissingExchangeFailsWithTheMethodAndPath() throws Exception {
        // Missing traffic must fail the case instead of letting a plausible 404 hide adapter drift.
        Files.writeString(
                directory.resolve("01.json"),
                """
                {"method":"GET","path":"/known","status":200,"headers":{},"body":"ok"}
                """);
        try (RecordedForge forge = new RecordedForge(directory, "test-token", "Bearer");
                HttpClient client = HttpClient.newHttpClient()) {
            client.send(
                    HttpRequest.newBuilder(URI.create(forge.api() + "/known"))
                            .header("Authorization", "Bearer test-token")
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            final HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(forge.api() + "/missing?part=2"))
                                    .header("Authorization", "Bearer test-token")
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("unrecorded traffic receives HTTP 404")
                    .isEqualTo(404);
            assertThatThrownBy(forge::verify)
                    .as("missing traffic fails by method and full path")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Unrecorded request: GET /missing?part=2");
        }
    }
}
