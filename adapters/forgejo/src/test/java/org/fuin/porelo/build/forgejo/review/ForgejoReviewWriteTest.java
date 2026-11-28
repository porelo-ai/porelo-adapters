package org.fuin.porelo.build.forgejo.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import org.fuin.porelo.build.review.ReviewRefusal;
import org.fuin.porelo.forge.forgejo.ForgejoClient;
import org.fuin.porelo.forge.forgejo.ForgejoException;
import org.fuin.porelo.forge.forgejo.model.CreatePullReviewComment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgejoReviewWriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COMMIT = "bb33e12342d6f5235c9c5acddfdb1fc6f86dedfd";

    @Test
    void aNewSideCommentUsesOnePositionOnThePinnedCommit() {
        final ForgejoReviewWrite.Call call = ForgejoReviewWrite.render(comment(), COMMIT);

        assertThat(call.review().body()).as("the review itself adds no general comment").isEmpty();
        assertThat(call.review().commitId())
                .as("the review is pinned to the released commit")
                .isEqualTo(COMMIT);
        assertThat(call.review().event())
                .as("the comment is submitted rather than pending")
                .isEqualTo("COMMENT");
        assertThat(call.review().comments())
                .as("one submission creates one line comment")
                .hasSize(1);
        final CreatePullReviewComment comment = call.review().comments().getFirst();
        assertThat(comment.newPosition()).as("the new side names its line").isEqualTo(10L);
        assertThat(comment.oldPosition()).as("the other side is unused").isZero();
        assertThat(comment.extraLinesCount()).as("Forgejo receives a single line anchor").isZero();
        assertThat(comment.path()).as("the repository URI is decoded once").isEqualTo("doc/a b.md");
        assertThat(comment.body())
                .as("the line comment carries the released text")
                .isEqualTo("Use the constant.");
        assertThat(call.body())
                .as("the caller can inspect the body that will be written")
                .isEqualTo(comment.body());
    }

    @Test
    void anOldSideCommentUsesOnlyTheOldPosition() {
        final ObjectNode result = comment();
        ((ObjectNode) result.path("properties")).put("porelo/diffSide", "old");

        final ForgejoReviewWrite.Call call = ForgejoReviewWrite.render(result, COMMIT);

        assertThat(call.review().comments())
                .as("an old-side submission also writes one comment")
                .hasSize(1);
        final CreatePullReviewComment comment = call.review().comments().getFirst();
        assertThat(comment.newPosition()).as("the new side is unused").isZero();
        assertThat(comment.oldPosition()).as("the old side names its line").isEqualTo(10L);
        assertThat(comment.extraLinesCount()).as("no additional lines are anchored").isZero();
        assertThat(call.review().commitId())
                .as("old-side comments still use the pinned commit")
                .isEqualTo(COMMIT);
        assertThat(call.review().event()).as("the review is released").isEqualTo("COMMENT");
    }

    @Test
    void aReplyIsRefusedBeforeAnyClientCall() {
        final ObjectNode result = comment();
        result.remove("locations");
        ((ObjectNode) result.path("properties")).put("porelo/replyTo", "101");
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ForgejoClient client = client(http);

        assertThatThrownBy(() -> send(client, ForgejoReviewWrite.render(result, COMMIT)))
                .as("Forgejo must not turn a reply into a new thread")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        refusal -> {
                            assertThat(refusal.reason())
                                    .as("replies are unsupported")
                                    .isEqualTo(ReviewRefusal.Reason.NOT_SUPPORTED);
                            assertThat(refusal.path())
                                    .as("the refusal identifies the requested reply")
                                    .isEqualTo("properties.porelo/replyTo");
                        })
                .hasMessage("Forgejo has no reply to a review comment");
        assertThat(http.requests).as("refusal precedes every request").isEmpty();
    }

    @Test
    void aMultiLineRegionIsRefusedBeforeAnyClientCall() {
        final ObjectNode result = comment();
        ((ObjectNode) result.at("/locations/0/physicalLocation/region")).put("endLine", 12);
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ForgejoClient client = client(http);

        assertThatThrownBy(() -> send(client, ForgejoReviewWrite.render(result, COMMIT)))
                .as("a span must not silently become a single-line comment")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        refusal -> {
                            assertThat(refusal.reason())
                                    .as("Forgejo cannot carry a span")
                                    .isEqualTo(ReviewRefusal.Reason.NOT_SUPPORTED);
                            assertThat(refusal.path())
                                    .as("the region needs correction")
                                    .isEqualTo("region");
                        });
        assertThat(http.requests).as("an unsupported span is never written").isEmpty();
    }

    @Test
    void aFixIsRefusedBeforeAnyClientCall() {
        final ObjectNode result = comment();
        final ObjectNode change =
                result.putArray("fixes").addObject().putArray("artifactChanges").addObject();
        change.putObject("artifactLocation").put("uri", "doc/a%20b.md");
        final ObjectNode replacement = change.putArray("replacements").addObject();
        replacement.putObject("deletedRegion").put("startLine", 10).put("endLine", 10);
        replacement.putObject("insertedContent").put("text", "replacement\n");
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ForgejoClient client = client(http);

        assertThatThrownBy(() -> send(client, ForgejoReviewWrite.render(result, COMMIT)))
                .as("a fix must not appear as inert text instead of a suggested change")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        refusal -> {
                            assertThat(refusal.reason())
                                    .as("Forgejo shows no suggested change")
                                    .isEqualTo(ReviewRefusal.Reason.NOT_SUPPORTED);
                            assertThat(refusal.path())
                                    .as("the fix cannot be carried")
                                    .isEqualTo("fixes");
                        });
        assertThat(http.requests).as("a fix is never sent").isEmpty();
    }

    @Test
    void sharedTextRenderingUndoesEscapesAndPreventsMentionNotifications() {
        final ObjectNode result = comment();
        ((ObjectNode) result.path("message")).put("text", "See \\[x\\] {{value}} \\\\ @author.");

        final ForgejoReviewWrite.Call call = ForgejoReviewWrite.render(result, COMMIT);

        assertThat(call.body())
                .as("Forgejo receives plain source with mentions in code spans")
                .isEqualTo("See [x] {value} \\ `@author`.");
        assertThat(call.review().comments().getFirst().body())
                .as("the request uses the same rendered body")
                .isEqualTo(call.body());
    }

    @Test
    void oneCreateAndOneReadReturnTheCommentIdRatherThanTheReviewId() {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(200, "{\"id\":900}"),
                        new ReplayedForgejo.Reply(200, "[{\"id\":101}]"));

        final String thread = send(client(http), ForgejoReviewWrite.render(comment(), COMMIT));

        assertThat(thread)
                .as("a later read names the thread by its root comment, not the review")
                .isEqualTo("101");
        assertThat(http.requests)
                .as("one write followed by one read")
                .extracting(HttpRequest::method)
                .containsExactly("POST", "GET");
        assertThat(http.requests)
                .as("the read addresses the review just created")
                .extracting(r -> r.uri().getPath())
                .containsExactly(
                        "/api/v1/repos/example-org/example-repo/pulls/17/reviews",
                        "/api/v1/repos/example-org/example-repo/pulls/17/reviews/900/comments");
    }

    @Test
    void aFailedCommentReadReportsTheCompletedWriteAndPreservesTheCause() {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(200, "{\"id\":900}"),
                        new ReplayedForgejo.Reply(500, "{\"message\":\"comments unavailable\"}"));

        assertThatThrownBy(() -> send(client(http), ForgejoReviewWrite.render(comment(), COMMIT)))
                .as("the caller must know the write happened despite the failed read")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comment was written but its id could not be read")
                .hasCauseInstanceOf(ForgejoException.class)
                .satisfies(
                        ex ->
                                assertThat(ex.getCause())
                                        .as("the forge's failure remains available")
                                        .hasMessageContaining("comments unavailable"));
        assertThat(http.requests)
                .as("a failed id lookup never repeats the create")
                .extracting(HttpRequest::method)
                .containsExactly("POST", "GET");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "[{\"id\":101},{\"id\":102}]",
                "[{}]",
                "[{\"id\":0}]",
                "[{\"id\":-1}]",
                "null",
                "[null]",
                "{"
            })
    void anUnreadableCommentIdDoesNotRepeatTheCreate(String comments) {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(200, "{\"id\":900}"),
                        new ReplayedForgejo.Reply(200, comments));

        assertThatThrownBy(() -> send(client(http), ForgejoReviewWrite.render(comment(), COMMIT)))
                .as("a missing or ambiguous comment id cannot identify the written thread")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comment was written but its id could not be read");
        assertThat(http.requests)
                .as("even an ambiguous read performs no further write")
                .extracting(HttpRequest::method)
                .containsExactly("POST", "GET");
    }

    @Test
    void aCreateFailurePropagatesWithoutReadingOrRepeatingTheWrite() {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(500, "{\"message\":\"review unavailable\"}"));

        assertThatThrownBy(() -> send(client(http), ForgejoReviewWrite.render(comment(), COMMIT)))
                .as("a failed create retains the client's delivery information")
                .isInstanceOf(ForgejoException.class)
                .hasMessageContaining("review unavailable");
        assertThat(http.requests)
                .as("no read or retry follows a failed create")
                .extracting(HttpRequest::method)
                .containsExactly("POST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":0}", "{\"id\":-1}"})
    void aCreatedReviewWithoutAnIdReportsTheWriteWithoutRepeatingIt(String review) {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(new ReplayedForgejo.Reply(200, review));

        assertThatThrownBy(() -> send(client(http), ForgejoReviewWrite.render(comment(), COMMIT)))
                .as("a created review without an id cannot be addressed for the comment read")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comment was written but its id could not be read");
        assertThat(http.requests)
                .as("a missing review id never authorizes another write")
                .extracting(HttpRequest::method)
                .containsExactly("POST");
    }

    private static String send(ForgejoClient client, ForgejoReviewWrite.Call call) {
        return ForgejoReviewWrite.send(
                client, "test-token", "example-org", "example-repo", 17, call);
    }

    private static ForgejoClient client(HttpClient http) {
        return new ForgejoClient(URI.create("https://forgejo.example.org/api/v1/"), http);
    }

    private static ObjectNode comment() {
        final ObjectNode result = JSON.createObjectNode();
        result.put("kind", "review").put("level", "none");
        result.putObject("message").put("text", "Use the constant.");
        final ObjectNode physical =
                result.putArray("locations").addObject().putObject("physicalLocation");
        physical.putObject("artifactLocation").put("uri", "doc/a%20b.md");
        physical.putObject("region").put("startLine", 10).put("endLine", 10);
        result.putObject("properties")
                .put("porelo/anchorKind", "line")
                .put("porelo/diffSide", "new");
        return result;
    }
}
