package org.fuin.porelo.standin;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.build.github.review.GitHubReviewWrite;
import org.fuin.porelo.build.github.review.ReviewRefusal;
import org.fuin.porelo.build.github.review.ReviewText;

/**
 * A person's comment as a client submits it: read with the profile's limits, held to what a
 * person's client may send, and checked as GitHub would render it. What only the daemon stamps is
 * refused here and stamped afterwards.
 */
final class Submission {

    /** A document is at most this many UTF-8 bytes. */
    static final int DOCUMENT_LIMIT = 1024 * 1024;

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final Set<String> ROOT = Set.of("version", "properties", "runs");

    private static final Set<String> RUN =
            Set.of("tool", "versionControlProvenance", "columnKind", "properties", "results");

    private static final Set<String> RESULT =
            Set.of("kind", "level", "message", "locations", "fixes", "properties");

    private static final Set<String> DAEMON_OWNED =
            Set.of(
                    "guid",
                    "automationDetails",
                    "baselineGuid",
                    "baselineState",
                    "suppressions",
                    "fingerprints",
                    "repositoryUri",
                    "porelo/completeness",
                    "porelo/projectId",
                    "porelo/repositoryId",
                    "porelo/reviewRound",
                    "porelo/threadId",
                    "porelo/replyTo",
                    "porelo/threadState",
                    "porelo/sourceKind",
                    "porelo/sourceLevel",
                    "porelo/authorId",
                    "porelo/taskId",
                    "porelo/taskRole",
                    "porelo/forgeInstanceId",
                    "porelo/pullRequestId",
                    "porelo/nativeRepositoryId",
                    "porelo/outdatedReason",
                    "porelo/outdated",
                    "porelo/authorDisplayName",
                    "porelo/originalRevisionId",
                    "porelo/originalPath",
                    "porelo/originalStartLine",
                    "porelo/originalEndLine",
                    "porelo/originalStartColumn",
                    "porelo/originalEndColumn",
                    "porelo/originalDiffSide",
                    "ruleId",
                    "relatedLocations");

    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                    .build();

