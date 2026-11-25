package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.regex.Pattern;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.fuin.porelo.forge.github.model.PullsCreateReplyForReviewCommentRequest;
import org.fuin.porelo.forge.github.model.PullsCreateReviewCommentRequest;
import org.jspecify.annotations.Nullable;

/**
 * Turns the one result of a released submission, already read and held to profile 2, into exactly
 * one {@code github} client call: a reply to the thread it names, or a new line comment on the
 * pinned commit. A mention in its text becomes a code span, so it notifies no one; a fix becomes a
 * {@code suggestion} fence on the new side.
 */
public final class SubmissionWrite {

    private static final Pattern THREAD = Pattern.compile("[1-9][0-9]{0,18}");

    private SubmissionWrite() {}

    /**
     * The one call a submission becomes.
     *
     * @param replyTo The thread's root comment ID, for a reply; {@code null} for a new comment.
     * @param comment The new line comment, for a new comment; {@code null} for a reply.
     * @param body The body GitHub receives.
     */
    public record Call(
            @Nullable Long replyTo,
            @Nullable PullsCreateReviewCommentRequest comment,
            String body) {}

    /**
     * Renders the submission's one result.
     *
     * @param result The result, held to profile 2's submission form.
     * @param commit The pinned commit a new comment is made on.
     * @return The call.
     * @throws ReviewRefusal {@code NotSupported} for a comment profile 2 cannot place on lines,
     *     {@code UnsafeText} or {@code ProfileViolation} for a text GitHub would read otherwise.
     */
    public static Call render(JsonNode result, String commit) {
        final JsonNode properties = result.path("properties");
        final String body = body(result);
        final JsonNode replyTo = properties.path("porelo/replyTo");
        if (!replyTo.isMissingNode()) {
            if (result.has("fixes")) {
                throw refusal("a reply carries no fix", "fixes");
            }
            final String thread = replyTo.asText();
            if (!THREAD.matcher(thread).matches()) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.PROFILE_VIOLATION,
                        "porelo/replyTo is not a thread this forge names",
                        "porelo/replyTo");
            }
            final long id;
            try {
                id = Long.parseLong(thread);
            } catch (NumberFormatException ex) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.PROFILE_VIOLATION,
                        "porelo/replyTo is not a thread this forge names",
                        "porelo/replyTo");
            }
            return new Call(id, null, body);
        }
        final JsonNode physical = result.path("locations").path(0).path("physicalLocation");
        final JsonNode region = physical.path("region");
        if (!"line".equals(properties.path("porelo/anchorKind").asText()) || !region.isObject()) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.NOT_SUPPORTED,
                    "profile 2 writes a comment on lines or a reply only",
                    "locations");
        }
        final String uri = physical.path("artifactLocation").path("uri").asText();
        final String path;
        try {
            path = RepositoryPath.path(uri);
        } catch (IllegalArgumentException ex) {
            throw refusal(String.valueOf(ex.getMessage()), "artifactLocation.uri");
        }
        final int start = region.path("startLine").asInt();
        final int end = region.path("endLine").asInt();
        if (start < 1 || end < start) {
            throw refusal("a region that ends before it starts", "region");
        }
        if (end - start >= Conversation.REGION_LIMIT) {
            throw refusal("a region over " + Conversation.REGION_LIMIT + " lines", "region");
        }
        final boolean newSide = "new".equals(properties.path("porelo/diffSide").asText());
        final StringBuilder text = new StringBuilder(body);
        if (result.has("fixes")) {
            if (!newSide) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.OLD_SIDE_FIX, "a fix on the old side", "fixes");
            }
            final int at = text.length() + 2;
            text.append("\n\n").append(fence(result.path("fixes"), uri, start, end));
            // An open fence in the text would swallow the suggestion, which then fixes nothing.
            final List<int[]> fences = ReviewText.fences(text.toString());
            if (fences.isEmpty() || fences.get(fences.size() - 1)[0] != at) {
                throw refusal("the text leaves a code fence open before the fix", "message.text");
            }
        }
        if (text.codePointCount(0, text.length()) > ReviewText.BODY_LIMIT) {
            throw refusal(
                    "a text and fix over " + ReviewText.BODY_LIMIT + " characters", "message.text");
        }
        final PullsCreateReviewCommentRequest.SideEnum side =
                newSide
                        ? PullsCreateReviewCommentRequest.SideEnum.RIGHT
                        : PullsCreateReviewCommentRequest.SideEnum.LEFT;
        final boolean multiLine = start < end;
        final PullsCreateReviewCommentRequest request =
                new PullsCreateReviewCommentRequest(
                        text.toString(),
                        commit,
                        path,
                        null,
                        side,
                        end,
                        multiLine ? start : null,
                        multiLine
                                ? PullsCreateReviewCommentRequest.StartSideEnum.valueOf(side.name())
                                : null,
                        null,
                        null);
        return new Call(null, request, text.toString());
    }

    /**
     * Makes the one call.
     *
     * @param client The {@code github} client.
     * @param token The token lent for this call.
     * @param owner The repository's owner.
     * @param repo The repository's name.
     * @param pullRequest The pull request's number.
     * @param call What {@link #render} gave.
     * @return The thread written to: the one answered, or the new comment's ID, as a decimal
     *     string.
     */
    public static String send(
            GitHubClient client,
            String token,
            String owner,
            String repo,
            long pullRequest,
            Call call) {
        final Long replyTo = call.replyTo();
        if (replyTo != null) {
            client.replyReviewComment(
                    token,
                    owner,
                    repo,
                    pullRequest,
                    replyTo,
                    new PullsCreateReplyForReviewCommentRequest(call.body()));
            return Long.toString(replyTo);
        }
        final PullRequestReviewComment comment =
                client.createReviewComment(
                        token,
                        owner,
                        repo,
                        pullRequest,
                        java.util.Objects.requireNonNull(call.comment()));
        return Long.toString(comment.id());
    }

    /** The profile's plain text as GitHub's body: escapes undone, mentions made code spans. */
    private static String body(JsonNode result) {
        final String message = result.path("message").path("text").asText();
        final StringBuilder plain = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            final char c = message.charAt(i);
            if (c == '\\') {
                final char next = i + 1 < message.length() ? message.charAt(i + 1) : 0;
                if (next != '\\' && next != '[' && next != ']') {
                    throw refusal(
                            "a backslash at " + i + " escapes no backslash or bracket",
                            "message.text");
                }
                plain.append(next);
                i++;
            } else {
                plain.append(c);
            }
        }
        // toForge reads braces doubled, as the profile writes them, and undoes that too.
        return ReviewText.toForge(plain.toString());
    }

    /** The one replacement of the anchor's lines, as a fence GitHub shows as a suggestion. */
    private static String fence(JsonNode fixes, String uri, int start, int end) {
        final JsonNode change = fixes.path(0).path("artifactChanges").path(0);
        final JsonNode replacement = change.path("replacements").path(0);
        final JsonNode deleted = replacement.path("deletedRegion");
        if (!uri.equals(change.path("artifactLocation").path("uri").asText())) {
            throw refusal("the fix is in another file", "fixes");
        }
        if (deleted.path("startLine").asInt() != start || deleted.path("endLine").asInt() != end) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.FIX_OUTSIDE_ANCHOR,
                    "the replaced lines are not the anchor's",
                    "fixes");
        }
        final String inserted = replacement.path("insertedContent").path("text").asText();
        final String refused = ReviewText.refusedCharacter(inserted);
        if (refused != null) {
            throw new ReviewRefusal(ReviewRefusal.Reason.UNSAFE_TEXT, refused, "fixes");
        }
        final String marks = "`".repeat(Math.max(3, longestRun(inserted) + 1));
        // SARIF's inserted lines end in a line feed; GitHub's fence holds the lines without the
        // last.
        final String lines =
                inserted.endsWith("\n") ? inserted.substring(0, inserted.length() - 1) : inserted;
        return marks
                + "suggestion\n"
                + (lines.isEmpty() && inserted.isEmpty() ? "" : lines + "\n")
                + marks;
    }

    private static int longestRun(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    private static ReviewRefusal refusal(String detail, String path) {
        return new ReviewRefusal(ReviewRefusal.Reason.PROFILE_VIOLATION, detail, path);
    }
}
