package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.PullRequestReview;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.junit.jupiter.api.Test;

class GitHubReviewReadTest {

    /** The head the fixtures' review is bound to. */
    private static final String HEAD = "bb33e12342d6f5235c9c5acddfdb1fc6f86dedfd";

    private static GitHubReviewRead.Read read(
            List<PullRequestReview> reviews,
            List<PullRequestReviewComment> comments,
            List<IssueComment> issueComments,
            List<GitHubReviewRead.Thread> threads) {
        return GitHubReviewRead.read(
                "github.com",
                new GitHubReviewRead.Native(reviews, comments, issueComments, threads, true, HEAD));
    }

    private static GitHubReviewRead.Read oneComment(
            java.util.function.Consumer<ObjectNode> comment,
            java.util.function.Consumer<ObjectNode> thread) {
        return read(
                List.of(),
                List.of(GitHubAnswers.comment(comment)),
                List.of(),
                List.of(GitHubAnswers.thread(thread)));
    }

    // Harm: a comment shown on the wrong side or line of the diff misleads the reviewer.
    @Test
    void aCommentWhoseSideDiffersFromItsThreadIsHeldNamingTheSide() {
        final GitHubReviewRead.Read read = oneComment(c -> c.put("side", "LEFT"), t -> {});

        assertThat(read.results()).as("the comment is left out").isEmpty();
        assertThat(read.held())
                .as("it is held, the reason naming the side")
                .singleElement()
                .satisfies(h -> assertThat(h.reason()).contains("side"));
        assertThat(read.completeness())
                .as("a read with a held item is not complete")
                .isEqualTo("partial");
    }

    @Test
    void aCommentWhoseLineDiffersFromItsThreadIsHeldNamingTheLine() {
        final GitHubReviewRead.Read read = oneComment(c -> c.put("line", 13), t -> {});

        assertThat(read.held())
                .as("it is held, the reason naming the line")
                .singleElement()
                .satisfies(h -> assertThat(h.reason()).contains("line"));
    }

    @Test
    void aCommentNoThreadNamesIsHeld() {
        final GitHubReviewRead.Read read =
                read(List.of(), List.of(GitHubAnswers.comment(c -> {})), List.of(), List.of());

        assertThat(read.held()).as("a comment without its thread state is held").hasSize(1);
    }

    @Test
    void aLineCommentKeepsItsPlaceSideThreadAndAuthor() {
        final GitHubReviewRead.Read read = oneComment(c -> {}, t -> {});

        final ObjectNode result = read.results().get(0);
        assertThat(result.at("/locations/0/physicalLocation/artifactLocation/uri").asText())
                .as("the path")
                .isEqualTo("src/main/java/Gate.java");
        assertThat(result.at("/locations/0/physicalLocation/region/startLine").asInt())
                .as("the first line")
                .isEqualTo(12);
        assertThat(result.at("/properties/porelo~1diffSide").asText())
                .as("RIGHT is new")
                .isEqualTo("new");
        assertThat(result.at("/properties/porelo~1nativeThreadId").asText())
                .as("the thread's node ID")
                .isEqualTo("PRRT_kwDOVc0D886yQCnS");
        assertThat(result.at("/properties/porelo~1authorId").asText())
                .as("the stable account ID, never the login")
                .isEqualTo("583231");
        assertThat(result.at("/properties/porelo~1threadState").asText())
                .as("state")
                .isEqualTo("open");
        assertThat(result.has("guid")).as("guid is the daemon's").isFalse();
        assertThat(result.at("/properties/porelo~1sourceLevel").isMissingNode())
                .as("the level is the daemon's")
                .isTrue();
    }

