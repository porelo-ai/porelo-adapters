package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.SubAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainmentTest {
    @TempDir Path root;

    // Harm: unsafe paths can disclose host files or feed unrelated instructions to an agent.
    @Test
    void refusesListedBriefsThatAreMissingLinkedOrOutsideTheirDirectory() throws Exception {
        for (final String damage : List.of("missing", "symlink", "parent-symlink", "outside")) {
            final Path clone = Files.createDirectory(root.resolve(damage));
            final Path issues = Files.createDirectories(clone.resolve("issues/group"));
            final Path briefs = Files.createDirectory(issues.resolve("I1.sub"));
            final Path outside = root.resolve("outside-" + damage + ".md");
            Files.writeString(outside, "Private host text.\n");
            final String target = damage.equals("outside") ? "../outside.md" : "I1.sub/builder.md";
            Files.writeString(issues.resolve("I1-Issue.md"), issue(target));
            if (damage.equals("symlink"))
                Files.createSymbolicLink(briefs.resolve("builder.md"), outside);
            if (damage.equals("parent-symlink")) {
                Files.delete(briefs);
                final Path external = Files.createDirectory(root.resolve("external-briefs"));
                Files.writeString(external.resolve("builder.md"), "Private directory text.\n");
                Files.createSymbolicLink(briefs, external);
            }
            final Throwable failure =
                    catchThrowable(() -> IssueIndex.full(entry(), new SafeFiles(clone)));
            assertThat(failure)
                    .as("%s brief is refused instead of delivering any instructions", damage)
                    .isInstanceOf(PlanningRefused.class);
            assertThat(((PlanningRefused) failure).parameters().toString())
                    .as("the refusal names the listed file")
                    .contains(damage.equals("outside") ? "outside.md" : "builder.md");
            assertThat(Files.readString(outside))
                    .as("refusal leaves the external file unchanged")
                    .isEqualTo("Private host text.\n");
        }
    }

    // Harm: globbing briefs can deliver instructions that were never listed or approved.
    @Test
    void deliversOnlyListedBriefsWithTheirCompleteText() throws Exception {
        final Path issues =
                Files.createDirectories(root.resolve("issues/group/I1.sub")).getParent();
        Files.writeString(issues.resolve("I1-Issue.md"), issue("I1.sub/builder.md"));
        final String brief = "# Build\n\nEvery line.\n\n```text\nlast line\n```\n";
        Files.writeString(issues.resolve("I1.sub/builder.md"), brief);
        Files.writeString(issues.resolve("I1.sub/unlisted.md"), "Private unlisted text.\n");
        final var full = IssueIndex.full(entry(), new SafeFiles(root));
        assertThat(full.subAgents())
                .as("the exact listed proposal is the complete delivered set")
                .containsExactly(
                        new SubAgent(
                                "builder",
                                SubAgent.Kind.BUILDER,
                                "example-agent",
                                "example-model",
                                brief));
        assertThat(full.whatMustBeTrue())
                .as("acceptance text remains complete")
                .isEqualTo("A complete result.\n");
    }

    // Harm: following a story, index or issue link can disclose arbitrary host text.
    @Test
    void refusesSymbolicLinksAtEveryFileAndDirectoryComponent() throws Exception {
        final Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("secret.md"), "Private host text.\n");
        final Path clone = Files.createDirectory(root.resolve("clone"));
        Files.createSymbolicLink(clone.resolve("linked.md"), outside.resolve("secret.md"));
        Files.createSymbolicLink(clone.resolve("linked-directory"), outside);
        final SafeFiles files = new SafeFiles(clone);
        for (final String path :
                List.of(
                        "linked.md",
                        "linked-directory/secret.md",
                        "../outside/secret.md",
                        "a/../../outside/secret.md")) {
            assertThat(catchThrowable(() -> files.read(path)))
                    .as("unsafe file %s is refused before its contents are delivered", path)
                    .isInstanceOf(PlanningRefused.class);
        }
    }

    // Harm: an unknown proposal kind must not be treated as authorization to start a builder.
    @Test
    void mapsANewerSubAgentKindToOtherAndKeepsAbsentProposalsEmpty() throws Exception {
        final Path issues =
                Files.createDirectories(root.resolve("issues/group/I1.sub")).getParent();
        Files.writeString(issues.resolve("I1.sub/builder.md"), "Complete brief.\n");
        Files.writeString(
                issues.resolve("I1-Issue.md"),
                issue("I1.sub/builder.md")
                        .replace("| builder | builder |", "| builder | architect |"));
        assertThat(IssueIndex.full(entry(), new SafeFiles(root)).subAgents().getFirst().kind())
                .as("an unknown kind never gains builder semantics")
                .isEqualTo(SubAgent.Kind.OTHER);
        Files.writeString(
                issues.resolve("I1-Issue.md"),
                "# I1 — First issue\n\n**What must be true.** A complete result.\n");
        assertThat(IssueIndex.full(entry(), new SafeFiles(root)).subAgents())
                .as("an absent Sub-agents section answers an explicit empty list")
                .isEmpty();
    }

    // Harm: an issue in an unknown state must not authorize starting work.
    @Test
    void refusesAnIssueWhoseIndexGroupTheWorkflowLacks() throws Exception {
        Files.createDirectory(root.resolve("issues"));
        Files.writeString(
                root.resolve("issues/README.md"),
                "# Issues\n\n"
                        + "## Unknown\n\n"
                        + "| # | Size | Blocked by | Title |\n"
                        + "|---|---|---|---|\n"
                        + "| <a id=\"i1\"></a>I1 | M | - | First issue |\n");
        final var workflow =
                new org.fuin.porelo.planning.api.Workflow(
                        List.of(
                                new org.fuin.porelo.planning.api.PlanState(
                                        "Queued",
                                        org.fuin.porelo.planning.api.PlanState.Kind.WAITING)),
                        List.of());
        final Settings settings =
                Settings.read(
                        java.util.Map.of(
                                "repository",
                                "https://example.org/planning.git",
                                "issueRepositoryPattern",
                                "https://example.org/{repository}.git",
                                "linkPattern",
                                "https://example.org/{repository}/{path}"),
                        "");
        final Throwable failure =
                catchThrowable(
                        () -> IssueIndex.read(new SafeFiles(root), "one", settings, workflow));
        assertThat(failure)
                .as("an unknown issue state is refused rather than answered")
                .isInstanceOf(PlanningRefused.class);
        assertThat(((PlanningRefused) failure).parameters().toString())
                .as("the refusal names the actual issue and unknown state")
                .contains("I1", "Unknown");
    }

    // Harm: index text can conceal the issue's actual acceptance scope if its heading is ignored.
    @Test
    void prefersTheIssueHeadingAndFallsBackToTheIndexTitleWhenItsFileIsAbsent() throws Exception {
        Files.createDirectories(root.resolve("issues/group"));
        Files.writeString(
                root.resolve("issues/README.md"),
                "# Issues\n\n"
                        + "## Queued\n\n"
                        + "| # | Size | Blocked by | Title |\n"
                        + "|---|---|---|---|\n"
                        + "| <a id=\"i1\"></a>[I1](group/I1-Issue.md) | M | - | Older title |\n"
                        + "| <a id=\"i2\"></a>I2 | S | [I1](README.md#i1) | Stored title |\n");
        Files.writeString(
                root.resolve("issues/group/I1-Issue.md"),
                "# I1 — Actual title\n\n**What must be true.** A complete result.\n");
        final var workflow =
                new org.fuin.porelo.planning.api.Workflow(
                        List.of(
                                new org.fuin.porelo.planning.api.PlanState(
                                        "Queued",
                                        org.fuin.porelo.planning.api.PlanState.Kind.WAITING)),
                        List.of());
        final Settings settings =
                Settings.read(
                        java.util.Map.of(
                                "repository",
                                "https://example.org/planning.git",
                                "issueRepositoryPattern",
                                "https://example.org/{repository}.git",
                                "linkPattern",
                                "https://example.org/{repository}/{path}"),
                        "");
        final var issues = IssueIndex.read(new SafeFiles(root), "one", settings, workflow);
        assertThat(issues.get("I1").summary().title())
                .as("a present issue file supplies its heading")
                .isEqualTo("Actual title");
        assertThat(issues.get("I2").summary())
                .as("an absent issue file retains the complete indexed summary and anchor link")
                .isEqualTo(
                        new IssueSummary(
                                "I2",
                                "one",
                                "Stored title",
                                "Queued",
                                "S",
                                List.of("I1"),
                                "https://example.org/one/issues/README.md#i2"));
    }

    private static IssueIndex.Entry entry() {
        return new IssueIndex.Entry(
                new IssueSummary("I1", "one", "First issue", "Queued", "M", List.of(), ""),
                java.util.Optional.of("issues/group/I1-Issue.md"));
    }

    private static String issue(String target) {
        return "# I1 — First issue\n\n"
                + "**What must be true.** A complete result.\n\n"
                + "## Sub-agents\n\n"
                + "| Name | Kind | Agent | Model | Brief |\n"
                + "|---|---|---|---|---|\n"
                + "| builder | builder | example-agent | example-model | [Brief]("
                + target
                + ") |\n";
    }
}
