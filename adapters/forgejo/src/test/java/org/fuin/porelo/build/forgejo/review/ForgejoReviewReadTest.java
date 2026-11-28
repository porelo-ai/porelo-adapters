package org.fuin.porelo.build.forgejo.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.build.review.ProfileResult;
import org.fuin.porelo.build.review.ReviewPage;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.fuin.porelo.forge.forgejo.model.PullReviewComment;
import org.fuin.porelo.forge.forgejo.model.User;
import org.junit.jupiter.api.Test;

class ForgejoReviewReadTest {

    private static final String HOST = "forgejo.example.org";

    private static final OffsetDateTime CREATED =
            OffsetDateTime.parse("2026-10-30T11:14:02.789+02:00");

    @Test
    void twoThreadsWithRepliesNameTheirRootsAndKeepTheirSidesAndLines() {
        final ForgejoReviewRead.Read read = ForgejoReviewRead.read(HOST, conversation());

        assertThat(read.complete()).as("every comment fits the profile").isTrue();
        assertThat(read.held()).as("every comment is represented").isZero();
        assertThat(read.results())
                .as("comments from different reviews share their anchor's thread in id order")
                .extracting(r -> r.at("/properties/porelo~1threadId").asText())
                .containsExactly("101", "101", "201", "201");
        assertThat(read.results())
                .as("only later comments answer the root")
                .extracting(r -> r.at("/properties/porelo~1replyTo").asText(null))
                .containsExactly(null, "101", null, "201");
        assertThat(read.results())
                .as("the forge host participates in each comment's stable guid")
                .extracting(r -> r.path("guid").asText())
                .containsExactly(
                        ProfileResult.guid(HOST, 101),
                        ProfileResult.guid(HOST, 102),
                        ProfileResult.guid(HOST, 201),
                        ProfileResult.guid(HOST, 202));
        assertThat(read.results().getFirst().path("guid").asText())
                .as("another forge's same comment id has another identity")
                .isNotEqualTo(ProfileResult.guid("github.com", 101));
        assertThat(read.results())
                .as("a position above zero with no original one is the new side")
                .extracting(r -> r.at("/properties/porelo~1diffSide").asText())
                .containsExactly("new", "new", "old", "old");
        assertThat(read.results())
                .as("each comment occupies its thread's one line")
                .extracting(r -> r.at("/locations/0/physicalLocation/region/startLine").asInt())
                .containsExactly(10, 10, 10, 10);
        assertThat(read.results())
                .as("both sides describe a single line")
                .extracting(r -> r.at("/locations/0/physicalLocation/region/endLine").asInt())
                .containsExactly(10, 10, 10, 10);
        assertThat(
                        read.results()
                                .getFirst()
                                .at("/locations/0/physicalLocation/artifactLocation/uri")
                                .asText())
                .as("repository paths use the shared URI encoding")
                .isEqualTo("doc/a%20b.md");
        assertThat(read.results().get(1).at("/properties/porelo~1forgeAccountId").asText())
                .as("a reply keeps its own author's identity")
                .isEqualTo("72");
        assertThat(read.results().get(1).at("/properties/porelo~1forgeAccountName").asText())
                .as("the login belongs to the comment's writer")
                .isEqualTo("reply-author");
        assertThat(read.results().getFirst().at("/properties/porelo~1createdAt").asText())
                .as("creation time is UTC with fractions removed")
                .isEqualTo("2026-10-30T09:14:02Z");
    }

    @Test
    void aPendingReviewIsLeftOutWithNothingHeld() {
        final PullReviewComment pending = comment(101, "Not submitted", "a.java", 10, 0);
        final ForgejoReviewRead.Native answers =
                new ForgejoReviewRead.Native(
                        List.of(
                                new ForgejoReviewRead.Reviewed(
                                        review(1, "PENDING"), List.of(pending))),
                        true);

        final ForgejoReviewRead.Read read = ForgejoReviewRead.read(HOST, answers);

        assertThat(read.results()).as("the account's unsubmitted review is not public").isEmpty();
        assertThat(read.held()).as("a pending review is not lost public data").isZero();
        assertThat(read.complete()).as("pending comments do not make a read partial").isTrue();
    }

    @Test
    void aPendingOccurrenceDoesNotHideTheSameSubmittedComment() {
        final PullReviewComment comment = comment(101, "Submitted", "a.java", 10, 0);
        final ForgejoReviewRead.Native answers =
                new ForgejoReviewRead.Native(
                        List.of(
                                new ForgejoReviewRead.Reviewed(
                                        review(1, "PENDING"), List.of(comment)),
                                new ForgejoReviewRead.Reviewed(
                                        review(2, "COMMENT"), List.of(comment))),
                        true);

        final ForgejoReviewRead.Read read = ForgejoReviewRead.read(HOST, answers);

        assertThat(read.results()).as("only submitted occurrences participate").hasSize(1);
        assertThat(read.held()).as("pending occurrences do not count as duplicates").isZero();
        assertThat(read.complete()).as("the submitted conversation was read whole").isTrue();
    }

