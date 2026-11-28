package org.fuin.porelo.build.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.build.api.ReviewProtocol;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** One published Review1 call, independent of its adapter's HTTP recordings. */
record ReviewCase(
        String name,
        String method,
        Map<String, Object> parameters,
        ReviewExpectation expected,
        List<String> exchanges) {

    private static final String RESOURCES = "/org/fuin/porelo/build/contract/review/";

    static List<ReviewCase> all() throws IOException {
        final Object parsed = Json.parseExact(resource("cases.json"));
        if (!(parsed instanceof List<?> names) || names.isEmpty()) {
            throw new IOException("The review contract needs a nonempty case list");
        }
        final java.util.ArrayList<ReviewCase> cases = new java.util.ArrayList<>();
        for (final Object entry : names) {
            if (!(entry instanceof String name) || !name.matches("[a-z][a-z-]+")) {
                throw new IOException("Invalid review case name");
            }
            final Map<String, Object> definition =
                    object(Json.parseExact(resource(name + "/case.json")));
            if (!(definition.get("method") instanceof String method)
                    || !List.of("Comments", "Post", "Resolve", "PullRequest").contains(method)) {
                throw new IOException("Unknown review method in " + name);
            }
            final Object listed = definition.get("exchanges");
            if (!(listed instanceof List<?> files)) {
                throw new IOException("Expected exchange list in " + name);
            }
            final java.util.ArrayList<String> exchanges = new java.util.ArrayList<>();
            for (final Object file : files) {
                if (!(file instanceof String text) || !text.matches("[0-9]+\\.json")) {
                    throw new IOException("Invalid exchange file in " + name);
                }
                exchanges.add(text);
            }
            cases.add(
                    new ReviewCase(
                            name,
                            ReviewProtocol.INTERFACE + "." + method,
                            object(definition.get("parameters")),
                            ReviewExpectation.read(object(definition.get("expected"))),
                            List.copyOf(exchanges)));
        }
        return List.copyOf(cases);
    }

    void install(final Path directory) throws IOException {
        Files.createDirectories(directory);
        for (final String file : exchanges) {
            Files.writeString(directory.resolve(file), resource(name + "/" + file));
        }
    }

    Map<String, Object> call(final RecordedForge forge) throws IOException {
        final Map<String, Object> call =
                object(Json.parseExact(forge.substitute(Json.write(parameters))));
        final Object document = call.get("document");
        if (document != null) {
            call.put("document", Json.write(document));
        }
        call.put("api", forge.api());
        call.put("token", forge.token());
        return call;
    }

    static Map<String, Object> object(final @Nullable Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException("Expected a review contract object");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final Object field = entry.getValue();
            if (!(entry.getKey() instanceof String key) || field == null) {
                throw new IOException("Expected named, non-null review fields");
            }
            result.put(key, field);
        }
        return result;
    }

    private static String resource(final String name) throws IOException {
        try (InputStream in = ReviewCase.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IOException("Missing review contract resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
