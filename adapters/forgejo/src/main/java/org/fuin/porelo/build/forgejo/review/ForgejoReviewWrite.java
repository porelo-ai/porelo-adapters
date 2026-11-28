package org.fuin.porelo.build.forgejo.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.fuin.porelo.build.review.ReviewRefusal;
import org.fuin.porelo.build.review.Submission;
import org.fuin.porelo.forge.forgejo.ForgejoClient;
import org.fuin.porelo.forge.forgejo.model.CreatePullReviewComment;
import org.fuin.porelo.forge.forgejo.model.CreatePullReviewOptions;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.fuin.porelo.forge.forgejo.model.PullReviewComment;

/** Turns a released submission into one Forgejo review containing one line comment. */
public final class ForgejoReviewWrite {

    private static final String ID_UNREAD = "The comment was written but its id could not be read";

    private ForgejoReviewWrite() {}

    /**
     * The one create call a submission becomes.
     *
     * @param review The review containing the line comment.
     * @param body The comment's body as the forge receives it.
     */
    public record Call(CreatePullReviewOptions review, String body) {}

    /**
     * Renders the submission's one result on the pinned commit.
     *
     * @param result The result, already held to profile 2's submission form.
     * @param commit The pinned commit.
     * @return The create call.
     * @throws ReviewRefusal If the result cannot be carried by Forgejo.
     */
    public static Call render(JsonNode result, String commit) {
        final Submission.Write write = Submission.read(result, false);
        if (write instanceof Submission.Reply) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.NOT_SUPPORTED,
                    "Forgejo has no reply to a review comment",
                    "properties.porelo/replyTo");
        }
        final Submission.Line line = (Submission.Line) write;
        if (line.start() != line.end()) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.NOT_SUPPORTED, "Forgejo comments on one line", "region");
        }
        final CreatePullReviewComment comment =
                new CreatePullReviewComment(
                        line.body(),
                        0L,
                        line.newSide() ? (long) line.start() : 0L,
                        line.newSide() ? 0L : (long) line.start(),
                        line.path());
        return new Call(
                new CreatePullReviewOptions("", List.of(comment), commit, "COMMENT"), line.body());
    }

    /**
     * Creates once, then reads the created review's single comment to learn its thread id.
     *
     * @param client The Forgejo client.
     * @param token The token lent for this call.
     * @param owner The repository's owner.
     * @param repo The repository's name.
     * @param index The pull request's number.
     * @param call What {@link #render} gave.
     * @return The new comment's id as a decimal string.
     * @throws IllegalStateException If the comment was written but its id could not be read.
     */
    public static String send(
            ForgejoClient client, String token, String owner, String repo, long index, Call call) {
        final PullReview review = client.createReview(token, owner, repo, index, call.review());
        final Long reviewId = review.id();
        if (reviewId == null || reviewId < 1) {
            throw new IllegalStateException(ID_UNREAD);
        }
        final List<PullReviewComment> comments;
        try {
            comments = client.reviewComments(token, owner, repo, index, reviewId);
        } catch (RuntimeException ex) {
            // Repeating the create after a failed read would write the released comment twice.
            throw new IllegalStateException(ID_UNREAD, ex);
        }
        if (comments.size() != 1) {
            throw new IllegalStateException(ID_UNREAD);
        }
        final Long commentId = comments.getFirst().id();
        if (commentId == null || commentId < 1) {
            throw new IllegalStateException(ID_UNREAD);
        }
        return commentId.toString();
    }
}
