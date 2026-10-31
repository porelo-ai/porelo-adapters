package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.PullRequestReview;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;

/** GitHub's answers in the shape GitHub sends them, read into the client's records. */
final class GitHubAnswers {

    static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private GitHubAnswers() {}

    static PullRequestReviewComment comment(Consumer<ObjectNode> change) {
        return read("review-comment.json", change, PullRequestReviewComment.class);
    }

    static PullRequestReview review(Consumer<ObjectNode> change) {
        return read("review.json", change, PullRequestReview.class);
    }

    static IssueComment issueComment(Consumer<ObjectNode> change) {
        return read("issue-comment.json", change, IssueComment.class);
    }

    static GitHubReviewRead.Thread thread(Consumer<ObjectNode> change) {
        final GitHubGraphQl.ReviewThread thread =
                read("thread.json", change, GitHubGraphQl.ReviewThread.class);
        return new GitHubReviewRead.Thread(thread, thread.comments().nodes());
    }

    private static <T> T read(String name, Consumer<ObjectNode> change, Class<T> type) {
        try (InputStream in = GitHubAnswers.class.getResourceAsStream(name)) {
            final ObjectNode node = (ObjectNode) JSON.readTree(in);
            change.accept(node);
            return JSON.treeToValue(node, type);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
