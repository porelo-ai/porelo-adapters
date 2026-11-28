package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.ReviewProtocol;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

class ReviewExpectationTest {

    @Test
    void everyPublishedPagePassesProfileTwoAndTheCompleteJsonComparison() throws Exception {
        int pages = 0;
        for (final ReviewCase test : ReviewCase.all()) {
            if (test.expected().fields().containsKey("document")) {
                pages++;
                test.expected()
                        .compareAnswer(
                                test.name(),
                                page(test),
                                "test-token",
                                ReviewExpectationTest::origin);
            }
        }
        assertThat(pages)
                .as("every conversation case supplies a checked expected page")
                .isEqualTo(6);
    }

    @Test
    void anotherAnchorOrAccountCannotPassAsTheRecordedPage() throws Exception {
        final ReviewCase test = test("comments-lines");
        for (final String changed : new String[] {"startLine", "porelo/forgeAccountId"}) {
            final Map<String, Object> page = page(test);
            final String document = (String) page.get("document");
            page.put(
                    "document",
                    changed.equals("startLine")
                            ? document.replace("\"startLine\":10", "\"startLine\":11")
                            : document.replace(
                                    "\"porelo/forgeAccountId\":\"583231\"",
                                    "\"porelo/forgeAccountId\":\"999\""));
            assertThatThrownBy(
                            () ->
                                    test.expected()
                                            .compareAnswer(
                                                    test.name(),
                                                    page,
                                                    "test-token",
                                                    ReviewExpectationTest::origin))
                    .as("a fabricated %s changes the meaning of the conversation", changed)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("complete wire answer");
        }
    }

    @Test
    void aCompleteClaimCannotConcealAHeldComment() throws Exception {
        final ReviewCase test = test("comments-held-bidi");
        final Map<String, Object> page = page(test);
        page.put(
                "document", ((String) page.get("document")).replace("\"partial\"", "\"complete\""));
        assertThatThrownBy(
                        () ->
                                test.expected()
                                        .compareAnswer(
                                                test.name(),
                                                page,
                                                "test-token",
                                                ReviewExpectationTest::origin))
                .as("an incomplete conversation must not release a review as complete")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("complete wire answer");
    }

    @Test
    void aLeakedTokenIsRejectedWithoutPrintingIt() {
        final String token = "lent-only-in-memory";
        final ReviewExpectation expected =
                new ReviewExpectation(
                        ReviewProtocol.INTERFACE + ".Failed", Map.of("message", "{{message}}"));
        assertThatThrownBy(
                        () ->
                                expected.compareError(
                                        "leak",
                                        ReviewProtocol.INTERFACE + ".Failed",
                                        Map.of("message", "forge echoed " + token),
                                        token))
                .as("a token leak must fail without copying the token into the test report")
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining(token);
        assertThatThrownBy(
                        () ->
                                new ReviewExpectation(null, Map.of("thread", "101"))
                                        .compareAnswer(
                                                "leak",
                                                Map.of("thread", token),
                                                token,
                                                UnaryOperator.identity()))
                .as("a successful reply is checked before an assertion can print it")
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining(token);
    }

    @Test
    void anUnexpectedErrorFieldCannotBeSilentlyDiscarded() throws Exception {
        final ReviewCase test = test("post-lost-answer");
        assertThatThrownBy(
                        () ->
                                test.expected()
                                        .compareError(
                                                test.name(),
                                                ReviewProtocol.INTERFACE + ".WriteOutcomeUnknown",
                                                Map.of("retry", true),
                                                "test-token"))
                .as("a named error carries exactly the fields its contract declares")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("error fields");
    }

    @Test
    void aTokenEncodedInsideAProfileDocumentIsRejectedBeforeAnyDiffCanPrintIt() throws Exception {
        final ReviewCase test = test("comments-lines");
        final Map<String, Object> page = page(test);
        final String token = "lent-only-in-memory";
        page.put(
                "document",
                ((String) page.get("document"))
                        .replace("example-reviewer", "\\u006c" + token.substring(1)));
        assertThatThrownBy(
                        () ->
                                test.expected()
                                        .compareAnswer(
                                                test.name(),
                                                page,
                                                token,
                                                ReviewExpectationTest::origin))
                .as("nested JSON escapes cannot bypass the token guard or reveal it in a diff")
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining(token);
    }

    private static ReviewCase test(final String name) throws Exception {
        return ReviewCase.all().stream()
                .filter(test -> test.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> page(final ReviewCase test) {
        String document = origin(Json.write(test.expected().fields().get("document")));
        int id = 0;
        while (document.contains("{{uuid}}")) {
            document =
                    document.replaceFirst(
                            Pattern.quote("{{uuid}}"),
                            "00000000-0000-4000-8000-%012d".formatted(++id));
        }
        return new java.util.LinkedHashMap<>(Map.of("document", document));
    }

    private static String origin(final String text) {
        return text.replace("{{web}}", "https://127.0.0.1:12345");
    }
}
