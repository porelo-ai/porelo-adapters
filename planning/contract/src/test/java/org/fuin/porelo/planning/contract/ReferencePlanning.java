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
        if (System.getProperty("planning.contract.fault", "").startsWith("accept-")
                && args.length == 3
                && args[0].equals("serve")) {
            serveInvalidSelectors(args);
            return;
        }
        PlanningMain.run(new ReferencePlanning(), args);
    }

    private static void serveInvalidSelectors(String[] args) {
        final ReferencePlanning adapter = new ReferencePlanning();
        try (var backend =
                        new org.fuin.porelo.planning.api.PlanningServer(
                                adapter,
                                Path.of(args[2]).resolve("reference-socket"),
                                Path.of(args[2]));
                var frontend =
                        new org.fuin.porelo.wire.varlink.VarlinkServer(
                                Path.of(args[1]),
                                org.fuin.porelo.planning.api.PlanningProtocol.INTERFACE)) {
            for (final String method :
                    List.of(
                            "Describe",
                            "GetWorkflow",
                            "ListStories",
                            "GetStory",
                            "ListStoryIssues",
                            "GetIssue",
                            "SetStates")) {
                frontend.method(
                        method,
                        (parameters, replies) -> {
                            final Map<String, Object> forwarded = new LinkedHashMap<>(parameters);
                            if (method.equals("SetStates")
                                    && parameters.get("changes") instanceof List<?> changes) {
                                // Model a nonconforming wire decoder that silently discards invalid
                                // selectors.
                                forwarded.put(
                                        "changes",
                                        changes.stream()
                                                .filter(
                                                        value -> {
                                                            final Map<?, ?> change =
                                                                    (Map<?, ?>) value;
                                                            final boolean both =
                                                                    change.get("story") != null
                                                                            && change.get("issue")
                                                                                    != null;
                                                            final boolean neither =
                                                                    change.get("story") == null
                                                                            && change.get("issue")
                                                                                    == null;
                                                            return !(adapter.fault.equals(
                                                                                    "accept-both-selectors")
                                                                            && both
                                                                    || adapter.fault.equals(
                                                                                    "accept-neither-selectors")
                                                                            && neither);
                                                        })
                                                .toList());
                            }
                            try (var caller =
                                    new org.fuin.porelo.wire.varlink.VarlinkClient(
                                            backend.socketPath())) {
                                replies.last(
                                        caller.call(
                                                org.fuin.porelo.planning.api.PlanningProtocol
                                                                .INTERFACE
                                                        + "."
                                                        + method,
                                                forwarded));
                            } catch (IOException ex) {
                                throw failed("Reference bridge connection failed");
                            }
                        });
            }
            final Thread serving = Thread.ofVirtual().start(backend::serve);
            try {
                System.out.println("ready " + frontend.socketPath());
                System.out.flush();
                frontend.run();
            } finally {
                backend.close();
                serving.interrupt();
            }
        }
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
        return List.of(
                System.getProperty("planning.contract.capabilities", "write,watch,issues")
                        .split(","));
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
        if (fault.equals("access-before-credential") && cached == null) access();
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
        if (cached == null) probe(settings, token, false);
        access();
    }

    private void probe(Map<String, Object> settings, String token, boolean hang) {
        if (!(settings.get("remote") instanceof String remote)) return;
        if (fault.equals("redirect-pre-store-failure")) throw failed("Remote was not contacted");
        try (var client =
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(2))
                        .build()) {
            final var request =
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(remote))
                            .header(
                                    "Authorization",
                                    "Basic "
                                            + java.util.Base64.getEncoder()
                                                    .encodeToString(
                                                            ("git:" + token)
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)));
            if (!hang) request.timeout(java.time.Duration.ofSeconds(1));
            final var response =
                    client.send(
                            request.build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 307) {
                if (fault.equals("refuse-redirect"))
                    throw failed("Cross-host redirect was refused");
                final var redirected =
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(
                                                response.headers()
                                                        .firstValue("Location")
                                                        .orElseThrow()))
                                .timeout(java.time.Duration.ofSeconds(1));
                if (fault.equals("redirect-auth"))
                    redirected.header(
                            "Authorization",
                            request.build().headers().firstValue("Authorization").orElseThrow());
                client.send(
                        redirected.build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                throw failed("Redirected store refused the request");
            }
        } catch (IOException ex) {
            throw failed("Remote probe did not answer");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed("Remote probe interrupted");
        }
    }

    private void access() {
        try {
            final Path accesses = directory.resolve("accesses");
            // Models a clone that is only observable before its cache has been initialized.
            if (cached == null || !fault.startsWith("access-before-"))
                Files.writeString(
                        accesses, Long.toString(Long.parseLong(Files.readString(accesses)) + 1));
            if (cached == null) cached = FileBoard.read(directory);
        } catch (IOException ex) {
            throw failed("Cannot read the fixture board");
        }
    }

    private void requireCapability(String capability, Map<String, Object> settings, String token) {
        if (fault.equals("access-before-capability")) context(settings, token);
        if (!capabilities().contains(capability)) throw PlanningRefused.notSupported(capability);
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
                                                summaries(s.id()),
                                                fault.equals("drop-priority")
                                                        ? null
                                                        : s.priority()))
                        .toList(),
                revision());
    }

    /** Returns the complete stored story or its named refusal. */
    @Override
    public synchronized Result<Story> getStory(
            Map<String, Object> settings, String token, String story) {
        context(settings, token);
        if (fault.equals("traversal-story-read") && story.contains("/")) {
            final Path target = directory.resolve(story + ".md").normalize();
            if (Files.isRegularFile(target)) {
                try {
                    return new Result<>(
                            Story.of(
                                    (Map<?, ?>)
                                            org.fuin.porelo.wire.Json.parse(
                                                    Files.readString(target))),
                            revision());
                } catch (IOException ex) {
                    throw failed("Cannot read decoy");
                }
            }
        }
        return new Result<>(Story.of(story(story)), revision());
    }

    /** Returns complete issue summaries for the named story. */
    @Override
    public synchronized Result<List<IssueSummary>> listStoryIssues(
            Map<String, Object> settings, String token, String story) {
        requireCapability("issues", settings, token);
        context(settings, token);
        story(story);
        return new Result<>(summaries(story), revision());
    }

    /** Delivers only listed briefs after checking every path before reading any of them. */
    @Override
    public synchronized Result<Issue> getIssue(
            Map<String, Object> settings, String token, String issue) {
        requireCapability("issues", settings, token);
        context(settings, token);
        if (fault.equals("traversal-issue-read") && issue.contains("/")) {
            final Path target = directory.resolve(issue + ".md").normalize();
            if (Files.isRegularFile(target)) {
                try {
                    return new Result<>(
                            Issue.of(
                                    (Map<?, ?>)
                                            org.fuin.porelo.wire.Json.parse(
                                                    Files.readString(target))),
                            revision());
                } catch (IOException ex) {
                    throw failed("Cannot read decoy");
                }
            }
        }
        if (issue.equals("I1") && Files.isSymbolicLink(directory.resolve("I1.md")))
            throw failed("Unsafe issue I1.md");
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

    /** Validates both resource kinds before publishing one atomic board replacement. */
    @Override
    public synchronized String setStates(
            Map<String, Object> settings,
            String token,
            List<StateChange> changes,
            String revision) {
        requireCapability("write", settings, token);
        if (changes.stream().anyMatch(c -> c.issue() != null))
            requireCapability("issues", settings, token);
        context(settings, token);
        if (fault.startsWith("traversal-") && fault.endsWith("-write")) {
            final String id =
                    fault.equals("traversal-story-write")
                            ? changes.getFirst().story()
                            : changes.getFirst().issue();
            if (id != null && id.contains("/")) {
                try {
                    Files.writeString(
                            directory.resolve(id + ".md"), "Overwritten outside directory");
                } catch (IOException ex) {
                    throw failed("Unsafe id " + id);
                }
                throw failed("Unsafe id " + id);
            }
        }
        if (changes.stream().anyMatch(c -> "I1".equals(c.issue()))
                && Files.isSymbolicLink(directory.resolve("I1.md"))) {
            if (fault.equals("symlink-write")) {
                try {
                    Files.writeString(directory.resolve("I1.md"), "Overwritten through symlink");
                } catch (IOException ex) {
                    throw failed("Cannot overwrite symlink");
                }
            }
            throw failed("Unsafe issue I1.md");
        }
        probe(settings, token, false);
        checkRevision(revision, changes);
        final Set<String> stories = new HashSet<>();
        final Set<String> issues = new HashSet<>();
        for (final StateChange change : changes) {
            final String id =
                    change.story() != null
                            ? change.story()
                            : java.util.Objects.requireNonNull(change.issue());
            final var entry = change.story() != null ? story(id) : issue(id);
            final Set<String> seen = change.story() != null ? stories : issues;
            if (!seen.add(id) && !(change.story() != null && fault.equals("duplicate-story")))
                throw disallowed(
                        id,
                        (String) entry.get("state"),
                        change.to(),
                        change.story() != null ? "Duplicate story" : "Duplicate issue");
            if (change.story() != null && !FileBoard.issues(cached, id).isEmpty())
                throw new PlanningRefused(Reason.STORY_HAS_ISSUES, Map.of("story", id));
            checkState(id, (String) entry.get("state"), change.to());
            if (fault.equals("half-batch")) {
                entry.put("state", change.to());
                save();
            }
        }
        if (changes.isEmpty()) return revision();
        canPush(token);
        if (fault.equals("half-push-no-watch")) publishPartially(settings, changes);
        leak(token);
        for (final StateChange change : changes) {
            (change.story() != null
                            ? story(change.story())
                            : issue(java.util.Objects.requireNonNull(change.issue())))
                    .put("state", change.to());
            if (fault.equals("split-batch")) save();
        }
        return save();
    }

    /** Fetches the single file and publishes a changed revision to existing watchers. */
    @Override
    public synchronized String refresh(Map<String, Object> settings, String token) {
        requireCapability("watch", settings, token);
        context(settings, token);
        if (fault.equals("blocked-refresh") && !subscriptions.isEmpty()) {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failed("Refresh interrupted");
            }
        }
        probe(settings, token, false);
        final String before = revision();
        cached = read();
        if (!before.equals(revision())) publish();
        return revision();
    }

    /** Registers and queues the initial revision atomically with respect to writes and refresh. */
    @Override
    public synchronized Watch watchChanges(Map<String, Object> settings, String token) {
        requireCapability("watch", settings, token);
        if (fault.equals("watch-hangs-on-refusal") && token.startsWith("refused-")) {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failed("Watch interrupted");
            }
        }
        if (!fault.equals("watch-ignores-credential")) context(settings, token);
        if (fault.equals("watch-fetch-hangs")) probe(settings, token, true);
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

    private void checkRevision(String supplied, List<StateChange> changes) {
        final Map<String, Object> current = read();
        final boolean storyWrite = changes.stream().anyMatch(c -> c.story() != null);
        final boolean ignoreExternal =
                fault.equals("issue-ignores-story")
                                && !storyWrite
                                && !FileBoard.stories(cached).equals(FileBoard.stories(current))
                        || fault.equals("story-ignores-story")
                                && storyWrite
                                && !FileBoard.stories(cached).equals(FileBoard.stories(current))
                        || fault.equals("story-ignores-issue")
                                && storyWrite
                                && !FileBoard.issues(cached).equals(FileBoard.issues(current));
        if (!ignoreExternal) cached = current;
        if (!fault.equals("ignore-revision") && !supplied.equals(revision()))
            throw new PlanningRefused(Reason.STALE_REVISION, Map.of("revision", revision()));
    }

    private void canPush(String token) {
        if (Files.exists(directory.resolve("refuse-push"))) {
            if (fault.equals("generic-token-error"))
                throw new org.fuin.porelo.wire.varlink.VarlinkException(
                        "org.example.Unknown", Map.of("detail", token));
            throw new PlanningRefused(
                    fault.equals("wrong-reason-token") ? Reason.FAILED : Reason.CREDENTIAL_REFUSED,
                    Map.of(
                            fault.equals("wrong-reason-token") ? "message" : "detail",
                            fault.equals("echo-token") || fault.equals("wrong-reason-token")
                                    ? token
                                    : "Publication was refused",
                            "retryAfter",
                            0));
        }
    }

    private void publishPartially(Map<String, Object> settings, List<StateChange> changes) {
        final Path remotes = Path.of((String) settings.get("publicationRemotes"));
        try (GitProcesses git =
                new GitProcesses(Files.createDirectory(directory.resolve("git-home")))) {
            for (final StateChange change : changes) {
                final String id = java.util.Objects.requireNonNull(change.issue());
                final String repository = (String) issue(id).get("repository");
                final Path clone = directory.resolve(repository);
                git.git(
                        directory,
                        "clone",
                        remotes.resolve(repository + ".git").toString(),
                        clone.toString());
                Files.writeString(clone.resolve(id + ".md"), change.to() + "\n");
                git.git(clone, "add", id + ".md");
                git.git(clone, "commit", "-m", "Change issue state");
                git.git(clone, "push", "origin", "HEAD:main");
            }
        } catch (IOException ex) {
            // The deliberate fault leaves the first publication in place after the second fails.
            throw new PlanningRefused(
                    Reason.CREDENTIAL_REFUSED,
                    Map.of("detail", "Second publication was refused", "retryAfter", 0));
        }
    }

    private void leak(String token) {
        try {
            final String encoded =
                    java.util.Base64.getEncoder()
                            .encodeToString(
                                    ("git:" + token)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (fault.equals("base64-file"))
                Files.writeString(directory.resolve("config"), "Authorization: Basic " + encoded);
            if (fault.equals("token-filename"))
                Files.writeString(directory.resolve(token), "retained");
            if (fault.equals("token-link"))
                Files.createSymbolicLink(directory.resolve("credential-link"), Path.of(token));
            if (fault.equals("runtime-token"))
                Files.writeString(
                        Path.of(System.getenv("PORELO_TEST_RUNTIME")).resolve("leak"), token);
            if (fault.equals("child-arguments")
                    || fault.equals("child-environment")
                    || fault.equals("child-stderr")) {
                final ProcessBuilder child =
                        new ProcessBuilder("/bin/sh", "-c", "read -r line; sleep 1", "helper");
                if (fault.equals("child-arguments")) child.command().add(token);
                if (fault.equals("child-environment")) child.environment().put("TOKEN", token);
                if (fault.equals("child-stderr")) {
                    child.command().set(2, "read -r line; printf '%s' \"$line\" >&2");
                    child.redirectError(ProcessBuilder.Redirect.INHERIT);
                }
                final Process process = child.start();
                try {
                    process.getOutputStream()
                            .write(
                                    (token + "\n")
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    process.getOutputStream().close();
                    if (!process.waitFor(5, TimeUnit.SECONDS))
                        throw failed("Leaking helper did not finish");
                } finally {
                    process.destroyForcibly();
                }
            }
            if (fault.equals("host-hook")) {
                final ProcessBuilder hook =
                        new ProcessBuilder(
                                "/bin/sh",
                                directory
                                        .getParent()
                                        .resolve("home/hooks/reference-transaction")
                                        .toString());
                if (!hook.start().waitFor(5, TimeUnit.SECONDS))
                    throw failed("Host hook did not finish");
            }
            if (fault.equals("orphan-child")) {
                final Process child = new ProcessBuilder("/bin/sleep", "60").start();
                // Keep stdin open so the child cannot exit before the shutdown check observes it.
                leakedChild = child;
            }
        } catch (IOException ex) {
            throw failed("Cannot inject test fault");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed("Fault helper interrupted");
        }
    }

    private Process leakedChild;

    private String save() {
        try {
            FileBoard.write(directory, cached);
        } catch (IOException ex) {
            throw failed("Cannot replace the fixture board");
        }
        if (!fault.equals("silent-writes")) publish();
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
        private boolean delivered;

        @Override
        public @Nullable Change next(long timeoutMillis) throws InterruptedException {
            if (delivered && fault.equals("closed-watch"))
                throw failed("Watch ended after baseline");
            final Object event = events.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (event instanceof Change change) {
                delivered = true;
                return change;
            }
            return null;
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
