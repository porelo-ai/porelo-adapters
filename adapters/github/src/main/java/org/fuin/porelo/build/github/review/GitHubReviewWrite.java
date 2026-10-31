package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.IssuesUpdateCommentRequest;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.fuin.porelo.forge.github.model.PullsCreateReplyForReviewCommentRequest;
import org.fuin.porelo.forge.github.model.PullsCreateReviewCommentRequest;
import org.jspecify.annotations.Nullable;

/**
 * Renders one result of the review profile, in the projection a write receives, as the native
 * comment GitHub takes, and sends it through the {@code github} client. GitHub anchors whole lines,
 * so columns are dropped and named; a fix becomes a {@code suggestion} fence.
 */
public final class GitHubReviewWrite {

    /** A suggestion replaces at most this many lines more than its anchor has. */
    public static final int FIX_EXTRA_LINES = 10;

    /** A suggestion's line holds at most this many Unicode scalar values. */
    public static final int FIX_LINE_LIMIT = 400;

    private static final Set<String> RESULT_FIELDS =
            Set.of(
                    "kind",
                    "level",
                    "ruleId",
                    "message",
                    "locations",
                    "relatedLocations",
                    "fixes",
                    "guid",
                    "properties");

    private static final Set<String> RESULT_PROPERTIES =
            Set.of("porelo/anchorKind", "porelo/diffSide");

    private static final Set<String> AUTHORITY =
            Set.of(
                    "porelo/sourceLevel",
                    "porelo/sourceKind",
                    "porelo/taskId",
                    "porelo/taskRole",
                    "porelo/threadId",
                    "porelo/replyTo",
                    "porelo/threadState",
                    "porelo/authorId",
                    "porelo/completeness",
                    "porelo/projectId",
                    "porelo/repositoryId",
                    "porelo/reviewRound",
                    "suppressions",
                    "fingerprints",
                    "baselineState");

    private GitHubReviewWrite() {}

    /** Where a native comment goes. */
    public enum Anchor {
        /** On the pull request's conversation. */
        GENERAL,
        /** On a file. */
        FILE,
        /** On lines of a file. */
        LINE
    }

    /**
     * The native comment a result becomes.
     *
     * @param anchor Where it goes.
     * @param body Its Markdown body, as GitHub receives it.
     * @param path The file, for {@code FILE} and {@code LINE}.
     * @param startLine The first line, for {@code LINE}.
     * @param line The last line, for {@code LINE}.
     * @param side {@code LEFT} or {@code RIGHT}, for {@code FILE} and {@code LINE}.
     * @param losses What GitHub cannot carry, each with how often.
     */
    public record Rendered(
            Anchor anchor,
            String body,
            @Nullable String path,
            @Nullable Integer startLine,
            @Nullable Integer line,
            @Nullable String side,
            Map<String, Integer> losses) {

        /** Copies the losses. */
        public Rendered {
            losses = Map.copyOf(losses);
        }
    }

    /**
     * The native IDs a write got.
     *
     * @param commentId The comment's REST ID.
     * @param commentNodeId Its node ID.
     * @param commentKind {@code review} or {@code issue}.
     * @param reviewId The review GitHub made for it, for a review comment.
     */
    public record Written(
            long commentId, String commentNodeId, String commentKind, @Nullable Long reviewId) {}

    /**
     * Renders one result.
     *
     * @param result One result, in the projection a write receives.
     * @return The native comment.
     * @throws ReviewRefusal If the profile or GitHub refuses it.
     */
    public static Rendered render(ObjectNode result) {
        onlyFields(result, RESULT_FIELDS, "result");
        final JsonNode properties = result.path("properties");
        if (properties.isObject()) {
            onlyFields((ObjectNode) properties, RESULT_PROPERTIES, "result.properties");
        }
        final Map<String, Integer> losses = new TreeMap<>();
        final String kind = text(result, "kind");
        final String level = text(result, "level");
        final StringBuilder body = new StringBuilder();
        if ("fail".equals(kind)) {
            if (!Set.of("note", "warning", "error").contains(level)) {
                throw refusal("a finding's level is note, warning or error", "level");
            }
            body.append('[')
                    .append(level)
                    .append("] `")
                    .append(text(result, "ruleId"))
                    .append("`\n\n");
        } else if (!"review".equals(kind) || !"none".equals(level)) {
            throw refusal("a comment is kind review and level none", "kind");
        }
        final JsonNode message = result.path("message");
        final JsonNode markdown = message.path("markdown");
        body.append(
                ReviewText.toForge(
                        markdown.isTextual() ? markdown.asText() : text(message, "text")));
        if (result.has("relatedLocations")) {
            losses.merge("related-locations-dropped", 1, Integer::sum);
        }
        final String anchorKind = text(properties, "porelo/anchorKind");
        return switch (anchorKind) {
            case "general" -> general(result, properties, body.toString(), losses);
            case "file" -> anchored(result, properties, body, losses, Anchor.FILE);
            case "line" -> anchored(result, properties, body, losses, Anchor.LINE);
            default ->
                    throw refusal(
                            "porelo/anchorKind is line, file or general", "porelo/anchorKind");
        };
    }

