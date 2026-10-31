package org.fuin.porelo.standin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.build.github.review.GitHubReviewWrite;
import org.fuin.porelo.build.github.review.ReviewDocument;
import org.fuin.porelo.build.github.review.ReviewRefusal;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * The daemon's review calls on {@code org.fuin.porelo.Tasks1}, served by the stand-in. What only
 * the daemon decides it answers with fixed values and says so; it forwards nothing, drops nothing
 * and tells no task; a comment reaches GitHub only when the caller asks with {@code toForge}.
 */
final class StandIn {

    /** The one project the stand-in serves. */
    static final String PROJECT = "standin";

    /** The one repository of that project. */
    static final String REPOSITORY = "standin";

    static final String INTERFACE = "org.fuin.porelo.Tasks1";

    private static final String DEFAULT_BRANCH = "refs/heads/main";

    private static final long WATCH_INTERVAL_MILLIS = 5_000;

    /** The bases the default branch was last made of. */
    private List<List<String>> defaultBranchChecked = List.of();

    private static final String FIXED =
            "the stand-in's fixed values: sourceLevel, riskClass, approvalsNeeded and the counts of held"
                    + " and outgoing forge comments; the daemon decides these";

    private static final Set<String> SERVED =
            Set.of(
                    "Projects",
                    "WatchProjects",
                    "Pending",
                    "Review",
                    "Comments",
                    "Comment",
                    "ResolveThread",
                    "Approve",
                    "Reject");

    /** Every other method of the daemon's interface, at the commit the interface copy names. */
    private static final List<String> NOT_SERVED =
            List.of(
                    "List",
                    "Watch",
                    "Start",
                    "RefreshTask",
                    "Stop",
                    "Remove",
                    "Panic",
                    "Lock",
                    "CanStart",
                    "Label",
                    "Prepare",
                    "Logs",
                    "LogArchive",
                    "WorkHeld",
                    "Tail",
                    "Screen",
                    "HandIn",
                    "TakeBack",
                    "HandIns",
                    "HostKeys",
                    "TrustHostKey",
                    "SyncUpstream",
                    "Backups",
                    "RestoreBackup",
                    "DeleteBackup",
                    "DefaultRepositories",
                    "AddToDefault",
                    "RemoveFromDefault",
                    "Prune",
                    "Following",
                    "RefreshProjects",
                    "Follow",
                    "Unfollow",
                    "Clear",
                    "DeployKey",
                    "ProjectFileSchema",
                    "CheckProjectFile",
                    "Sets",
                    "Egress",
                    "SetEgress",
                    "SetClearance",
                    "NarrowTask",
                    "WidenTask",
                    "Prompts",
                    "Decide",
                    "Credentials",
                    "CredentialDeclare",
                    "CredentialForget",
                    "SshKeys",
                    "CredentialCheck",
                    "ImportCredential",
                    "Keyslots",
                    "EnrollDevice",
                    "RevokeKeyslot",
                    "UnlockWithShare",
                    "Authorizations",
                    "Authorize",
                    "Destinations",
                    "Destination",
                    "WriteDestination",
                    "RemoveDestination",
                    "VaultClear",
                    "KeepHostVaultShare",
                    "HostVaultShare",
                    "ReadHostVaultShare",
                    "ForgetHostVaultShare",
                    "PendingBundle",
                    "Landed",
                    "Agents",
                    "Providers",
                    "Installable",
                    "MessageKey",
                    "Peers",
                    "Talk",
                    "Held",
                    "Say",
                    "Tell",
                    "ReadHeld",
                    "Release",
                    "Moderate",
                    "SetUpMessages",
                    "JoinMessages",
                    "AdmitMachine",
                    "DismissMachine",
                    "CreateRoom",
                    "MessageMembers",
                    "RemoveMessageMember",
                    "Doctor",
                    "Node",
                    "Process",
                    "Events",
                    "VerifyEvents");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PEOPLE_HERE = "people here";

    private final Pushes pushes;

