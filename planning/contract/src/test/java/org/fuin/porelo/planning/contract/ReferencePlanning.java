package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planning.api.Change;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanningAdapter;
import org.fuin.porelo.planning.api.PlanningMain;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.fuin.porelo.planning.api.StateChange;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.planning.api.SubAgent;
import org.fuin.porelo.planning.api.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * Test-only, single-file store; its monitor protects the cache, writes and subscriber registration.
 */
public final class ReferencePlanning implements PlanningAdapter {
    private final String fault = System.getProperty("planning.contract.fault", "");
    private final Set<Subscription> subscriptions = new HashSet<>();
    private Path directory;
    private Map<String, Object> cached;

    /** Starts the test adapter through exactly the same API server as an installed adapter. */
    public static void main(String[] args) {
        PlanningMain.run(new ReferencePlanning(), args);
    }

    /** Returns this test executable's name. */
    @Override
    public String name() {
        return "reference-planning";
    }

    /** Returns the test-only version. */
    @Override
    public String version() {
        return "test";
    }

    /** Declares the capabilities proved by the reference contract. */
    @Override
    public List<String> capabilities() {
        return List.of("write", "watch", "issues");
    }

    /** Declares per-call tokens as the only accepted credential kind. */
    @Override
    public List<String> credentials() {
        return List.of("token");
    }

    /** Keeps only the caller's durable directory. */
    @Override
    public void initialize(Path directory) {
        this.directory = directory;
    }

    private void context(Map<String, Object> settings, String token) {
        if (!"token".equals(settings.get("credentialKind")))
            throw PlanningRefused.notSupported("credentialKind");
        if (token.startsWith("refused-"))
            throw new PlanningRefused(
                    Reason.CREDENTIAL_REFUSED,
                    Map.of(
                            "detail",
                            fault.equals("echo-token")
                                    ? token
                                    : "The supplied credential was refused",
                            "retryAfter",
                            0));
        try {
            final Path accesses = directory.resolve("accesses");
            Files.writeString(
                    accesses, Long.toString(Long.parseLong(Files.readString(accesses)) + 1));
            if (cached == null) cached = FileBoard.read(directory);
        } catch (IOException ex) {
            throw failed("Cannot read the fixture board");
        }
    }

    /** Returns the cached ordered workflow without fetching. */
    @Override
    public synchronized Result<Workflow> getWorkflow(Map<String, Object> settings, String token) {
        context(settings, token);
        return new Result<>(Workflow.of((Map<?, ?>) cached.get("workflow")), revision());
    }

    /** Returns stored story states and complete issue summaries at the cache revision. */
    @Override
    public synchronized Result<List<StorySummary>> listStories(
            Map<String, Object> settings, String token, @Nullable List<String> states) {
        context(settings, token);
        return new Result<>(
                FileBoard.stories(cached).stream()
                        .map(Story::of)
                        .filter(s -> states == null || states.contains(s.state()))
                        .map(
                                s ->
                                        new StorySummary(
                                                s.id(),
                                                s.title(),
                                                s.state(),
                                                s.link(),
                                                summaries(s.id())))
                        .toList(),
                revision());
    }

    /** Returns the complete stored story or its named refusal. */
    @Override
    public synchronized Result<Story> getStory(
            Map<String, Object> settings, String token, String story) {
        context(settings, token);
        return new Result<>(Story.of(story(story)), revision());
    }

    /** Returns complete issue summaries for the named story. */
    @Override
    public synchronized Result<List<IssueSummary>> listStoryIssues(
            Map<String, Object> settings, String token, String story) {
        context(settings, token);
        story(story);
        return new Result<>(summaries(story), revision());
    }

    /** Delivers only listed briefs after checking every path before reading any of them. */
    @Override
    public synchronized Result<Issue> getIssue(
            Map<String, Object> settings, String token, String issue) {
        context(settings, token);
        final var stored = issue(issue);
        final Map<String, Object> full = new LinkedHashMap<>(stored);
        final List<Map<String, Object>> proposals = FileBoard.agents(stored);
        final List<Path> paths =
                proposals.stream()
                        .map(agent -> briefPath(issue, (String) agent.get("brief")))
                        .toList();
        final List<SubAgent> agents = new ArrayList<>();
        for (int index = 0; index < proposals.size(); index++) {
            final Map<String, Object> agent = new LinkedHashMap<>(proposals.get(index));
            try {
                agent.put("brief", Files.readString(paths.get(index)));
            } catch (IOException ex) {
                throw failed("Cannot read listed brief " + paths.get(index).getFileName());
            }
            agents.add(SubAgent.of(agent));
        }
        if (fault.equals("unlisted-brief")) {
            final Path extra = directory.resolve(issue + ".sub/unlisted.md");
            if (Files.exists(extra)) {
                try {
                    agents.add(
                            new SubAgent(
                                    "unlisted",
                                    SubAgent.Kind.BUILDER,
                                    "example-agent",
                                    "example-model",
                                    Files.readString(extra)));
                } catch (IOException ex) {
                    throw failed("Cannot read unlisted brief");
                }
            }
        }
        full.put("subAgents", agents.stream().map(SubAgent::asMap).toList());
        return new Result<>(Issue.of(full), revision());
    }