    /**
     * Sends a rendered comment.
     *
     * @param client The {@code github} client.
     * @param token The token lent for this call.
     * @param owner The repository's owner.
     * @param repo The repository's name.
     * @param pullRequest The pull request's number.
     * @param commit The head the comment was made against.
     * @param rendered What {@link #render} gave.
     * @param replyTo The REST ID of the thread's root comment, for a reply; {@code null} otherwise.
     * @return The native IDs it got.
     */
    public static Written send(
            GitHubClient client,
            String token,
            String owner,
            String repo,
            long pullRequest,
            String commit,
            Rendered rendered,
            @Nullable Long replyTo) {
        if (replyTo != null) {
            final PullRequestReviewComment reply =
                    client.replyReviewComment(
                            token,
                            owner,
                            repo,
                            pullRequest,
                            replyTo,
                            new PullsCreateReplyForReviewCommentRequest(rendered.body()));
            return new Written(reply.id(), reply.nodeId(), "review", reply.pullRequestReviewId());
        }
        if (rendered.anchor() == Anchor.GENERAL) {
            final IssueComment comment =
                    client.createIssueComment(
                            token,
                            owner,
                            repo,
                            pullRequest,
                            new IssuesUpdateCommentRequest(rendered.body()));
            return new Written(comment.id(), comment.nodeId(), "issue", null);
        }
        final boolean file = rendered.anchor() == Anchor.FILE;
        final Integer line = rendered.line();
        final Integer start = rendered.startLine();
        final boolean multiLine = !file && start != null && line != null && start < line;
        final PullsCreateReviewCommentRequest.SideEnum side =
                PullsCreateReviewCommentRequest.SideEnum.valueOf(
                        java.util.Objects.requireNonNull(rendered.side()));
        final PullRequestReviewComment comment =
                client.createReviewComment(
                        token,
                        owner,
                        repo,
                        pullRequest,
                        new PullsCreateReviewCommentRequest(
                                rendered.body(),
                                commit,
                                java.util.Objects.requireNonNull(rendered.path()),
                                null,
                                side,
                                file ? null : line,
                                multiLine ? start : null,
                                multiLine
                                        ? PullsCreateReviewCommentRequest.StartSideEnum.valueOf(
                                                side.name())
                                        : null,
                                null,
                                file
                                        ? PullsCreateReviewCommentRequest.SubjectTypeEnum.FILE
                                        : PullsCreateReviewCommentRequest.SubjectTypeEnum.LINE));
        return new Written(comment.id(), comment.nodeId(), "review", comment.pullRequestReviewId());
    }

    private static Rendered general(
            ObjectNode result, JsonNode properties, String body, Map<String, Integer> losses) {
        if (result.has("locations") || properties.has("porelo/diffSide")) {
            throw refusal("a general comment has no location and no side", "locations");
        }
        if (result.has("fixes")) {
            throw refusal("a general comment has no fix", "fixes");
        }
        return new Rendered(Anchor.GENERAL, body, null, null, null, null, losses);
    }