    private final Git git;

    private final @Nullable GitHubSource github;

    private final List<Long> pullRequests;

    private final @Nullable String token;

    private final Clock clock;

    private final PrintStream log;

    private final String person;

    StandIn(
            Pushes pushes,
            Git git,
            @Nullable GitHubSource github,
            List<Long> pullRequests,
            @Nullable String token,
            Clock clock,
            PrintStream log) {
        this.pushes = pushes;
        this.git = git;
        this.github = github;
        this.pullRequests = List.copyOf(pullRequests);
        this.token = token;
        this.clock = clock;
        this.log = log;
        final String user = System.getProperty("user.name", "person");
        this.person = user.matches("[A-Za-z0-9._-]{1,128}") ? user : "person";
    }

    /**
     * Registers every method on a server.
     *
     * @param server The server.
     * @return The server.
     */
    VarlinkServer register(VarlinkServer server) {
        server.describedBy(idl()).reporting("review stand-in " + ReviewDocument.version());
        server.method("Projects", guarded("Projects", parameters -> projects()));
        server.method("WatchProjects", this::watchProjects);
        server.method("Pending", guarded("Pending", this::pending));
        server.method("Review", guarded("Review", this::review));
        server.method("Comments", guarded("Comments", this::comments));
        server.method("Comment", guarded("Comment", this::comment));
        server.method("ResolveThread", guarded("ResolveThread", this::resolveThread));
        server.method("Approve", guarded("Approve", this::approve));
        server.method("Reject", guarded("Reject", this::reject));
        for (final String name : NOT_SERVED) {
            server.method(
                    name,
                    (parameters, replies) -> {
                        throw failed(
                                "The review stand-in serves only "
                                        + String.join(", ", new java.util.TreeSet<>(SERVED))
                                        + "; "
                                        + name
                                        + " is the daemon's");
                    });
        }
        return server;
    }

    /** A method's body, answering one map. */
    @FunctionalInterface
    private interface Body {
        Map<String, Object> call(Map<String, Object> parameters);
    }

    /**
     * Runs a method so that neither its answer nor its error nor its log line can carry the token,
     * and every error is the interface's own.
     */
    private VarlinkServer.Method guarded(String method, Body body) {
        return (parameters, replies) -> replies.last(checked(method, body, parameters));
    }

    /** One answer of a method, refused if it or its error would carry the token. */
    private Map<String, Object> checked(String method, Body body, Map<String, Object> parameters) {
        final Map<String, Object> answer = unlogged(method, body, parameters);
        log(method + " answered");
        return answer;
    }

    /** As {@link #checked}, without the log line of an answer, which a watch writes only once. */
    private Map<String, Object> unlogged(String method, Body body, Map<String, Object> parameters) {
        Map<String, Object> answer;
        try {
            answer = body.call(parameters);
        } catch (ReviewRefusal ex) {
            log(method + " refused: " + ex.reason().error() + " at " + ex.path());
            throw clean(refused(ex));
        } catch (VarlinkException ex) {
            log(method + " answered " + ex.getErrorName());
            throw clean(ex);
        } catch (RuntimeException ex) {
            log(method + " failed: " + ex.getClass().getSimpleName());
            throw clean(failed(String.valueOf(ex.getMessage())));
        }
        final String text = json(answer);
        if (carriesToken(text)) {
            log(method + " failed: its answer would have carried the token");
            throw failed("The stand-in's answer would have carried the token; nothing was sent");
        }
        return answer;
    }

    private VarlinkException clean(VarlinkException ex) {
        final Object parameters = ex.getParameters();
        if (carriesToken(String.valueOf(parameters))
                || carriesToken(String.valueOf(ex.getMessage()))) {
            return failed("The stand-in's error would have carried the token; it is not shown");
        }
        return ex;
    }

    private boolean carriesToken(String text) {
        return token != null && !token.isEmpty() && text.contains(token);
    }

