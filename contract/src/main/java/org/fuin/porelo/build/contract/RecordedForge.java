package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Json;

final class RecordedForge implements AutoCloseable {

    private record Exchange(
            String method,
            String path,
            String host,
            int status,
            Map<String, String> headers,
            byte[] body) {}

    private final HttpServer server;
    private final String token;
    private final List<Exchange> exchanges;
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private int next;

    RecordedForge(final Path directory, final String token) throws IOException {
        this.token = token;
        exchanges = read(directory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    String token() {
        return token;
    }

    String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized void verify() {
        assertThat(failures).as("recorded requests and token boundaries").isEmpty();
        assertThat(next).as("all recorded exchanges were requested").isEqualTo(exchanges.size());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void serve(final HttpExchange request) throws IOException {
        try (request) {
            final String asked = request.getRequestMethod() + " " + request.getRequestURI();
            final boolean blob =
                    ("localhost:" + server.getAddress().getPort())
                            .equalsIgnoreCase(request.getRequestHeaders().getFirst("Host"));
            final String authorization = request.getRequestHeaders().getFirst("Authorization");
            if (blob ? authorization != null : !("Bearer " + token).equals(authorization)) {
                failures.add(
                        blob
                                ? "Authorization reached blob host: " + asked
                                : "API token missing or wrong: " + asked);
            }
            final int index =
                    next == exchanges.size() && exchanges.getLast().status() == 0 ? next - 1 : next;
            if (index >= exchanges.size() || !matches(exchanges.get(index), request, blob)) {
                failures.add("Unrecorded request: " + asked);
                request.sendResponseHeaders(404, -1);
                return;
            }
            final Exchange exchange = exchanges.get(index);
            // A closed GET may be retried before HttpClient reports the transport failure.
            next = index + 1;
            if (exchange.status() == 0) {
                return;
            }
            exchange.headers()
                    .forEach(
                            (name, value) ->
                                    request.getResponseHeaders()
                                            .set(
                                                    name,
                                                    value.replace("{{api}}", api())
                                                            .replace(
                                                                    "{{blob}}",
                                                                    "http://localhost:"
                                                                            + server.getAddress()
                                                                                    .getPort())));
            request.sendResponseHeaders(
                    exchange.status(), exchange.body().length == 0 ? -1 : exchange.body().length);
            request.getResponseBody().write(exchange.body());
        } catch (IOException ex) {
            failures.add("Recording response failed: " + ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private static boolean matches(
            final Exchange exchange, final HttpExchange request, final boolean blob) {
        return exchange.method().equals(request.getRequestMethod())
                && exchange.path().equals(request.getRequestURI().toString())
                && exchange.host().equals(blob ? "blob" : "api");
    }

    private static List<Exchange> read(final Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            final List<Path> files =
                    paths.filter(path -> path.getFileName().toString().matches("[0-9]+\\.json"))
                            .sorted()
                            .toList();
            if (files.isEmpty()) {
                throw new IOException("No exchanges in " + directory);
            }
            final List<Exchange> result = new ArrayList<>();
            for (final Path path : files) {
                final Object parsed = Json.parse(Files.readString(path));
                if (!(parsed instanceof Map<?, ?> map)) {
                    throw new IOException("Expected an exchange object: " + path);
                }
                for (final Map<?, ?> expanded : expand(map)) {
                    result.add(readExchange(expanded, directory, path));
                }
            }
            return List.copyOf(result);
        }
    }

    private static List<Map<?, ?>> expand(final Map<?, ?> map) throws IOException {
        if (!map.containsKey("repeat")) {
            return List.of(map);
        }
        final int count = count(map, "repeat");
        if (!(map.get("exchanges") instanceof List<?> templates) || templates.isEmpty()) {
            throw new IOException("A repeat needs a nonempty exchanges array");
        }
        final List<Map<?, ?>> result = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            for (final Object template : templates) {
                if (!(template instanceof Map<?, ?> exchange) || exchange.containsKey("repeat")) {
                    throw new IOException(
                            "A repeat contains only exchange objects, without nested repeats");
                }
                result.add(
                        (Map<?, ?>)
                                Json.parse(
                                        Json.write(exchange)
                                                .replace("{{id}}", Integer.toString(id))));
            }
        }
        return result;
    }

    private static Exchange readExchange(final Map<?, ?> map, final Path directory, final Path path)
            throws IOException {
        final String method = text(map, "method");
        final String requestPath = text(map, "path");
        final String host = map.containsKey("host") ? text(map, "host") : "api";
        final int status = integer(map, "status");
        if (!method.matches("[A-Z]+")
                || !requestPath.startsWith("/")
                || !List.of("api", "blob").contains(host)
                || status != 0 && (status < 100 || status > 599)) {
            throw new IOException("Invalid method, path, host, or status: " + path);
        }
        final Map<String, String> headers = new LinkedHashMap<>();
        if (!(map.get("headers") instanceof Map<?, ?> supplied)) {
            throw new IOException("Expected headers object: " + path);
        }
        for (final Map.Entry<?, ?> header : supplied.entrySet()) {
            if (!(header.getKey() instanceof String name)
                    || !(header.getValue() instanceof String value)
                    || !name.matches("[A-Za-z0-9-]+")
                    || value.contains("\r")
                    || value.contains("\n")) {
                throw new IOException("Invalid response header: " + path);
            }
            headers.put(name, value);
        }
        return new Exchange(
                method, requestPath, host, status, Map.copyOf(headers), body(map, directory));
    }

    private static byte[] body(final Map<?, ?> map, final Path directory) throws IOException {
        final long forms =
                Stream.of("body", "bodyFile", "bodyBytes", "bodyGenerated")
                        .filter(map::containsKey)
                        .count();
        if (forms != 1) {
            throw new IOException(
                    "An exchange needs exactly one of body, bodyFile, bodyBytes, bodyGenerated");
        }
        if (map.containsKey("body")) {
            return text(map, "body").getBytes(StandardCharsets.UTF_8);
        }
        if (map.containsKey("bodyBytes")) {
            final int bytes = integer(map, "bodyBytes");
            if (bytes < 4 || bytes > 16 * 1024 * 1024) {
                throw new IOException("bodyBytes must be between 4 and 16777216");
            }
            return ContractCase.generatedTail(bytes);
        }
        if (map.containsKey("bodyGenerated")) {
            return generatedBody(map.get("bodyGenerated"));
        }
        final Path root = directory.toRealPath();
        final Path file = root.resolve(text(map, "bodyFile")).toRealPath();
        if (!file.startsWith(root)) {
            throw new IOException("bodyFile leaves its case directory");
        }
        return Files.readAllBytes(file);
    }

    private static byte[] generatedBody(final Object value) throws IOException {
        if (!(value instanceof Map<?, ?> generated)
                || !"github-jobs".equals(text(generated, "kind"))
                || !(generated.get("template") instanceof Map<?, ?> template)) {
            throw new IOException("bodyGenerated needs kind github-jobs and a template object");
        }
        final int count = count(generated, "count");
        final int first = integer(template, "id");
        if (first < 1 || first > Integer.MAX_VALUE - count) {
            throw new IOException("Generated job IDs must be positive and fit an integer");
        }
        final String name = text(template, "name");
        final List<Map<String, Object>> jobs = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            final Map<String, Object> job = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> field : template.entrySet()) {
                if (!(field.getKey() instanceof String key)) {
                    throw new IOException("Generated job field names must be strings");
                }
                job.put(key, field.getValue());
            }
            job.put("id", first + offset);
            job.put("name", name.replace("{{id}}", Integer.toString(first + offset)));
            if (offset == count - 1 && generated.containsKey("lastConclusion")) {
                job.put("conclusion", text(generated, "lastConclusion"));
            }
            jobs.add(job);
        }
        return Json.write(Map.of("total_count", count, "jobs", jobs))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static int count(final Map<?, ?> map, final String key) throws IOException {
        final int count = integer(map, key);
        if (count < 1 || count > 256) {
            throw new IOException(key + " must be between 1 and 256; got " + count);
        }
        return count;
    }

    private static String text(final Map<?, ?> map, final String key) throws IOException {
        if (map.get(key) instanceof String value) {
            return value;
        }
        throw new IOException("Expected string field " + key);
    }

    private static int integer(final Map<?, ?> map, final String key) throws IOException {
        if (map.get(key) instanceof Number value && value.doubleValue() == value.intValue()) {
            return value.intValue();
        }
        throw new IOException("Expected integer field " + key);
    }
}
