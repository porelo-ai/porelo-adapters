package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotSafetyTest {
    private static final String URL = "https://example.org/project";
    @TempDir Path root;

    // Harm: planning and issue clones with the same key must not collide on valid URL spellings.
    @Test
    void separatesPlanningFromTheSameRepositorysIssueClone() throws Exception {
        final Path directory = prepare(List.of("project#I1"), true, URL);
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(directory);
        try {
            assertThat(adapter.listStoryIssues(settings("stories", URL), "", "S1").value())
                    .as("the planning and issue repositories may have the same key")
                    .extracting(issue -> issue.id())
                    .containsExactly("I1");
        } finally {
            close(adapter);
        }
    }

    // Harm: a failed initial read must not permanently freeze an invalid configuration.
    @Test
    void acceptsCorrectedSettingsAfterAFailedRead() throws Exception {
        final Path directory = prepare(List.of(), false, URL);
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(directory);
        try {
            assertThat(catchThrowable(() -> adapter.getWorkflow(settings("missing", URL), "")))
                    .as("the first read observes the missing workflow")
                    .isInstanceOf(PlanningRefused.class);
            assertThat(adapter.listStories(settings("stories", URL), "", null).value())
                    .as("correcting the directory recovers without restarting")
                    .extracting(story -> story.id())
                    .containsExactly("S1");
        } finally {
            close(adapter);
        }
    }

    // Harm: a URL typo rejected by origin validation must not poison later valid settings.
    @Test
    void acceptsTheCorrectOriginAfterARejectedUrl() throws Exception {
        final Path directory = prepare(List.of(), false, URL);
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(directory);
        try {
            assertThat(
                            catchThrowable(
                                    () ->
                                            adapter.getWorkflow(
                                                    settings(
                                                            "stories",
                                                            "https://other.example.org/project"),
                                                    "")))
                    .as("an existing clone refuses an unrelated origin")
                    .isInstanceOf(PlanningRefused.class);
            assertThat(adapter.listStories(settings("stories", URL), "", null).value())
                    .as("the correct origin remains usable after a refused call")
                    .extracting(story -> story.id())
                    .containsExactly("S1");
        } finally {
            close(adapter);
        }
    }

    // Harm: changing presentation settings must not make a valid stored snapshot unreadable.
    @Test
    void acceptsNewLinkPatternsWithoutReplacingClones() throws Exception {
        final Path directory = prepare(List.of(), false, URL);
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(directory);
        try {
            adapter.getWorkflow(settings("stories", URL), "");
            final var changed = new java.util.LinkedHashMap<>(settings("stories", URL));
            changed.put("linkPattern", "https://example.org/new/{repository}/{path}");
            assertThat(adapter.getStory(changed, "", "S1").value().link())
                    .as("links follow the current call's settings")
                    .isEqualTo("https://example.org/new/project/stories/S1-Work.md");
        } finally {
            close(adapter);
        }
    }

    // Harm: a broken story reference must be reported as corrupt storage, with its source.
    @Test
    void namesTheStoryAndRepositoryForADanglingStoredReference() throws Exception {
        final Path directory = prepare(List.of("project#I2"), true, URL + ".git");
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(directory);
        try {
            final Throwable failure =
                    catchThrowable(
                            () -> adapter.getWorkflow(settings("stories", URL + ".git"), ""));
            assertThat(failure)
                    .as("broken references are refused")
                    .isInstanceOf(PlanningRefused.class);
            assertThat(((PlanningRefused) failure).reason())
                    .as("NoSuchIssue is reserved for caller-requested IDs")
                    .isEqualTo(Reason.FAILED);
            assertThat(((PlanningRefused) failure).parameters().toString())
                    .as("the refusal identifies the broken reference and its story")
                    .contains("S1", "project#I2");
        } finally {
            close(adapter);
        }
    }

    private Path prepare(List<String> references, boolean issues, String planningUrl)
            throws Exception {
        final Path source = Files.createDirectory(root.resolve("source"));
        Files.createDirectory(source.resolve("stories"));
        Files.writeString(
                source.resolve("stories/workflow.yml"),
                "states:\n  - name: Queued\n    closed: false\nphases: []\n");
        Files.writeString(
                source.resolve("stories/S1-Work.md"),
                "# S1 — Work\n\n## Data\n\n```json\n"
                        + org.fuin.porelo.wire.Json.write(
                                Map.of(
                                        "id",
                                        "S1",
                                        "title",
                                        "Work",
                                        "state",
                                        "Queued",
                                        "gain",
                                        "Complete work",
                                        "test",
                                        "",
                                        "issues",
                                        references))
                        + "\n```\n");
        Files.createDirectory(source.resolve("issues"));
        Files.writeString(
                source.resolve("issues/README.md"),
                "## Queued\n\n| # | Size | Blocked by | Title |\n|---|---|---|---|\n"
                        + "| <a id=\"i1\"></a>I1 | S | - | Work |\n");
        git(source, "init", "--quiet");
        git(source, "add", "stories", "issues");
        git(
                source,
                "-c",
                "user.name=Example",
                "-c",
                "user.email=example@example.org",
                "commit",
                "--quiet",
                "-m",
                "Fixture");
        final Path bare = root.resolve("source.git");
        git(root, "clone", "--quiet", "--bare", source.toString(), bare.toString());
        final Path directory = Files.createDirectory(root.resolve("adapter"));
        Files.createDirectory(directory.resolve("repositories"));
        clone(bare, directory.resolve("planning"), planningUrl);
        clone(
                bare,
                directory.resolve("repositories/project.git-or-worktree"),
                issues ? URL + ".git" : planningUrl);
        return directory;
    }

    private void clone(Path bare, Path target, String url) throws Exception {
        git(root, "clone", "--quiet", "--no-hardlinks", bare.toString(), target.toString());
        git(target, "remote", "set-url", "origin", url);
    }

    private static Map<String, Object> settings(String stories, String url) {
        return Map.of(
                "repository",
                url,
                "storiesDirectory",
                stories,
                "issueRepositoryPattern",
                URL.replace("project", "{repository}.git"),
                "linkPattern",
                "https://example.org/{repository}/{path}");
    }

    private static void git(Path directory, String... args) throws Exception {
        final List<String> command = new ArrayList<>(List.of("/usr/bin/git"));
        command.addAll(List.of(args));
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS))
                    .as("local fixture Git finishes")
                    .isTrue();
            assertThat(process.exitValue()).as("local fixture Git succeeds").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private static void close(Object value) throws Exception {
        if (value instanceof AutoCloseable closeable) closeable.close();
    }
}