    private void log(String line) {
        log.println(carriesToken(line) ? "a log line that would have carried the token" : line);
    }

    // ------------------------------------------------------------------------------- the calls

    private Map<String, Object> projects() {
        refresh(false);
        final int pending = pushes.all().size();
        final Map<String, Object> repository = new LinkedHashMap<>();
        repository.put("name", REPOSITORY);
        repository.put("own", true);
        repository.put("upstream", github == null ? "" : github.repositoryUri());
        repository.put("mirror", git.dir().toString());
        repository.put("pending", pending);
        final Map<String, Object> project = new LinkedHashMap<>();
        project.put("name", PROJECT);
        project.put("mirror", git.dir().toString());
        project.put("pending", pending);
        project.put("repositories", List.of(repository));
        return Map.of("projects", List.of(project));
    }

    /** Projects once, or with "more" again whenever it changes, until the client is gone. */
    private void watchProjects(Map<String, Object> parameters, VarlinkServer.Replies replies)
            throws java.io.IOException {
        final Body body = p -> projects();
        Map<String, Object> last = checked("WatchProjects", body, parameters);
        if (!replies.streaming()) {
            replies.last(last);
            return;
        }
        replies.more(last);
        while (replies.open()) {
            try {
                Thread.sleep(WATCH_INTERVAL_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            final Map<String, Object> now = unlogged("WatchProjects", body, parameters);
            if (!now.equals(last)) {
                log("WatchProjects answered");
                replies.more(now);
                last = now;
            }
        }
    }

    private Map<String, Object> pending(Map<String, Object> parameters) {
        // GitHub first: a check of what was read before would refuse what GitHub has changed.
        refresh(false);
        gate(parameters);
        final List<Object> pending = new ArrayList<>();
        for (final Push push : pushes.all()) {
            pending.add(pendingPush(push));
        }
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("mirror", git.dir().toString());
        answer.put("mode", "gatekeeping");
        answer.put("seededFrom", "");
        answer.put("pending", pending);
        return answer;
    }

    private Map<String, Object> pendingPush(Push push) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", push.name);
        out.put("commit", push.commit);
        out.put("subject", push.subject);
        out.put("waiting", waiting(push.at));
        out.put("at", push.at);
        out.put(
                "fetch",
                "git fetch "
                        + git.dir()
                        + " refs/porelo/incoming/"
                        + push.name
                        + ":refs/remotes/porelo/"
                        + push.name);
        out.put("sourceLevel", push.sourceLevel);
        out.put("sourceWords", push.shows);
        out.put("riskClass", push.riskClass);
        out.put("approvalsNeeded", push.approvalsNeeded);
        out.put("review", summary(push));
        out.put("standIn", FIXED);
        return out;
    }

    private Map<String, Object> summary(Push push) {
        final int[] open = push.openCounts();
        final List<Object> runs = new ArrayList<>();
        final int round = push.round();
        for (final Push.Run run : push.runs.values()) {
            if (run.round != round) {
                continue;
            }
            int findings = 0;
            synchronized (run.results) {
                for (final ObjectNode result : run.results) {
                    if ("fail".equals(result.path("kind").asText())) {
                        findings++;
                    }
                }
            }
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("run", run.id);
            out.put("name", run.name);
            out.put("kind", run.kind);
            out.put("state", run.completeness());
            out.put("findings", findings);
            // Nothing here clears a finding: every finding is open.
            out.put("openFindings", findings);
            runs.add(out);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("round", round);
        out.put("openFindings", open[0]);
        out.put("openThreads", open[1]);
        out.put("heldInbound", 0);
        out.put("waitingOutbound", 0);
        out.put("runs", runs);
        return out;
    }

    private Map<String, Object> review(Map<String, Object> parameters) {
        gate(parameters);
        final Push push = push(parameters);
        final String against = optional(parameters, "against");
        final String base = against == null ? push.base : git.commit(against);
        final List<Object> files = new ArrayList<>();
        // NUL-separated, so no path comes back quoted.
        final Map<String, String> status = new LinkedHashMap<>();
        final String[] names =
                git.read("diff", "-z", "--no-renames", "--name-status", base, push.commit)
                        .split("\0");
        for (int i = 0; i + 1 < names.length; i += 2) {
            status.put(names[i + 1], names[i]);
        }
        for (final String line :
                git.read("diff", "-z", "--no-renames", "--numstat", base, push.commit)
                        .split("\0")) {
            if (line.isBlank()) {
                continue;
            }
            final String[] parts = line.split("\t", 3);
            final Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", parts[2]);
            file.put("status", status.getOrDefault(parts[2], "M"));
            file.put("added", "-".equals(parts[0]) ? -1 : Integer.parseInt(parts[0]));
            file.put("removed", "-".equals(parts[1]) ? -1 : Integer.parseInt(parts[1]));
            file.put("rank", "ORDINARY");
            file.put("reason", "the stand-in ranks nothing");
            final String risk = push.fileClasses.get(parts[2]);
            if (risk != null) {
                file.put("riskClass", risk);
            }
            files.add(file);
        }
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("diff", git.read("diff", "--no-color", "--no-renames", base, push.commit));
        answer.put(
                "log", git.read("log", "--no-color", "--format=%H %s", base + ".." + push.commit));
        answer.put("files", files);
        answer.put("base", base);
        answer.put(
                "standIn",
                "every file ORDINARY and its class from a fixture or C1: the stand-in's fixed values");
        return answer;
    }

    private Map<String, Object> comments(Map<String, Object> parameters) {
        gate(parameters);
        final Push push = push(parameters);
        final String runId = required(parameters, "run");
        final Push.Run run = push.runs.get(runId);
        if (run == null) {
            throw failed("No run " + runId + " on " + push.name);
        }
        final Object roundParameter = parameters.get("round");
        if (roundParameter != null
                && !(roundParameter instanceof Integer || roundParameter instanceof Long)) {
            throw invalid("round");
        }
        final int round =
                roundParameter instanceof Number n ? Math.toIntExact(n.longValue()) : push.round();
        if (round != run.round) {
            throw failed("Run " + runId + " is of round " + run.round + ", not " + round);
        }
        final String cursor = optional(parameters, "cursor");
        final int from = cursor == null ? 0 : offset(cursor, push, run);
        final ObjectNode document = run.shell.deepCopy();
        final var page = Push.page(run, from, ReviewDocument.RESULT_LIMIT);
        page.forEach(result -> overlayLocalState(push, (ObjectNode) result));
        ((ObjectNode) document.at("/runs/0")).set("results", page);
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("sarif", ReviewDocument.text(document));
        final int next = from + page.size();
        final int total;
        synchronized (run.results) {
            total = run.results.size();
        }
        if (next < total) {
            answer.put("cursor", cursor(push, run, next));
        }
        if (!run.held.isEmpty()) {
            final List<Object> held = new ArrayList<>();
            for (final var item : run.held) {
                held.add(
                        Map.of(
                                "kind",
                                item.kind(),
                                "id",
                                Long.toString(item.id()),
                                "reason",
                                item.reason()));
            }
            answer.put("held", held);
        }
        return answer;
    }

    private Map<String, Object> comment(Map<String, Object> parameters) {
        gate(parameters);
        final Push push = push(parameters);
        final Map<String, Object> answer;
        // One comment of a push at a time, so one id never makes two comments or two writes.
        synchronized (push) {
            answer = comment(parameters, push);
        }
        if (answer.containsKey("sentToForge")) {
            // Outside the push's lock: a refresh takes the forge's lock, then each push's.
            refresh(true);
        }
        return answer;
    }

    private Map<String, Object> comment(Map<String, Object> parameters, Push push) {
        final String sarif = required(parameters, "sarif");
        final String id = optional(parameters, "id");
        if (id != null) {
            if (!id.matches("[A-Za-z0-9._-]{1,64}")) {
                throw refusal(
                        "ProfileViolation",
                        Map.of("detail", "id is [A-Za-z0-9._-]{1,64}", "rule", "id", "path", "id"));
            }
            final var earlier = push.commentIds.get(id);
            if (earlier != null) {
                if (!earlier.getKey().equals(callKey(parameters, sarif))) {
                    throw refusal("IdReused", Map.of("id", id));
                }
                return earlier.getValue();
            }
        }
        final Submission submission = Submission.read(sarif);
        if (!submission.revision.equals(push.commit) || !submission.base.equals(push.base)) {
            throw refusal(
                    "MovedSinceReview",
                    Map.of("name", push.name, "reviewed", submission.revision, "now", push.commit));
        }
        anchorInBlob(push, submission);
        final String replyTo = optional(parameters, "replyTo");
        final ObjectNode parent = replyTo == null ? null : push.result(replyTo);
        if (replyTo != null && parent == null) {
            throw refusal("NoSuchResult", Map.of("guid", replyTo));
        }
        final boolean toForge = flag(parameters, "toForge");
        final GitHubReviewWrite.Written sent = toForge ? send(push, submission, parent) : null;
        final Map<String, Object> answer = store(push, submission, parent, sent);
        if (sent != null) {
            answer.put("sentToForge", sentAnswer(sent, submission));
        }
        if (id != null) {
            push.commentIds.put(id, Map.entry(callKey(parameters, sarif), answer));
        }
        return answer;
    }

    private Map<String, Object> store(
            Push push,
            Submission submission,
            @Nullable ObjectNode parent,
            GitHubReviewWrite.@Nullable Written sent) {
        final ObjectNode document = submission.document;
        final ObjectNode run = (ObjectNode) document.at("/runs/0");
        ((ObjectNode) run.at("/versionControlProvenance/0"))
                .put(
                        "repositoryUri",
                        github == null
                                ? "porelo://" + PROJECT + "/" + REPOSITORY
                                : github.repositoryUri());
        ((ObjectNode) run.get("properties")).put("porelo/completeness", "complete");
        final ObjectNode result = submission.result;
        final ObjectNode properties = result.with("properties");
        final String guid = java.util.UUID.randomUUID().toString();
        final String thread;
        if (parent == null) {
            thread = push.newThread(guid);
        } else {
            final ObjectNode parentProperties = parent.with("properties");
            final String parentThread = parentProperties.path("porelo/threadId").asText(null);
            thread =
                    parentThread != null
                            ? parentThread
                            : push.newThread(parent.path("guid").asText());
            if (parentThread == null) {
                parentProperties.put("porelo/threadId", thread);
                parentProperties.put("porelo/threadState", "open");
            }
            properties.put("porelo/replyTo", parent.path("guid").asText());
        }
        result.put("guid", guid);
        properties.put("porelo/threadId", thread);
        properties.put("porelo/threadState", push.localThreadState.getOrDefault(thread, "open"));
        properties.put("porelo/sourceKind", "person");
        properties.put("porelo/authorId", person);
        properties.put("porelo/sourceLevel", "I1");
        final int round = push.round();
        final ObjectNode shell = document.deepCopy();
        ((ObjectNode) shell.at("/runs/0")).remove("results");
        final ObjectNode stored;
        synchronized (push) {
            Push.Run local = null;
            for (final Push.Run existing : push.runs.values()) {
                if (existing.name.equals(PEOPLE_HERE) && existing.round == round) {
                    local = existing;
                }
            }
            if (local == null) {
                local = push.putRun(PEOPLE_HERE, "person", round, shell, List.of(), List.of());
            }
            synchronized (local.results) {
                local.results.add(result);
            }
            if (sent != null) {
                // One copy: the forge's is this result, never a second one.
                push.markSent(result, sent);
            }
            // The run as the daemon stamped it, around this one result.
            stored = local.shell.deepCopy();
            ((ObjectNode) stored.at("/runs/0")).putArray("results").add(result.deepCopy());
        }
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("guid", guid);
        answer.put("thread", thread);
        answer.put("sarif", ReviewDocument.text(stored));
        answer.put(
                "standIn",
                "porelo/sourceLevel I1 is the stand-in's fixed value for a comment here");
        return answer;
    }

    /**
     * Sends a comment to GitHub at once, as the caller asked; the daemon would hold it for release.
     */
    private GitHubReviewWrite.Written send(
            Push push, Submission submission, @Nullable ObjectNode parent) {
        final Long number = push.pullRequest;
        if ("PullRequestMoved".equals(push.refuseToForge)) {
            throw refusal(
                    "PullRequestMoved",
                    Map.of("pullRequest", 0, "written", push.commit, "now", "0".repeat(40)));
        }
        if (number == null || github == null || token == null) {
            throw refusal("NoPullRequest", Map.of("name", push.name));
        }
        final String head = github.head(number);
        if (!head.equals(push.commit)) {
            throw refusal(
                    "PullRequestMoved",
                    Map.of("pullRequest", number.intValue(), "written", push.commit, "now", head));
        }
        if ("LEFT".equals(submission.rendered.side())) {
            final String mergeBase = github.mergeBase(number);
            if (!mergeBase.equals(push.base)) {
                throw refusal(
                        "PullRequestBaseMoved",
                        Map.of(
                                "pullRequest",
                                number.intValue(),
                                "written",
                                push.base,
                                "now",
                                mergeBase));
            }
        }
        final Long replyTo = forgeParent(parent, submission.result.has("fixes"));
        final GitHubReviewWrite.Written written;
        try {
            written =
                    GitHubReviewWrite.send(
                            github.client(),
                            token,
                            github.owner(),
                            github.repo(),
                            number,
                            push.commit,
                            submission.rendered,
                            replyTo);
        } catch (RuntimeException ex) {
            throw refusal(
                    "AdapterRefused",
                    Map.of("error", "Failed", "detail", String.valueOf(ex.getMessage())));
        }
        return written;
    }

    private static Map<String, Object> sentAnswer(
            GitHubReviewWrite.Written written, Submission submission) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("commentId", Long.toString(written.commentId()));
        out.put("commentKind", written.commentKind());
        out.put(
                "words",
                "Sent to GitHub at once, without the release step the daemon will have"
                        + (submission.rendered.losses().isEmpty()
                                ? ""
                                : "; GitHub could not carry: "
                                        + String.join(
                                                ", ", submission.rendered.losses().keySet())));
        return out;
    }

    /**
     * A reply on GitHub goes to the root of a review comment's thread; a suggestion, and an answer
     * to any other parent, is a new comment at its own anchor, since a reply takes its parent's.
     */
    static @Nullable Long forgeParent(@Nullable ObjectNode parent, boolean suggestion) {
        if (parent == null || suggestion) {
            return null;
        }
        final JsonNode properties = parent.path("properties");
        if (!"review".equals(properties.path("porelo/nativeCommentKind").asText())) {
            return null;
        }
        final String root =
                properties.has("porelo/nativeReplyTo")
                        ? properties.path("porelo/nativeReplyTo").asText()
                        : properties.path("porelo/nativeCommentId").asText();
        return Long.valueOf(root);
    }

    private void anchorInBlob(Push push, Submission submission) {
        final GitHubReviewWrite.Rendered rendered = submission.rendered;
        if (rendered.anchor() == GitHubReviewWrite.Anchor.GENERAL) {
            return;
        }
        final String path = Objects.requireNonNull(rendered.path());
        final String revision = "LEFT".equals(rendered.side()) ? push.base : push.commit;
        final String content = git.file(revision, path);
        final int start = rendered.startLine() == null ? 0 : rendered.startLine();
        final int end = rendered.line() == null ? 0 : rendered.line();
        if (content == null
                || (rendered.anchor() == GitHubReviewWrite.Anchor.LINE && end > lines(content))) {
            throw refusal(
                    "AnchorOutsideBlob", Map.of("path", path, "startLine", start, "endLine", end));
        }
    }

    private static int lines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        final int newlines = (int) content.chars().filter(c -> c == '\n').count();
        return content.endsWith("\n") ? newlines : newlines + 1;
    }

