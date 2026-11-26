package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.jspecify.annotations.Nullable;

/**
 * Converts a pull request's line comments and their replies, as GitHub answers them, to results of
 * Porelo's review profile 2. A thread is named by its root comment's REST ID; each comment and each
 * reply is a result of its own, produced by the GitHub account that wrote it. A thread GitHub no
 * longer places on lines, and a file comment, are left out, as the profile has no anchor for them;
 * so is a comment of the reading account's own review not yet submitted. A comment the profile
 * cannot carry is held: counted, left out, and the read is not complete.
 */
public final class Conversation {

    /** At most this many bytes of UTF-8 in one result's text, as GitHub gave it. */
    public static final int TEXT_LIMIT = 8192;

    /** A line region spans at most this many lines. */
    public static final int REGION_LIMIT = 100;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Conversation() {}

    /**
     * One review thread as GraphQL answers it, with every comment of it, all pages read.
     *
     * @param thread The thread.
     * @param comments Every comment of it, oldest first.
     */
    public record Thread(GitHubGraphQl.ReviewThread thread, List<GitHubGraphQl.Comment> comments) {

        /** Copies the comments. */
        public Thread {
            comments = List.copyOf(comments);
        }
    }

    /**
     * What GitHub answered about one pull request's line comments.
     *
     * @param comments Its review comments, as REST lists them.
     * @param threads Its review threads, in GitHub's order.
     * @param complete Whether both lists were read to their end.
     */
    public record Native(
            List<PullRequestReviewComment> comments, List<Thread> threads, boolean complete) {

        /** Copies the lists. */
        public Native {
            comments = List.copyOf(comments);
            threads = List.copyOf(threads);
        }
    }

    /**
     * The converted read.
     *
     * @param results The results, in the threads' order and each thread's items in theirs.
     * @param held How many comments were held.
     * @param complete Whether everything was read and nothing held.
     */
    public record Read(List<ObjectNode> results, int held, boolean complete) {

        /** Copies the results. */
        public Read {
            results = List.copyOf(results);
        }
    }

    /**
     * Converts one pull request's answers.
     *
     * @param forgeHost The forge's web host, such as {@code github.com}, which each result's {@code
     *     guid} is made from with the comment's ID.
     * @param answers What GitHub answered.
     * @return The results and what was held.
     */
    public static Read read(String forgeHost, Native answers) {
        final Map<Long, PullRequestReviewComment> byId = new HashMap<>();
        answers.comments().forEach(c -> byId.put(c.id(), c));
        final Set<Long> seen = new HashSet<>();
        final List<ObjectNode> results = new ArrayList<>();
        int held = 0;
        for (final Thread thread : answers.threads()) {
            final List<PullRequestReviewComment> comments = new ArrayList<>();
            for (final GitHubGraphQl.Comment node : thread.comments()) {
                if (pending(node)) {
                    continue;
                }
                final Long id = node.databaseId();
                final PullRequestReviewComment comment = id == null ? null : byId.get(id);
                // A comment GitHub names twice, as a moved page cursor may, is kept once.
                if (comment == null || seen.contains(id)) {
                    held++;
                } else {
                    comments.add(comment);
                    seen.add(id);
                }
            }
            if (comments.isEmpty() || leftOut(thread, comments.get(0))) {
                continue;
            }
            final List<ObjectNode> converted = thread(forgeHost, thread, comments);
            held += comments.size() - converted.size();
            results.addAll(converted);
        }
        for (final PullRequestReviewComment comment : answers.comments()) {
            if (!seen.contains(comment.id()) && comment.line() != null && !file(comment)) {
                held++;
            }
        }
        return new Read(results, held, answers.complete() && held == 0);
    }

    /**
     * Whether a comment belongs to the reading account's own review not yet submitted: nobody else
     * sees it, and GitHub's REST list leaves it out, so it is no result and nothing held.
     */
    private static boolean pending(GitHubGraphQl.Comment comment) {
        return "PENDING".equals(comment.state());
    }

    /** A thread the profile has no anchor for: one GitHub no longer places, or on a file. */
    private static boolean leftOut(Thread thread, PullRequestReviewComment root) {
        return Boolean.TRUE.equals(thread.thread().isOutdated()) || file(root);
    }

