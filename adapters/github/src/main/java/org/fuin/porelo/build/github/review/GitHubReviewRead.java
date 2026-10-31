package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.AuthorAssociation;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.PullRequestReview;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.jspecify.annotations.Nullable;

/**
 * Converts a pull request's reviews, review comments, general comments and threads, as GitHub
 * answers them, to results of Porelo's review profile. It writes native IDs only; every Porelo ID
 * and authority field is the daemon's. A native item whose conversion would lose anchor, identity,
 * state or edit is held: named in {@link Read#held()}, left out, and the read is not complete.
 */
public final class GitHubReviewRead {

    /** A line anchor spans at most this many lines. */
    public static final int LINE_SPAN_LIMIT = 100;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_=-]{1,128}");

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final Pattern SUGGESTION_OPENING = Pattern.compile("(`{3,})suggestion[ \\t]*");

    private final String forgeInstance;

    private GitHubReviewRead(String forgeInstance) {
        this.forgeInstance = forgeInstance;
    }

    /**
     * One review thread as GraphQL answers it, with every comment of it, all pages read.
     *
     * @param thread The thread.
     * @param comments Every comment of it, oldest first.
     */
    public record Thread(GitHubGraphQl.ReviewThread thread, List<GitHubGraphQl.Comment> comments) {

        /** Copies the comments. */
        public Thread {
            comments = List.copyOf(comments);
        }
    }

    /**
     * What GitHub answered about one pull request.
     *
     * @param reviews Its reviews.
     * @param comments Its review comments.
     * @param issueComments Its general comments.
     * @param threads Its review threads.
     * @param complete Whether every list was read to its end.
     */
    public record Native(
            List<PullRequestReview> reviews,
            List<PullRequestReviewComment> comments,
            List<IssueComment> issueComments,
            List<Thread> threads,
            boolean complete) {

        /** Copies the lists. */
        public Native {
            reviews = List.copyOf(reviews);
            comments = List.copyOf(comments);
            issueComments = List.copyOf(issueComments);
            threads = List.copyOf(threads);
        }
    }

    /**
     * A native item left out of a read, and why.
     *
     * @param kind {@code review}, {@code comment} or {@code issueComment}.
     * @param id Its REST ID.
     * @param reason What would be lost, naming the field.
     */
    public record Held(String kind, long id, String reason) {}

    /**
     * The converted read.
     *
     * @param results The results, in the forge's order: reviews, then review comments, then general
     *     comments.
     * @param losses Each loss's code and how often it happened.
     * @param held What was left out.
     * @param completeness {@code complete} or {@code partial}.
     * @param verdict The observed aggregate verdict, or {@code null} when there is no review.
     */
    public record Read(
            List<ObjectNode> results,
            Map<String, Integer> losses,
            List<Held> held,
            String completeness,
            @Nullable String verdict) {

        /** Copies the collections. */
        public Read {
            results = List.copyOf(results);
            losses = Map.copyOf(losses);
            held = List.copyOf(held);
        }
    }

    /**
     * Converts one pull request's answers.
     *
     * @param forgeInstance The forge's host, such as {@code github.com}; a viewer link on another
     *     host is dropped.
     * @param answers What GitHub answered.
     * @return The results, losses and held items.
     */
    public static Read read(String forgeInstance, Native answers) {
        return new GitHubReviewRead(forgeInstance).convert(answers);
    }

    private Read convert(Native answers) {
        final Map<String, Integer> losses = new TreeMap<>();
        final List<Held> held = new ArrayList<>();
        final List<ObjectNode> results = new ArrayList<>();
        for (final PullRequestReview review : answers.reviews()) {
            item("review", review.id(), () -> review(review, losses), results, held);
        }
        final Map<Long, Thread> threadOf = threadsByComment(answers.threads());
        for (final PullRequestReviewComment comment : answers.comments()) {
            item(
                    "comment",
                    comment.id(),
                    () -> reviewComment(comment, threadOf.get(comment.id()), losses),
                    results,
                    held);
        }
        for (final IssueComment comment : answers.issueComments()) {
            item("issueComment", comment.id(), () -> issueComment(comment, losses), results, held);
        }
        final String completeness = answers.complete() && held.isEmpty() ? "complete" : "partial";
        return new Read(results, losses, held, completeness, verdict(answers.reviews()));
    }

    /** Converts one item, or holds it with the reason its conversion gave. */
    private static void item(
            String kind,
            long id,
            java.util.function.Supplier<ObjectNode> conversion,
            List<ObjectNode> results,
            List<Held> held) {
        try {
            results.add(conversion.get());
        } catch (HeldItem ex) {
            held.add(new Held(kind, id, ex.reason));
        }
    }

    private ObjectNode review(PullRequestReview review, Map<String, Integer> losses) {
        final ObjectNode result = comment();
        final String state = reviewState(review.state());
        final boolean bodyless = review.body().isEmpty();
        result.putObject("message")
                .put("text", bodyless ? bodylessText(state) : text(review.body(), losses));
        provenance(result, review.submittedAt(), null);
        viewer(result, review.htmlUrl(), losses);
        final ObjectNode properties = result.putObject("properties");
        properties.put("porelo/anchorKind", "general");
        author(properties, review.user(), review.authorAssociation());
        properties.put("porelo/nativeReviewId", Long.toString(review.id()));
        properties.put("porelo/nativeReviewNodeId", nodeId(review.nodeId(), "node_id"));
        properties.put("porelo/reviewState", state);
        final String commit = review.commitId();
        if (commit == null || !COMMIT.matcher(commit).matches()) {
            throw new HeldItem("commit_id is not a full commit");
        }
        properties.put("porelo/reviewCommitId", commit);
        properties.put("porelo/bodyless", bodyless);
        properties.put("porelo/draft", "pending".equals(state));
        return result;
    }

    private ObjectNode reviewComment(
            PullRequestReviewComment comment,
            @Nullable Thread thread,
            Map<String, Integer> losses) {
        if (thread == null) {
            throw new HeldItem("no review thread names this comment");
        }
        agrees(comment, thread.thread());
        final ObjectNode result = comment();
        final boolean outdated = thread.thread().isOutdated();
        final String side = side(comment);
        final boolean newSide = "new".equals(side);
        final Suggestion suggestion =
                newSide && !outdated && comment.line() != null
                        ? Suggestion.of(comment.body())
                        : Suggestion.none(comment.body());
        result.putObject("message").put("text", text(suggestion.text(), losses));
        final boolean file = comment.subjectType() == PullRequestReviewComment.SubjectTypeEnum.FILE;
        final Integer line = comment.line();
        final int start =
                comment.startLine() != null ? comment.startLine() : line == null ? 0 : line;
        if (file) {
            location(result, comment.path(), null, 0);
        } else if (line != null) {
            span(start, line);
            location(result, comment.path(), start, line);
            if (suggestion.fix() != null) {
                fix(result, comment.path(), start, line, suggestion.fix());
            }
        } else if (!outdated) {
            throw new HeldItem("line is null on a thread that is not outdated");
        }
        provenance(result, comment.createdAt(), comment.updatedAt());
        viewer(result, comment.htmlUrl(), losses);
        final ObjectNode properties = result.putObject("properties");
        properties.put("porelo/anchorKind", file ? "file" : "line");
        properties.put("porelo/diffSide", side);
        author(properties, comment.user(), comment.authorAssociation());
        properties.put("porelo/nativeCommentId", Long.toString(comment.id()));
        properties.put("porelo/nativeCommentKind", "review");
        properties.put("porelo/nativeCommentNodeId", nodeId(comment.nodeId(), "node_id"));
        properties.put("porelo/nativeThreadId", nodeId(thread.thread().id(), "thread id"));
        if (comment.pullRequestReviewId() != null) {
            properties.put("porelo/nativeReviewId", Long.toString(comment.pullRequestReviewId()));
        }
        if (comment.inReplyToId() != null) {
            properties.put("porelo/nativeReplyTo", Long.toString(comment.inReplyToId()));
        }
        properties.put("porelo/threadState", thread.thread().isResolved() ? "resolved" : "open");
        resolver(properties, thread.thread());
        properties.put("porelo/outdated", outdated);
        properties.put("porelo/draft", false);
        if (outdated) {
            original(properties, comment, side, file);
        }
        return result;
    }

    private ObjectNode issueComment(IssueComment comment, Map<String, Integer> losses) {
        final String body = comment.body();
        if (body == null) {
            throw new HeldItem("body is null");
        }
        final ObjectNode result = comment();
        result.putObject("message").put("text", text(body, losses));
        provenance(result, comment.createdAt(), comment.updatedAt());
        viewer(result, comment.htmlUrl(), losses);
        final ObjectNode properties = result.putObject("properties");
        properties.put("porelo/anchorKind", "general");
        author(properties, comment.user(), comment.authorAssociation());
        properties.put("porelo/nativeCommentId", Long.toString(comment.id()));
        properties.put("porelo/nativeCommentKind", "issue");
        properties.put("porelo/nativeCommentNodeId", nodeId(comment.nodeId(), "node_id"));
        properties.put("porelo/draft", false);
        return result;
    }

    /** The REST comment and the GraphQL thread it belongs to must say the same of its place. */
    private static void agrees(
            PullRequestReviewComment comment, GitHubGraphQl.ReviewThread thread) {
        if (!comment.path().equals(thread.path())) {
            throw new HeldItem("path differs from its thread's");
        }
        final PullRequestReviewComment.SideEnum side = comment.side();
        if (side != null && !side.getValue().equals(thread.diffSide())) {
            throw new HeldItem("side differs from its thread's diffSide");
        }
        if (!java.util.Objects.equals(comment.line(), thread.line())) {
            throw new HeldItem("line differs from its thread's line");
        }
        final PullRequestReviewComment.StartSideEnum startSide = comment.startSide();
        if (startSide != null && side != null && !startSide.getValue().equals(side.getValue())) {
            throw new HeldItem("start_side differs from side");
        }
    }

    private static String side(PullRequestReviewComment comment) {
        final PullRequestReviewComment.SideEnum side = comment.side();
        if (side == null) {
            throw new HeldItem("side is null");
        }
        return side == PullRequestReviewComment.SideEnum.LEFT ? "old" : "new";
    }

    private static void span(int start, int end) {
        if (start < 1 || end < start) {
            throw new HeldItem("start_line and line are not a span");
        }
        if (end - start + 1 > LINE_SPAN_LIMIT) {
            throw new HeldItem("a span over " + LINE_SPAN_LIMIT + " lines");
        }
    }

    private static void original(
            ObjectNode properties, PullRequestReviewComment comment, String side, boolean file) {
        final String commit = comment.originalCommitId();
        if (!COMMIT.matcher(commit).matches()) {
            throw new HeldItem("original_commit_id is not a full commit");
        }
        properties.put("porelo/originalRevisionId", commit);
        properties.put("porelo/originalPath", pathUri(comment.path()));
        final Integer line = comment.originalLine();
        if (!file && line != null) {
            final int start =
                    comment.originalStartLine() != null ? comment.originalStartLine() : line;
            span(start, line);
            properties.put("porelo/originalStartLine", Integer.toString(start));
            properties.put("porelo/originalEndLine", Integer.toString(line));
        }
        properties.put("porelo/originalDiffSide", side);
    }

    private static void location(ObjectNode result, String path, @Nullable Integer start, int end) {
        final ObjectNode physical =
                result.putArray("locations").addObject().putObject("physicalLocation");
        physical.putObject("artifactLocation").put("uri", pathUri(path));
        if (start != null) {
            physical.putObject("region").put("startLine", start).put("endLine", end);
        }
    }

    private static void fix(ObjectNode result, String path, int start, int end, String inserted) {
        final String refused = ReviewText.refusedCharacter(inserted);
        if (refused != null) {
            throw new HeldItem("the suggestion holds " + refused);
        }
        final String[] lines = inserted.split("\n", -1);
        if (lines.length > end - start + 1 + GitHubReviewWrite.FIX_EXTRA_LINES
                || inserted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > ReviewText.BODY_LIMIT
                || java.util.Arrays.stream(lines)
                        .anyMatch(
                                l ->
                                        l.codePointCount(0, l.length())
                                                > GitHubReviewWrite.FIX_LINE_LIMIT)) {
            throw new HeldItem("a suggestion over the profile's limits");
        }
        final ObjectNode change =
                result.putArray("fixes").addObject().putArray("artifactChanges").addObject();
        change.putObject("artifactLocation").put("uri", pathUri(path));
        final ObjectNode replacement = change.putArray("replacements").addObject();
        replacement.putObject("deletedRegion").put("startLine", start).put("endLine", end);
        replacement.putObject("insertedContent").put("text", inserted);
    }

    private void author(
            ObjectNode properties,
            @Nullable SimpleUser user,
            @Nullable AuthorAssociation association) {
        properties.put("porelo/sourceKind", "forge");
        properties.put("porelo/forgeInstanceId", forgeInstance);
        if (user == null) {
            properties.put("porelo/authorId", "unknown");
            properties.put("porelo/authorType", "unknown");
        } else {
            properties.put("porelo/authorId", Long.toString(positive(user.id(), "user.id")));
            properties.put("porelo/authorDisplayName", displayName(user.login()));
            properties.put("porelo/authorNodeId", nodeId(user.nodeId(), "user.node_id"));
            properties.put("porelo/authorType", accountType(user.type()));
        }
        if (association != null) {
            properties.put("porelo/authorAssociation", association.getValue());
        }
        properties.put("porelo/authorMember", member(association));
    }

    private static void resolver(ObjectNode properties, GitHubGraphQl.ReviewThread thread) {
        final GitHubGraphQl.Account by = thread.resolvedBy();
        if (!thread.isResolved()) {
            return;
        }
        if (by == null || by.databaseId() == null) {
            properties.put("porelo/resolverId", "unknown");
            properties.put("porelo/resolverType", "unknown");
        } else {
            properties.put(
                    "porelo/resolverId", Long.toString(positive(by.databaseId(), "resolvedBy")));
            properties.put("porelo/resolverType", "user");
        }
        // Whether the resolver is a member is not in what GraphQL answers for a thread.
        properties.put("porelo/resolverMember", "unknown");
    }

    /** OWNER and MEMBER say the author belongs to the owning organization; others do not tell. */
    private static String member(@Nullable AuthorAssociation association) {
        return association == AuthorAssociation.OWNER || association == AuthorAssociation.MEMBER
                ? "true"
                : "unknown";
    }

    private static String accountType(String type) {
        return switch (type) {
            case "User" -> "user";
            case "Bot" -> "bot";
            case "Organization" -> "organization";
            default -> "unknown";
        };
    }

    private static String reviewState(String state) {
        return switch (state) {
            case "APPROVED" -> "approved";
            case "CHANGES_REQUESTED" -> "changesRequested";
            case "COMMENTED" -> "commented";
            case "DISMISSED" -> "dismissed";
            case "PENDING" -> "pending";
            default -> throw new HeldItem("state " + state + " is not one the profile knows");
        };
    }

    /** A review without a body says only its state, in a fixed text that is not the reviewer's. */
    private static String bodylessText(String state) {
        return switch (state) {
            case "approved" -> "Approved.";
            case "changesRequested" -> "Changes requested.";
            case "dismissed" -> "Dismissed.";
            case "pending" -> "Pending.";
            default -> "Commented.";
        };
    }

    /**
     * The run's verdict: each reviewer's latest verdict that is not a comment, then changes
     * requested over approved over commented; dismissed when every review is.
     */
    static @Nullable String verdict(List<PullRequestReview> reviews) {
        final Map<String, String> latest = new LinkedHashMap<>();
        boolean any = false;
        boolean allDismissed = true;
        for (final PullRequestReview review : reviews) {
            if ("PENDING".equals(review.state())) {
                continue;
            }
            any = true;
            final String reviewer =
                    review.user() == null ? "unknown" : Long.toString(review.user().id());
            final String state = review.state();
            allDismissed &= "DISMISSED".equals(state);
            if (!"COMMENTED".equals(state) || !latest.containsKey(reviewer)) {
                latest.put(reviewer, state);
            }
        }
        if (!any) {
            return null;
        }
        if (allDismissed) {
            return "dismissed";
        }
        if (latest.containsValue("CHANGES_REQUESTED")) {
            return "changesRequested";
        }
        return latest.containsValue("APPROVED") ? "approved" : "commented";
    }

    private static Map<Long, Thread> threadsByComment(List<Thread> threads) {
        final Map<Long, Thread> out = new HashMap<>();
        for (final Thread thread : threads) {
            for (final GitHubGraphQl.Comment comment : thread.comments()) {
                if (comment.databaseId() != null) {
                    out.put(comment.databaseId(), thread);
                }
            }
        }
        return out;
    }

    private static String text(String body, Map<String, Integer> losses) {
        final ReviewText.Read read = ReviewText.fromForge(body);
        if (read.isHeld()) {
            throw new HeldItem("the body holds " + read.held());
        }
        read.losses().forEach(loss -> losses.merge(loss, 1, Integer::sum));
        return java.util.Objects.requireNonNull(read.text());
    }

    private static String displayName(String login) {
        if (ReviewText.refusedCharacter(login) != null
                || login.codePointCount(0, login.length()) > ReviewText.DISPLAY_NAME_LIMIT) {
            throw new HeldItem("the author's login is not a display name");
        }
        return login;
    }

    private static String nodeId(String id, String field) {
        if (!NODE_ID.matcher(id).matches()) {
            throw new HeldItem(field + " is not a node ID");
        }
        return id;
    }

    private static long positive(long id, String field) {
        if (id < 1) {
            throw new HeldItem(field + " is not a positive ID");
        }
        return id;
    }

    private static void provenance(
            ObjectNode result, @Nullable OffsetDateTime created, @Nullable OffsetDateTime updated) {
        if (created == null) {
            return;
        }
        final ObjectNode provenance = result.putObject("provenance");
        provenance.put("firstDetectionTimeUtc", utc(created));
        if (updated != null) {
            provenance.put("lastDetectionTimeUtc", utc(updated));
        }
    }

    private static String utc(OffsetDateTime time) {
        return UTC.format(
                time.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }

    private void viewer(ObjectNode result, URI link, Map<String, Integer> losses) {
        if ("https".equals(link.getScheme())
                && forgeInstance.equalsIgnoreCase(link.getAuthority())
                && link.getRawUserInfo() == null
                && link.toASCIIString().length() <= 1024) {
            result.put("hostedViewerUri", link.toASCIIString());
        } else {
            losses.merge("link-dropped", 1, Integer::sum);
        }
    }

    private static String pathUri(String path) {
        try {
            return RepositoryPath.uri(path);
        } catch (IllegalArgumentException ex) {
            throw new HeldItem("path: " + String.valueOf(ex.getMessage()));
        }
    }

    private static ObjectNode comment() {
        final ObjectNode result = NODES.objectNode();
        result.put("kind", "review");
        result.put("level", "none");
        return result;
    }

    /** What a comment's body says: its text, and the one suggestion it makes, if any. */
    private record Suggestion(String text, @Nullable String fix) {

        static Suggestion none(String body) {
            return new Suggestion(body, null);
        }

        /** Only a fence standing in prose is a suggestion; one quoted in a longer fence is code. */
        static Suggestion of(String body) {
            final String lines = body.replace("\r\n", "\n").replace('\r', '\n');
            int[] found = null;
            String fix = null;
            for (final int[] fence : ReviewText.fences(lines)) {
                final String inner = suggestion(lines.substring(fence[0], fence[1]));
                if (inner != null) {
                    if (found != null) {
                        throw new HeldItem("more than one suggestion in one comment");
                    }
                    found = fence;
                    fix = inner;
                }
            }
            if (found == null || fix == null) {
                return none(body);
            }
            final String rest = (lines.substring(0, found[0]) + lines.substring(found[1])).strip();
            return new Suggestion(rest.isEmpty() ? "Suggested change." : rest, fix);
        }

        /** A fence's lines when it is a closed suggestion fence, else {@code null}. */
        private static @Nullable String suggestion(String fence) {
            final String[] lines = fence.split("\n", -1);
            final var opening = SUGGESTION_OPENING.matcher(lines[0]);
            if (!opening.matches()) {
                return null;
            }
            final String last = lines[lines.length - 1].strip();
            if (lines.length < 2
                    || last.length() < opening.group(1).length()
                    || !last.chars().allMatch(c -> c == '`')) {
                throw new HeldItem("a suggestion fence that is not closed");
            }
            if (lines.length == 2) {
                // GitHub deletes the lines; a whole-line fix of the profile cannot say that.
                throw new HeldItem("a suggestion that deletes its lines");
            }
            return String.join("\n", java.util.Arrays.asList(lines).subList(1, lines.length - 1));
        }
    }

    /** Why one native item is held; caught per item, never thrown out of a read. */
    private static final class HeldItem extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        HeldItem(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }
}