    private Map<String, Object> resolveThread(Map<String, Object> parameters) {
        gate(parameters);
        final Push push = push(parameters);
        final String thread = required(parameters, "thread");
        if (!push.hasThread(thread)) {
            throw refusal("NoSuchThread", Map.of("thread", thread));
        }
        final String state = flag(parameters, "reopen") ? "open" : "resolved";
        push.localThreadState.put(thread, state);
        return Map.of("thread", thread, "state", state);
    }

    private Map<String, Object> approve(Map<String, Object> parameters) {
        gate(parameters);
        final Push push = push(parameters);
        final String commit = optional(parameters, "commit");
        if (commit != null
                && (!commit.equals(push.commit) || "MovedSinceReview".equals(push.refuseApprove))) {
            throw refusal(
                    "MovedSinceReview",
                    Map.of("name", push.name, "reviewed", commit, "now", push.commit));
        }
        final String branch = optional(parameters, "branch");
        return Map.of(
                "forwarded",
                "nothing: the review stand-in forwards nothing, approves nothing and sends nothing",
                "branch",
                branch == null ? "" : branch);
    }

    private Map<String, Object> reject(Map<String, Object> parameters) {
        gate(parameters);
        final Push push = push(parameters);
        // Nothing is dropped and no task is told: the stand-in has no task.
        return Map.of("rejected", push.name, "told", Boolean.FALSE);
    }