    static {
        JSON.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(24)
                                .maxStringLength(16_384)
                                .maxNumberLength(20)
                                .maxDocumentLength(DOCUMENT_LIMIT)
                                .build());
    }

    final ObjectNode document;

    final ObjectNode result;

    final String revision;

    final String base;

    final GitHubReviewWrite.Rendered rendered;

    private Submission(
            ObjectNode document,
            ObjectNode result,
            String revision,
            String base,
            GitHubReviewWrite.Rendered rendered) {
        this.document = document;
        this.result = result;
        this.revision = revision;
        this.base = base;
        this.rendered = rendered;
    }

    /**
     * Reads a person's submission.
     *
     * @param sarif The document, as the call carried it.
     * @return The submission.
     * @throws ReviewRefusal If the profile or a person's column refuses it.
     */
    static Submission read(String sarif) {
        if (sarif.getBytes(StandardCharsets.UTF_8).length > DOCUMENT_LIMIT) {
            throw new ReviewRefusal(ReviewRefusal.Reason.TOO_LARGE, "over 1 MiB", "$");
        }
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(sarif);
        } catch (IOException ex) {
            // The parser's words name where it stopped; they hold no body.
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.PROFILE_VIOLATION, "not one strict JSON document", "$");
        }
        if (!(parsed instanceof ObjectNode document)) {
            throw violation("a document is an object", "$");
        }
        only(document, ROOT, "$");
        if (!"2.1.0".equals(document.path("version").asText())) {
            throw violation("version is 2.1.0", "$.version");
        }
        only(
                (ObjectNode) object(document, "properties", "$"),
                Set.of("porelo/profileVersion"),
                "$.properties");
        final JsonNode profileVersion = document.path("properties").path("porelo/profileVersion");
        if (!profileVersion.isTextual() || !"1".equals(profileVersion.asText())) {
            throw violation("porelo/profileVersion is \"1\"", "$.properties");
        }
        final JsonNode runs = document.path("runs");
        if (!runs.isArray() || runs.size() != 1 || !runs.get(0).isObject()) {
            throw violation("exactly one run", "$.runs");
        }
        final ObjectNode run = (ObjectNode) runs.get(0);
        only(run, RUN, "$.runs[0]");
        only((ObjectNode) object(run, "tool", "$.runs[0]"), Set.of("driver"), "$.runs[0].tool");
        only(
                (ObjectNode) object(run.path("tool"), "driver", "$.runs[0].tool"),
                Set.of("name", "version"),
                "$.runs[0].tool.driver");
        if (!"unicodeCodePoints".equals(run.path("columnKind").asText())) {
            throw violation("columnKind is unicodeCodePoints", "$.runs[0].columnKind");
        }
        final JsonNode provenance = run.path("versionControlProvenance");
        if (!provenance.isArray() || provenance.size() != 1 || !provenance.get(0).isObject()) {
            throw violation(
                    "exactly one versionControlProvenance", "$.runs[0].versionControlProvenance");
        }
        only(
                (ObjectNode) provenance.get(0),
                Set.of("revisionId"),
                "$.runs[0].versionControlProvenance[0]");
        final String revision = commit(provenance.get(0).path("revisionId"), "revisionId");
        final ObjectNode runProperties = (ObjectNode) object(run, "properties", "$.runs[0]");
        only(runProperties, Set.of("porelo/baseRevisionId"), "$.runs[0].properties");
        final String base =
                commit(runProperties.path("porelo/baseRevisionId"), "porelo/baseRevisionId");
        final JsonNode results = run.path("results");
        if (!results.isArray() || results.size() != 1 || !results.get(0).isObject()) {
            throw violation("exactly one result", "$.runs[0].results");
        }
        final ObjectNode result = (ObjectNode) results.get(0);
        only(result, RESULT, "$.runs[0].results[0]");
        only(
                (ObjectNode) object(result, "message", "$.runs[0].results[0]"),
                Set.of("text", "markdown"),
                "message");
        // A person's column: every other key of a result is the daemon's or an adapter's.
        only(
                (ObjectNode) object(result, "properties", "$.runs[0].results[0]"),
                Set.of("porelo/anchorKind", "porelo/diffSide"),
                "$.runs[0].results[0].properties");
        if (!"review".equals(result.path("kind").asText())
                || !"none".equals(result.path("level").asText())) {
            throw violation(
                    "a person writes comments: kind review, level none",
                    "$.runs[0].results[0].kind");
        }
        everyString(document, "$");
        return new Submission(document, result, revision, base, GitHubReviewWrite.render(result));
    }

    private static void only(ObjectNode node, Set<String> allowed, String where) {
        final Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            final String name = names.next();
            if (DAEMON_OWNED.contains(name)) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.FORBIDDEN_FIELD,
                        name + " is the daemon's",
                        where + "." + name);
            }
            if (!allowed.contains(name)) {
                throw violation(name + " is not what a person's client sends", where + "." + name);
            }
        }
    }

    private static JsonNode object(JsonNode parent, String field, String where) {
        final JsonNode node = parent.path(field);
        if (!node.isObject()) {
            throw violation(field + " is an object", where + "." + field);
        }
        return node;
    }

    private static String commit(JsonNode node, String field) {
        if (!node.isTextual() || !COMMIT.matcher(node.asText()).matches()) {
            throw violation(field + " is a full commit", field);
        }
        return node.asText();
    }

    /** No string anywhere in the document, keys included, may hold a refused character. */
    private static void everyString(JsonNode node, String where) {
        if (node.isTextual()) {
            final String refused = ReviewText.refusedCharacter(node.asText());
            if (refused != null) {
                throw new ReviewRefusal(ReviewRefusal.Reason.UNSAFE_TEXT, refused, where);
            }
        } else if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            e -> {
                                if (ReviewText.refusedCharacter(e.getKey()) != null) {
                                    throw new ReviewRefusal(
                                            ReviewRefusal.Reason.UNSAFE_TEXT, "a key", where);
                                }
                                everyString(e.getValue(), where + "." + e.getKey());
                            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                everyString(node.get(i), where + "[" + i + "]");
            }
        }
    }

    private static ReviewRefusal violation(String detail, String path) {
        return new ReviewRefusal(ReviewRefusal.Reason.PROFILE_VIOLATION, detail, path);
    }
}
