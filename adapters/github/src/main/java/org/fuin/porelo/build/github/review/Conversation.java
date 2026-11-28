package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.build.review.ProfileResult;
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
        final ProfileResult.Anchor anchor = root.inReplyToId() == null ? anchor(root) : null;
        if (anchor == null) {
            return List.of();
        }
        final String threadId = Long.toString(root.id());
        final boolean resolved = Boolean.TRUE.equals(thread.thread().isResolved());
        final List<ObjectNode> out = new ArrayList<>();
        for (final PullRequestReviewComment comment : comments) {
            final boolean reply = comment != root;
            if (reply && !Long.valueOf(root.id()).equals(comment.inReplyToId())) {
                continue;
            }
            final ObjectNode result = result(forgeHost, comment, anchor, threadId, resolved, reply);
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
            ProfileResult.Anchor anchor,
            String threadId,
            boolean resolved,
            boolean reply) {
        final SimpleUser user = comment.user();
        if (user == null) {
            return null;
        }
        return ProfileResult.of(
                forgeHost,
                new ProfileResult.Comment(
                        comment.id(),
                        comment.body(),
                        user.id(),
                        user.login(),
                        comment.createdAt().toInstant()),
                anchor,
                threadId,
                resolved,
                reply);
    }

    /** Where a thread stands, or {@code null} when the profile has no anchor for its lines. */
    private static ProfileResult.@Nullable Anchor anchor(PullRequestReviewComment root) {
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
        return ProfileResult.Anchor.of(
                root.path(),
                root.startLine() == null ? line : root.startLine(),
                line,
                side != PullRequestReviewComment.SideEnum.LEFT);
    }
}