    // ------------------------------------------------------------------------------- helpers

    private void refresh(boolean force) {
        if (github != null && !pullRequests.isEmpty()) {
            github.refresh(pushes, pullRequests, force);
        }
        defaultBranch();
    }

    /**
     * Points the mirror's HEAD at a branch whose merge base with every push is that push's base, as
     * a review display computes it: one commit with every base as a parent.
     */
    private synchronized void defaultBranch() {
        // Each push's name, base and commit: a new commit can stack a push as a new base can.
        final List<List<String>> checked =
                pushes.all().stream()
                        .map(p -> List.of(p.name, p.base, p.commit))
                        .sorted(java.util.Comparator.comparing(Object::toString))
                        .toList();
        if (checked.equals(defaultBranchChecked) || checked.isEmpty()) {
            return;
        }
        final List<String> bases = checked.stream().map(p -> p.get(1)).distinct().sorted().toList();
        final String branch = git.commitTree(Map.of(), bases, "The stand-in's bases");
        for (final List<String> push : checked) {
            // One push's history holding another's base, as stacked pull requests do.
            if (!git.mergeBase(branch, push.get(2)).equals(push.get(1))) {
                throw failed(
                        "No default branch gives push "
                                + push.get(0)
                                + " its base "
                                + push.get(1)
                                + "; the stand-in serves no pull requests stacked on one another");
            }
        }
        git.updateRef(DEFAULT_BRANCH, branch);
        git.read("symbolic-ref", "HEAD", DEFAULT_BRANCH);
        defaultBranchChecked = checked;
    }

