package org.fuin.porelo.build.forgejo.review;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.fuin.porelo.build.review.ProfileResult;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.fuin.porelo.forge.forgejo.model.PullReviewComment;
import org.fuin.porelo.forge.forgejo.model.User;
import org.jspecify.annotations.Nullable;

/** Converts Forgejo's submitted line comments to results of Porelo's review profile 2. */
public final class ForgejoReviewRead {

    private ForgejoReviewRead() {}

    /**
     * One review and its comments.
     *
     * @param review The review.
     * @param comments Every comment read from it.
     */
    public record Reviewed(PullReview review, List<PullReviewComment> comments) {

        /** Copies the comments. */
        public Reviewed {
            comments = List.copyOf(comments);
        }
    }

    /**
     * The client's answers for a pull request.
     *
     * @param reviews The reviews and their comments.
     * @param complete Whether all answers were read to their end.
     */
    public record Native(List<Reviewed> reviews, boolean complete) {

        /** Copies the reviews. */
        public Native {
            reviews = List.copyOf(reviews);
        }
    }

    /**
     * The converted conversation.
     *
     * @param results Threads in root-id order, each thread's comments in id order.
     * @param held How many comments could not be carried, including duplicate occurrences.
     * @param complete Whether every answer was read and nothing was held.
     */
    public record Read(List<ObjectNode> results, int held, boolean complete) {

        /** Copies the results. */
        public Read {
            results = List.copyOf(results);
        }
    }

    /**
     * Converts one pull request's reviews, grouping comments by path, side and line.
     *
     * @param forgeHost The Forgejo instance's web host, with its port when it has one, which each
     *     result's guid is made from.
     * @param answers What the client read.
     * @return The results and the count held.
     */
    public static Read read(String forgeHost, Native answers) {
        final Set<Long> seen = new HashSet<>();
        final Map<Long, PullReviewComment> byId = new TreeMap<>();
        int held = 0;
        for (final Reviewed reviewed : answers.reviews()) {
            if ("PENDING".equals(reviewed.review().state())) {
                continue;
            }
            for (final PullReviewComment comment : reviewed.comments()) {
                final Long id = comment.id();
                if (id == null || id < 1 || !seen.add(id)) {
                    held++;
                } else {
                    byId.put(id, comment);
                }
            }
        }
        final Map<ProfileResult.Anchor, List<PullReviewComment>> threads = new LinkedHashMap<>();
        for (final PullReviewComment comment : byId.values()) {
            final ProfileResult.Anchor anchor = anchorFromPositions(comment);
            if (anchor == null) {
                held++;
            } else {
                threads.computeIfAbsent(anchor, key -> new ArrayList<>()).add(comment);
            }
        }
        final List<ObjectNode> results = new ArrayList<>();
        for (final var thread : threads.entrySet()) {
            final List<ObjectNode> converted =
                    thread(forgeHost, thread.getKey(), thread.getValue());
            held += thread.getValue().size() - converted.size();
            results.addAll(converted);
        }
        return new Read(results, held, answers.complete() && held == 0);
    }

    private static List<ObjectNode> thread(
            String forgeHost, ProfileResult.Anchor anchor, List<PullReviewComment> comments) {
        final PullReviewComment root = comments.getFirst();
        final String threadId = Objects.requireNonNull(root.id()).toString();
        final boolean resolved = root.resolver() != null;
        final List<ObjectNode> results = new ArrayList<>();
        for (final PullReviewComment comment : comments) {
            final ObjectNode result =
                    result(forgeHost, comment, anchor, threadId, resolved, comment != root);
            if (result == null) {
                // Replies without a representable root would name a thread absent from the page.
                if (comment == root) {
                    return List.of();
                }
            } else {
                results.add(result);
            }
        }
        return results;
    }

    private static @Nullable ObjectNode result(
            String forgeHost,
            PullReviewComment comment,
            ProfileResult.Anchor anchor,
            String threadId,
            boolean resolved,
            boolean reply) {
        final User user = comment.user();
        final OffsetDateTime createdAt = comment.createdAt();
        if (user == null || user.id() == null || createdAt == null) {
            return null;
        }
        return ProfileResult.of(
                forgeHost,
                new ProfileResult.Comment(
                        Objects.requireNonNull(comment.id()),
                        comment.body(),
                        user.id(),
                        user.login(),
                        createdAt.toInstant()),
                anchor,
                threadId,
                resolved,
                reply);
    }

    /** Forgejo names one side: a position above zero the new side's line, else the old side's. */
    private static ProfileResult.@Nullable Anchor anchorFromPositions(PullReviewComment comment) {
        final Integer position = comment.position();
        final Integer original = comment.originalPosition();
        final int onNew = position == null ? 0 : position;
        final int onOld = original == null ? 0 : original;
        // Forgejo names one side; a comment naming both has no one side the profile could name.
        if (onNew < 0 || onOld < 0 || (onNew > 0) == (onOld > 0)) {
            return null;
        }
        return onNew > 0
                ? ProfileResult.Anchor.of(comment.path(), onNew, onNew, true)
                : ProfileResult.Anchor.of(comment.path(), onOld, onOld, false);
    }
}
