package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.fuin.porelo.planning.api.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndexSafetyTest {
    private static final String HEADER =
            "## Queued\n\n| # | Size | Blocked by | Title |\n|---|---|---|---|\n";
    private static final String ISSUE = "# I1 — Work\n\n**What must be true.** Complete work.\n";
    private static final Settings SETTINGS =
            Settings.read(
                    Map.of(
                            "repository", "https://example.org/planning.git",
                            "issueRepositoryPattern", "https://example.org/{repository}.git",
                            "linkPattern", "https://example.org/{repository}/{path}"),
                    "");
    private static final Workflow WORKFLOW =
            WorkflowFile.parse("states:\n  - name: Queued\n    closed: false\nphases: []\n");

    @TempDir Path root;

    // Harm: silently losing a linked file discards acceptance criteria and approved briefs.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "wrong-name",
                "wrong-path",
                "parent-link",
                "unlinked",
                "fragment-link"
            })
    void refusesMissingMismatchedOrUnlinkedIssueFiles(String damage) throws Exception {
        write("issues/README.md", HEADER + row("[I1](group/I1-Work.md)", "-"));
        if (damage.equals("wrong-name")) {
            write("issues/README.md", HEADER + row("[I1](group/I2-Work.md)", "-"));
            write("issues/group/I2-Work.md", ISSUE);
        }
        if (damage.equals("wrong-path")) write("issues/elsewhere/I1-Work.md", ISSUE);
        if (damage.equals("parent-link")) {
            final Path outside = Files.createDirectory(root.resolve("external"));
            Files.writeString(outside.resolve("I1-Work.md"), ISSUE);
            Files.createSymbolicLink(root.resolve("issues/group"), outside);
        }
        if (damage.equals("unlinked")) {
            write("issues/README.md", HEADER + row("I1", "-"));
            write("issues/I1-Work.md", ISSUE);
        }
        if (damage.equals("fragment-link"))
            write("issues/README.md", HEADER + row("[I1](I1-Work.md#acceptance)", "-"));
        refused(() -> read(), "a linked issue cannot degrade to a fileless issue");
    }

    // Harm: changing ID case loses references to the actual indexed issue.
    @Test
    void preservesTheLinkedIssueIdsCase() throws Exception {
        write("issues/README.md", HEADER + row("[iOne](iOne-Work.md)", "-"));
        write("issues/iOne-Work.md", ISSUE.replace("I1", "iOne"));
        assertThat(read().keySet())
                .as("the link's ID is the reference key")
                .containsExactly("iOne");
    }

    // Harm: a dangling link can bypass an index or reveal whether its host target exists.
    @Test
    void refusesDanglingOptionalFilesWithoutInspectingTheirTargets() throws Exception {
        Files.createDirectories(root.resolve("stories"));
        Files.createSymbolicLink(root.resolve("stories/README.md"), root.resolve("absent"));
        final PlanningRefused failure =
                refused(
                        () -> new SafeFiles(root).optional("stories/README.md"),
                        "a dangling optional index is unsafe rather than absent");
        assertThat(failure.parameters().toString())
                .as("the refusal describes the link, never its target")
                .contains("link or non-file");
    }

    // Harm: ignored index rows hide work or make a blocked issue appear ready.
    @ParameterizedTest
    @ValueSource(strings = {"missing-anchor", "invalid-anchor", "plain-blocker", "mixed-blocker"})
    void refusesMalformedIssueRowsAndUnlinkedBlockers(String damage) throws Exception {
        String row = row("I1", "-");
        if (damage.equals("missing-anchor")) row = row.replace("<a id=\"i1\"></a>", "");
        if (damage.equals("invalid-anchor")) row = row.replace("id=\"i1\"", "id=\"!\"");
        if (damage.equals("plain-blocker")) row = row("I1", "I2");
        if (damage.equals("mixed-blocker")) row = row("I1", "[I2](README.md#i2), I3");
        write("issues/README.md", HEADER + row);
        refused(() -> read(), "malformed rows and blockers must not disappear");
    }

    // Harm: an omitted story anchor must not silently remove work from the board.
    @ParameterizedTest
    @ValueSource(strings = {"", "<a id=\"!\"></a>"})
    void refusesMalformedStoryRows(String anchor) throws Exception {
        write(
                "stories/README.md",
                "| Story | Priority | Issues | Gain |\n|---|---|---|---|\n| "
                        + anchor
                        + "[S1](S1-Work.md) | High | - | Work |\n");
        refused(
                () -> StoryIndex.files(new SafeFiles(root).read("stories/README.md")),
                "every story table row requires a valid anchor");
    }

    // Harm: omitted subgroup indexes lose issues and prevent complete snapshots.
    @ParameterizedTest
    @ValueSource(strings = {"base/README.md", "base/README.md#queued"})
    void followsSubgroupIndexesAndUsesTheirClosedIssueAnchors(String target) throws Exception {
        write("issues/README.md", "# Issues\n\n[Base](" + target + ")\n");
        write("issues/base/README.md", HEADER + row("[I1](I1-Work.md)", "-") + row("I2", "-"));
        write("issues/base/I1-Work.md", ISSUE);
        final var entries = read();
        assertThat(entries.keySet())
                .as("all linked subgroup rows are read")
                .containsExactly("I1", "I2");
        assertThat(entries.get("I2").summary().link())
                .as("a fileless issue links to its owning index")
                .isEqualTo("https://example.org/one/issues/base/README.md#i2");
    }

    // Harm: repeated indexes or duplicate IDs make the selected work ambiguous.
    @ParameterizedTest
    @ValueSource(strings = {"cycle", "duplicate-id", "duplicate-index"})
    void refusesCyclesAndDuplicatesAcrossIndexes(String damage) throws Exception {
        write("issues/README.md", "[Base](base/README.md)\n");
        write("issues/base/README.md", HEADER + row("I1", "-"));
        if (damage.equals("cycle")) {
            write("issues/base/README.md", "[Self](README.md)\n" + HEADER + row("I1", "-"));
        } else if (damage.equals("duplicate-index")) {
            write("issues/README.md", "[Base](base/README.md)\n[Again](base/README.md)\n");
        } else {
            write("issues/README.md", "[Base](base/README.md)\n" + HEADER + row("I1", "-"));
        }
        refused(() -> read(), "index traversal refuses repeated paths and IDs");
    }

    // Harm: malformed agent fields yield an invalid wire answer and terminate the adapter.
    @ParameterizedTest
    @ValueSource(strings = {"kind", "agent", "model", "control"})
    void refusesBlankOrControlCharactersInSubAgentFields(String damage) throws Exception {
        String proposal = "| builder | builder | runner | model | [Brief](I1.sub/builder.md) |\n";
        if (damage.equals("kind"))
            proposal = proposal.replace("| builder | builder |", "| builder | | ");
        if (damage.equals("agent")) proposal = proposal.replace("runner", "");
        if (damage.equals("model")) proposal = proposal.replace("model", "");
        if (damage.equals("control")) proposal = proposal.replace("runner", "run\u0001ner");
        write("issues/README.md", HEADER + row("[I1](I1-Work.md)", "-"));
        write(
                "issues/I1-Work.md",
                ISSUE
                        + "\n## Sub-agents\n\n"
                        + "| Name | Kind | Agent | Model | Brief |\n|---|---|---|---|---|\n"
                        + proposal);
        write("issues/I1.sub/builder.md", "Complete brief.\n");
        final var entry = read().get("I1");
        final var failure =
                refused(
                        () -> IssueIndex.full(entry, new SafeFiles(root)),
                        "agent fields must decode safely");
        assertThat(failure.parameters().toString())
                .as("the malformed proposal is identified")
                .contains("I1", "builder");
    }

    // Harm: harmless metadata must not make the entire planning store unreadable.
    @Test
    void ignoresNonMarkdownMetadataButRefusesLinkedDirectories() throws Exception {
        write("issues/README.md", HEADER + row("I1", "-"));
        write("issues/.gitignore", "*.tmp\n");
        write("issues/.gitkeep", "");
        assertThat(read().keySet()).as("metadata cannot hide the issue set").containsExactly("I1");
        Files.createSymbolicLink(
                root.resolve("issues/linked"), Files.createDirectory(root.resolve("outside")));
        refused(() -> read(), "symlinked directories are refused even when not listed");
    }

    // Harm: example headings must not authorize example sub-agents.
    @ParameterizedTest
    @ValueSource(strings = {"```", "~~~~"})
    void ignoresExampleSubAgentHeadingsInsideFences(String fence) throws Exception {
        write("issues/README.md", HEADER + row("[I1](I1-Work.md)", "-"));
        write(
                "issues/I1-Work.md",
                ISSUE
                        + "\n## Plan\n\n"
                        + fence
                        + "markdown\n"
                        + "## Sub-agents\nexample only\n"
                        + fence
                        + "\n");
        assertThat(IssueIndex.full(read().get("I1"), new SafeFiles(root)).subAgents())
                .as("fenced examples are never proposals")
                .isEmpty();
    }

    // Harm: missing acceptance criteria must not authorize unspecified work.
    @Test
    void refusesAnExistingIssueWithoutAcceptance() throws Exception {
        write("issues/README.md", HEADER + row("[I1](I1-Work.md)", "-"));
        write("issues/I1-Work.md", "# I1 — Work\n\n**What must be true:** Complete work.\n");
        refused(
                () -> IssueIndex.full(read().get("I1"), new SafeFiles(root)),
                "existing issue files require acceptance");
    }

    private Map<String, IssueIndex.Entry> read() {
        return IssueIndex.read(new SafeFiles(root), "one", SETTINGS, WORKFLOW);
    }

    private void write(String path, String text) throws Exception {
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), text);
    }

    private static String row(String issue, String blocker) {
        final String id = issue.startsWith("[") ? issue.substring(1, issue.indexOf(']')) : issue;
        return "| <a id=\""
                + id.toLowerCase(java.util.Locale.ROOT)
                + "\"></a>"
                + issue
                + " | S | "
                + blocker
                + " | Work |\n";
    }

    private static PlanningRefused refused(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String harm) {
        final Throwable failure = catchThrowable(call);
        assertThat(failure).as(harm).isInstanceOf(PlanningRefused.class);
        final PlanningRefused refused = (PlanningRefused) failure;
        assertThat(refused.reason())
                .as("malformed stored data uses Failed")
                .isEqualTo(Reason.FAILED);
        return refused;
    }
}
