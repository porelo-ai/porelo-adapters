package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ReviewPageTest {

    static final JsonSchema PROFILE_2 = schema();

    static final ReviewPage.Run RUN =
            new ReviewPage.Run(
                    "https://github.com/example-org/example-repo",
                    "https://github.com/example-org/example-repo/pull/17",
                    "bb33e12342d6f5235c9c5acddfdb1fc6f86dedfd",
                    "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432");

    @Test
    void twoThreadsWithRepliesBecomeOnePageThatPassesProfileTwo() {
        final Conversation.Read read =
                Conversation.read(
                        ConversationTest.HOST, ConversationTest.answers(c -> {}, t -> {}));

        final ReviewPage.Page page = page(read.results(), read.complete(), null);

        assertThat(PROFILE_2.validate(page.text(), InputFormat.JSON))
                .as("the page holds to profile 2's schema")
                .isEmpty();
        assertThat(page.cursor()).as("one page holds the whole conversation").isNull();
        assertThat(page.document().at("/runs/0/properties/porelo~1completeness").asText())
                .isEqualTo("complete");
        assertThat(page.document().at("/runs/0/results").size()).isEqualTo(5);
    }

    @Test
    void aPageWithAHeldCommentIsPartialAndStillPassesProfileTwo() {
        final Conversation.Read read =
                Conversation.read(
                        ConversationTest.HOST,
                        ConversationTest.answers(
                                c -> {
                                    if (c.get("id").asLong() == 202) {
                                        c.put("body", "x​");
                                    }
                                },
                                t -> {}));

        final ReviewPage.Page page = page(read.results(), read.complete(), null);

        assertThat(PROFILE_2.validate(page.text(), InputFormat.JSON)).isEmpty();
        assertThat(page.document().at("/runs/0/properties/porelo~1completeness").asText())
                .isEqualTo("partial");
    }

    @Test
    void moreThanOneHundredResultsArePagedByCursor() {
        final List<ObjectNode> results = copies(250, "short");

        final ReviewPage.Page first = page(results, true, null);
        final ReviewPage.Page second = page(results, true, first.cursor());
        final ReviewPage.Page third = page(results, true, second.cursor());

        assertThat(List.of(first.cursor(), second.cursor()))
                .as("each cursor names the next page's first result")
                .allSatisfy(c -> assertThat(c).matches("(100|200)\\.[0-9a-f]{16}"));
        assertThat(third.cursor()).as("the last page has no next").isNull();
        assertThat(
                        List.of(first, second, third).stream()
                                .map(p -> p.document().at("/runs/0/results").size())
                                .toList())
                .containsExactly(100, 100, 50);
        assertThat(
                        List.of(first, second, third).stream()
                                .map(
                                        p ->
                                                p.document()
                                                        .at(
                                                                "/runs/0/properties/porelo~1completeness")
                                                        .asText())
                                .toList())
                .as("complete only on the last page")
                .containsExactly("partial", "partial", "complete");
    }

    @Test
    void aResultGoneBetweenTwoPagesMakesTheNextPagePartial() {
        final List<ObjectNode> results = copies(150, "short");
        final ReviewPage.Page first = page(results, true, null);
        final List<ObjectNode> shrunk = new ArrayList<>(results);
        shrunk.remove(3);

        final ReviewPage.Page second = page(shrunk, true, first.cursor());

        assertThat(second.cursor()).as("the second page is the last").isNull();
        assertThat(completeness(second))
                .as("a result before the cursor went, so the walk skipped one unnoticed")
                .isEqualTo("partial");
    }

    @Test
    void aResultAddedBeforeTheCursorKeepsEveryLaterPagePartial() {
        final List<ObjectNode> results = copies(250, "short");
        final ReviewPage.Page first = page(results, true, null);
        final List<ObjectNode> grown = new ArrayList<>(results);
        grown.add(0, copies(251, "new").get(250));

        final ReviewPage.Page second = page(grown, true, first.cursor());
        final ReviewPage.Page third = page(grown, true, second.cursor());

        assertThat(List.of(completeness(second), completeness(third)))
                .as("once the walk saw a change, its last page cannot say complete either")
                .containsExactly("partial", "partial");
        assertThat(third.cursor()).isNull();
    }

    @Test
    void aPageStopsBeforeOneMebibyte() {
        final List<ObjectNode> results = copies(100, "{{".repeat(8_000));

        final ReviewPage.Page first = page(results, true, null);

        assertThat(first.text().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ReviewPage.BYTE_LIMIT);
        assertThat(first.cursor()).as("the rest goes on the next page").isNotNull();
        assertThat(PROFILE_2.validate(first.text(), InputFormat.JSON)).isEmpty();
    }

    @Test
    void aCursorThisAdapterDidNotWriteIsRefused() {
        for (final String cursor :
                List.of(
                        "-1",
                        "01",
                        "x",
                        "1e3",
                        "9999999999",
                        "100",
                        "100.",
                        "100.0123456789abcdeF",
                        "100.0123456789abcde",
                        "100.0123456789abcdef.y",
                        "01.0123456789abcdef")) {
            assertThatThrownBy(() -> page(List.of(), true, cursor))
                    .as(cursor)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aCursorPastAShrunkConversationAnswersAnEmptyPartialPage() {
        final ReviewPage.Page page = page(copies(3, "x"), true, "50.0123456789abcdef");

        assertThat(page.document().at("/runs/0/results").size()).isZero();
        assertThat(page.document().at("/runs/0/properties/porelo~1completeness").asText())
                .as("what changed since is not known")
                .isEqualTo("partial");
        assertThat(page.cursor()).isNull();
    }

    private static String completeness(ReviewPage.Page page) {
        return page.document().at("/runs/0/properties/porelo~1completeness").asText();
    }

    private static ReviewPage.Page page(List<ObjectNode> results, boolean complete, String cursor) {
        return ReviewPage.page(
                results, complete, cursor, (taken, word) -> ReviewPage.document(RUN, taken, word));
    }

    /** Copies of the first converted result, each its own guid, with the given text. */
    private static List<ObjectNode> copies(int count, String text) {
        final ObjectNode one =
                Conversation.read(ConversationTest.HOST, ConversationTest.answers(c -> {}, t -> {}))
                        .results()
                        .get(0);
        final List<ObjectNode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final ObjectNode copy = one.deepCopy();
            copy.put("guid", Conversation.guid(ConversationTest.HOST, i + 1));
            ((ObjectNode) copy.get("message")).put("text", text);
            out.add(copy);
        }
        return out;
    }

    private static JsonSchema schema() {
        final String name = "/org/fuin/porelo/build/api/review/review-profile-2.schema.json";
        try (InputStream in = ReviewPageTest.class.getResourceAsStream(name)) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Objects.requireNonNull(in, name));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
