package org.fuin.porelo.planning.storiesgit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanningAdapter;
import org.fuin.porelo.planning.api.PlanningMain;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.fuin.porelo.planning.api.StateChange;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * Reads stories and issues from owned clones. The monitor serializes clone creation and snapshot
 * reads; immutable answers escape it, and no token is retained between calls.
 */
public final class StoriesGit implements PlanningAdapter {
    private @Nullable Path directory;
    private @Nullable GitStore store;
    private @Nullable Settings configured;

    /** Creates an adapter whose storage is supplied by initialize. */
    public StoriesGit() {}

    /** Runs describe or serve through the Planning1 API. */
    public static void main(String[] args) {
        PlanningMain.run(new StoriesGit(), args);
    }

    /** Returns the installed executable's name. */
    @Override
    public String name() {
        return "stories-git";
    }

    /** Returns the version filtered by the build, without requiring project storage. */
    @Override
    public String version() {
        try (var input = StoriesGit.class.getResourceAsStream("version.properties")) {
            if (input == null) return "";
            final Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "");
        } catch (IOException ex) {
            return "";
        }
    }

    /** Declares complete issue snapshots and issue detail reads. */
    @Override
    public List<String> capabilities() {
        return List.of("issues");
    }

    /** Accepts the current call's installation token or ordinary token. */
    @Override
    public List<String> credentials() {
        return List.of("github-app", "token");
    }

    /** Keeps the existing project directory; creates storage only on the first data read. */
    @Override
    public synchronized void initialize(Path directory) {
        if (this.directory != null)
            throw Refusals.failed("Expected initialize once; got an already initialized adapter");
        try {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw Refusals.failed(
                        "Expected an existing project directory; got a link or non-directory");
            this.directory = directory.toRealPath();
        } catch (IOException ex) {
            throw Refusals.failed(
                    "Expected an accessible project directory; got an unreadable directory");
        }
    }

    /** Returns the file's ordered states and phases at the complete clone revision. */
    @Override
    public synchronized Result<Workflow> getWorkflow(Map<String, Object> settings, String token) {
        return read(
                settings,
                token,
                snapshot -> new Result<>(snapshot.workflow(), snapshot.revision()));
    }

    /** Returns stored states and all issue summaries; null selects all and an empty list none. */
    @Override
    public synchronized Result<List<StorySummary>> listStories(
            Map<String, Object> settings, String token, @Nullable List<String> states) {
        return read(
                settings,
                token,
                snapshot ->
                        new Result<>(
                                snapshot.stories().values().stream()
                                        .filter(
                                                file ->
                                                        states == null
                                                                || states.contains(
                                                                        file.story().state()))
                                        .map(
                                                file ->
                                                        new StorySummary(
                                                                file.story().id(),
                                                                file.story().title(),
                                                                file.story().state(),
                                                                file.story().link(),
                                                                snapshot.summaries(file),
                                                                file.story().priority()))
                                        .toList(),
                                snapshot.revision()));
    }

    /** Returns the stored story and gain and test text, refusing an unknown id. */
    @Override
    public synchronized Result<Story> getStory(
            Map<String, Object> settings, String token, String story) {
        return read(
                settings,
                token,
                snapshot -> new Result<>(snapshot.story(story).story(), snapshot.revision()));
    }

    /** Returns every issue of the story, including an empty list for an independent story. */
    @Override
    public synchronized Result<List<IssueSummary>> listStoryIssues(
            Map<String, Object> settings, String token, String story) {
        return read(
                settings,
                token,
                snapshot ->
                        new Result<>(
                                snapshot.summaries(snapshot.story(story)), snapshot.revision()));
    }

    /** Delivers acceptance text and only explicitly listed, contained brief files. */
    @Override
    public synchronized Result<Issue> getIssue(
            Map<String, Object> settings, String token, String issue) {
        return read(
                settings,
                token,
                snapshot -> {
                    Settings.id(issue);
                    final StoredIssue found = snapshot.issues().get(issue);
                    if (found == null)
                        throw new PlanningRefused(Reason.NO_SUCH_ISSUE, Map.of("issue", issue));
                    return new Result<>(
                            IssueIndex.full(found.entry(), found.files()), snapshot.revision());
                });
    }

    /** Refuses writes before store access because write support is not declared. */
    @Override
    public synchronized String setStates(
            Map<String, Object> settings,
            String token,
            List<StateChange> changes,
            String revision) {
        throw PlanningRefused.notSupported("write");
    }

    /** Refuses refresh because watch and fetch support is not declared. */
    @Override
    public synchronized String refresh(Map<String, Object> settings, String token) {
        throw PlanningRefused.notSupported("watch");
    }

    /** Refuses subscriptions because watch support is not declared. */
    @Override
    public synchronized Watch watchChanges(Map<String, Object> settings, String token) {
        throw PlanningRefused.notSupported("watch");
    }

    private <T> T read(Map<String, Object> values, String token, Function<Snapshot, T> operation) {
        try {
            final Settings settings = settings(values, token);
            return operation.apply(snapshot(settings, token));
        } catch (PlanningRefused refused) {
            throw Refusals.safe(refused, token);
        }
    }

    private Settings settings(Map<String, Object> values, String token) {
        final Settings settings = Settings.read(values, token);
        if (configured != null && !configured.equals(settings))
            throw Refusals.invalid(
                    "Expected unchanged settings for this project directory; got a different store"
                            + " configuration");
        if (directory == null)
            throw Refusals.failed(
                    "Expected initialize before a data call; got no project directory");
        if (store == null) store = new GitStore(directory);
        configured = settings;
        store.begin();
        return settings;
    }

    private SafeFiles planning(Settings settings, String token) {
        return new SafeFiles(
                java.util.Objects.requireNonNull(store)
                        .clone(settings.planningKey(), settings.repository(), token));
    }

    private Workflow workflow(SafeFiles files, Settings settings) {
        final String name = settings.storiesDirectory() + "/workflow.yml";
        return WorkflowFile.parse(
                files.optional(name)
                        .orElseThrow(
                                () ->
                                        new PlanningRefused(
                                                Reason.WORKFLOW_MISSING,
                                                Map.of(
                                                        "detail",
                                                        "Expected workflow "
                                                                + name
                                                                + "; got a missing file"))));
    }

    private Snapshot snapshot(Settings settings, String token) {
        final SafeFiles planning = planning(settings, token);
        final Workflow workflow = workflow(planning, settings);
        final Map<String, StoryFile> stories = new LinkedHashMap<>();
        final List<String> storyPaths =
                planning.optional(settings.storiesDirectory() + "/README.md")
                        .map(
                                index ->
                                        StoryIndex.files(index).stream()
                                                .map(
                                                        path ->
                                                                settings.storiesDirectory()
                                                                        + "/"
                                                                        + path)
                                                .toList())
                        .orElseGet(() -> planning.markdown(settings.storiesDirectory()));
        for (final String path : storyPaths) {
            if (Path.of(path).getFileName().toString().equals("README.md")) continue;
            final String content = planning.read(path);
            if (Markdown.section(content, "Data").isEmpty())
                throw Refusals.failed(
                        "Expected story Data JSON in " + path + "; got no Data section");
            final StoryFile file =
                    StoryFile.parse(content, settings.link(settings.planningKey(), path), workflow);
            final String id = file.story().id();
            if (!Path.of(path)
                    .getFileName()
                    .toString()
                    .matches(java.util.regex.Pattern.quote(id) + "(?:-.*)?\\.md"))
                throw Refusals.failed("Expected story filename matching " + id + "; got " + path);
            if (stories.putIfAbsent(id, file) != null)
                throw Refusals.failed("Expected unique story " + id + "; got multiple files");
        }
        final Map<String, SafeFiles> repositories = new LinkedHashMap<>();
        final Map<String, StoredIssue> issues = new LinkedHashMap<>();
        for (final StoryFile story : stories.values()) {
            for (final StoryFile.Reference reference : story.issues()) {
                if (!repositories.containsKey(reference.repository())) {
                    final String key = reference.repository();
                    final SafeFiles files =
                            new SafeFiles(
                                    java.util.Objects.requireNonNull(store)
                                            .clone(key, settings.cloneUrl(key), token));
                    repositories.put(key, files);
                    IssueIndex.read(files, key, settings, workflow)
                            .forEach(
                                    (id, entry) -> {
                                        if (issues.putIfAbsent(id, new StoredIssue(entry, files))
                                                != null)
                                            throw Refusals.failed(
                                                    "Expected globally unique issue "
                                                            + id
                                                            + "; got the same id in multiple"
                                                            + " repositories");
                                    });
                }
                final StoredIssue issue = issues.get(reference.id());
                if (issue == null
                        || !issue.entry().summary().repository().equals(reference.repository()))
                    throw new PlanningRefused(
                            Reason.NO_SUCH_ISSUE, Map.of("issue", reference.id()));
            }
        }
        return new Snapshot(
                workflow,
                java.util.Collections.unmodifiableMap(stories),
                Map.copyOf(issues),
                java.util.Objects.requireNonNull(store).revision());
    }

    private record StoredIssue(IssueIndex.Entry entry, SafeFiles files) {}

    private record Snapshot(
            Workflow workflow,
            Map<String, StoryFile> stories,
            Map<String, StoredIssue> issues,
            String revision) {
        StoryFile story(String id) {
            Settings.id(id);
            final StoryFile file = stories.get(id);
            if (file == null) throw new PlanningRefused(Reason.NO_SUCH_STORY, Map.of("story", id));
            return file;
        }

        List<IssueSummary> summaries(StoryFile story) {
            final List<IssueSummary> summaries = new ArrayList<>();
            for (final StoryFile.Reference reference : story.issues()) {
                final var issue = issues.get(reference.id());
                // A story naming an issue no index holds is a broken store, never a shorter list.
                if (issue == null)
                    throw Refusals.failed(
                            "story "
                                    + story.story().id()
                                    + " names "
                                    + reference.id()
                                    + ", which no issue index holds");
                summaries.add(issue.entry().summary());
            }
            return List.copyOf(summaries);
        }
    }
}
