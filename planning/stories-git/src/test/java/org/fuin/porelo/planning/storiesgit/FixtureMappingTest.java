package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.StorySummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureMappingTest {
    @TempDir Path root;

    // Harm: a fixture that writes a different format can make the published contract pass falsely.
    @Test
    void mapsTheCompleteContractBoardToTheRealFileFormats() throws Exception {
        final var board = new StoriesGitContractTest().board();
        final var encoded = StoriesGitFixture.encode(board);
        final Settings settings =
                Settings.read(
                        Map.of(
                                "repository",
                                "https://example.org/planning.git",
                                "issueRepositoryPattern",
                                "https://example.org/{repository}.git",
                                "linkPattern",
                                "https://example.org/{repository}/{path}"),
                        "");
        for (final var repository : encoded.entrySet()) {
            final Path clone = Files.createDirectory(root.resolve(repository.getKey()));
            for (final var file : repository.getValue().entrySet()) {
                final Path path = clone.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
        }
        final SafeFiles planning = new SafeFiles(root.resolve("planning.git"));
        final var workflow = WorkflowFile.parse(planning.read("stories/workflow.yml"));
        assertThat(workflow)
                .as("the encoded workflow represents exactly the suite's board")
                .isEqualTo(board.workflow());
        final List<String> storyPaths = StoryIndex.files(planning.read("stories/README.md"));
        assertThat(storyPaths)
                .as("the fixture index names precisely the two scenarios")
                .containsExactly("S1-Story.md", "S2-Story.md");
        for (final var expected : board.stories()) {
            final String path = "stories/" + expected.id() + "-Story.md";
            final var file =
                    StoryFile.parse(planning.read(path), settings.link("planning", path), workflow);
            assertThat(file.story())
                    .as("the real story format preserves every field the contract asserts")
                    .isEqualTo(expected);
            final var actualIssues = new java.util.ArrayList<Issue>();
            for (final var reference : file.issues()) {
                final SafeFiles repository =
                        new SafeFiles(root.resolve(reference.repository() + ".git"));
                final var entry =
                        IssueIndex.read(repository, reference.repository(), settings, workflow)
                                .get(reference.id());
                actualIssues.add(IssueIndex.full(entry, repository));
            }
            assertThat(actualIssues)
                    .as(
                            "the real index, issue files and brief tables preserve the full"
                                    + " expected issues")
                    .containsExactlyElementsOf(board.issues().get(expected.id()));
            assertThat(
                            new StorySummary(
                                    expected.id(),
                                    expected.title(),
                                    expected.state(),
                                    expected.link(),
                                    actualIssues.stream().map(Issue::summary).toList(),
                                    file.story().priority()))
                    .as("the mapping preserves complete listing snapshots")
                    .isEqualTo(
                            board.summaries().stream()
                                    .filter(story -> story.id().equals(expected.id()))
                                    .findFirst()
                                    .orElseThrow());
        }
    }
}
