package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/** Assembles one document of Porelo's review profile: one run of one pull request's results. */
public final class ReviewDocument {

    /** The profile this adapter writes. */
    public static final String PROFILE_VERSION = "1";

    /** At most this many results in one document. */
    public static final int RESULT_LIMIT = 100;

    /** The adapter's name, as the run's conversion names it. */
    public static final String ADAPTER = "porelo-adapter-github";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewDocument() {}

    /**
     * What a run says about the pull request it was read from.
     *
     * @param forgeInstance The forge's host, such as {@code github.com}.
     * @param repositoryUri The configured repository, an absolute {@code https} URI.
     * @param repositoryId The forge's stable repository ID.
     * @param pullRequest The pull request's number.
     * @param head The exact reviewed head commit.
     * @param mergeBase The merge base, the old side of the diff.
     * @param fromFork {@code true}, {@code false} or {@code unknown}.
     */
    public record Run(
            String forgeInstance,
            String repositoryUri,
            long repositoryId,
            long pullRequest,
            String head,
            String mergeBase,
            String fromFork) {}

    /**
     * Writes one document.
     *
     * @param run What the run says.
     * @param results At most {@link #RESULT_LIMIT} results.
     * @param completeness {@code complete}, {@code partial}, {@code failed} or {@code unknown}.
     * @param verdict The observed review verdict, or {@code null} when there is none.
     * @return The document.
     */
    public static ObjectNode document(
            Run run, List<ObjectNode> results, String completeness, @Nullable String verdict) {
        if (results.size() > RESULT_LIMIT) {
            throw new IllegalArgumentException("A document holds at most " + RESULT_LIMIT);
        }
        final ObjectNode root = NODES.objectNode();
        root.put("version", "2.1.0");
        root.putObject("properties").put("porelo/profileVersion", PROFILE_VERSION);
        final ObjectNode out = root.putArray("runs").addObject();
        out.putObject("tool").putObject("driver").put("name", "github");
        out.putObject("conversion")
                .putObject("tool")
                .putObject("driver")
                .put("name", ADAPTER)
                .put("version", version());
        out.putArray("versionControlProvenance")
                .addObject()
                .put("repositoryUri", run.repositoryUri())
                .put("revisionId", run.head());
        out.put("columnKind", "unicodeCodePoints");
        final ObjectNode properties = out.putObject("properties");
        properties.put("porelo/baseRevisionId", run.mergeBase());
        properties.put("porelo/completeness", completeness);
        if (verdict != null) {
            properties.put("porelo/reviewVerdict", verdict);
        }
        properties.put("porelo/forgeInstanceId", run.forgeInstance());
        properties.put("porelo/pullRequestId", Long.toString(run.pullRequest()));
        properties.put("porelo/nativeRepositoryId", Long.toString(run.repositoryId()));
        properties.put("porelo/fromFork", run.fromFork());
        final var array = out.putArray("results");
        results.forEach(array::add);
        return root;
    }

    /**
     * Writes a document as compact JSON.
     *
     * @param document The document.
     * @return Its text.
     */
    public static String text(ObjectNode document) {
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A document could not be written", ex);
        }
    }

    /**
     * Returns this adapter's real build version.
     *
     * @return Version, as the build wrote it.
     */
    public static String version() {
        try (InputStream in = ReviewDocument.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                throw new IllegalStateException("The build wrote no version.properties");
            }
            final Properties properties = new Properties();
            properties.load(in);
            final String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.startsWith("${")) {
                throw new IllegalStateException("version.properties names no version");
            }
            return version;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