    private Path briefPath(String issue, String name) {
        final Path relative = Path.of(name);
        if (relative.isAbsolute()
                || relative.getNameCount() != 2
                || !relative.getName(0).toString().equals(issue + ".sub")
                || relative.getFileName().toString().equals(".."))
            throw failed("Unsafe listed brief " + name);
        final Path path = directory.resolve(relative);
        if (Files.isSymbolicLink(path.getParent())
                || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw failed("Missing or unsafe listed brief " + name);
        return path;
    }

    /** Validates the revision and refuses independently writing a story with issues. */
    @Override
    public synchronized String setStoryState(
            Map<String, Object> settings, String token, String story, String to, String revision) {
        context(settings, token);
        checkRevision(revision);
        final var entry = story(story);
        if (!FileBoard.issues(cached, story).isEmpty())
            throw new PlanningRefused(Reason.STORY_HAS_ISSUES, Map.of("story", story));
        checkState(story, (String) entry.get("state"), to);
        canPush(token);
        entry.put("state", to);
        return save();
    }

    /** Validates the entire batch before publishing one atomic board replacement. */
    @Override
    public synchronized String setIssueStates(
            Map<String, Object> settings,
            String token,
            List<StateChange> changes,
            String revision) {
        context(settings, token);
        checkRevision(revision);
        final Set<String> seen = new HashSet<>();
        for (final StateChange change : changes) {
            final var entry = issue(change.issue());
            if (!seen.add(change.issue()))
                throw disallowed(
                        change.issue(),
                        (String) entry.get("state"),
                        change.to(),
                        "Duplicate issue");
            checkState(change.issue(), (String) entry.get("state"), change.to());
            if (fault.equals("half-batch")) {
                entry.put("state", change.to());
                save();
            }
        }
        if (changes.isEmpty()) return revision();
        canPush(token);
        for (final StateChange change : changes) issue(change.issue()).put("state", change.to());
        return save();
    }

    /** Fetches the single file and publishes a changed revision to existing watchers. */
    @Override
    public synchronized String refresh(Map<String, Object> settings, String token) {
        context(settings, token);
        if (fault.equals("blocked-refresh") && !subscriptions.isEmpty()) {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failed("Refresh interrupted");
            }
        }
        final String before = revision();
        cached = read();
        if (!before.equals(revision())) publish();
        return revision();
    }

    /** Registers and queues the initial revision atomically with respect to writes and refresh. */
    @Override
    public synchronized Watch watchChanges(Map<String, Object> settings, String token) {
        context(settings, token);
        final Subscription subscription = new Subscription();
        subscriptions.add(subscription);
        subscription.events.add(new Change(revision(), List.of(), List.of()));
        return subscription;
    }

    private String revision() {
        return (String) cached.get("revision");
    }

    private Map<String, Object> read() {
        try {
            return FileBoard.read(directory);
        } catch (IOException ex) {
            throw failed("Cannot read the fixture board");
        }
    }

    private void checkRevision(String supplied) {
        cached = read();
        if (!fault.equals("ignore-revision") && !supplied.equals(revision()))
            throw new PlanningRefused(Reason.STALE_REVISION, Map.of("revision", revision()));
    }

    private void canPush(String token) {
        if (Files.exists(directory.resolve("refuse-push")))
            throw new PlanningRefused(
                    Reason.CREDENTIAL_REFUSED,
                    Map.of(
                            "detail",
                            fault.equals("echo-token") ? token : "Publication was refused",
                            "retryAfter",
                            0));
    }

    private String save() {
        try {
            FileBoard.write(directory, cached);
        } catch (IOException ex) {
            throw failed("Cannot replace the fixture board");
        }
        publish();
        return revision();
    }

    private void publish() {
        final Change change = new Change(revision(), List.of(), List.of());
        subscriptions.forEach(s -> s.events.add(change));
    }

    private List<IssueSummary> summaries(String story) {
        return FileBoard.issues(cached, story).stream().map(Issue::of).map(Issue::summary).toList();
    }

    private Map<String, Object> story(String id) {
        safeId(id);
        return FileBoard.stories(cached).stream()
                .filter(s -> s.get("id").equals(id))
                .findFirst()
                .orElseThrow(() -> new PlanningRefused(Reason.NO_SUCH_STORY, Map.of("story", id)));
    }

    private Map<String, Object> issue(String id) {
        safeId(id);
        return FileBoard.issues(cached).stream()
                .filter(i -> i.get("id").equals(id))
                .findFirst()
                .orElseThrow(() -> new PlanningRefused(Reason.NO_SUCH_ISSUE, Map.of("issue", id)));
    }

    private static void safeId(String id) {
        if (!id.matches("[A-Za-z0-9_-]+")) throw failed("Unsafe id " + id);
    }

    private void checkState(String id, String from, String to) {
        if (Workflow.of((Map<?, ?>) cached.get("workflow")).states().stream()
                .noneMatch(s -> s.name().equals(to)))
            throw disallowed(id, from, to, "State is absent from the workflow");
    }

    private static PlanningRefused disallowed(String id, String from, String to, String reason) {
        return new PlanningRefused(
                Reason.STATE_NOT_ALLOWED,
                Map.of("item", id, "from", from, "to", to, "reason", reason));
    }

    private static PlanningRefused failed(String message) {
        return new PlanningRefused(Reason.FAILED, Map.of("message", message));
    }

    private final class Subscription implements Watch {
        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final Object end = new Object();
        private boolean closed;

        @Override
        public @Nullable Change next(long timeoutMillis) throws InterruptedException {
            final Object event = events.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            return event instanceof Change change ? change : null;
        }

        @Override
        public void close() {
            synchronized (ReferencePlanning.this) {
                if (closed) return;
                closed = true;
                subscriptions.remove(this);
                events.clear();
                events.offer(end);
            }
        }
    }
}
