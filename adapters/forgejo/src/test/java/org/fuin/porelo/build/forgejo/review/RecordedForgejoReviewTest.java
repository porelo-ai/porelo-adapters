package org.fuin.porelo.build.forgejo.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.forge.forgejo.ForgejoClient;
import org.fuin.porelo.forge.forgejo.model.CreatePullReviewComment;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.junit.jupiter.api.Test;

/** The review conversion over what Forgejo 17.0.0 answered, as the recorder captured it. */
class RecordedForgejoReviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String HOST = "127.0.0.1:3997";

    private static final String HEAD = "9f4b530e5ed93571ea9c468722a4d8189acd5c20";

    @Test
    void theRecordedConversationReadsIntoTwoThreadsWithTheReplyAndTheResolvedOldSide()
            throws IOException {
        final ForgejoReviewRead.Read read = ForgejoReviewRead.read(HOST, recorded());

        assertThat(read.results())
                .as("both roots and the reply; the pending comment left out")
                .extracting(r -> r.path("message").path("text").asText())
                .containsExactly(
                        "Explain the new login.",
                        reply().path("message").path("text").asText(),
                        old().path("message").path("text").asText());
        assertThat(read.held())
                .as("Forgejo's own answers hold nothing the profile refuses")
                .isZero();
        assertThat(read.complete()).as("every answer was read whole").isTrue();
    }

    @Test
    void theRecordedReplyJoinsTheNewSideThreadOfAnotherReview() throws IOException {
        final JsonNode reply = reply();

        assertThat(reply.at("/properties/porelo~1replyTo").asText())
                .as("Forgejo answers the reply in a review of its own, on the same line and side")
                .isEqualTo("2");
        assertThat(reply.at("/properties/porelo~1diffSide").asText())
                .as("position above zero is the new side")
                .isEqualTo("new");
        assertThat(reply.at("/properties/porelo~1forgeAccountName").asText())
                .as("the reply is the pull request author's")
                .isEqualTo("core");
    }

    @Test
    void theRecordedOldSideCommentIsResolvedOnItsLine() throws IOException {
        final JsonNode old = old();

        assertThat(old.at("/properties/porelo~1diffSide").asText())
                .as("original_position above zero with position zero is the old side")
                .isEqualTo("old");
        assertThat(old.at("/locations/0/physicalLocation/region/startLine").asInt())
                .as("the recorded original_position")
                .isEqualTo(2);
        assertThat(old.at("/properties/porelo~1threadState").asText())
                .as("Forgejo shows a resolver on the resolved conversation's comment")
                .isEqualTo("resolved");
    }

    @Test
    void aRenderedSingleLineCommentIsTheRequestForgejoAccepted() {
        final ForgejoReviewWrite.Call call = ForgejoReviewWrite.render(lineComment(), HEAD);
        final CreatePullReviewComment comment = call.review().comments().getFirst();

        assertThat(call.review().event()).as("the recorder's event").isEqualTo("COMMENT");
        assertThat(call.review().body()).as("the recorder's empty review body").isEmpty();
        assertThat(call.review().commitId()).as("the recorder's pinned head").isEqualTo(HEAD);
        assertThat(call.review().comments()).as("the recorder's one comment").hasSize(1);
        assertThat(comment.path()).as("the recorder's path").isEqualTo("login.txt");
        assertThat(comment.newPosition()).as("the recorder's new_position").isEqualTo(3L);
        assertThat(comment.oldPosition()).as("the recorder's old_position").isZero();
        assertThat(comment.extraLinesCount()).as("the recorder's extra_lines_count").isZero();
        assertThat(comment.body())
                .as("the recorder's comment body")
                .isEqualTo("Recorded single-line comment.");
    }

    @Test
    void theCreatedReviewsRecordedCommentNamesTheThread() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(200, body("06")),
                        new ReplayedForgejo.Reply(200, body("07")));

        final String thread =
                ForgejoReviewWrite.send(
                        client(http),
                        "test-token",
                        "core",
                        "probe",
                        1,
                        ForgejoReviewWrite.render(lineComment(), HEAD));

        assertThat(thread)
                .as("the new comment's id, which a later read names its thread by")
                .isEqualTo("9");
    }

    private static ForgejoReviewRead.Native recorded() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(200, body("02")),
                        new ReplayedForgejo.Reply(200, body("03")),
                        new ReplayedForgejo.Reply(200, body("04")),
                        new ReplayedForgejo.Reply(200, body("05")));
        final ForgejoClient client = client(http);
        final List<ForgejoReviewRead.Reviewed> reviewed = new ArrayList<>();
        for (final PullReview review :
                client.reviews("test-token", "core", "probe", 1, 1, 50).items()) {
            reviewed.add(
                    new ForgejoReviewRead.Reviewed(
                            review,
                            client.reviewComments(
                                    "test-token",
                                    "core",
                                    "probe",
                                    1,
                                    Objects.requireNonNull(review.id()))));
        }
        return new ForgejoReviewRead.Native(reviewed, true);
    }

    private static JsonNode reply() throws IOException {
        return ForgejoReviewRead.read(HOST, recorded()).results().get(1);
    }

    private static JsonNode old() throws IOException {
        return ForgejoReviewRead.read(HOST, recorded()).results().get(2);
    }

    private static ObjectNode lineComment() {
        final ObjectNode result = JSON.createObjectNode();
        result.put("kind", "review").put("level", "none");
        result.putObject("message").put("text", "Recorded single-line comment.");
        final ObjectNode physical =
                result.putArray("locations").addObject().putObject("physicalLocation");
        physical.putObject("artifactLocation").put("uri", "login.txt");
        physical.putObject("region").put("startLine", 3).put("endLine", 3);
        result.putObject("properties")
                .put("porelo/anchorKind", "line")
                .put("porelo/diffSide", "new");
        return result;
    }

    private static ForgejoClient client(ReplayedForgejo.RecordingHttpClient http) {
        return new ForgejoClient(URI.create("https://forgejo.example.org/api/v1/"), http);
    }

    private static String body(String file) throws IOException {
        final String name = "/recordings/pull-request-reviews/" + file + ".json";
        try (InputStream in = RecordedForgejoReviewTest.class.getResourceAsStream(name)) {
            return JSON.readTree(Objects.requireNonNull(in, name)).path("body").asText();
        }
    }
}