    private static void overlayLocalState(Push push, ObjectNode result) {
        final ObjectNode properties = result.with("properties");
        final String thread = properties.path("porelo/threadId").asText(null);
        if (thread != null) {
            final String state = push.localThreadState.get(thread);
            if (state != null) {
                properties.put("porelo/threadState", state);
            }
        }
    }

    static String cursor(Push push, Push.Run run, int offset) {
        final String raw =
                push.name + "\n" + push.commit + "\n" + run.id + "\n" + run.round + "\n" + offset;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static int offset(String cursor, Push push, Push.Run run) {
        try {
            final String[] parts =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                            .split("\n", -1);
            if (parts.length == 5
                    && parts[0].equals(push.name)
                    && parts[1].equals(push.commit)
                    && parts[2].equals(run.id)
                    && parts[3].equals(Integer.toString(run.round))) {
                final int offset = Integer.parseInt(parts[4]);
                if (offset > 0 && offset < run.results.size()) {
                    return offset;
                }
            }
        } catch (IllegalArgumentException ex) {
            // Not one of ours: refused below.
        }
        throw refusal("CursorRefused", Map.of("cursor", cursor));
    }

    private void gate(Map<String, Object> parameters) {
        final String project = required(parameters, "project");
        if (!PROJECT.equals(project)) {
            throw refusal("NoSuchProject", Map.of("project", project));
        }
        final String repository = optional(parameters, "repository");
        if (repository != null && !REPOSITORY.equals(repository)) {
            throw failed("The stand-in's project has one repository, " + REPOSITORY);
        }
        // Every call on the project, so no push is served with a base its mirror cannot give.
        defaultBranch();
    }

    private Push push(Map<String, Object> parameters) {
        final String name = required(parameters, "name");
        final Push push = pushes.get(name);
        if (push == null) {
            throw refusal("NoSuchPush", Map.of("name", name));
        }
        return push;
    }

    private String waiting(String at) {
        try {
            final Duration waited = Duration.between(Instant.parse(at), clock.instant());
            final long minutes = Math.max(0, waited.toMinutes());
            return minutes < 120 ? minutes + " minutes" : waited.toHours() + " hours";
        } catch (java.time.DateTimeException ex) {
            return "";
        }
    }

    private static String required(Map<String, Object> parameters, String name) {
        final Object value = parameters.get(name);
        if (!(value instanceof String text)) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", name));
        }
        return text;
    }

