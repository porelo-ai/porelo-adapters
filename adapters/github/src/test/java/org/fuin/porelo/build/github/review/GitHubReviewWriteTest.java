package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class GitHubReviewWriteTest {

    private static ObjectNode result(String json) {
        try {
            return (ObjectNode) GitHubAnswers.JSON.readTree(json);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static ObjectNode line(String text, String extraProperties, String fixes) {
        return result(
                """
                {"kind":"review","level":"none","message":{"text":%s},
                 "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/Gate.java"},
                   "region":{"startLine":12,"endLine":13}}}],%s
                 "properties":{"porelo/anchorKind":"line","porelo/diffSide":"new"%s}}
                """
                        .formatted(GitHubAnswers.JSON.valueToTree(text), fixes, extraProperties));
    }

    private static String fix(int start, int end, String uri) {
        return """
               "fixes":[{"artifactChanges":[{"artifactLocation":{"uri":"%s"},
                 "replacements":[{"deletedRegion":{"startLine":%d,"endLine":%d},
                   "insertedContent":{"text":"a\\nb"}}]}]}],
               """
                .formatted(uri, start, end);
    }

    // Harm: a mention in a released comment notifies a person on the forge.
    @Test
    void aMentionInProseBecomesACodeSpanAndOneInCodeStays() {
        final GitHubReviewWrite.Rendered rendered =
                GitHubReviewWrite.render(line("ask @octocat or `@team`", "", ""));

        assertThat(rendered.body())
                .as("prose mention escaped, code untouched")
                .isEqualTo("ask `@octocat` or `@team`");
    }

    // Harm: a client claims an authority field the daemon owns.
    @Test
    void aSourceLevelIsRefusedAsForbidden() {
        assertThatThrownBy(
                        () ->
                                GitHubReviewWrite.render(
                                        line("x", ",\"porelo/sourceLevel\":\"I1\"", "")))
                .as("an authority field")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .isEqualTo(ReviewRefusal.Reason.FORBIDDEN_FIELD));
    }

    @Test
    void anUnlistedPropertyIsAProfileViolation() {
        assertThatThrownBy(() -> GitHubReviewWrite.render(line("x", ",\"x-extra\":\"1\"", "")))
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .as("not in a write's projection")
                                        .isEqualTo(ReviewRefusal.Reason.PROFILE_VIOLATION));
    }

    // Harm: a suggestion replaces lines other than the ones the person anchored.
    @Test
    void aFixOfOtherLinesThanItsAnchorIsRefused() {
        assertThatThrownBy(
                        () -> GitHubReviewWrite.render(line("x", "", fix(12, 14, "src/Gate.java"))))
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .as("outside the anchor")
                                        .isEqualTo(ReviewRefusal.Reason.FIX_OUTSIDE_ANCHOR));
    }

    @Test
    void aFixInAnotherFileIsRefused() {
        assertThatThrownBy(
                        () ->
                                GitHubReviewWrite.render(
                                        line("x", "", fix(12, 13, "src/Other.java"))))
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .isEqualTo(ReviewRefusal.Reason.FIX_OUTSIDE_ANCHOR));
    }

    @Test
    void aFixOfItsAnchorBecomesASuggestionFence() {
        final GitHubReviewWrite.Rendered rendered =
                GitHubReviewWrite.render(line("Like this.", "", fix(12, 13, "src/Gate.java")));

        assertThat(rendered.body())
                .as("the text, then the fence")
                .isEqualTo("Like this.\n\n```suggestion\na\nb\n```");
        assertThat(rendered.startLine()).as("first line").isEqualTo(12);
        assertThat(rendered.line()).as("last line").isEqualTo(13);
        assertThat(rendered.side()).as("new is RIGHT").isEqualTo("RIGHT");
    }

    @Test
    void aFixOnTheOldSideIsRefused() {
        final ObjectNode result = line("x", "", fix(12, 13, "src/Gate.java"));
        ((ObjectNode) result.get("properties")).put("porelo/diffSide", "old");

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r -> assertThat(r.reason()).isEqualTo(ReviewRefusal.Reason.OLD_SIDE_FIX));
    }

    @Test
    void aSingleBraceIsRefusedAndDoubledOnesAreUndone() {
        assertThat(GitHubReviewWrite.render(line("use {{x}}", "", "")).body())
                .as("doubled braces undone")
                .isEqualTo("use {x}");
        assertThatThrownBy(() -> GitHubReviewWrite.render(line("use {x}", "", "")))
                .as("a single brace has nothing to stand for")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .isEqualTo(ReviewRefusal.Reason.PROFILE_VIOLATION));
    }

    @Test
    void aZeroWidthSpaceIsUnsafeText() {
        assertThatThrownBy(() -> GitHubReviewWrite.render(line("a​b", "", "")))
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r -> assertThat(r.reason()).isEqualTo(ReviewRefusal.Reason.UNSAFE_TEXT));
    }

    @Test
    void aPathLeavingTheRepositoryIsRefused() {
        final ObjectNode result = line("x", "", "");
        ((ObjectNode) result.at("/locations/0/physicalLocation/artifactLocation"))
                .put("uri", "../../.ssh/id_ed25519");

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("dot components")
                .isInstanceOf(ReviewRefusal.class);
    }

    // Harm: GitHub refuses a line comment that names a subject type, so it never reaches the PR.
    @Test
    void aLineCommentSendsItsLinesAndSideAndNoSubjectType() {
        final GitHubReviewWrite.Rendered rendered =
                GitHubReviewWrite.render(line("x", "", fix(12, 13, "src/Gate.java")));

        final com.fasterxml.jackson.databind.JsonNode sent =
                wire(GitHubReviewWrite.reviewCommentRequest(rendered, "a".repeat(40)));

        assertThat(fieldNames(sent))
                .as("the fields GitHub accepts for a line comment")
                .containsExactlyInAnyOrder(
                        "body", "commit_id", "path", "side", "line", "start_line", "start_side");
        assertThat(sent.path("start_line").asInt()).as("first line").isEqualTo(12);
        assertThat(sent.path("line").asInt()).as("last line").isEqualTo(13);
    }

    @Test
    void aFileCommentSendsItsSubjectTypeAndNoLine() {
        final ObjectNode result = line("x", "", "");
        ((ObjectNode) result.get("properties")).put("porelo/anchorKind", "file");
        ((ObjectNode) result.at("/locations/0/physicalLocation")).remove("region");

        final com.fasterxml.jackson.databind.JsonNode sent =
                wire(
                        GitHubReviewWrite.reviewCommentRequest(
                                GitHubReviewWrite.render(result), "a".repeat(40)));

        assertThat(fieldNames(sent))
                .as("the fields GitHub accepts for a file comment")
                .containsExactlyInAnyOrder("body", "commit_id", "path", "subject_type");
        assertThat(sent.path("subject_type").asText()).as("file level").isEqualTo("file");
    }

    /** The request as the client sends it: fields without a value are left out. */
    private static com.fasterxml.jackson.databind.JsonNode wire(Object request) {
        return GitHubAnswers.JSON
                .copy()
                .setSerializationInclusion(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                .valueToTree(request);
    }

    private static java.util.List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        final java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // Harm: a mention GitHub renders as prose notifies that person or team.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "```x``` @octocat",
                "one ` mark\n\n@octocat and ` another",
                "ask _@octocat_ now",
                "ask __@octocat__ now"
            })
    void aMentionGitHubWouldRenderAsProseBecomesACodeSpan(String text) {
        assertThat(ReviewText.toForge(text))
                .as("the mention is a code span, so it notifies no one")
                .contains("`@octocat`");
    }
}
