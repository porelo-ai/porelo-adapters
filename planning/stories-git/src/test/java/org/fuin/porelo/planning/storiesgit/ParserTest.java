package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.fuin.porelo.planning.api.PlanPhase;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.fuin.porelo.planning.api.Workflow;
import org.junit.jupiter.api.Test;

class ParserTest {
    private static final String FIRST_STORY =
            "ST11-Porelo-Adapters-And-Porelo-Ai-Github-Io-Each.md";
    private static final String SECOND_STORY = "ST294-Plan-The-Stories-On-A-Board-In-The-Window.md";
    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of(
                            new PlanState("Idea", PlanState.Kind.WAITING),
                            new PlanState("Accepted", PlanState.Kind.WAITING)),
                    List.of());

    // Red first. Harm: answering an unknown stored state can authorize work with invented
    // semantics.
    @Test
    void refusesAStoryWhoseStateTheWorkflowLacks() throws Exception {
        final Workflow withoutIdea =
                new Workflow(List.of(new PlanState("Accepted", PlanState.Kind.WAITING)), List.of());
        final Throwable failure =
                catchThrowable(() -> StoryFile.parse(sample(FIRST_STORY), "", withoutIdea));
        assertThat(failure)
                .as("a story whose state is absent must be refused, never answered")
                .isInstanceOf(PlanningRefused.class);
        final PlanningRefused refused = (PlanningRefused) failure;
        assertThat(refused.reason())
                .as("unknown stored states use Failed")
                .isEqualTo(Reason.FAILED);
        assertThat(refused.parameters().get("message"))
                .as("the refusal identifies the actual story and stored state")
                .asString()
                .contains("ST11", "Idea");
    }

    // Harm: wrong reference parsing associates work with another repository or issue.
    @Test
    void readsTheRealStoryWithAllItsRepositoryReferences() throws Exception {
        final StoryFile file =
                StoryFile.parse(sample(FIRST_STORY), "https://example.org/story", WORKFLOW);
        assertThat(file.story().id()).as("the sample's Data id is authoritative").isEqualTo("ST11");
        assertThat(file.story().title())
                .as("the complete sample title survives")
                .isEqualTo("porelo-adapters and porelo-ai.github.io each in its own task");
        assertThat(file.story().state()).as("the stored state is preserved").isEqualTo("Idea");
        assertThat(file.priority()).as("the real priority is read from Data").isEqualTo("Highest");
        assertThat(file.issues())
                .as("every real repository reference survives unchanged")
                .containsExactly(
                        new StoryFile.Reference("porelo-adapters", "GH11"),
                        new StoryFile.Reference("porelo-ai.github.io", "SI05"),
                        new StoryFile.Reference("porelo-ai.github.io", "SI06"));
        assertThat(file.story().description())
                .as("both complete gain and test paragraphs are delivered")
                .isEqualTo(
                        "The adapters repository and the project website can each be developed in"
                                + " their own task on the host and reach Review with a green build on"
                                + " the host's forge.\n\n"
                                + "Each move issue reaches Review inside its task with Forgejo's build"
                                + " green.");
    }

    // Harm: a fabricated issue for an independent story changes whether its state is writable.
    @Test
    void readsTheRealIndependentStoryWithoutInventingIssues() throws Exception {
        final StoryFile file = StoryFile.parse(sample(SECOND_STORY), "", WORKFLOW);
        assertThat(file.story().id()).as("the second sample's Data id").isEqualTo("ST294");
        assertThat(file.story().state())
                .as("the second sample's stored state")
                .isEqualTo("Accepted");
        assertThat(file.priority()).as("the second sample's Data priority").isEqualTo("High");
        assertThat(file.issues()).as("the real empty references array stays empty").isEmpty();
        assertThat(file.story().description())
                .as("the full test text is retained")
                .endsWith("Rename a state in the configuration and the board shows it.");
    }

    // Harm: dropping or adding indexed stories silently changes the board's reference set.
    @Test
    void readsEveryRealStoryIndexEntryExactlyOnce() throws Exception {
        final List<String> paths = StoryIndex.files(sample("stories-README.md"));
        assertThat(paths.stream().map(path -> path.substring(0, path.indexOf('-'))).toList())
                .as("the real index contains exactly the complete numbered story set")
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, 294).mapToObj(number -> "ST" + number).toList());
        assertThat(paths)
                .as("both unmodified story samples are named by the real index")
                .contains(FIRST_STORY, SECOND_STORY);
    }

    // Harm: assigning the wrong group or dependency can start a blocked issue.
    @Test
    void readsTheRealIssueIndexGroupsSizesAndBlockingLinks() throws Exception {
        final var entries = IssueIndex.parse(sample("issues-README.md"));
        assertThat(entries.stream().map(entry -> entry.summary().id()).toList())
                .as("the complete real issue reference set, including closed entries")
                .containsExactly(
                        "GH18", "GH19", "GH20", "GH21", "GH22", "GH02", "GH11", "GH09", "GH13",
                        "GH17", "GH16", "GH14", "GH10", "GH15", "GH05", "GH12", "GH08", "GH06",
                        "GH07", "GH03", "GH01", "GH04");
        final var row =
                entries.stream()
                        .filter(entry -> entry.summary().id().equals("GH11"))
                        .findFirst()
                        .orElseThrow();
        assertThat(row.summary().state())
                .as("the issue's state comes from its actual index group")
                .isEqualTo("Analyzed");
        assertThat(row.summary().size()).as("the sample issue's indexed size").isEqualTo("S");
        final var blocked =
                entries.stream()
                        .filter(entry -> entry.summary().id().equals("GH02"))
                        .findFirst()
                        .orElseThrow();
        assertThat(blocked.summary().blockedBy())
                .as("all blocking link ids survive")
                .containsExactly("GH17", "GH09");
    }

    // Harm: incomplete acceptance text can cause the wrong scope of work to be executed.
    @Test
    void readsTheRealIssueHeadingAndCompleteAcceptanceParagraph() throws Exception {
        final String issue = sample("GH11-The-Adapters-Agent-Works-In-A-Porelo-Task.md");
        assertThat(Markdown.title(issue, "GH11"))
                .as("the title comes from the actual first heading")
                .contains("The Adapters' Agent Works In A Porelo Task");
        assertThat(Markdown.acceptance(issue))
                .as("the real paragraph includes all wrapped lines and no following section")
                .isEqualTo(
                        "porelo-adapters' agent works in a Porelo task on the move's host, as the"
                                + " project's procedure for an agent's\n"
                                + "move lays out, and takes this issue to\n"
                                + "Review inside the task with the host's Forgejo build green. It talks"
                                + " in the project's room as `Agent Adapters`, from\n"
                                + "the nickname `Adapters` that porelo-project's `project.yml` is to"
                                + " give the repository `github`.\n");
    }

    // Harm: invented working states or reordered phases can authorize work in the wrong column.
    @Test
    void readsWorkflowOrderAndPhasesWithoutInventingWorkingStates() {
        final Workflow workflow =
                WorkflowFile.parse(
                        "states:\n"
                                + "  - name: Queued\n"
                                + "    closed: false\n"
                                + "    measured: true\n"
                                + "  - {name: 'Building renamed', closed: false}\n"
                                + "  - name: Closed\n"
                                + "    closed: true\n"
                                + "phases:\n"
                                + "  - name: Before work\n"
                                + "    states: [Queued]\n"
                                + "  - name: Work\n"
                                + "    states:\n"
                                + "      - Building renamed\n"
                                + "  - name: After work\n"
                                + "    states: [Closed]\n");
        assertThat(workflow.states())
                .as("closed is the only kind marker in the file format")
                .containsExactly(
                        new PlanState("Queued", PlanState.Kind.WAITING),
                        new PlanState("Building renamed", PlanState.Kind.WAITING),
                        new PlanState("Closed", PlanState.Kind.CLOSED));
        assertThat(workflow.phases())
                .as("phase names, member names and order are preserved")
                .containsExactly(
                        new PlanPhase("Before work", List.of("Queued")),
                        new PlanPhase("Work", List.of("Building renamed")),
                        new PlanPhase("After work", List.of("Closed")));
    }

    // Harm: malformed booleans or aliases can silently create unintended workflow states.
    @Test
    void refusesMalformedOrAmbiguousWorkflows() {
        for (final String text :
                List.of(
                        "states:\n  - name: Queued\n    closed: perhaps\n",
                        "states:\n"
                                + "  - name: Queued\n"
                                + "    closed: false\n"
                                + "  - name: Queued\n"
                                + "    closed: true\n",
                        "states:\n  - name: *alias\n    closed: false\n",
                        "states:\n"
                                + "  - name: Queued\n"
                                + "    closed: false\n"
                                + "phases:\n"
                                + "  - name: Phase\n"
                                + "    states: [Unknown]\n")) {
            assertThat(catchThrowable(() -> WorkflowFile.parse(text)))
                    .as("malformed workflow is refused instead of assigning guessed semantics")
                    .isInstanceOf(PlanningRefused.class);
        }
    }

    static String sample(String name) throws IOException {
        try (var input = ParserTest.class.getResourceAsStream("/samples/" + name)) {
            if (input == null)
                throw new IOException("Expected sample " + name + "; got a missing resource");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
