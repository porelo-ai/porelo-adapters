package org.fuin.porelo.planning.storiesgit;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.Workflow;
import org.fuin.porelo.planning.contract.Board;
import org.fuin.porelo.planning.contract.Fixture;
import org.fuin.porelo.planning.contract.PlanningContract;

/** The contract suite over stories-git, started as the class or as the native executable. */
abstract class StoriesGitContract extends PlanningContract {
    StoriesGitContract(Class<?> main) {
        super(main);
    }

    @Override
    protected Fixture fixture(Path root) throws Exception {
        return new StoriesGitFixture(root);
    }

    @Override
    protected Board board() {
        final Board source = Board.standard();
        final Workflow workflow =
                new Workflow(
                        source.workflow().states().stream()
                                .map(
                                        state ->
                                                new PlanState(
                                                        state.name(),
                                                        state.kind() == PlanState.Kind.CLOSED
                                                                ? PlanState.Kind.CLOSED
                                                                : PlanState.Kind.WAITING))
                                .toList(),
                        source.workflow().phases());
        final List<Story> stories =
                source.stories().stream()
                        .map(
                                story ->
                                        new Story(
                                                story.id(),
                                                story.title(),
                                                story.state(),
                                                "https://example.org/planning/stories/"
                                                        + story.id()
                                                        + "-Story.md",
                                                story.description()))
                        .toList();
        final Map<String, List<Issue>> issues = new LinkedHashMap<>();
        source.issues()
                .forEach(
                        (story, entries) ->
                                issues.put(
                                        story,
                                        entries.stream()
                                                .map(
                                                        issue ->
                                                                new Issue(
                                                                        issue.id(),
                                                                        issue.repository(),
                                                                        issue.title(),
                                                                        issue.state(),
                                                                        issue.size(),
                                                                        issue.blockedBy(),
                                                                        "https://example.org/"
                                                                                + issue.repository()
                                                                                + "/issues/"
                                                                                + issue.id()
                                                                                + "-Issue.md",
                                                                        issue.whatMustBeTrue(),
                                                                        issue.subAgents()))
                                                .toList()));
        return new Board(workflow, stories, issues);
    }
}
