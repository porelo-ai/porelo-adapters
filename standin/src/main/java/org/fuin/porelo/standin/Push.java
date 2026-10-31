package org.fuin.porelo.standin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.build.github.review.GitHubReviewRead;
import org.jspecify.annotations.Nullable;

/**
 * One push waiting at the stand-in's gate, from a GitHub pull request or a fixture, with its runs
 * and what the stand-in decided in the daemon's place. Every Porelo ID - a result's guid, a
 * thread's - is made here, the way only the daemon makes them, and kept while the stand-in runs.
 */
final class Push {

    /** Associations GitHub gives a repository's own people; their comments are E1, others E0. */
    private static final Set<String> E1_ASSOCIATIONS = Set.of("OWNER", "MEMBER", "COLLABORATOR");

    final String name;

    final String subject;

    final String at;

    /** The pull request it comes from, or {@code null} for a fixture. */
    final @Nullable Long pullRequest;

    final String sourceLevel;

    final String riskClass;

    final Map<String, String> fileClasses;

    final int approvalsNeeded;

    final @Nullable String refuseApprove;

    final @Nullable String refuseToForge;

    final String shows;

    volatile String commit;

    volatile String base;

    /** The runs, by id, in the order a person reads them. */
    final Map<String, Run> runs = new LinkedHashMap<>();

    /** A guid per native item, so a refreshed read keeps them. */
    private final Map<String, String> guids = new ConcurrentHashMap<>();

    /** A thread id per native thread or per local root. */
    private final Map<String, String> threads = new ConcurrentHashMap<>();

    /** Threads resolved or reopened here, by thread id; nothing of this goes to the forge. */
    final Map<String, String> localThreadState = new ConcurrentHashMap<>();

    /** A Comment's caller id, with the document it came with and what it answered. */
    final Map<String, Map.Entry<String, Map<String, Object>>> commentIds =
            new ConcurrentHashMap<>();

    Push(
            String name,
            String subject,
            String at,
            @Nullable Long pullRequest,
            String sourceLevel,
            String riskClass,
            Map<String, String> fileClasses,
            int approvalsNeeded,
            @Nullable String refuseApprove,
            @Nullable String refuseToForge,
            String shows,
            String commit,
            String base) {
        this.name = name;
        this.subject = subject;
        this.at = at;
        this.pullRequest = pullRequest;
        this.sourceLevel = sourceLevel;
        this.riskClass = riskClass;
        this.fileClasses = Map.copyOf(fileClasses);
        this.approvalsNeeded = approvalsNeeded;
        this.refuseApprove = refuseApprove;
        this.refuseToForge = refuseToForge;
        this.shows = shows;
        this.commit = commit;
        this.base = base;
    }

    /** One producer's run on this push, in one round. */
    static final class Run {

        final String id;

        final String name;

        final String kind;

        final int round;

        /** The document around the results: root and run, with no results. */
        volatile ObjectNode shell;

        /** Results as the daemon would store them: stamped. */
        final List<ObjectNode> results = new ArrayList<>();

        volatile List<GitHubReviewRead.Held> held = List.of();

        Run(String id, String name, String kind, int round, ObjectNode shell) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.round = round;
            this.shell = shell;
        }

