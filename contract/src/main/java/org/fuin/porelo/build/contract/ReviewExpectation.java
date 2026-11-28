package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.fuin.porelo.build.api.ReviewProtocol;
import org.fuin.porelo.build.api.review.ProfileCheck;
import org.fuin.porelo.build.api.review.SarifReading;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/** Compares the actual wire reply before any installed reader can scrub or reshape it. */
record ReviewExpectation(@Nullable String error, Map<String, Object> fields) {

    static ReviewExpectation read(final Map<String, Object> definition) throws IOException {
        if (definition.keySet().equals(Set.of("answer"))) {
            return new ReviewExpectation(null, ReviewCase.object(definition.get("answer")));
        }
        if (definition.keySet().equals(Set.of("error", "fields"))
                && definition.get("error") instanceof String name) {
            return new ReviewExpectation(
                    ReviewProtocol.INTERFACE + "." + name,
                    ReviewCase.object(definition.get("fields")));
        }
        throw new IOException("Expected either a review answer or an error with fields");
    }

    void compare(final ReviewCase test, final VarlinkClient client, final RecordedForge forge)
            throws Exception {
        Map<String, Object> answer;
        try {
            answer = client.call(test.method(), test.call(forge));
        } catch (VarlinkException ex) {
            secretFree(String.valueOf(ex.getMessage()), forge.token());
            compareError(test.name(), ex.getErrorName(), ex.getParameters(), forge.token());
            return;
        }
        compareAnswer(test.name(), answer, forge.token(), forge::substitute);
    }

    void compareError(
            final String name,
            final @Nullable String actualError,
            final @Nullable Map<String, Object> parameters,
            final String token) {
        secretFree(String.valueOf(actualError), token);
        secretFree(Json.write(parameters), token);
        assertThat(actualError).as("%s answers a named Review1 error", name).isNotNull();
        assertThat(actualError).as("%s error name", name).isEqualTo(error);
        assertThat(normalized(parameters, fields, new HashSet<>()))
                .as("%s error fields", name)
                .isEqualTo(fields);
    }

    void compareAnswer(
            final String name,
            final Map<String, Object> actual,
            final String token,
            final UnaryOperator<String> substitute)
            throws Exception {
        secretFree(Json.write(actual), token);
        assertThat(error).as("%s must answer its expected refusal", name).isNull();
        final Map<String, Object> answer = new LinkedHashMap<>(actual);
        if (answer.get("document") instanceof String document) {
            final Map<String, Object> parsed = ReviewCase.object(Json.parseExact(document));
            secretFree(Json.write(parsed), token);
            ProfileCheck.check(
                    ProfileCheck.Channel.ADAPTER,
                    SarifReading.read(document.getBytes(StandardCharsets.UTF_8)));
            answer.put("document", parsed);
        }
        final Map<String, Object> expected =
                ReviewCase.object(Json.parseExact(substitute.apply(Json.write(fields))));
        assertThat(normalized(answer, expected, new HashSet<>()))
                .as("%s complete wire answer and profile document", name)
                .isEqualTo(expected);
    }

    static void secretFree(final String text, final String token) {
        // Asserting the boolean prevents a broken adapter's token from entering assertion output.
        assertThat(text.contains(token))
                .as("the lent token never appears in an answer or error")
                .isFalse();
    }

    private static @Nullable Object normalized(
            final @Nullable Object actual,
            final @Nullable Object expected,
            final Set<String> guids) {
        if (expected instanceof Map<?, ?> wanted && actual instanceof Map<?, ?> got) {
            final Map<String, @Nullable Object> result = new LinkedHashMap<>();
            got.forEach(
                    (key, value) ->
                            result.put((String) key, normalized(value, wanted.get(key), guids)));
            return result;
        }
        if (expected instanceof List<?> wanted && actual instanceof List<?> got) {
            final List<@Nullable Object> result = new ArrayList<>();
            for (int index = 0; index < got.size(); index++) {
                result.add(
                        normalized(
                                got.get(index),
                                index < wanted.size() ? wanted.get(index) : null,
                                guids));
            }
            return result;
        }
        if ("{{uuid}}".equals(expected) && actual instanceof String guid) {
            assertThat(guid)
                    .as("each comment has a profile UUIDv4")
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
            assertThat(guids.add(guid)).as("different comments have different identities").isTrue();
            // The profile requires an identity, not GitHub's choice of hashing algorithm.
            return expected;
        }
        if ("{{message}}".equals(expected) && actual instanceof String text) {
            assertThat(text).as("Failed explains why the adapter could not answer").isNotBlank();
            return expected;
        }
        return actual;
    }
}
