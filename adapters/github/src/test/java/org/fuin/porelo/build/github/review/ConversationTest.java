package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import org.fuin.porelo.build.review.ProfileResult;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.junit.jupiter.api.Test;

class ConversationTest {

    static final String HOST = "github.com";

    static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void anOutdatedThreadAndAFileCommentAreLeftOutWithoutMakingTheReadPartial() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() >= 201) {
                                        c.put("subject_type", "file").putNull("line");
                                    }
                                },
                                t -> {
                                    if (t.get("id").asText().equals("PRRT_1")) {
                                        t.put("isOutdated", true);
                                    }
                                }));

        assertThat(read.results()).as("the profile has no anchor for either").isEmpty();
        assertThat(read.complete()).as("leaving them out holds nothing").isTrue();
    }

    @Test
    void aCommentGitHubCallsPendingButRestListsIsHeldNotDropped() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {},
                                t -> {
                                    if ("PRRT_1".equals(t.get("id").asText())) {
                                        ((ObjectNode) t.at("/comments/nodes/1"))
                                                .put("state", "PENDING");
                                    }
                                }));

        assertThat(read.complete())
                .as("a comment others can read must never vanish behind a complete read")
                .isFalse();
        assertThat(read.held()).isEqualTo(1);
    }

    @Test
    void aHeldRootHoldsItsWholeThread() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() == 201) {
                                        c.put("body", "x".repeat(ProfileResult.TEXT_LIMIT + 1));
                                    }
                                },
                                t -> {}));

        assertThat(read.held()).as("the root and its two replies").isEqualTo(3);
        assertThat(read.results()).hasSize(2);
    }

    @Test
    void aCommentsTimeIsItsCreationInWholeSecondsOfUtc() {
        final List<ObjectNode> results =
                Conversation.read(
                                HOST,
                                answers(
                                        c -> {
                                            if (c.get("id").asLong() == 102) {
                                                c.put(
                                                        "created_at",
                                                        "2026-10-30T11:14:02.789+02:00");
                                                c.put("updated_at", "2026-11-02T08:00:00Z");
                                            }
                                        },
                                        t -> {}))
                        .results();

        assertThat(results.get(1).at("/properties/porelo~1createdAt").asText())
                .as("converted to UTC, its fraction cut, never the edit time")
                .isEqualTo("2026-10-30T09:14:02Z");
    }

    @Test
    void aCommentOfADeletedAccountIsHeld() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() == 102) {
                                        c.putNull("user");
                                    }
                                },
                                t -> {}));

        assertThat(read.held()).as("no account to name as producer").isEqualTo(1);
    }

    @Test
    void aLineCommentInNoThreadIsHeld() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {},
                                t -> {
                                    if (t.get("id").asText().equals("PRRT_1")) {
                                        ((ArrayNode) t.at("/comments/nodes")).remove(1);
                                    }
                                }));

        assertThat(read.held()).as("the reply GraphQL does not list").isEqualTo(1);
        assertThat(read.complete()).isFalse();
    }

    @Test
    void aRegionOverOneHundredLinesHoldsItsThread() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() <= 102) {
                                        c.put("start_line", 1).put("line", 101);
                                    }
                                },
                                t -> {}));

        assertThat(read.held()).isEqualTo(2);
    }

    @Test
    void aLineOrParagraphSeparatorIsHeld() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() == 203) {
                                        c.put("body", "a\u2028b");
                                    }
                                },
                                t -> {}));

        assertThat(read.held()).as("the profile refuses U+2028").isEqualTo(1);
    }

    @Test
    void aThreadWithoutALineThatIsNotOutdatedIsHeldNotLeftOut() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() <= 102) {
                                        c.putNull("line");
                                    }
                                },
                                t -> {}));

        assertThat(read.held()).as("GitHub still places the thread, so it is lost").isEqualTo(2);
        assertThat(read.complete()).isFalse();
    }

    @Test
    void aSpanStartingOnTheOtherSideHoldsItsThread() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {
                                    if (c.get("id").asLong() <= 102) {
                                        c.put("start_side", "LEFT");
                                    }
                                },
                                t -> {}));

        assertThat(read.held()).as("no one side to name").isEqualTo(2);
    }

    @Test
    void aCommentGitHubNamesTwiceIsKeptOnce() {
        final Conversation.Read read =
                Conversation.read(
                        HOST,
                        answers(
                                c -> {},
                                t -> {
                                    if (t.get("id").asText().equals("PRRT_1")) {
                                        final ArrayNode nodes = (ArrayNode) t.at("/comments/nodes");
                                        nodes.add(nodes.get(1).deepCopy());
                                    }
                                }));

        assertThat(read.results())
                .extracting(r -> r.get("guid").asText())
                .as("one result per comment")
                .doesNotHaveDuplicates();
        assertThat(read.complete()).as("a page that moved is not a whole read").isFalse();
    }

    @Test
    void aCommentKeepsOneGuidShapedAsAUuid4() {
        final String guid = ProfileResult.guid(HOST, 101);

        assertThat(guid)
                .as("the profile's UUIDv4 pattern")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(ProfileResult.guid(HOST, 101)).as("the same on every read").isEqualTo(guid);
        assertThat(ProfileResult.guid(HOST, 102)).isNotEqualTo(guid);
        assertThat(ProfileResult.guid("git.example.org", 101))
                .as("another forge's comment of the same ID")
                .isNotEqualTo(guid);
    }

    static Conversation.Native answers(Consumer<ObjectNode> comment, Consumer<ObjectNode> thread) {
        return answers(comment, thread, all -> {});
    }

    static Conversation.Native answers(
            Consumer<ObjectNode> comment, Consumer<ObjectNode> thread, Consumer<ArrayNode> all) {
        final ArrayNode comments = (ArrayNode) tree("conversation-comments.json");
        comments.forEach(c -> comment.accept((ObjectNode) c));
        final ArrayNode threads = (ArrayNode) tree("conversation-threads.json");
        threads.forEach(t -> thread.accept((ObjectNode) t));
        all.accept(threads);
        try {
            final List<PullRequestReviewComment> rest =
                    JSON.readerFor(new TypeReference<List<PullRequestReviewComment>>() {})
                            .readValue(comments);
            final List<GitHubGraphQl.ReviewThread> graph =
                    JSON.readerFor(new TypeReference<List<GitHubGraphQl.ReviewThread>>() {})
                            .readValue(threads);
            return new Conversation.Native(
                    rest,
                    graph.stream()
                            .map(t -> new Conversation.Thread(t, t.comments().nodes()))
                            .toList(),
                    true);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static JsonNode tree(String name) {
        try (InputStream in = ConversationTest.class.getResourceAsStream(name)) {
            return JSON.readTree(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