        String completeness() {
            return shell.at("/runs/0/properties/porelo~1completeness").asText("unknown");
        }
    }

    /**
     * The current round: the highest any run has.
     *
     * @return The round, 1 when there is no run.
     */
    int round() {
        return runs.values().stream().mapToInt(r -> r.round).max().orElse(1);
    }

    /**
     * Adds a run or replaces the run of the same producer and round, keeping its id.
     *
     * @param name The producer, as a person reads it.
     * @param kind person, task, forge or scanner.
     * @param round The round.
     * @param shell The document without results.
     * @param results The results as the producer wrote them; stamped here.
     * @param held What the producer's read left out.
     * @return The run.
     */
    synchronized Run putRun(
            String name,
            String kind,
            int round,
            ObjectNode shell,
            List<ObjectNode> results,
            List<GitHubReviewRead.Held> held) {
        Run run = null;
        for (final Run existing : runs.values()) {
            if (existing.name.equals(name) && existing.round == round) {
                run = existing;
            }
        }
        if (run == null) {
            run = new Run(UUID.randomUUID().toString(), name, kind, round, shell);
            runs.put(run.id, run);
        }
        stampRun(shell, run);
        run.shell = shell;
        run.held = List.copyOf(held);
        final List<ObjectNode> stamped = new ArrayList<>();
        for (final ObjectNode result : results) {
            stamped.add(stamp(result, kind));
        }
        synchronized (run.results) {
            run.results.clear();
            run.results.addAll(stamped);
        }
        return run;
    }

    /**
     * Finds a result of this push by its guid, in any run.
     *
     * @param guid The guid.
     * @return The result, or {@code null}.
     */
    synchronized @Nullable ObjectNode result(String guid) {
        for (final Run run : runs.values()) {
            synchronized (run.results) {
                for (final ObjectNode result : run.results) {
                    if (guid.equals(result.path("guid").asText())) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Tells whether a thread id is one of this push's.
     *
     * @param thread The thread id.
     * @return {@code true} if it is.
     */
    boolean hasThread(String thread) {
        return threads.containsValue(thread);
    }

    /**
     * Makes a new thread id for a local root.
     *
     * @param guid The root's guid.
     * @return The thread id.
     */
    String newThread(String guid) {
        return threads.computeIfAbsent("local:" + guid, k -> UUID.randomUUID().toString());
    }

    /**
     * Makes a guid for a local result.
     *
     * @return The guid.
     */
    String newGuid() {
        return UUID.randomUUID().toString();
    }

    /** Stamps what the daemon stamps on a run: its guid, the project, repository and round. */
    private void stampRun(ObjectNode shell, Run run) {
        final ObjectNode out = (ObjectNode) shell.at("/runs/0");
        out.putObject("automationDetails").put("guid", run.id);
        final ObjectNode properties = out.with("properties");
        properties.put("porelo/projectId", StandIn.PROJECT);
        properties.put("porelo/repositoryId", StandIn.REPOSITORY);
        properties.put("porelo/reviewRound", Integer.toString(run.round));
    }

    /**
     * Stamps what only the daemon stamps on a result read from a producer: its guid, its thread,
     * the result it replies to, and its source level - the last the stand-in's fixed value.
     */
    private ObjectNode stamp(ObjectNode result, String kind) {
        final ObjectNode properties = result.with("properties");
        final String guid = guids.computeIfAbsent(nativeKey(result), k -> newGuid());
        result.put("guid", guid);
        final String nativeThread = properties.path("porelo/nativeThreadId").asText(null);
        if (nativeThread != null) {
            final String thread =
                    threads.computeIfAbsent(
                            "native:" + nativeThread, k -> UUID.randomUUID().toString());
            properties.put("porelo/threadId", thread);
            final String local = localThreadState.get(thread);
            if (local != null) {
                properties.put("porelo/threadState", local);
            }
        }
        final String replyTo = properties.path("porelo/nativeReplyTo").asText(null);
        if (replyTo != null) {
            properties.put(
                    "porelo/replyTo",
                    // GitHub replies only to a review comment.
                    guids.computeIfAbsent("comment:review:" + replyTo, k -> newGuid()));
        }
        final String level = level(kind, properties);
        if (level != null) {
            properties.put("porelo/sourceLevel", level);
        }
        return result;
    }

    private static @Nullable String level(String kind, ObjectNode properties) {
        return switch (kind) {
            case "forge" ->
                    E1_ASSOCIATIONS.contains(properties.path("porelo/authorAssociation").asText())
                            ? "E1"
                            : "E0";
            case "person" -> "I1";
            case "task" -> "A";
            default -> null;
        };
    }

    private static String nativeKey(ObjectNode result) {
        final JsonNode properties = result.path("properties");
        final String comment = properties.path("porelo/nativeCommentId").asText(null);
        if (comment != null) {
            // Issue and review comments are numbered apart, so one ID can name two comments.
            return "comment:"
                    + properties.path("porelo/nativeCommentKind").asText()
                    + ":"
                    + comment;
        }
        final String review = properties.path("porelo/nativeReviewId").asText(null);
        if (review != null) {
            return "review:" + review;
        }
        // A fixture result without a native ID: its place in its run's text is its identity.
        return "text:" + result.toString();
    }

    /**
     * Counts open findings and open threads over the current round.
     *
     * @return Open findings, open threads.
     */
    synchronized int[] openCounts() {
        int findings = 0;
        final java.util.Set<String> openThreads = new java.util.HashSet<>();
        final int round = round();
        for (final Run run : runs.values()) {
            if (run.round != round) {
                continue;
            }
            synchronized (run.results) {
                for (final ObjectNode result : run.results) {
                    if ("fail".equals(result.path("kind").asText())) {
                        findings++;
                    }
                    final JsonNode properties = result.path("properties");
                    final String thread = properties.path("porelo/threadId").asText();
                    // A resolve here overrides what the producer said, as Comments shows it.
                    final String state =
                            localThreadState.getOrDefault(
                                    thread, properties.path("porelo/threadState").asText());
                    if ("open".equals(state)) {
                        openThreads.add(thread);
                    }
                }
            }
        }
        return new int[] {findings, openThreads.size()};
    }

    /**
     * Returns one page of a run's results.
     *
     * @param run The run.
     * @param from The first index.
     * @param size At most this many.
     * @return The results.
     */
    static ArrayNode page(Run run, int from, int size) {
        final ArrayNode out =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        synchronized (run.results) {
            for (int i = from; i < Math.min(run.results.size(), from + size); i++) {
                out.add(run.results.get(i).deepCopy());
            }
        }
        return out;
    }
}