    // Harm: a bidi control in a forge comment reaches a task or a person disguised.
    @Test
    void aBodyWithABidiControlIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("body", "Looks fine ‮;) approved"), t -> {});

        assertThat(read.results()).as("nothing of it is delivered").isEmpty();
        assertThat(read.held().get(0).reason())
                .as("the reason names the character")
                .contains("U+202E");
    }

    @Test
    void anEmojisVariationSelectorInProseIsDroppedAndNamed() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(GitHubAnswers.issueComment(c -> c.put("body", "Nice ❤️ work"))),
                        List.of());

        assertThat(read.results().get(0).at("/message/text").asText())
                .as("the selector after the emoji is gone")
                .isEqualTo("Nice ❤ work");
        assertThat(read.losses()).as("and named").containsEntry("variation-selector-dropped", 1);
    }

    @Test
    void aVariationSelectorInCodeStillHoldsTheItem() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(GitHubAnswers.issueComment(c -> c.put("body", "`❤️`"))),
                        List.of());

        assertThat(read.held()).as("only prose drops it").hasSize(1);
    }

    @Test
    void aSuggestionOnTheNewSideBecomesOneFixOfItsLines() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c -> c.put("body", "```suggestion\n    return Gate.TIMEOUT;\n```\nEdited"),
                        t -> {});

        final ObjectNode result = read.results().get(0);
        assertThat(
                        result.at("/fixes/0/artifactChanges/0/replacements/0/insertedContent/text")
                                .asText())
                .as("the fence's lines, no trailing newline")
                .isEqualTo("    return Gate.TIMEOUT;");
        assertThat(
                        result.at(
                                        "/fixes/0/artifactChanges/0/replacements/0/deletedRegion/startLine")
                                .asInt())
                .as("the anchor's lines")
                .isEqualTo(12);
        assertThat(result.at("/message/text").asText())
                .as("the text without the fence")
                .isEqualTo("Edited");
    }

    @Test
    void aSuggestionOnTheOldSideStaysText() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c -> c.put("side", "LEFT").put("body", "```suggestion\nx\n```"),
                        t -> t.put("diffSide", "LEFT"));

        final ObjectNode result = read.results().get(0);
        assertThat(result.has("fixes")).as("an old-side fence is not actionable").isFalse();
        assertThat(result.at("/message/text").asText()).as("it stays text").contains("suggestion");
    }

    @Test
    void anOutdatedCommentWithoutACurrentLineHasNoLocationAndItsOriginalAnchor() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c -> c.putNull("line").put("original_line", 9),
                        t -> t.put("isOutdated", true).putNull("line"));

        final ObjectNode result = read.results().get(0);
        assertThat(result.has("locations")).as("nothing is invented at the head").isFalse();
        assertThat(result.at("/properties/porelo~1outdated").asBoolean()).as("outdated").isTrue();
        assertThat(result.at("/properties/porelo~1originalEndLine").asText())
                .as("the original line")
                .isEqualTo("9");
    }

    @Test
    void aDeletedAccountIsUnknownNeverALogin() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(GitHubAnswers.issueComment(c -> c.putNull("user"))),
                        List.of());

        assertThat(read.results().get(0).at("/properties/porelo~1authorId").asText())
                .as("a deleted account")
                .isEqualTo("unknown");
    }

    @Test
    void aLaterCommentReviewDoesNotReplaceAnApproval() {
        final PullRequestReview approved = GitHubAnswers.review(r -> r.put("state", "APPROVED"));
        final PullRequestReview commented = GitHubAnswers.review(r -> r.put("id", 5722360486L));

        assertThat(read(List.of(approved, commented), List.of(), List.of(), List.of()).verdict())
                .as("GitHub makes every later inline comment a COMMENTED review")
                .isEqualTo("approved");
    }

    @Test
    void aReviewWithoutABodySaysItsStateAndIsMarkedBodyless() {
        final ObjectNode result =
                read(List.of(GitHubAnswers.review(r -> {})), List.of(), List.of(), List.of())
                        .results()
                        .get(0);

        assertThat(result.at("/properties/porelo~1bodyless").asBoolean()).as("bodyless").isTrue();
        assertThat(result.at("/message/text").asText()).as("a fixed text").isEqualTo("Commented.");
    }

    @Test
    void aViewerLinkOnAnotherHostIsDropped() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(
                                GitHubAnswers.issueComment(
                                        c -> c.put("html_url", "https://example.org/x"))),
                        List.of());

        assertThat(read.results().get(0).has("hostedViewerUri"))
                .as("not the forge's link")
                .isFalse();
        assertThat(read.losses()).as("named").containsKey("link-dropped");
    }

    @Test
    void bracesAreDoubledInTheMessage() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(GitHubAnswers.issueComment(c -> c.put("body", "use {x}"))),
                        List.of());

        assertThat(read.results().get(0).at("/message/text").asText())
                .as("a literal brace is doubled")
                .isEqualTo("use {{x}}");
    }

    // Harm: an example quoted in a longer fence becomes a fix someone may apply.
    @Test
    void aSuggestionQuotedInALongerFenceIsNoFix() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c ->
                                c.put(
                                        "body",
                                        "Example:\n````md\n```suggestion\nSystem.exit(1);\n```\n````"),
                        t -> {});

        assertThat(read.results().get(0).has("fixes"))
                .as("GitHub shows the quoted fence as code, not as a suggestion")
                .isFalse();
    }

    // Harm: a suggestion that deletes its lines would be applied as one blank line instead.
    @Test
    void aSuggestionThatDeletesItsLinesIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("body", "```suggestion\n```"), t -> {});

        assertThat(read.results()).as("nothing the profile cannot say is delivered").isEmpty();
        assertThat(read.held()).as("the comment is held").hasSize(1);
    }

    @Test
    void aSuggestionOfOneEmptyLineIsAnEmptyReplacement() {
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("body", "```suggestion\n\n```"), t -> {});

        assertThat(
                        read.results()
                                .get(0)
                                .at("/fixes/0/artifactChanges/0/replacements/0/insertedContent")
                                .path("text")
                                .asText("absent"))
                .as("one empty line replaces the anchor")
                .isEqualTo("");
    }

    // Harm: an oversized suggestion becomes a fix no write may carry back, or floods a reader.
    @Test
    void aSuggestionOverItsLimitsIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c -> c.put("body", "```suggestion\n" + "x\n".repeat(50) + "```"), t -> {});

        assertThat(read.results()).as("over the anchor plus ten lines").isEmpty();
        assertThat(read.held()).as("the comment is held").hasSize(1);
    }

    // Harm: a message over the profile's limit is refused by every reader of the document.
    @Test
    void aBodyOverTheLimitOnceItsBracesAreDoubledIsHeld() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(GitHubAnswers.issueComment(c -> c.put("body", "{".repeat(5000)))),
                        List.of());

        assertThat(read.held()).as("10000 bytes once doubled").hasSize(1);
    }

    // Harm: markup rebuilt from its pieces reaches a reader that renders it.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"<<script>script>alert(1)<</script>/script>", "see <HTTPS://example.org/x>"})
    void markupRebuiltFromItsPiecesIsDroppedToo(String body) {
        final String text = ReviewText.fromForge(body).text();

        assertThat(text)
                .as("no tag and no autolink is left")
                .doesNotContain("<")
                .doesNotContain(">");
    }

    // Harm: one URI the profile refuses makes a reader refuse the whole page.
    @Test
    void aCommentOnAPathWithAPercentIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("path", "src/100%.txt"), t -> t.put("path", "src/100%.txt"));

        assertThat(read.results()).as("no URI the profile refuses is written").isEmpty();
        assertThat(read.held()).as("the comment is held").hasSize(1);
    }

    // Harm: an approval of an older commit reads as approval of a head nobody reviewed.
    @Test
    void anApprovalOfAnotherCommitCountsNothingForTheHead() {
        final PullRequestReview approved =
                GitHubAnswers.review(
                        r -> r.put("state", "APPROVED").put("commit_id", "1".repeat(40)));

        assertThat(read(List.of(approved), List.of(), List.of(), List.of()).verdict())
                .as("no review is bound to the head")
                .isNull();
    }

    // Harm: two deleted accounts merged into one let an approval hide a change request.
    @Test
    void twoDeletedAccountsAreTwoReviewers() {
        final PullRequestReview changes =
                GitHubAnswers.review(r -> r.putNull("user").put("state", "CHANGES_REQUESTED"));
        final PullRequestReview approved =
                GitHubAnswers.review(
                        r -> r.putNull("user").put("state", "APPROVED").put("id", 5722360486L));

        assertThat(read(List.of(changes, approved), List.of(), List.of(), List.of()).verdict())
                .as("the change request stands")
                .isEqualTo("changesRequested");
    }
}
