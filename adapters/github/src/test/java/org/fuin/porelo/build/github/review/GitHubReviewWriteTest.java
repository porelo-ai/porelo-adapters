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
                .isEqualTo("ask ``@octocat`` or `@team`");
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
                .as("a fix in another file")
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
                .as("a fix on the old side")
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
                .as("a zero-width space")
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

    // Harm: a mention GitHub renders as prose notifies that person or team. Each expected body
    // is checked by hand against CommonMark: the mention ends inside a code span.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "```x``` @octocat|```x``` ````@octocat````",
                "one ` mark\\n\\n@octocat and ` another|one ` mark\\n\\n``@octocat`` and ` another",
                "ask _@octocat_ now|ask _`@octocat`_ now",
                "ask __@octocat__ now|ask __`@octocat`__ now",
                "one ` mark, @alice and @bob|one ` mark, ``@alice`` and ``@bob``",
                "ask \\@octocat|ask \\ `@octocat`",
                "see `@octocat here|see ` ``@octocat`` here",
                "`\\n# @octocat `|`\\n# ``@octocat`` `",
                "> `\\n>\\n> @octocat `|> `\\n>\\n> ``@octocat`` `"
            })
    void aMentionGitHubWouldRenderAsProseBecomesACodeSpan(String text, String body) {
        assertThat(ReviewText.toForge(unescape(text)))
                .as("the mention is a code span no stray backtick can pair with")
                .isEqualTo(unescape(body));
    }

    /** A CSV record holds no line end: a backslash and n stand for one. */
    private static String unescape(String csv) {
        return csv.replace("\\\\", "\u0000").replace("\\n", "\n").replace("\u0000", "\\");
    }

    private static ObjectNode finding(String ruleId) {
        final ObjectNode result = line("x", "", "");
        result.put("kind", "fail").put("level", "error").put("ruleId", ruleId);
        return result;
    }

    // Harm: a rule ID closing its code span lets a mention or a bidi override reach GitHub.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"R1` @octocat `", "R1\u202E", "R1\nR2"})
    void aRuleIdThatCouldLeaveItsCodeSpanIsRefused(String ruleId) {
        assertThatThrownBy(() -> GitHubReviewWrite.render(finding(ruleId)))
                .as("refused before anything is sent")
                .isInstanceOf(ReviewRefusal.class);
    }

    // Harm: a bidi override in the message's text reaches GitHub behind a Markdown form.
    @Test
    void theTextIsCheckedAndSentWhateverTheMarkdownSays() {
        final ObjectNode result = line("evil \u202E", "", "");
        ((ObjectNode) result.get("message")).put("markdown", "");

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("the text is what goes, so it is what is checked")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r -> assertThat(r.reason()).isEqualTo(ReviewRefusal.Reason.UNSAFE_TEXT));
    }

    // Harm: a malformed fix crashes the call with Java's own words instead of naming the field.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "\"fixes\":{\"a\":1},",
                "\"fixes\":[{\"artifactChanges\":{\"a\":1}}],",
                "\"fixes\":[{\"artifactChanges\":[{\"artifactLocation\":{\"uri\":\"src/Gate.java\"},\"replacements\":{\"a\":1}}]}],"
            })
    void aFixThatIsNoArrayIsRefused(String fixes) {
        assertThatThrownBy(() -> GitHubReviewWrite.render(line("x", "", fixes)))
                .as("a refusal naming the fix, not a crash")
                .isInstanceOf(ReviewRefusal.class);
    }

    // Harm: a field the profile forbids rides along unchecked inside an allowed object.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "/locations/0/physicalLocation/artifactLocation:uriBaseId",
                "/locations/0/physicalLocation:contextRegion",
                "/locations/0:message",
                "/message:id",
                "/fixes/0:description",
                "/fixes/0/artifactChanges/0/replacements/0/insertedContent:binary",
                "/fixes/0/artifactChanges/0/replacements/0/deletedRegion:charOffset"
            })
    void aNestedFieldOutsideTheProjectionIsRefused(String where) {
        final ObjectNode result = line("x", "", fix(12, 13, "src/Gate.java"));
        final String[] at = where.split(":");
        ((ObjectNode) result.at(at[0])).put(at[1], "1");

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("refused, naming the field")
                .isInstanceOf(ReviewRefusal.class);
    }

    // Harm: a line given as text or a fraction is read as another line than the one meant.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"12\"", "12.9"})
    void aFixLineThatIsNoIntegerIsRefused(String startLine) {
        final ObjectNode result =
                line(
                        "x",
                        "",
                        fix(12, 13, "src/Gate.java")
                                .replace("\"startLine\":12", "\"startLine\":" + startLine));

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("refused, never coerced")
                .isInstanceOf(ReviewRefusal.class);
    }

    @Test
    void aOneLineFixWithoutEndLinesIsItsAnchor() {
        final ObjectNode result =
                result(
                        """
                        {"kind":"review","level":"none","message":{"text":"x"},
                         "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/Gate.java"},
                           "region":{"startLine":12}}}],
                         "fixes":[{"artifactChanges":[{"artifactLocation":{"uri":"src/Gate.java"},
                           "replacements":[{"deletedRegion":{"startLine":12},
                             "insertedContent":{"text":"y"}}]}]}],
                         "properties":{"porelo/anchorKind":"line","porelo/diffSide":"new"}}
                        """);

        assertThat(GitHubReviewWrite.render(result).body())
                .as("SARIF's endLine defaults to startLine on both")
                .endsWith("```suggestion\ny\n```");
    }

    // Harm: a body over GitHub's limit for the profile is held by every later read of it.
    @Test
    void aBodyOverTheLimitWithItsRuleIdIsRefused() {
        final ObjectNode result = finding("R".repeat(100));
        ((ObjectNode) result.get("message")).put("text", "x".repeat(8150));

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("the whole body counts")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .as("too large")
                                        .isEqualTo(ReviewRefusal.Reason.TOO_LARGE));
    }

    // Harm: a comment on the old side of a file lands on the new side.
    @Test
    void aFileCommentOnTheOldSideIsRefused() {
        final ObjectNode result = line("x", "", "");
        ((ObjectNode) result.get("properties"))
                .put("porelo/anchorKind", "file")
                .put("porelo/diffSide", "old");
        ((ObjectNode) result.at("/locations/0/physicalLocation")).remove("region");

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("GitHub's file comment names no side")
                .isInstanceOf(ReviewRefusal.class);
    }

    // Harm: GitHub renders HTML, an autolink or a character reference in prose, which hides text
    // from the mention escape or turns into a mention, a bidi override or a tracking image.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "ask &#64;octocat",
                "ask &commat;octocat",
                "a&#x200B;b",
                "<!--\n~~~\n-->\n@octocat",
                "<a title=\"`\"> @octocat `</a>",
                "see <https://example.org/x.png>",
                "mail <user@example.org>"
            })
    void markupInProseIsRefused(String text) {
        assertThatThrownBy(() -> ReviewText.toForge(text))
                .as("the profile's text holds no HTML, autolink or character reference")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .as("a profile violation")
                                        .isEqualTo(ReviewRefusal.Reason.PROFILE_VIOLATION));
    }

    // Harm: a backslash-escaped backtick opens no code span on GitHub, so the mention after it
    // would notify.
    @Test
    void aMentionAfterAnEscapedBacktickIsEscaped() {
        assertThat(ReviewText.toForge("\\` @octocat `"))
                .as("the escaped backtick is prose, the mention a code span")
                .isEqualTo("\\` ``@octocat`` `");
    }

    // Harm: markup inside code is code; refusing it would refuse every review of HTML.
    @Test
    void markupInCodeIsSentAsItIs() {
        assertThat(ReviewText.toForge("use `<b>&amp;</b>` here"))
                .as("a code span is never rewritten")
                .isEqualTo("use `<b>&amp;</b>` here");
    }

    // Harm: a line number past 64 bits wraps to line 1 and anchors the comment there.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"18446744073709551617"})
    void aLineNumberPastTheRangeIsRefusedNotWrapped(String startLine) {
        final ObjectNode result = line("x", "", "").deepCopy();
        ((ObjectNode) result.at("/locations/0/physicalLocation/region"))
                .put("startLine", new java.math.BigInteger(startLine))
                .put("endLine", new java.math.BigInteger(startLine));

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("refused, never read as another line")
                .isInstanceOf(ReviewRefusal.class);
    }

    // Harm: a body whose braces a read doubles past the limit is held on every later read.
    @Test
    void aBodyOverTheLimitOnceItsBracesAreDoubledIsRefused() {
        final ObjectNode result = finding("R".repeat(100));
        ((ObjectNode) result.get("message")).put("text", "{{".repeat(4080));

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("the body a read gives back counts")
                .isInstanceOfSatisfying(
                        ReviewRefusal.class,
                        r ->
                                assertThat(r.reason())
                                        .as("too large")
                                        .isEqualTo(ReviewRefusal.Reason.TOO_LARGE));
    }

    // Harm: an inverted region is stored and served as SARIF no reader can place.
    @Test
    void aOneLineRegionWhoseColumnsAreInvertedIsRefused() {
        final ObjectNode result = line("x", "", "");
        ((ObjectNode) result.at("/locations/0/physicalLocation/region"))
                .put("endLine", 12)
                .put("startColumn", 10)
                .put("endColumn", 2);

        assertThatThrownBy(() -> GitHubReviewWrite.render(result))
                .as("the end column comes before the start")
                .isInstanceOf(ReviewRefusal.class);
    }
}
