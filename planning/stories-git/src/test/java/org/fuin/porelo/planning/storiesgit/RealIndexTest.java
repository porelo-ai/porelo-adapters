package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the parser to issue indexes as the Porelo repositories write them on their main branches.
 */
class RealIndexTest {
    private static final Pattern HEADING = Pattern.compile("^#{2,3} (.+)$");
    private static final Pattern ROW = Pattern.compile("^\\| <a id=\"([a-z]+[0-9]+)\"></a>");

    // Harm: one hand-written index the parser refuses fails the whole board of a real project.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "index-porelo-agy.md",
                "index-porelo-base.md",
                "index-porelo-forge-clients.md",
                "index-porelo-frontend.md",
                "index-porelo-intellij.md",
                "index-porelo-message-matrix.md",
                "index-porelo-project.md"
            })
    void readsEveryRowOfARealIndexInItsGroup(String name) throws IOException {
        final String text = sample(name);
        final Map<String, String> expected = new TreeMap<>();
        String state = "";
        for (final String line : text.split("\\R")) {
            final Matcher heading = HEADING.matcher(line);
            if (heading.matches()) state = heading.group(1).strip();
            final Matcher row = ROW.matcher(line);
            if (row.find()) expected.put(row.group(1).toUpperCase(java.util.Locale.ROOT), state);
        }
        final Map<String, String> actual = new TreeMap<>();
        for (final var entry : IssueIndex.parse(text))
            actual.put(
                    entry.summary().id().toUpperCase(java.util.Locale.ROOT),
                    entry.summary().state());
        assertThat(expected).as("the sample holds issue rows to compare").hasSizeGreaterThan(10);
        assertThat(actual)
                .as("every anchored row of %s is read, in the group it stands under", name)
                .isEqualTo(expected);
    }

    // Harm: a finished issue whose file is gone must not make the board refuse its repository.
    @Test
    void takesAFinishedRowLinkingItsOwnAnchorAsAnIssueWithoutFile() throws IOException {
        final var finished =
                IssueIndex.parse(sample("index-porelo-message-matrix.md")).stream()
                        .filter(entry -> entry.summary().id().equals("MX65"))
                        .findFirst()
                        .orElseThrow();
        assertThat(finished.summary().state())
                .as("MX65 stands under Finished")
                .isEqualTo("Finished");
        assertThat(finished.path()).as("its own anchor is no issue file").isEmpty();
    }

    // Harm: free text or a dash in Blocked by must neither refuse the index nor invent a blocker.
    @Test
    void readsLinkedBlockersAndLeavesTheirFreeTextAside() {
        final String text =
                """
                ## Backlog

                | # | Size | Blocked by | What it covers |
                |---|---|---|---|
                | <a id="gh1"></a>[GH1](GH1-One.md) | S | `porelo` [B12](https://example.org/b12), the test organisation from the operator | One |
                | <a id="gh2"></a>[GH2](GH2-Two.md) | S | — | Two |
                """;
        final var entries = IssueIndex.parse(text);
        assertThat(entries.get(0).summary().blockedBy())
                .as("the linked issue is the blocker, the free text is not")
                .containsExactly("B12");
        assertThat(entries.get(1).summary().blockedBy()).as("a dash blocks nothing").isEmpty();
    }

    // Harm: a refusal that does not name the index leaves the person no way to find the row.
    @Test
    void namesTheIndexWhenARowStandsOutsideAnIssueTable() {
        final String text =
                """
                ## Backlog

                | Set | Files |
                |---|---|
                | <a id="gh1"></a>[GH1](GH1-One.md) | one |
                """;
        final Throwable refusal =
                catchThrowable(() -> IssueIndex.parse(text, "issues/base/README.md"));
        assertThat(refusal)
                .as("an anchored row without an issue header is refused")
                .isInstanceOf(PlanningRefused.class);
        assertThat(((PlanningRefused) refusal).parameters().toString())
                .as("the refusal names the index and the issue")
                .contains("issues/base/README.md")
                .contains("gh1");
    }

    private static String sample(String name) throws IOException {
        try (var input = RealIndexTest.class.getResourceAsStream("/samples/" + name)) {
            assertThat(input).as("sample %s exists", name).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
