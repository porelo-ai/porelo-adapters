package org.fuin.porelo.standin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixture pushes: their commits made in the stand-in's clone from the files the fixture names,
 * their runs served from the fixture's documents. Each exists to show a review display one case.
 */
final class FixtureSource {

    private static final String DIR = "fixtures/";

    private static final ObjectMapper JSON = new ObjectMapper();

    private FixtureSource() {}

    /**
     * Loads every fixture push into the clone and the gate.
     *
     * @param git The stand-in's clone.
     * @param pushes Where the pushes are kept.
     * @param repositoryUri The repository URI the documents name.
     */
    static void load(Git git, Pushes pushes, String repositoryUri) {
        for (final JsonNode fixture : read("pushes.json")) {
            final String name = fixture.path("name").asText();
            if (!name.matches("fixture-[a-z0-9-]{1,32}")) {
                throw new IllegalStateException("A fixture's name is fixture-<a-z0-9->: " + name);
            }
            final String base =
                    git.commitTree(files(fixture.path("base")), null, "Base of " + name);
            final String head =
                    git.commitTree(
                            files(fixture.path("head")), base, fixture.path("subject").asText());
            git.updateRef("refs/porelo/incoming/" + name, head);
            final Push push =
                    new Push(
                            name,
                            fixture.path("subject").asText(),
                            "2026-01-01T00:00:00Z",
                            null,
                            fixture.path("sourceLevel").asText("unknown"),
                            fixture.path("riskClass").asText("C3"),
                            texts(fixture.path("fileClasses")),
                            fixture.path("approvalsNeeded").asInt(2),
                            fixture.path("refuse").path("approve").asText(null),
                            fixture.path("refuse").path("toForge").asText(null),
                            fixture.path("shows").asText(""),
                            head,
                            base);
            pushes.add(push);
            for (final JsonNode run : fixture.path("runs")) {
                final ObjectNode document =
                        run.has("generate")
                                ? generated(run, head, base, repositoryUri)
                                : document(
                                        run.path("document").asText(), head, base, repositoryUri);
                final ArrayNode results =
                        (ArrayNode) ((ObjectNode) document.at("/runs/0")).remove("results");
                final List<ObjectNode> list = new ArrayList<>();
                if (results != null) {
                    results.forEach(r -> list.add((ObjectNode) r));
                }
                push.putRun(
                        run.path("name").asText(),
                        run.path("kind").asText(),
                        run.path("round").asInt(1),
                        document,
                        list,
                        List.of());
            }
        }
    }

    private static ObjectNode document(
            String path, String head, String base, String repositoryUri) {
        if (!path.matches("[a-z0-9-]+/[a-z0-9-]+\\.json")) {
            throw new IllegalStateException("A fixture document is <push>/<run>.json: " + path);
        }
        final String text =
                text(path)
                        .replace("${head}", head)
                        .replace("${base}", base)
                        .replace("${repositoryUri}", repositoryUri);
        try {
            return (ObjectNode) JSON.readTree(text);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A scanner run of one finding per line, so its results span several pages. */
    private static ObjectNode generated(
            JsonNode run, String head, String base, String repositoryUri) {
        final JsonNodeFactory nodes = JsonNodeFactory.instance;
        final ObjectNode root = nodes.objectNode();
        root.put("version", "2.1.0");
        root.putObject("properties").put("porelo/profileVersion", "1");
        final ObjectNode out = root.putArray("runs").addObject();
        out.putObject("tool").putObject("driver").put("name", run.path("name").asText());
        out.putArray("versionControlProvenance")
                .addObject()
                .put("repositoryUri", repositoryUri)
                .put("revisionId", head);
        out.put("columnKind", "unicodeCodePoints");
        out.putObject("properties")
                .put("porelo/baseRevisionId", base)
                .put("porelo/completeness", "complete");
        final ArrayNode results = out.putArray("results");
        final String path = run.path("file").asText("src/Many.java");
        for (int line = 1; line <= run.path("generate").asInt(); line++) {
            final ObjectNode result = results.addObject();
            result.put("kind", "fail")
                    .put("level", "warning")
                    .put("ruleId", "standin/one-per-line");
            result.putObject("message").put("text", "Finding " + line + " of a generated run.");
            final ObjectNode physical =
                    result.putArray("locations").addObject().putObject("physicalLocation");
            physical.putObject("artifactLocation").put("uri", path);
            physical.putObject("region").put("startLine", line).put("endLine", line);
            result.putObject("properties")
                    .put("porelo/anchorKind", "line")
                    .put("porelo/diffSide", "new")
                    .put("porelo/sourceKind", "scanner");
        }
        return root;
    }

    private static Map<String, String> files(JsonNode node) {
        final Map<String, String> out = texts(node);
        if (out.isEmpty()) {
            throw new IllegalStateException("A fixture commit names no file");
        }
        return out;
    }

    private static Map<String, String> texts(JsonNode node) {
        final Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static JsonNode read(String name) {
        try {
            return JSON.readTree(text(name));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String text(String name) {
        try (InputStream in = FixtureSource.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