    @Test
    void aReplyWithAZeroWidthCharacterIsHeldAndMakesTheReadPartial() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(101, "Root", "a.java", 10, 0),
                        comment(102, "Hidden\u200btext", "a.java", 10, 0),
                        comment(103, "Safe reply", "a.java", 10, 0));

        assertThat(read.held()).as("unsafe text cannot be carried").isEqualTo(1);
        assertThat(read.complete()).as("a held reply is missing public data").isFalse();
        assertThat(read.results())
                .as("the root and safe reply survive")
                .extracting(r -> r.path("guid").asText())
                .containsExactly(ProfileResult.guid(HOST, 101), ProfileResult.guid(HOST, 103));
    }

    @Test
    void aHeldRootHoldsEveryCommentOfItsThread() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(103, "Safe reply", "a.java", 10, 0),
                        comment(101, "Hidden\u200broot", "a.java", 10, 0),
                        comment(102, "Safe reply", "a.java", 10, 0),
                        comment(201, "Another thread", "a.java", 11, 0));

        assertThat(read.held())
                .as("the lowest id is the root even if it arrives later")
                .isEqualTo(3);
        assertThat(read.results())
                .as("no orphan reply names a root that was held")
                .extracting(r -> r.at("/properties/porelo~1threadId").asText())
                .containsExactly("201");
        assertThat(read.complete()).as("the held thread makes the read partial").isFalse();
    }

    @Test
    void aResolvedRootMarksEveryCommentOfItsThreadResolved() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(
                                101,
                                "Root",
                                "a.java",
                                10,
                                0,
                                user(71, "author"),
                                user(80, "resolver")),
                        comment(102, "Reply", "a.java", 10, 0));

        assertThat(read.results())
                .as("the root's resolver determines the entire thread's state")
                .extracting(r -> r.at("/properties/porelo~1threadState").asText())
                .containsExactly("resolved", "resolved");
    }

    @Test
    void aReplysResolverDoesNotResolveAnOpenRoot() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(101, "Root", "a.java", 10, 0),
                        comment(
                                102,
                                "Reply",
                                "a.java",
                                10,
                                0,
                                user(71, "author"),
                                user(80, "resolver")));

        assertThat(read.results())
                .as("only the root determines resolution")
                .extracting(r -> r.at("/properties/porelo~1threadState").asText())
                .containsExactly("open", "open");
    }

    @Test
    void aCommentSeenTwiceIsKeptOnceAndTheOtherOccurrenceIsHeld() {
        final PullReviewComment root = comment(101, "Root", "a.java", 10, 0);
        final ForgejoReviewRead.Read read =
                read(root, comment(102, "Reply", "a.java", 10, 0), root);

        assertThat(read.results()).as("one result per comment id").hasSize(2);
        assertThat(read.held()).as("the repeated occurrence signals an unstable read").isEqualTo(1);
        assertThat(read.complete()).as("duplicates prevent a complete read").isFalse();
    }

    @Test
    void commentsWithoutAnAnchorAreHeld() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(101, "No line", "a.java", 0, 0),
                        comment(102, "No positions", "a.java", null, null),
                        comment(103, "No path", null, 10, 0),
                        comment(104, "Invalid path", "../a.java", 10, 0));

        assertThat(read.held()).as("every unplaceable comment is counted").isEqualTo(4);
        assertThat(read.results()).as("none can be anchored in the profile").isEmpty();
        assertThat(read.complete())
                .as("unplaceable comments must not disappear silently")
                .isFalse();
    }

    @Test
    void aCommentNamingBothSidesIsHeld() {
        final ForgejoReviewRead.Read read = read(comment(101, "Both sides", "a.java", 10, 12));

        assertThat(read.results())
                .as("a comment on both sides has no one side the profile could name")
                .isEmpty();
        assertThat(read.held()).as("it is counted, never placed on one side").isEqualTo(1);
    }

    @Test
    void aCommentWithANegativePositionIsHeld() {
        final ForgejoReviewRead.Read read = read(comment(101, "Negative", "a.java", 10, -1));

        assertThat(read.results())
                .as("Forgejo never answers a negative position, so the comment's side is unknown")
                .isEmpty();
        assertThat(read.held()).as("it is counted, never placed on one side").isEqualTo(1);
    }

    @Test
    void aRootWithoutAUserHoldsItsReplies() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(101, "Root", "a.java", 10, 0, null, null),
                        comment(102, "Reply", "a.java", 10, 0));

        assertThat(read.held()).as("a root without a producer holds its whole thread").isEqualTo(2);
        assertThat(read.results()).as("no reply is promoted to replace a held root").isEmpty();
    }

    @Test
    void aReplyWithoutAUserIsHeldWithoutLosingItsRoot() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(101, "Root", "a.java", 10, 0),
                        comment(102, "Reply", "a.java", 10, 0, null, null));

        assertThat(read.held()).as("a deleted account cannot be named as producer").isEqualTo(1);
        assertThat(read.results()).as("the representable root remains").hasSize(1);
        assertThat(read.complete()).as("the reply was lost").isFalse();
    }

    @Test
    void theSameLineInDifferentFilesFormsDifferentThreads() {
        final ForgejoReviewRead.Read read =
                read(
                        comment(101, "First file", "a.java", 10, 0),
                        comment(102, "Second file", "b.java", 10, 0));

        assertThat(read.results())
                .as("the path is part of the thread's identity")
                .extracting(r -> r.at("/properties/porelo~1threadId").asText())
                .containsExactly("101", "102");
    }

    @Test
    void anUnfinishedNativeReadStaysPartialWithoutHoldingAnything() {
        final ForgejoReviewRead.Native whole = conversation();
        final ForgejoReviewRead.Read read =
                ForgejoReviewRead.read(HOST, new ForgejoReviewRead.Native(whole.reviews(), false));

        assertThat(read.results()).as("all comments received are retained").hasSize(4);
        assertThat(read.held()).as("incompleteness does not invent a held comment").isZero();
        assertThat(read.complete()).as("the client did not reach the end").isFalse();
    }

    @Test
    void aConversationPagePassesTheProfileTwoSchema() throws IOException {
        final ForgejoReviewRead.Read read = ForgejoReviewRead.read(HOST, conversation());
        final ReviewPage.Run run =
                new ReviewPage.Run(
                        "https://forgejo.example.org/example-org/example-repo",
                        "https://forgejo.example.org/example-org/example-repo/pulls/17",
                        "bb33e12342d6f5235c9c5acddfdb1fc6f86dedfd",
                        "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432");
        final ReviewPage.Page page =
                ReviewPage.page(
                        read.results(),
                        read.complete(),
                        null,
                        (results, completeness) -> ReviewPage.document(run, results, completeness));
        final String name = "/org/fuin/porelo/build/api/review/review-profile-2.schema.json";

        try (InputStream in = ForgejoReviewReadTest.class.getResourceAsStream(name)) {
            final var schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(Objects.requireNonNull(in, name));
            assertThat(schema.validate(page.text(), InputFormat.JSON))
                    .as("Forgejo results obey the shared profile 2 page contract")
                    .isEmpty();
        }
        assertThat(page.document().at("/runs/0/results").size())
                .as("schema validation covers both threads and their replies")
                .isEqualTo(4);
        assertThat(page.document().at("/runs/0/properties/porelo~1completeness").asText())
                .as("the page carries the converter's completeness")
                .isEqualTo("complete");
        assertThat(page.cursor()).as("four results fit one page").isNull();
    }

    private static ForgejoReviewRead.Native conversation() {
        return new ForgejoReviewRead.Native(
                List.of(
                        new ForgejoReviewRead.Reviewed(
                                review(2, "COMMENT"),
                                List.of(
                                        comment(202, "Old reply", "doc/a b.md", 0, 10),
                                        comment(
                                                102,
                                                "New reply",
                                                "doc/a b.md",
                                                10,
                                                0,
                                                user(72, "reply-author"),
                                                null))),
                        new ForgejoReviewRead.Reviewed(
                                review(1, "APPROVED"),
                                List.of(
                                        comment(201, "Old root", "doc/a b.md", 0, 10),
                                        comment(101, "New root", "doc/a b.md", 10, 0)))),
                true);
    }

    private static ForgejoReviewRead.Read read(PullReviewComment... comments) {
        return ForgejoReviewRead.read(
                HOST,
                new ForgejoReviewRead.Native(
                        List.of(
                                new ForgejoReviewRead.Reviewed(
                                        review(1, "COMMENT"), List.of(comments))),
                        true));
    }

    private static PullReview review(long id, String state) {
        return new PullReview(
                "",
                null,
                "commit",
                false,
                null,
                id,
                false,
                null,
                false,
                state,
                CREATED,
                null,
                CREATED,
                user(71, "author"));
    }

    private static PullReviewComment comment(
            long id, String body, String path, Integer position, Integer original) {
        return comment(id, body, path, position, original, user(71, "author"), null);
    }

    private static PullReviewComment comment(
            long id,
            String body,
            String path,
            Integer position,
            Integer original,
            User user,
            User resolver) {
        return new PullReviewComment(
                body, "commit", CREATED, null, 0L, null, id, "base", original, path, position, 1L,
                null, resolver, CREATED, user);
    }

    private static User user(long id, String login) {
        return new User(
                true, null, CREATED, null, null, null, null, null, null, id, false, null, null,
                null, login, null, false, null, false, null, null, null, null, null);
    }
}
