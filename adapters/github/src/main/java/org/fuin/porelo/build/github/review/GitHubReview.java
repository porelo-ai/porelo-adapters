package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.fuin.porelo.build.http.Http;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.Strictness;
import org.jspecify.annotations.Nullable;

/**
 * One person's review of a pull request on GitHub: its facts, its conversation as profile 2 pages,
 * one released result written and one thread resolved. Every call takes the REST base URL and the
 * token lent for it, keeps neither, and reaches no verdict: nothing here approves, requests changes
 * or merges, and resolving is the only state of a thread it changes. Each call ends within its own
 * length, however many requests it takes.
 */
public final class GitHubReview {

    private final Supplier<HttpClient> http;

    private final Duration requestTimeout;

    private final Duration callLength;

    private final Clock clock;

    /**
     * Constructor with the client's own request timeout and {@link Http#CALL} for a call.
     *
     * @param http Gives the client each call sends with: trusting the account's authorities,
     *     following no redirect.
     */
    public GitHubReview(Supplier<HttpClient> http) {
        this(http, GitHubClient.DEFAULT_REQUEST_TIMEOUT, Http.CALL, Clock.systemUTC());
    }

    /**
     * Constructor.
     *
     * @param http Gives the client each call sends with: trusting the account's authorities,
     *     following no redirect.
     * @param requestTimeout How long one request to GitHub may take.
     * @param call How long one call may take, all its requests together.
     * @param clock What time it is, for what a call has left.
     */
    public GitHubReview(
            Supplier<HttpClient> http, Duration requestTimeout, Duration call, Clock clock) {
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.callLength = call;
        this.clock = clock;
    }

    /**
     * Reads a pull request's facts.
     *
     * @param api The REST base URL.
     * @param token The token lent for this call.
     * @param url The pull request's URL.
     * @return Its facts.
     */
    public PullRequestFacts.Facts pullRequest(String api, String token, String url) {
        final HttpClient within = oneCall();
        final URI base = base(api);
        final PullRequestFacts.Address address = address(base, url);
        return reading(
                () -> {
                    final PullRequestFacts facts = new PullRequestFacts(rest(base, within));
                    PullRequestFacts.Facts read = facts.read(token, address);
                    if ("merged".equals(read.state())) {
                        read =
                                read.withMergeCommit(
                                        PullRequestFacts.mergeCommit(
                                                graphQl(base, within), token, address));
                    }
                    return read.with(facts.account(token));
                },
                ReviewFailure.Kind.NO_SUCH_PULL_REQUEST);
    }

    /**
     * Finds the open pull request from a branch into a repository, opening none.
     *
     * @param api The REST base URL.
     * @param token The token lent for this call.
     * @param upstream The repository's URL.
     * @param branch The branch's name.
     * @return The pull request's URL, or {@code null} when none is open.
     */
    public @Nullable String pullRequestOf(
            String api, String token, String upstream, String branch) {
        final HttpClient within = oneCall();
        final URI base = base(api);
        final PullRequestFacts.Address repository;
        try {
            repository = PullRequestFacts.repository(base, upstream);
        } catch (IllegalArgumentException ex) {
            throw new InvalidArgument("upstream: " + ex.getMessage());
        }
        try {
            PullRequestFacts.checkBranch(branch);
        } catch (IllegalArgumentException ex) {
            throw new InvalidArgument("branch: " + ex.getMessage());
        }
        return reading(
                () ->
                        new PullRequestFacts(rest(base, within))
                                .openOf(token, repository, branch, PullRequestFacts.webHost(base)),
                ReviewFailure.Kind.NO_SUCH_PULL_REQUEST);
    }

