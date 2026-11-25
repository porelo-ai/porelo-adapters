package org.fuin.porelo.standin.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.fuin.porelo.build.github.review.RepositoryPath;
import org.fuin.porelo.build.github.review.ReviewText;
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

    // Harm: one review GitHub sends with a null body would stop the whole pull request's read.
    @Test
    void aReviewWhoseBodyIsNullIsReadAsBodyless() {
        final ObjectNode result =
                read(
                                List.of(GitHubAnswers.review(r -> r.putNull("body"))),
                                List.of(),
                                List.of(),
                                List.of())
                        .results()
                        .get(0);

        assertThat(result.at("/properties/porelo~1bodyless").asBoolean())
                .as("a null body is no body")
                .isTrue();
        assertThat(result.at("/message/text").asText())
                .as("the fixed text of its state")
                .isEqualTo("Commented.");
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

    // Harm: a suggestion GitHub took is lost from the review, and the read is never complete.
    @Test
    void aLongSuggestionReplacingOneLineIsReadAsItsFix() {
        final String inserted = ("y".repeat(1000) + "\n").repeat(49) + "y".repeat(1000);
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("body", "```suggestion\n" + inserted + "\n```"), t -> {});

        assertThat(read.held()).as("nothing is held").isEmpty();
        assertThat(
                        read.results()
                                .get(0)
                                .at("/fixes/0/artifactChanges/0/replacements/0/insertedContent")
                                .path("text")
                                .asText())
                .as("fifty lines of a thousand characters replace one line")
                .isEqualTo(inserted);
    }

    // Harm: a comment GitHub took is lost from the review, and the read is never complete.
    @Test
    void aBodyOfGitHubsMostCharactersIsReadThoughItsBracesDouble() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(GitHubAnswers.issueComment(c -> c.put("body", "{".repeat(65_536)))),
                        List.of());

        assertThat(read.held()).as("GitHub's largest body is read").isEmpty();
        assertThat(read.results().get(0).at("/message/text").asText())
                .as("the message holds every brace doubled")
                .hasSize(131_072);
    }

    // Harm: a comment GitHub took is lost when its characters are counted as UTF-16 units.
    @Test
    void aBodyOfGitHubsMostCharactersOutsideTheBasicPlaneIsRead() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(
                                GitHubAnswers.issueComment(
                                        c -> c.put("body", "\uD83D\uDE00".repeat(65_536)))),
                        List.of());

        assertThat(read.held()).as("65,536 characters, 131,072 UTF-16 units, are read").isEmpty();
    }

    // Harm: one hostile comment GitHub holds crashes the read of the whole pull request.
    // The bound catches a hang, not the parser's cost: 65,536 "<" take seconds on a loaded host.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {">", "> ", "[", "- ", "1. ", "*", "`", "<", "](", "  "})
    void aDeeplyNestedBodyOfGitHubsMostCharactersIsReadOrHeldInTime(String unit) {
        final String body = unit.repeat(65_536 / unit.length());

        final GitHubReviewRead.Read read =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(60),
                        () ->
                                read(
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                GitHubAnswers.issueComment(
                                                        c -> c.put("body", body))),
                                        List.of()));

        assertThat(read.results().size() + read.held().size())
                .as("the comment is read or held, never lost")
                .isEqualTo(1);
    }

    // Harm: a commenter makes every read of a pull request cost minutes, since commonmark's parse
    // of many "<" is quadratic; a read must parse such a body once, not once per check.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "```suggestion\nx\n```\n"})
    void aCommentCostlyToParseIsReadAtTheCostOfOneParse(String fence) {
        final String body = fence + "<".repeat(32_768);
        final org.commonmark.parser.Parser parser =
                org.commonmark.parser.Parser.builder()
                        .extensions(
                                List.of(
                                        org.commonmark.ext.gfm.tables.TablesExtension.create(),
                                        org.commonmark.ext.footnotes.FootnotesExtension.create()))
                        .includeSourceSpans(
                                org.commonmark.parser.IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build();
        long parse = Long.MAX_VALUE;
        long read = Long.MAX_VALUE;
        // The least of three runs each, so a loaded host slows both alike.
        for (int run = 0; run < 3; run++) {
            final long start = System.nanoTime();
            parser.parse(body);
            final long parsed = System.nanoTime();
            final GitHubReviewRead.Read answer = oneComment(c -> c.put("body", body), t -> {});
            parse = Math.min(parse, parsed - start);
            read = Math.min(read, System.nanoTime() - parsed);
            assertThat(answer.results()).as("the comment is read").hasSize(1);
        }

        assertThat((double) read / parse)
                .as("a read costs about one parse of the body, not two or more")
                .isLessThan(1.5);
    }

    // Harm: a fence the cheap block-only search finds otherwise than the full parse makes a reader
    // apply code as a suggestion that GitHub shows as text, or miss the suggestion it shows.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "[a]: https://example.org\n```suggestion\nx\n```\n",
                "[^1]: note\n    ```suggestion\nx\n```\n",
                "[^1]: note\n```suggestion\nx\n```\n",
                "[^1]: ```suggestion\n    x\n    ```\n",
                "[^1]: note\n\n    ```suggestion\n    x\n    ```\n",
                "| a | b |\n|---|---|\n| 1 | 2 |\n```suggestion\nx\n```\n",
                "> ```suggestion\nlazy\n```\n",
                "    ```suggestion\nx\n    ```\n",
                "   ```suggestion\nx\n   ```\n",
                "<pre>\n```suggestion\nx\n```\n</pre>\n",
                "```suggestion\nunclosed\n",
                "~~~suggestion\nx\n~~~\nprose <b>x</b> [l](u)\n```\ny\n```\n",
                "- item\n  ```suggestion\n  x\n  ```\n",
                "para\n```suggestion\nx\n```\n````\n```\n````\n"
            })
    void aFenceIsFoundWhereTheFullParseFindsIt(String body) {
        final org.commonmark.node.Node root =
                org.commonmark.parser.Parser.builder()
                        .extensions(
                                List.of(
                                        org.commonmark.ext.gfm.tables.TablesExtension.create(),
                                        org.commonmark.ext.footnotes.FootnotesExtension.create()))
                        .includeSourceSpans(
                                org.commonmark.parser.IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parse(body);
        final List<List<Integer>> expected = new java.util.ArrayList<>();
        for (org.commonmark.node.Node child = root.getFirstChild();
                child != null;
                child = child.getNext()) {
            if (child instanceof org.commonmark.node.FencedCodeBlock) {
                final var spans = child.getSourceSpans();
                final var last = spans.get(spans.size() - 1);
                expected.add(
                        List.of(
                                spans.get(0).getInputIndex(),
                                last.getInputIndex() + last.getLength()));
            }
        }

        assertThat(ReviewText.fences(body).stream().map(f -> List.of(f[0], f[1])).toList())
                .as("the same top-level fences at the same places as the full parse")
                .isEqualTo(expected);
    }

    // Harm: a body larger than GitHub hands out floods every reader of the document.
    @Test
    void aBodyOverGitHubsMostCharactersIsHeld() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(),
                        List.of(),
                        List.of(
                                GitHubAnswers.issueComment(
                                        c -> c.put("body", "\u00e9".repeat(65_537)))),
                        List.of());

        assertThat(read.held()).as("one character over is held").hasSize(1);
    }

    // Harm: a comment on a file git and GitHub hold is lost from the review.
    @Test
    void aCommentOnAPathOfTwentyThousandBytesIsRead() {
        final String path = ("d".repeat(199) + "/").repeat(100) + "Gate.java";
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("path", path), t -> t.put("path", path));

        assertThat(read.held()).as("nothing is held").isEmpty();
        assertThat(
                        RepositoryPath.path(
                                read.results()
                                        .get(0)
                                        .at("/locations/0/physicalLocation/artifactLocation/uri")
                                        .asText()))
                .as("the whole path")
                .isEqualTo(path);
    }

    // Harm: a comment GitHub took on a long block is lost from the review.
    @Test
    void aCommentOnAHundredAndFiftyLinesIsRead() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c ->
                                c.put("start_line", 1)
                                        .put("original_start_line", 1)
                                        .put("start_side", "RIGHT")
                                        .put("line", 150)
                                        .put("original_line", 150),
                        t -> t.put("line", 150).put("startDiffSide", "RIGHT"));

        assertThat(read.held()).as("nothing is held").isEmpty();
        assertThat(read.results().get(0).at("/locations/0/physicalLocation/region/endLine").asInt())
                .as("the anchor ends on line 150")
                .isEqualTo(150);
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

    // Harm: a draft nobody submitted reads as a published comment.
    @Test
    void aCommentOfAPendingReviewIsADraft() {
        final GitHubReviewRead.Read read =
                read(
                        List.of(GitHubAnswers.review(r -> r.put("state", "PENDING"))),
                        List.of(GitHubAnswers.comment(c -> {})),
                        List.of(),
                        List.of(GitHubAnswers.thread(t -> {})));

        assertThat(
                        read.results().stream()
                                .filter(
                                        r ->
                                                r.at("/properties/porelo~1nativeCommentId")
                                                        .isTextual())
                                .findFirst()
                                .orElseThrow()
                                .at("/properties/porelo~1draft")
                                .asBoolean())
                .as("its review is pending")
                .isTrue();
    }

    // Harm: a fence GitHub does not close leaves a quoted suggestion read as a fix.
    @Test
    void aClosingFenceWithOtherThanSpacesIsNoClosingFence() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c -> c.put("body", "```\n```\u3000\n```suggestion\nSystem.exit(1);\n```"),
                        t -> {});

        assertThat(read.results().get(0).has("fixes"))
                .as("the suggestion is quoted inside the first fence")
                .isFalse();
    }

    // Harm: a suggestion GitHub does not close is applied as if it were.
    @Test
    void aSuggestionClosedOnlyByAnIndentedLineIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(c -> c.put("body", "```suggestion\nfoo\n    ```"), t -> {});

        assertThat(read.results()).as("four spaces make no closing fence").isEmpty();
        assertThat(read.held()).as("held, not applied").hasSize(1);
    }

    // Harm: a suggestion GitHub hides inside an HTML comment becomes a fix that can be applied.
    @Test
    void aSuggestionInsideAnHtmlCommentIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c ->
                                c.put(
                                        "body",
                                        "Looks good!\n<!--\n```suggestion\nSystem.exit(1);\n```\n-->"),
                        t -> {});

        assertThat(read.results())
                .as("no fix comes out of hidden text, and the text GitHub hides is dropped")
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.has("fixes")).as("no fix").isFalse();
                            assertThat(r.at("/message/text").asText())
                                    .as("only what GitHub shows")
                                    .isEqualTo("Looks good!\n");
                        });
    }

    // Harm: a suggestion with an image before it is held, so the reviewer's fix is lost.
    @Test
    void aSuggestionAfterAnImageIsAFix() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c ->
                                c.put(
                                        "body",
                                        "![](https://github.com/user-attachments/assets/1)\n```suggestion\nreturn 1;\n```\nSee screenshot."),
                        t -> {});

        assertThat(read.results())
                .as("the suggestion is read as a fix")
                .singleElement()
                .satisfies(r -> assertThat(r.has("fixes")).as("it has a fix").isTrue());
    }

    // Harm: a suggestion inside an HTML block GitHub shows as text becomes a fix.
    @Test
    void aSuggestionInsideAnHtmlBlockIsHeld() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c ->
                                c.put(
                                        "body",
                                        "<div>\nPlease:\n```suggestion\nSystem.exit(1);\n```\n</div>\n\nThanks."),
                        t -> {});

        assertThat(read.results()).as("no fix comes out of an HTML block").isEmpty();
        assertThat(read.held())
                .as("the comment is held, its text being GitHub's to show")
                .singleElement()
                .satisfies(h -> assertThat(h.reason()).as("why").contains("HTML block"));
    }

    // Harm: a comment naming a generic type in prose is held though GitHub shows it as text.
    @Test
    void aLessThanSignThatOpensNoMarkupIsText() {
        assertThat(ReviewText.fromForge("Prefer Map<String, Integer> here").text())
                .as("carried as it is")
                .isEqualTo("Prefer Map<String, Integer> here");
    }

    /** A body GitHub renders, and what a read makes of it; {@code null} when it is held. */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> markup() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "see <javascript:alert(1)>", "see javascript:alert(1)"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "see <HTTPS://example.org/x>", "see HTTPS://example.org/x"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "contact <user@example.org>", "contact user@example.org"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<<script>script>alert(1)<</script>/script>", null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "see <img title=\"<\" src=x onerror=alert(1)>", "see "),
                org.junit.jupiter.params.provider.Arguments.of(
                        "- item\n  ```\n<script>alert(1)</script>", null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "Looks good!\n<!-- a >\nHidden text GitHub never renders", "Looks good!\n"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<!-- a > ` --> <script>alert(1)</script> &#x202E; <!-- ` -->", null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<!--\n~~~\n-->\n<script>alert(1)</script>", null),
                org.junit.jupiter.params.provider.Arguments.of("a &#x202E; b", null),
                org.junit.jupiter.params.provider.Arguments.of("see <!DOCTYPE html>", "see "),
                org.junit.jupiter.params.provider.Arguments.of("see <?php x ?>", "see "),
                org.junit.jupiter.params.provider.Arguments.of("see <![CDATA[a < b]]>", "see "),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<a>    <<script>script>alert(1)<</script>/script>", null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "Looks good!\n<!<a>--\nDo not merge: auth flaw!", null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<details>\n<summary>Security flaw</summary>\nDo not merge.\n</details>\n\nReviewed.",
                        null),
                org.junit.jupiter.params.provider.Arguments.of("Nice \u2764&#xFE0F; work", null),
                org.junit.jupiter.params.provider.Arguments.of("```java&#x202E;\nx\n```", null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "[\nclick here](https://example.org)", "click here"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "[click here\n](https://example.org)", "click here"),
                org.junit.jupiter.params.provider.Arguments.of("<!-- a note -->\nShown.", "Shown."),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<!-- hidden -->Do not merge: auth flaw!<!-- hidden -->\n\nReviewed.",
                        null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "<!-- hidden --!>Do not merge: auth flaw!<!-- hidden -->\n\nReviewed.",
                        null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "See " + "<".repeat(1000) + "a>".repeat(1000), null));
    }

    // Harm: a body whose dropped tags keep forming new ones takes a parse per tag, for minutes.
    @Test
    void aBodyThatRebuildsItsTagsIsHeldAtOnce() {
        final String body = "<".repeat(21845) + "a>".repeat(21845);

        final ReviewText.Read read =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(10), () -> ReviewText.fromForge(body));

        assertThat(read.isHeld()).as("held, read once").isTrue();
    }

    // Harm: a footnote GitHub renders hides HTML in what is read as code.
    @Test
    void htmlInAFootnoteIsDropped() {
        assertThat(
                        ReviewText.fromForge(
                                        "See[^1]\n\n[^1]: note\n\n    <img src=x onerror=alert(1)> more")
                                .text())
                .as("the footnote is text, its HTML dropped")
                .doesNotContain("<img");
    }

    // Harm: markup a reader renders survives as text, markup rebuilt from its pieces does, text
    // GitHub hides is shown, or a character reference stands for a bidi override.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("markup")
    void markupIsDroppedAsGitHubRendersItOrTheBodyIsHeld(String body, String expected) {
        assertThat(ReviewText.fromForge(body).text())
                .as("what GitHub shows of it, without its markup, or held")
                .isEqualTo(expected);
    }

    // Harm: a suggestion with only an image beside it is held, so the reviewer's fix is lost.
    @Test
    void aSuggestionWithOnlyAnImageBesideItIsAFix() {
        final GitHubReviewRead.Read read =
                oneComment(
                        c ->
                                c.put(
                                        "body",
                                        "![](https://github.com/user-attachments/assets/1)\n```suggestion\nreturn 1;\n```"),
                        t -> {});

        assertThat(read.results())
                .as("the suggestion is read as a fix, its text saying what it is")
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.has("fixes")).as("it has a fix").isTrue();
                            assertThat(r.at("/message/text").asText())
                                    .as("the words a suggestion alone gets")
                                    .isEqualTo("Suggested change.");
                        });
        assertThat(read.losses()).as("the image's loss is named").containsKey("link-dropped");
    }
}
