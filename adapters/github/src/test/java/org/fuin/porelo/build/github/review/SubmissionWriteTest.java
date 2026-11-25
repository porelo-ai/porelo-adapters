package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fuin.porelo.forge.github.model.PullsCreateReviewCommentRequest;
import org.junit.jupiter.api.Test;

class SubmissionWriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COMMIT = "bb33e12342d6f5235c9c5acddfdb1fc6f86dedfd";

    @Test
    void aNewCommentBecomesOneLineCommentOnThePinnedCommit() {
        final SubmissionWrite.Call call = SubmissionWrite.render(comment(), COMMIT);

        assertThat(call.replyTo()).as("a new comment answers no thread").isNull();
        final PullsCreateReviewCommentRequest request = call.comment();
        assertThat(request).isNotNull();
        assertThat(request.commitId()).as("made on the pinned commit").isEqualTo(COMMIT);
        assertThat(request.path()).as("the URI decoded once").isEqualTo("doc/a b.md");
        assertThat(request.startLine()).isEqualTo(10);
        assertThat(request.line()).isEqualTo(12);
        assertThat(request.side()).isEqualTo(PullsCreateReviewCommentRequest.SideEnum.RIGHT);
    }

    @Test
    void aReplyBecomesOneReplyToTheNamedThread() {
        final ObjectNode reply = comment();
        reply.remove("locations");
        ((ObjectNode) reply.get("properties")).put("porelo/replyTo", "4431405345");

        final SubmissionWrite.Call call = SubmissionWrite.render(reply, COMMIT);

        assertThat(call.replyTo()).isEqualTo(4431405345L);
        assertThat(call.comment()).as("a reply makes no new comment").isNull();
    }

    @Test
    void theProfilesEscapesAreUndoneForGitHub() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "See \\[x\\] in {{a}} \\\\ b.");

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .as("brackets, backslash and braces as the person wrote them")
                .isEqualTo("See [x] in {a} \\ b.");
    }

    @Test
    void aMentionNotifiesNoOne() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "Ask @octocat.");

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .as("a mention in a code span notifies nobody")
                .isEqualTo("Ask `@octocat`.");
    }

    @Test
    void aSuggestionFenceInTheTextIsRefusedInWords() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "```suggestion\nx\n```");

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("suggestion fence");
    }

    @Test
    void aFixBecomesASuggestionFenceLongerThanItsBackticks() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "x = ```y```;"));

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .isEqualTo("Use the constant.\n\n````suggestion\nx = ```y```;\n````");
    }

    @Test
    void aFixOfOtherLinesIsRefused() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 11, 12, "x"));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .extracting(e -> ((ReviewRefusal) e).reason())
                .isEqualTo(ReviewRefusal.Reason.FIX_OUTSIDE_ANCHOR);
    }

    @Test
    void aCommentWithoutLinesIsNotInTheProfile() {
        final ObjectNode general = comment();
        general.remove("locations");

        assertThatThrownBy(() -> SubmissionWrite.render(general, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .extracting(e -> ((ReviewRefusal) e).reason())
                .as("a general comment is not one profile 2 writes")
                .isEqualTo(ReviewRefusal.Reason.NOT_SUPPORTED);
    }

    @Test
    void aReplyToSomethingThatIsNoThreadIdIsRefused() {
        final ObjectNode reply = comment();
        ((ObjectNode) reply.get("properties")).put("porelo/replyTo", "PRRT_kwDO");

        assertThatThrownBy(() -> SubmissionWrite.render(reply, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("porelo/replyTo");
    }

    @Test
    void aLoneBackslashIsRefused() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "a \\n b");

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("backslash");
    }

    @Test
    void aThreadIdPastTheLongRangeIsRefusedInWords() {
        final ObjectNode reply = comment();
        ((ObjectNode) reply.get("properties")).put("porelo/replyTo", "9999999999999999999");

        assertThatThrownBy(() -> SubmissionWrite.render(reply, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("porelo/replyTo");
    }

    @Test
    void aFixDeletingTheLinesIsAnEmptySuggestion() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 10, 12, ""));

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .as("GitHub deletes the lines of an empty suggestion")
                .endsWith("```suggestion\n```");
    }

    @Test
    void aFixsLastLineFeedEndsItsLastLine() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "a\nb\n"));

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .as("no extra empty line")
                .endsWith("```suggestion\na\nb\n```");
    }

    @Test
    void aTextLeavingAFenceOpenBeforeAFixIsRefused() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "See\n```java\nx");
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "y"));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("fence open");
    }

    @Test
    void aFixOnTheOldSideIsRefused() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("properties")).put("porelo/diffSide", "old");
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "y"));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .extracting(e -> ((ReviewRefusal) e).reason())
                .isEqualTo(ReviewRefusal.Reason.OLD_SIDE_FIX);
    }

    @Test
    void aLineOrParagraphSeparatorInTheTextIsRefused() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "a\u2029b");

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .isInstanceOf(ReviewRefusal.class)
                .extracting(e -> ((ReviewRefusal) e).reason())
                .isEqualTo(ReviewRefusal.Reason.UNSAFE_TEXT);
    }

    @Test
    void theTextIsSentAsTheMarkdownSourceItWasReadAs() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message"))
                .put("text", "See \\[docs\\](https://example.org) and `x`.");

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .as("what a person released goes as written, a link included")
                .isEqualTo("See [docs](https://example.org) and `x`.");
    }

    @Test
    void aRegionEndingBeforeItStartsIsRefused() {
        final ObjectNode result = comment();
        ((ObjectNode) result.at("/locations/0/physicalLocation/region"))
                .put("startLine", 12)
                .put("endLine", 10);
        result.set("fixes", fix("doc/a%20b.md", 12, 10, "x"));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .as("it would become a comment and a suggestion on one line")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("ends before it starts");
    }

    @Test
    void aRegionOverOneHundredLinesIsRefused() {
        final ObjectNode result = comment();
        ((ObjectNode) result.at("/locations/0/physicalLocation/region"))
                .put("startLine", 1)
                .put("endLine", 101);

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .as("profile 2 carries no span the read side would hold out")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("over 100 lines");
    }

    @Test
    void aTextAndFixOverGitHubsLimitAreRefusedBeforeAnyCall() {
        final ObjectNode result = comment();
        ((ObjectNode) result.get("message")).put("text", "a".repeat(63_000));
        result.set("fixes", fix("doc/a%20b.md", 10, 12, ("b".repeat(400) + "\n").repeat(10)));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .as("GitHub would refuse the body with 422")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("text and fix over");
    }

    @Test
    void aFixOfMoreThanTenExtraLinesIsRefused() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "x\n".repeat(14)));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .as("three anchored lines take at most thirteen")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("lines");
    }

    @Test
    void aFixLineOverFourHundredCharactersIsRefused() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "\uD83D\uDE00".repeat(401) + "\n"));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .as("the bound counts Unicode scalar values, not UTF-16 units")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("400");
    }

    @Test
    void aFixOverEightKibibytesIsRefused() {
        final ObjectNode result = comment();
        result.set("fixes", fix("doc/a%20b.md", 10, 12, ("é".repeat(380) + "\n").repeat(11)));

        assertThatThrownBy(() -> SubmissionWrite.render(result, COMMIT))
                .as("eleven lines of 380 two-byte characters are over 8192 bytes")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("8192");
    }

    @Test
    void aFixAtEveryBoundIsWritten() {
        final ObjectNode result = comment();
        final String line = "\uD83D\uDE00".repeat(400) + "\n";
        result.set("fixes", fix("doc/a%20b.md", 10, 12, "a\n".repeat(12) + "b".repeat(400) + "\n"));
        final ObjectNode wide = comment();
        wide.set("fixes", fix("doc/a%20b.md", 10, 12, line + ("é".repeat(300) + "\n").repeat(9)));

        assertThat(SubmissionWrite.render(result, COMMIT).body())
                .as("thirteen lines for three, the last 400 long")
                .contains("b".repeat(400));
        assertThat(SubmissionWrite.render(wide, COMMIT).body())
                .as("ten lines, the first 400 scalar values of four bytes, under 8192 bytes")
                .contains(line);
    }

    @Test
    void aFixOfExactlyEightKibibytesIsWrittenAndOneByteMoreIsNot() {
        final String tenWide = ("é".repeat(400) + "\n").repeat(10);
        final ObjectNode at = comment();
        at.set("fixes", fix("doc/a%20b.md", 10, 12, tenWide + "a".repeat(181) + "\n"));
        final ObjectNode past = comment();
        past.set("fixes", fix("doc/a%20b.md", 10, 12, tenWide + "a".repeat(182) + "\n"));

        assertThat(SubmissionWrite.render(at, COMMIT).body())
                .as("8192 bytes of UTF-8, its last line feed counted")
                .contains("a".repeat(181));
        assertThatThrownBy(() -> SubmissionWrite.render(past, COMMIT))
                .as("8193 bytes")
                .isInstanceOf(ReviewRefusal.class)
                .hasMessageContaining("8192");
    }

    private static ObjectNode comment() {
        final ObjectNode result = JSON.createObjectNode();
        result.put("kind", "review").put("level", "none");
        result.putObject("message").put("text", "Use the constant.");
        final ObjectNode physical =
                result.putArray("locations").addObject().putObject("physicalLocation");
        physical.putObject("artifactLocation").put("uri", "doc/a%20b.md");
        physical.putObject("region").put("startLine", 10).put("endLine", 12);
        result.putObject("properties")
                .put("porelo/anchorKind", "line")
                .put("porelo/diffSide", "new");
        return result;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode fix(
            String uri, int start, int end, String text) {
        final var fixes = JSON.createArrayNode();
        final ObjectNode change = fixes.addObject().putArray("artifactChanges").addObject();
        change.putObject("artifactLocation").put("uri", uri);
        final ObjectNode replacement = change.putArray("replacements").addObject();
        replacement.putObject("deletedRegion").put("startLine", start).put("endLine", end);
        replacement.putObject("insertedContent").put("text", text);
        return fixes;
    }
}