    /**
     * Reads one page of a pull request's conversation.
     *
     * @param api The REST base URL.
     * @param token The token lent for this call.
     * @param url The pull request's URL.
     * @param cursor Where the page begins, {@code null} for the first.
     * @return The page and the next page's cursor.
     */
    public ReviewPage.Page comments(String api, String token, String url, @Nullable String cursor) {
        final HttpClient within = oneCall();
        final URI base = base(api);
        final PullRequestFacts.Address address = address(base, url);
        final String host = PullRequestFacts.webHost(base);
        return reading(
                () -> {
                    final GitHubClient rest = rest(base, within);
                    final PullRequestFacts.Facts facts =
                            new PullRequestFacts(rest).read(token, address);
                    final Conversation.Read read =
                            Conversation.read(
                                    host,
                                    new ConversationSource(rest, graphQl(base, within))
                                            .read(
                                                    token,
                                                    address.owner(),
                                                    address.repo(),
                                                    address.number()));
                    final ReviewPage.Run run =
                            new ReviewPage.Run(
                                    facts.repository(), url, facts.head(), facts.mergeBase());
                    try {
                        return ReviewPage.page(
                                read.results(),
                                read.complete(),
                                cursor,
                                (taken, word) -> ReviewPage.document(run, taken, word));
                    } catch (IllegalArgumentException ex) {
                        throw new InvalidArgument("cursor: " + ex.getMessage());
                    }
                },
                ReviewFailure.Kind.NO_SUCH_PULL_REQUEST);
    }

