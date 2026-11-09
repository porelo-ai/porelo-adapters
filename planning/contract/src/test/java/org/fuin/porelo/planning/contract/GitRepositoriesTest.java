package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoriesTest {
    @TempDir Path directory;

    @Test
    void changesBehindTheAdapterAreReadFromTheBareRemote() throws Exception {
        // Reading a cached clone could let stale revision and refresh guards pass without a change.
        try (GitRepositories repositories = new GitRepositories(directory.resolve("forge"))) {
            final Path bare =
                    repositories.create(
                            "stories.git",
                            Map.of(
                                    "stories/first.md",
                                    "Ready\n",
                                    "workflow.yml",
                                    "states: [Ready, Done]\n"));
            final String before = repositories.head("stories.git");
            final String after =
                    repositories.commit("stories.git", Map.of("stories/first.md", "Done\n"));

            assertThat(bare.resolve("HEAD"))
                    .as("the server receives a real bare repository")
                    .exists();
            assertThat(after)
                    .as("the behind-the-adapter change advances main")
                    .isNotEqualTo(before);
            assertThat(repositories.head("stories.git"))
                    .as("head reads the pushed remote ref")
                    .isEqualTo(after);
            assertThat(repositories.file("stories.git", "stories/first.md"))
                    .as("file reads the changed remote object")
                    .isEqualTo("Done\n");
            assertThat(repositories.file("stories.git", "workflow.yml"))
                    .as("a replacement preserves unrelated files")
                    .isEqualTo("states: [Ready, Done]\n");
        }
    }

    @Test
    void escapingAndGitMetadataPathsCannotWriteOutsideTheFixture() throws Exception {
        // An unsafe seed path could overwrite a host file or install executable Git configuration.
        final Path root = directory.resolve("forge");
        try (GitRepositories repositories = new GitRepositories(root)) {
            for (final String path :
                    new String[] {
                        "../outside",
                        ".git/config",
                        "nested/../../outside",
                        directory.resolve("outside").toString()
                    }) {
                assertThatThrownBy(() -> repositories.create("stories.git", Map.of(path, "damage")))
                        .as("unsafe fixture paths are refused before Git or file writes")
                        .isInstanceOf(IOException.class);
            }
            assertThat(Files.exists(directory.resolve("outside")))
                    .as("no seed escapes the root")
                    .isFalse();
            assertThat(Files.exists(root.resolve("stories.git")))
                    .as("invalid seeds create no partial bare repository")
                    .isFalse();
            repositories.create("stories.git", Map.of("story.md", "Ready\n"));
            assertThatThrownBy(() -> repositories.file("stories.git", "missing.md"))
                    .as("a missing remote file fails with the Git command and its output")
                    .isInstanceOf(IOException.class)
                    .hasMessageContainingAll("show", "missing.md", "got 128", "fatal:");
        }
    }
}
