package org.fuin.porelo.build.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.review.ProfileCheck;

/**
 * The one result of a released submission, already read and held to profile 2, read as what a forge
 * writes: a reply to the thread it names, or a new comment on lines of one side. A mention in its
 * text becomes a code span, so it notifies no one; a fix becomes a {@code suggestion} fence on the
 * new side, where the forge shows one.
 */
public final class Submission {

    private static final int FIX_BYTES = ProfileCheck.MAX_TEXT_BYTES;

    private static final int FIX_EXTRA_LINES = ProfileCheck.EXTRA_REPLACEMENT_LINES;

    private static final int FIX_LINE = ProfileCheck.MAX_REPLACEMENT_LINE;

    private static final Pattern THREAD = Pattern.compile("[1-9][0-9]{0,18}");

    private Submission() {}

    /** What the result asks the forge to write. */
    public sealed interface Write permits Reply, Line {

        /**
         * The body the forge receives.
         *
         * @return The body.
         */
        String body();
    }

    /**
     * A reply to a thread.
     *
     * @param thread The thread's root comment's ID.
     * @param body The body the forge receives.
     */
    public record Reply(long thread, String body) implements Write {}

    /**
     * A new comment on lines.
     *
     * @param path The file's path in the repository.
     * @param start The first line.
     * @param end The last line.
     * @param newSide Whether the lines are on the new side of the diff.
     * @param body The body the forge receives, a fix's fence included.
     * @param fixed Whether the body carries a fix.
     */
    public record Line(String path, int start, int end, boolean newSide, String body, boolean fixed)
            implements Write {}

    /**
     * Reads the submission's one result.
     *
     * @param result The result, held to profile 2's submission form.
     * @param suggestions Whether the forge shows a {@code suggestion} fence as a fix; a fix is
     *     refused as {@code NotSupported} where it does not.
     * @return What the forge writes.
     * @throws ReviewRefusal {@code NotSupported} for a comment profile 2 cannot place on lines,
     *     {@code UnsafeText} or {@code ProfileViolation} for a text the forge would read otherwise.
     */
    public static Write read(JsonNode result, boolean suggestions) {
        final JsonNode properties = result.path("properties");
        final String body = body(result);
        final JsonNode replyTo = properties.path("porelo/replyTo");
        if (!replyTo.isMissingNode()) {
            if (result.has("fixes")) {
                throw refusal("a reply carries no fix", "fixes");
            }
            final String thread = replyTo.asText();
            if (!THREAD.matcher(thread).matches()) {
                throw notAThread();
            }
            try {
                return new Reply(Long.parseLong(thread), body);
            } catch (NumberFormatException ex) {
                throw notAThread();
            }
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
        if (end - start >= ProfileResult.REGION_LIMIT) {
            throw refusal("a region over " + ProfileResult.REGION_LIMIT + " lines", "region");
        }
        final boolean newSide = "new".equals(properties.path("porelo/diffSide").asText());
        final StringBuilder text = new StringBuilder(body);
        final boolean fixed = result.has("fixes");
        if (fixed) {
            if (!suggestions) {
                throw new ReviewRefusal(
                        ReviewRefusal.Reason.NOT_SUPPORTED,
                        "this forge shows no suggested change",
                        "fixes");
            }
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
        return new Line(path, start, end, newSide, text.toString(), fixed);
    }

    /** The profile's plain text as the forge's body: escapes undone, mentions made code spans. */
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

    /** The one replacement of the anchor's lines, as a fence the forge shows as a suggestion. */
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
        // SARIF's inserted lines end in a line feed; the fence holds the lines without the last.
        final String lines =
                inserted.endsWith("\n") ? inserted.substring(0, inserted.length() - 1) : inserted;
        bounded(lines, inserted, end - start + 1);
        return marks
                + "suggestion\n"
                + (lines.isEmpty() && inserted.isEmpty() ? "" : lines + "\n")
                + marks;
    }

    /** Holds a suggestion to profile 2's bounds, which the forge would otherwise take or refuse. */
    private static void bounded(String lines, String inserted, int anchored) {
        if (inserted.getBytes(StandardCharsets.UTF_8).length > FIX_BYTES) {
            throw refusal("a fix over " + FIX_BYTES + " bytes", "fixes");
        }
        final String[] each = lines.isEmpty() ? new String[0] : lines.split("\n", -1);
        if (each.length > anchored + FIX_EXTRA_LINES) {
            throw refusal(
                    "a fix of more lines than its anchor's and " + FIX_EXTRA_LINES + " more",
                    "fixes");
        }
        for (final String line : each) {
            if (line.codePointCount(0, line.length()) > FIX_LINE) {
                throw refusal("a fix line over " + FIX_LINE + " characters", "fixes");
            }
        }
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

    private static ReviewRefusal notAThread() {
        return new ReviewRefusal(
                ReviewRefusal.Reason.PROFILE_VIOLATION,
                "porelo/replyTo is not a thread this forge names",
                "porelo/replyTo");
    }

    private static ReviewRefusal refusal(String detail, String path) {
        return new ReviewRefusal(ReviewRefusal.Reason.PROFILE_VIOLATION, detail, path);
    }
}
