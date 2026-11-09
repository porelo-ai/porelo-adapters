package org.fuin.porelo.planning.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.PlanPhase;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.SubAgent;
import org.fuin.porelo.planning.api.Workflow;

/** Store-independent input to a fixture; stored story states are independent of issue states. */
public record Board(Workflow workflow, List<Story> stories, Map<String, List<Issue>> issues) {
    /** Copies the board so fixture mutations cannot change the suite's expectations. */
    public Board {
        stories = List.copyOf(stories);
        final Map<String, List<Issue>> copy = new LinkedHashMap<>();
        issues.forEach((id, entries) -> copy.put(id, List.copyOf(entries)));
        issues = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the suite's board, including an empty story, two repositories and complete briefs.
     */
    public static Board standard() {
        final Workflow workflow =
                new Workflow(
                        List.of(
                                new PlanState("Queued", PlanState.Kind.WAITING),
                                new PlanState("Building renamed", PlanState.Kind.WORKING),
                                new PlanState("Closed", PlanState.Kind.CLOSED),
                                new PlanState("Paused", PlanState.Kind.OTHER)),
                        List.of(
                                new PlanPhase("Before work", List.of("Queued")),
                                new PlanPhase("Work", List.of("Building renamed", "Paused")),
                                new PlanPhase("After work", List.of("Closed"))));
        final Issue first =
                new Issue(
                        "I1",
                        "one",
                        "First issue",
                        "Queued",
                        "M",
                        List.of(),
                        "https://example.org/one/I1",
                        "The complete result survives a restart.\n",
                        List.of(
                                new SubAgent(
                                        "builder",
                                        SubAgent.Kind.BUILDER,
                                        "example-agent",
                                        "example-model",
                                        "# Build\n\n"
                                                + "Preserve every line.\n\n"
                                                + "```text\n"
                                                + "last line\n"
                                                + "```\n"),
                                new SubAgent(
                                        "reader",
                                        SubAgent.Kind.READER,
                                        "example-agent",
                                        "example-model",
                                        "# Read\n\nCheck the failure paths.\n")));
        final Issue second =
                new Issue(
                        "I2",
                        "two",
                        "Second issue",
                        "Queued",
                        "S",
                        List.of("I1"),
                        "https://example.org/two/I2",
                        "A refused change writes nothing.\n",
                        List.of());
        return new Board(
                workflow,
                List.of(
                        new Story(
                                "S1",
                                "Story with issues",
                                "",
                                "https://example.org/S1",
                                "Stored description.\n"),
                        new Story(
                                "S2",
                                "Independent story",
                                "Queued",
                                "https://example.org/S2",
                                "No issues.\n")),
                Map.of("S1", List.of(first, second), "S2", List.of()));
    }

    /** Returns the issue with the requested id, refusing a malformed fixture request. */
    public Issue issue(String id) {
        return issues.values().stream()
                .flatMap(List::stream)
                .filter(i -> i.id().equals(id))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Expected seeded issue; got " + id));
    }

    /** Returns listing expectations with the complete issue snapshot. */
    public List<StorySummary> summaries() {
        return stories.stream()
                .map(
                        s ->
                                new StorySummary(
                                        s.id(),
                                        s.title(),
                                        s.state(),
                                        s.link(),
                                        issues.getOrDefault(s.id(), List.of()).stream()
                                                .map(Issue::summary)
                                                .toList()))
                .toList();
    }

    /** Returns a new board with one issue's stored state changed. */
    public Board withIssueState(String id, String state) {
        issue(id);
        final Map<String, List<Issue>> changed = new LinkedHashMap<>();
        issues.forEach(
                (story, entries) ->
                        changed.put(
                                story,
                                entries.stream()
                                        .map(
                                                i ->
                                                        i.id().equals(id)
                                                                ? new Issue(
                                                                        i.id(),
                                                                        i.repository(),
                                                                        i.title(),
                                                                        state,
                                                                        i.size(),
                                                                        i.blockedBy(),
                                                                        i.link(),
                                                                        i.whatMustBeTrue(),
                                                                        i.subAgents())
                                                                : i)
                                        .toList()));
        return new Board(workflow, stories, changed);
    }

    /** Returns a new board with one story's stored state changed. */
    public Board withStoryState(String id, String state) {
        if (stories.stream().noneMatch(s -> s.id().equals(id)))
            throw new IllegalArgumentException("Expected seeded story; got " + id);
        return new Board(
                workflow,
                stories.stream()
                        .map(
                                s ->
                                        s.id().equals(id)
                                                ? new Story(
                                                        s.id(),
                                                        s.title(),
                                                        state,
                                                        s.link(),
                                                        s.description())
                                                : s)
                        .toList(),
                issues);
    }
}
