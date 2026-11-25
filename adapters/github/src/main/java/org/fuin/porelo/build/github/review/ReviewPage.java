package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Cuts a pull request's results into the profile's pages: at most {@link #RESULT_LIMIT} results and
 * {@link #BYTE_LIMIT} bytes of UTF-8 per document, and at least one result while any are left. A
 * page's cursor is the index of its first result in the whole conversation, read anew each call.
 */
public final class ReviewPage {

    /** At most this many results in one document. */
    public static final int RESULT_LIMIT = 100;

    /** At most this many bytes of UTF-8 in one document. */
    public static final int BYTE_LIMIT = 1_048_576;

    private static final Pattern CURSOR = Pattern.compile("0|[1-9][0-9]{0,8}");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewPage() {}

    /**
     * One page and where the next begins.
     *
     * @param document The page's document.
     * @param text The document as written, at most {@link #BYTE_LIMIT} bytes.
     * @param cursor Where the next page begins, or {@code null} on the last.
     */
    public record Page(ObjectNode document, String text, @Nullable String cursor) {}

    /**
     * Writes the page that begins at a cursor.
     *
     * @param results Every result of the conversation, in its order.
     * @param complete Whether the conversation was read whole with nothing held.
     * @param cursor Where the page begins: {@code null} or empty for the first.
     * @param document Writes a document of the given results and completeness.
     * @return The page.
     * @throws IllegalArgumentException If the cursor is not one this class writes.
     */
    public static Page page(
            List<ObjectNode> results,
            boolean complete,
            @Nullable String cursor,
            BiFunction<List<ObjectNode>, String, ObjectNode> document) {
        final int from = from(cursor);
        // The conversation may have shrunk since the cursor was written: nothing more to read.
        if (from > results.size()) {
            return written(document.apply(List.of(), "partial"), null);
        }
        final List<ObjectNode> taken = new ArrayList<>();
        for (int i = from; i < results.size() && taken.size() < RESULT_LIMIT; i++) {
            taken.add(results.get(i));
            // "complete" is the longer word, so a page that fits with it fits either way.
            if (bytes(written(document.apply(taken, "complete"), null).text()) > BYTE_LIMIT) {
                taken.remove(taken.size() - 1);
                if (taken.isEmpty()) {
                    throw new IllegalStateException("A single result does not fit a document");
                }
                break;
            }
        }
        final int next = from + taken.size();
        final boolean last = next == results.size();
        return written(
                document.apply(taken, last && complete ? "complete" : "partial"),
                last ? null : Integer.toString(next));
    }

    /**
     * What a pull request's page says of its run.
     *
     * @param repositoryUrl The forge's https URL of the repository.
     * @param pullRequestUrl The pull request's https URL.
     * @param head The reviewed head commit.
     * @param mergeBase The merge base, the old side of the diff.
     */
    public record Run(String repositoryUrl, String pullRequestUrl, String head, String mergeBase) {}

    /**
     * Writes a profile 2 page of a pull request's results.
     *
     * @param run What the run says.
     * @param results At most {@link #RESULT_LIMIT} results.
     * @param completeness {@code complete} or {@code partial}.
     * @return The document.
     */
    public static ObjectNode document(Run run, List<ObjectNode> results, String completeness) {
        final ObjectNode root = JSON.createObjectNode();
        root.put("version", "2.1.0");
        root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        root.putObject("properties")
                .put("porelo/profileVersion", "2")
                .put("porelo/documentKind", "page");
        final ObjectNode out = root.putArray("runs").addObject();
        out.putObject("tool").putObject("driver").put("name", "Porelo");
        out.putArray("versionControlProvenance")
                .addObject()
                .put("repositoryUri", run.repositoryUrl())
                .put("revisionId", run.head());
        out.put("columnKind", "unicodeCodePoints");
        out.putObject("properties")
                .put("porelo/pullRequest", run.pullRequestUrl())
                .put("porelo/baseRevisionId", run.mergeBase())
                .put("porelo/completeness", completeness);
        final var array = out.putArray("results");
        results.forEach(array::add);
        return root;
    }

    private static int from(@Nullable String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        if (!CURSOR.matcher(cursor).matches()) {
            throw new IllegalArgumentException("not a cursor this adapter wrote");
        }
        return Integer.parseInt(cursor);
    }

    private static Page written(ObjectNode document, @Nullable String cursor) {
        try {
            return new Page(document, JSON.writeValueAsString(document), cursor);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A document could not be written", ex);
        }
    }

    private static int bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
