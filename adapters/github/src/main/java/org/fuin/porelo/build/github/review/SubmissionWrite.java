package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.JsonNode;
import org.fuin.porelo.build.review.ReviewRefusal;
import org.fuin.porelo.build.review.Submission;
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
        final Submission.Write write = Submission.read(result, true);
        if (write instanceof Submission.Reply reply) {
            return new Call(reply.thread(), null, reply.body());
        }
        final Submission.Line line = (Submission.Line) write;
        final PullsCreateReviewCommentRequest.SideEnum side =
                line.newSide()
                        ? PullsCreateReviewCommentRequest.SideEnum.RIGHT
                        : PullsCreateReviewCommentRequest.SideEnum.LEFT;
        final boolean multiLine = line.start() < line.end();
        final PullsCreateReviewCommentRequest request =
                new PullsCreateReviewCommentRequest(
                        line.body(),
                        commit,
                        line.path(),
                        null,
                        side,
                        line.end(),
                        multiLine ? line.start() : null,
                        multiLine
                                ? PullsCreateReviewCommentRequest.StartSideEnum.valueOf(side.name())
                                : null,
                        null,
                        null);
        return new Call(null, request, line.body());
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
}