    private static boolean file(PullRequestReviewComment comment) {
        return comment.subjectType() == PullRequestReviewComment.SubjectTypeEnum.FILE;
    }

    /** The thread's results; a held root holds its replies, which have no place without it. */
    private static List<ObjectNode> thread(
            String forgeHost, Thread thread, List<PullRequestReviewComment> comments) {
        final PullRequestReviewComment root = comments.get(0);
        final Anchor anchor = root.inReplyToId() == null ? Anchor.of(root) : null;
        if (anchor == null) {
            return List.of();
        }
        final String threadId = Long.toString(root.id());
        final String state =
                Boolean.TRUE.equals(thread.thread().isResolved()) ? "resolved" : "open";
        final List<ObjectNode> out = new ArrayList<>();
        for (final PullRequestReviewComment comment : comments) {
            final boolean reply = comment != root;
            if (reply && !Long.valueOf(root.id()).equals(comment.inReplyToId())) {
                continue;
            }
            final ObjectNode result = result(forgeHost, comment, anchor, threadId, state, reply);
            if (result == null) {
                if (!reply) {
                    return List.of();
                }
                continue;
            }
            out.add(result);
        }
        return out;
    }

    private static @Nullable ObjectNode result(
            String forgeHost,
            PullRequestReviewComment comment,
            Anchor anchor,
            String threadId,
            String state,
            boolean reply) {
        final String text = ProfileText.message(comment.body());
        final SimpleUser user = comment.user();
        if (text == null || user == null || user.id() < 1 || !ProfileText.isName(user.login())) {
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
        properties.put("porelo/threadState", state);
        properties.put("porelo/sourceKind", "forge-account");
        properties.put("porelo/forgeAccountId", Long.toString(user.id()));
        properties.put("porelo/forgeAccountName", user.login());
        // Whole seconds in UTC: when it was written, never when it was last edited.
        properties.put(
                "porelo/createdAt",
                comment.createdAt()
                        .toInstant()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString());
        return result;
    }

    /**
     * The same comment's {@code guid} on every page and call: SHA-256 of the forge's host and the
     * comment's ID, its bits shaped as a UUIDv4, as the profile's pattern asks.
     *
     * @param forgeHost The forge's web host.
     * @param commentId The comment's REST ID.
     * @return The guid.
     */
    static String guid(String forgeHost, long commentId) {
        final byte[] hash;
        try {
            hash =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest((forgeHost + ":" + commentId).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing", ex);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        final java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(hash, 0, 16);
        return new java.util.UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    /** Where a thread stands: its file, lines and side, as the profile writes them. */
    private record Anchor(String uri, int start, int end, String side) {

        static @Nullable Anchor of(PullRequestReviewComment root) {
            final Integer line = root.line();
            final PullRequestReviewComment.SideEnum side = root.side();
            if (line == null || side == null) {
                return null;
            }
            // A span starting on the other side has no one side the profile could name.
            final PullRequestReviewComment.StartSideEnum startSide = root.startSide();
            if (root.startLine() != null
                    && startSide != null
                    && !startSide.getValue().equals(side.getValue())) {
                return null;
            }
            final int start = root.startLine() == null ? line : root.startLine();
            if (start < 1 || line < start || line - start + 1 > REGION_LIMIT) {
                return null;
            }
            final String uri;
            try {
                uri = RepositoryPath.uri(root.path());
            } catch (IllegalArgumentException ex) {
                return null;
            }
            return new Anchor(
                    uri,
                    start,
                    line,
                    side == PullRequestReviewComment.SideEnum.LEFT ? "old" : "new");
        }
    }

    /** The text rules of the profile for what a forge wrote. */
    static final class ProfileText {

        private ProfileText() {}

        /**
         * A forge's text as the profile's plain message: within its limit, no refused character, a
         * backslash and square brackets escaped and literal braces doubled.
         *
         * @param body What the forge holds.
         * @return The message, or {@code null} when the profile cannot carry it.
         */
        static @Nullable String message(@Nullable String body) {
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

        /** Whether a login can be shown as an account's name. */
        static boolean isName(@Nullable String login) {
            return login != null
                    && !login.isEmpty()
                    && ReviewText.refusedCharacter(login) == null
                    && login.codePointCount(0, login.length()) <= ReviewText.DISPLAY_NAME_LIMIT;
        }
    }
}