    private static Rendered anchored(
            ObjectNode result,
            JsonNode properties,
            StringBuilder body,
            Map<String, Integer> losses,
            Anchor anchor) {
        final JsonNode locations = result.path("locations");
        if (!locations.isArray() || locations.size() != 1) {
            throw refusal("an anchored comment has exactly one location", "locations");
        }
        final JsonNode physical = locations.get(0).path("physicalLocation");
        final String uri = text(physical.path("artifactLocation"), "uri");
        final String path = path(uri, "locations[0].physicalLocation.artifactLocation.uri");
        final JsonNode diffSide = properties.path("porelo/diffSide");
        final String side;
        if (diffSide.isMissingNode()) {
            if (anchor == Anchor.LINE) {
                throw refusal("a line comment names porelo/diffSide", "porelo/diffSide");
            }
            side = "RIGHT";
        } else {
            side = side(diffSide.asText());
        }
        final JsonNode region = physical.path("region");
        if (anchor == Anchor.FILE) {
            if (!region.isMissingNode()) {
                throw refusal("a file comment has no region", "region");
            }
            if (result.has("fixes")) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.FIX_OUTSIDE_ANCHOR,
                        "a file comment has no fix",
                        "fixes");
            }
            return new Rendered(Anchor.FILE, body.toString(), path, null, null, side, losses);
        }
        final int start = line(region, "startLine");
        final int end = region.has("endLine") ? line(region, "endLine") : start;
        if (end < start || end - start + 1 > GitHubReviewRead.LINE_SPAN_LIMIT) {
            throw refusal("a region of 1 to 100 lines", "region");
        }
        if (region.has("startColumn") || region.has("endColumn")) {
            losses.merge("columns-dropped", 1, Integer::sum);
        }
        onlyFields(
                (ObjectNode) region,
                Set.of("startLine", "endLine", "startColumn", "endColumn"),
                "region");
        if (result.has("fixes")) {
            if (!"RIGHT".equals(side)) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.OLD_SIDE_FIX, "a fix on the old side", "fixes");
            }
            body.append("\n\n").append(fence(result.path("fixes"), uri, start, end));
        }
        return new Rendered(Anchor.LINE, body.toString(), path, start, end, side, losses);
    }

    /** The one replacement of the anchor's lines, as a fence GitHub shows as a suggestion. */
    private static String fence(JsonNode fixes, String uri, int start, int end) {
        if (fixes.size() != 1
                || fixes.get(0).path("artifactChanges").size() != 1
                || fixes.get(0).path("artifactChanges").get(0).path("replacements").size() != 1) {
            throw outside("exactly one fix with one change and one replacement");
        }
        final JsonNode change = fixes.get(0).path("artifactChanges").get(0);
        if (!uri.equals(change.path("artifactLocation").path("uri").asText())) {
            throw outside("the fix is in another file");
        }
        final JsonNode replacement = change.path("replacements").get(0);
        final JsonNode deleted = replacement.path("deletedRegion");
        if (deleted.has("startColumn") || deleted.has("endColumn")) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.NOT_SUPPORTED,
                    "GitHub replaces whole lines; a fix with columns",
                    "fixes");
        }
        final int fixEnd = deleted.has("endLine") ? deleted.path("endLine").asInt() : -1;
        if (deleted.path("startLine").asInt() != start || fixEnd != end) {
            throw outside("the replaced lines are not the anchor's");
        }
        final String inserted = replacement.path("insertedContent").path("text").asText(null);
        if (inserted == null) {
            throw outside("the replacement inserts no text");
        }
        checkInserted(inserted, end - start + 1);
        final String marks = "`".repeat(Math.max(3, longestRun(inserted, '`') + 1));
        return marks + "suggestion\n" + inserted + "\n" + marks;
    }

    private static void checkInserted(String inserted, int anchorLines) {
        final String refused = ReviewText.refusedCharacter(inserted);
        if (refused != null) {
            throw new ReviewRefusal(ReviewRefusal.Reason.UNSAFE_TEXT, refused, "fixes");
        }
        final String[] lines = inserted.split("\n", -1);
        if (lines.length > anchorLines + FIX_EXTRA_LINES
                || inserted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > ReviewText.BODY_LIMIT) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.TOO_LARGE, "a suggestion over its limits", "fixes");
        }
        for (final String line : lines) {
            if (line.codePointCount(0, line.length()) > FIX_LINE_LIMIT) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.TOO_LARGE,
                        "a suggestion line over " + FIX_LINE_LIMIT,
                        "fixes");
            }
        }
    }

    private static int longestRun(String text, char c) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == c ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static void onlyFields(ObjectNode node, Set<String> allowed, String where) {
        final Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            final String name = names.next();
            if (AUTHORITY.contains(name)) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.FORBIDDEN_FIELD,
                        name + " is the daemon's",
                        where + "." + name);
            }
            if (!allowed.contains(name)) {
                throw refusal(name + " is not in a write's projection", where + "." + name);
            }
        }
    }

    private static String side(String diffSide) {
        return switch (diffSide) {
            case "new" -> "RIGHT";
            case "old" -> "LEFT";
            default -> throw refusal("porelo/diffSide is old or new", "porelo/diffSide");
        };
    }

    private static String path(String uri, String where) {
        try {
            return RepositoryPath.path(uri);
        } catch (IllegalArgumentException ex) {
            throw refusal(String.valueOf(ex.getMessage()), where);
        }
    }

    private static int line(JsonNode region, String field) {
        final JsonNode value = region.path(field);
        if (!value.isIntegralNumber() || value.asLong() < 1 || value.asLong() > Integer.MAX_VALUE) {
            throw refusal(field + " is a line number from 1", "region." + field);
        }
        return value.asInt();
    }

    private static String text(JsonNode node, String field) {
        final JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw refusal(field + " is a non-empty string", field);
        }
        return value.asText();
    }

    private static ReviewRefusal refusal(String detail, String path) {
        return new ReviewRefusal(ReviewRefusal.Reason.PROFILE_VIOLATION, detail, path);
    }

    private static ReviewRefusal outside(String detail) {
        return new ReviewRefusal(ReviewRefusal.Reason.FIX_OUTSIDE_ANCHOR, detail, "fixes");
    }
}