    /**
     * Writes a released submission's one result as exactly one comment or reply. It is never sent
     * twice: a write whose answer was lost answers {@code WriteOutcomeUnknown}.
     *
     * @param api The REST base URL.
     * @param token The token lent for this call.
     * @param url The pull request's URL, which the submission must name.
     * @param submission The submission, already read and held to profile 2.
     * @return The thread written to, as a decimal string.
     */
    public String post(String api, String token, String url, JsonNode submission) {
        final HttpClient within = oneCall();
        final URI base = base(api);
        final PullRequestFacts.Address address = address(base, url);
        final JsonNode run = submission.path("runs").path(0);
        if (!url.equals(run.path("properties").path("porelo/pullRequest").asText())) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NOT_IN_PROFILE,
                    "the submission names another pull request",
                    null,
                    "runs[0].properties.porelo/pullRequest");
        }
        // A resolve comes through resolve alone: posting the result and dropping the list would
        // leave the threads open without a word.
        if (run.path("properties").has("porelo/resolveThreads")) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NOT_IN_PROFILE,
                    "a post resolves no thread",
                    null,
                    "runs[0].properties.porelo/resolveThreads");
        }
        final JsonNode results = run.path("results");
        if (!results.isArray() || results.size() != 1) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NOT_IN_PROFILE,
                    "a post carries exactly one result",
                    null,
                    "runs[0].results");
        }
        if (results.get(0).path("properties").has("porelo/judgment")) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NOT_IN_PROFILE,
                    "a judgment never reaches a forge",
                    null,
                    "runs[0].results[0].properties.porelo/judgment");
        }
        final String commit =
                run.path("versionControlProvenance").path(0).path("revisionId").asText();
        final SubmissionWrite.Call call;
        try {
            call = SubmissionWrite.render(results.get(0), commit);
        } catch (ReviewRefusal ex) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NOT_IN_PROFILE,
                    ex.getMessage() + " at " + ex.path(),
                    ex,
                    "runs[0].results[0]." + ex.path());
        }
        try {
            return SubmissionWrite.send(
                    rest(base, within),
                    token,
                    address.owner(),
                    address.repo(),
                    address.number(),
                    call);
        } catch (GitHubException ex) {
            throw ReviewFailure.of(
                    ex,
                    true,
                    call.replyTo() == null
                            ? ReviewFailure.Kind.NO_SUCH_PULL_REQUEST
                            : ReviewFailure.Kind.NO_SUCH_THREAD);
        }
    }

    /**
     * Marks one thread of a pull request resolved, as the account the token stands for. A thread
     * GitHub already shows resolved is done without a write. It is the one state the adapter
     * changes: nothing here reopens a thread.
     *
     * @param api The REST base URL.
     * @param token The token lent for this call.
     * @param url The pull request's URL.
     * @param thread The thread, named by its root comment's REST id as a decimal string.
     */
    public void resolve(String api, String token, String url, String thread) {
        final HttpClient within = oneCall();
        final URI base = base(api);
        final PullRequestFacts.Address address = address(base, url);
        final long root = root(thread);
        final GitHubGraphQl graphQl = graphQl(base, within);
        final ConversationSource.Found found;
        try {
            found =
                    new ConversationSource(rest(base, within), graphQl)
                            .thread(token, address.owner(), address.repo(), address.number(), root);
        } catch (GitHubException ex) {
            throw ReviewFailure.ofGraphQl(ex, false, ReviewFailure.Kind.NO_SUCH_PULL_REQUEST);
        } catch (ConversationSource.NoSuchPullRequest ex) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NO_SUCH_PULL_REQUEST, String.valueOf(ex.getMessage()), ex);
        }
        final GitHubGraphQl.ReviewThread open = found.thread();
        if (open == null) {
            if (found.complete()) {
                throw new ReviewFailure(
                        ReviewFailure.Kind.NO_SUCH_THREAD,
                        "the pull request holds no thread of that id",
                        null);
            }
            throw new IllegalStateException(
                    "the pull request's threads were not read to their end, and the thread is not"
                            + " among those read");
        }
        if (Boolean.TRUE.equals(open.isResolved())) {
            return;
        }
        final GitHubGraphQl.ThreadState now;
        try {
            now =
                    graphQl.resolveReviewThread(token, open.id())
                            .data()
                            .resolveReviewThread()
                            .thread();
        } catch (GitHubException ex) {
            throw ReviewFailure.ofGraphQl(ex, true, ReviewFailure.Kind.NO_SUCH_THREAD);
        }
        if (!open.id().equals(now.id()) || !Boolean.TRUE.equals(now.isResolved())) {
            throw new IllegalStateException(
                    "GitHub answered the resolve without showing the thread resolved");
        }
    }

    /** A thread's id as Porelo names it: its root comment's REST id, a positive decimal. */
    private static long root(String thread) {
        if (!thread.matches("[1-9][0-9]{0,18}")) {
            throw new InvalidArgument("thread: not a positive decimal id");
        }
        try {
            return Long.parseLong(thread);
        } catch (NumberFormatException ex) {
            throw new InvalidArgument("thread: not a positive decimal id");
        }
    }

    private static <T> T reading(Supplier<T> call, ReviewFailure.Kind missing) {
        try {
            return call.get();
        } catch (GitHubException ex) {
            throw ReviewFailure.of(ex, false, missing);
        } catch (ConversationSource.NoSuchPullRequest ex) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NO_SUCH_PULL_REQUEST, String.valueOf(ex.getMessage()), ex);
        }
    }

    /** The client for one call, which sends nothing once the call's length has passed. */
    private HttpClient oneCall() {
        return new CallDeadline(http.get(), clock, clock.instant().plus(callLength));
    }

    private GitHubClient rest(URI base, HttpClient within) {
        return new GitHubClient(
                base,
                URI.create("https://" + PullRequestFacts.webHost(base) + "/"),
                within,
                Strictness.LENIENT,
                requestTimeout,
                GitHubClient.DEFAULT_RATE_LIMIT_DELAY);
    }

    private GitHubGraphQl graphQl(URI base, HttpClient within) {
        return new GitHubGraphQl(
                graphQlBase(base),
                within,
                Strictness.LENIENT,
                requestTimeout,
                GitHubClient.DEFAULT_RATE_LIMIT_DELAY);
    }

    /**
     * The base the client puts {@code graphql} under: the REST base on github.com, {@code
     * https://<host>/api/} on an Enterprise Server, whose REST base is {@code /api/v3}.
     */
    static URI graphQlBase(URI base) {
        final String path = base.getRawPath() == null ? "" : base.getRawPath();
        return path.startsWith("/api/v3") ? base.resolve("/api/") : base;
    }

    private static URI base(String api) {
        try {
            final URI base = new URI(api);
            PullRequestFacts.webHost(base);
            return base;
        } catch (java.net.URISyntaxException | IllegalArgumentException ex) {
            throw new InvalidArgument("api: " + ex.getMessage());
        }
    }

    private static PullRequestFacts.Address address(URI base, String url) {
        try {
            return PullRequestFacts.address(base, url);
        } catch (IllegalArgumentException ex) {
            throw new InvalidArgument("url: " + ex.getMessage());
        }
    }

    /** A parameter the call cannot take; its words name the parameter, never a token. */
    public static final class InvalidArgument extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message Which parameter and why.
         */
        public InvalidArgument(String message) {
            super(message);
        }
    }
}