    private static @Nullable String optional(Map<String, Object> parameters, String name) {
        final Object value = parameters.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", name));
        }
        return text;
    }

    private static VarlinkException refused(ReviewRefusal ex) {
        final String detail = String.valueOf(ex.getMessage());
        return switch (ex.reason()) {
            case PROFILE_VIOLATION ->
                    refusal(
                            "ProfileViolation",
                            Map.of("detail", detail, "rule", detail, "path", ex.path()));
            case TOO_LARGE ->
                    refusal(
                            "TooLarge",
                            Map.of("detail", detail, "limit", detail, "path", ex.path()));
            case FORBIDDEN_FIELD, UNSAFE_TEXT, FIX_OUTSIDE_ANCHOR, OLD_SIDE_FIX ->
                    refusal(ex.reason().error(), Map.of("detail", detail, "path", ex.path()));
            case NOT_SUPPORTED ->
                    refusal("AdapterRefused", Map.of("error", "NotSupported", "detail", detail));
        };
    }

    static VarlinkException refusal(String error, Map<String, Object> parameters) {
        return new VarlinkException(INTERFACE + "." + error, parameters);
    }

    /** What makes two Comment calls the same call: the document, the parent and the forge. */
    private static String callKey(Map<String, Object> parameters, String sarif) {
        final String replyTo = optional(parameters, "replyTo");
        return (replyTo == null ? "no parent" : "parent " + replyTo)
                + "\n"
                + flag(parameters, "toForge")
                + "\n"
                + sarif;
    }

    /** An optional bool: absent is false, anything but a bool is refused. */
    private static boolean flag(Map<String, Object> parameters, String name) {
        final Object value = parameters.get(name);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw invalid(name);
        }
        return flag;
    }

    private static VarlinkException invalid(String parameter) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", parameter));
    }

    static VarlinkException failed(String message) {
        return refusal("Failed", Map.of("message", message));
    }

    private static String json(Map<String, Object> answer) {
        try {
            return JSON.writeValueAsString(answer);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("An answer could not be written", ex);
        }
    }

    private static String idl() {
        try (InputStream in = StandIn.class.getResourceAsStream("org.fuin.porelo.Tasks1.varlink")) {
            return new String(
                    Objects.requireNonNull(in, "the interface copy").readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
