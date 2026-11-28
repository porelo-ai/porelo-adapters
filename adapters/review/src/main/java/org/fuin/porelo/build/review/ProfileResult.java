package org.fuin.porelo.build.review;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One line comment a forge holds, written as a result of Porelo's review profile 2, the same on
 * every forge: produced by the forge account that wrote it, in its thread, on its file, lines and
 * side. What the profile cannot carry gives no result, and the caller holds it.
 */
public final class ProfileResult {

    /** At most this many bytes of UTF-8 in one result's text, as the forge gave it. */
    public static final int TEXT_LIMIT = 8192;

    /** A line region spans at most this many lines. */
    public static final int REGION_LIMIT = 100;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ProfileResult() {}

    /**
     * Where a thread stands, as the profile writes it.
     *
     * @param uri The file's repository-relative URI.
     * @param start The first line.
     * @param end The last line.
     * @param side {@code old} or {@code new}.
     */
    public record Anchor(String uri, int start, int end, String side) {

        /**
         * The anchor of a forge's lines, or {@code null} when the profile has none for them.
         *
         * @param path The file's path in the repository, as the forge gave it.
         * @param start The first line.
         * @param end The last line.
         * @param newSide Whether the lines are on the new side of the diff.
         * @return The anchor, or {@code null}.
         */
        public static @Nullable Anchor of(
                @Nullable String path, int start, int end, boolean newSide) {
            if (path == null || start < 1 || end < start || end - start + 1 > REGION_LIMIT) {
                return null;
            }
            final String uri;
            try {
                uri = RepositoryPath.uri(path);
            } catch (IllegalArgumentException ex) {
                return null;
            }
            return new Anchor(uri, start, end, newSide ? "new" : "old");
        }
    }

    /**
     * One comment as the forge holds it.
     *
     * @param id The forge's ID of the comment.
     * @param body Its text.
     * @param accountId The forge's ID of the account that wrote it.
     * @param accountName That account's login.
     * @param createdAt When it was written.
     */
    public record Comment(
            long id,
            @Nullable String body,
            long accountId,
            @Nullable String accountName,
            Instant createdAt) {}

    /**
     * Writes one comment as a result.
     *
     * @param forgeHost The forge's web host, which the result's {@code guid} is made from.
     * @param comment The comment.
     * @param anchor Where its thread stands.
     * @param threadId The thread's ID, its root comment's.
     * @param resolved Whether the forge shows the thread resolved.
     * @param reply Whether the comment answers the thread's root.
     * @return The result, or {@code null} when the profile cannot carry the comment.
     */
    public static @Nullable ObjectNode of(
            String forgeHost,
            Comment comment,
            Anchor anchor,
            String threadId,
            boolean resolved,
            boolean reply) {
        final String text = message(comment.body());
        final String name = comment.accountName();
        if (text == null || comment.accountId() < 1 || name == null || !isName(name)) {
            return null;
        }
        final ObjectNode result = NODES.objectNode();
        result.put("guid", guid(forgeHost, comment.id()));
        result.put("kind", "review");
        result.put("level", "none");
        result.putObject("message").put("text", text);
        final ObjectNode physical =
                result.putArray("locations").addObject().putObject("physicalLocation");
        physical.putObject("artifactLocation").put("uri", anchor.uri());
        physical.putObject("region").put("startLine", anchor.start()).put("endLine", anchor.end());
        final ObjectNode properties = result.putObject("properties");
        properties.put("porelo/anchorKind", "line");
        properties.put("porelo/diffSide", anchor.side());
        properties.put("porelo/threadId", threadId);
        if (reply) {
            properties.put("porelo/replyTo", threadId);
        }
        properties.put("porelo/threadState", resolved ? "resolved" : "open");
        properties.put("porelo/sourceKind", "forge-account");
        properties.put("porelo/forgeAccountId", Long.toString(comment.accountId()));
        properties.put("porelo/forgeAccountName", name);
        // Whole seconds in UTC: when it was written, never when it was last edited.
        properties.put(
                "porelo/createdAt", comment.createdAt().truncatedTo(ChronoUnit.SECONDS).toString());
        return result;
    }

    /**
     * The same comment's {@code guid} on every page and call: SHA-256 of the forge's host and the
     * comment's ID, its bits shaped as a UUIDv4, as the profile's pattern asks.
     *
     * @param forgeHost The forge's web host.
     * @param commentId The comment's ID.
     * @return The guid.
     */
    public static String guid(String forgeHost, long commentId) {
        final byte[] hash;
        try {
            hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest((forgeHost + ":" + commentId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing", ex);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        final ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    /**
     * A forge's text as the profile's plain message: within its limit, no refused character, a
     * backslash and square brackets escaped and literal braces doubled.
     *
     * @param body What the forge holds.
     * @return The message, or {@code null} when the profile cannot carry it.
     */
    public static @Nullable String message(@Nullable String body) {
        if (body == null) {
            return null;
        }
        final String text = body.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()
                || text.getBytes(StandardCharsets.UTF_8).length > TEXT_LIMIT
                || ReviewText.refusedCharacter(text) != null) {
            return null;
        }
        return text.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "{{")
                .replace("}", "}}");
    }

    /**
     * Whether a login can be shown as an account's name.
     *
     * @param login The login.
     * @return Whether it can.
     */
    public static boolean isName(@Nullable String login) {
        return login != null
                && !login.isEmpty()
                && ReviewText.refusedCharacter(login) == null
                && login.codePointCount(0, login.length()) <= ReviewText.DISPLAY_NAME_LIMIT;
    }
}
