package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.Strictness;
import org.jspecify.annotations.Nullable;

/**
 * One person's review of a pull request on GitHub: its facts, its conversation as profile 2 pages,
 * and one released result written. Every call takes the REST base URL and the token lent for it,
 * keeps neither, and reaches no verdict: nothing here approves, requests changes or resolves.
 */
public final class GitHubReview {

    private final Supplier<HttpClient> http;

    private final Duration requestTimeout;

    /**
     * Constructor with the client's own request timeout.
     *
     * @param http Gives the client each call sends with: trusting the account's authorities,
     *     following no redirect.
     */
    public GitHubReview(Supplier<HttpClient> http) {
        this(http, GitHubClient.DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Constructor.
     *
     * @param http Gives the client each call sends with: trusting the account's authorities,
     *     following no redirect.
     * @param requestTimeout How long one request to GitHub may take.
     */
    public GitHubReview(Supplier<HttpClient> http, Duration requestTimeout) {
        this.http = http;
        this.requestTimeout = requestTimeout;
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
        final URI base = base(api);
        final PullRequestFacts.Address address = address(base, url);
        return reading(
                () -> {
                    final PullRequestFacts facts = new PullRequestFacts(rest(base));
                    PullRequestFacts.Facts read = facts.read(token, address);
                    if ("merged".equals(read.state())) {
                        read =
                                read.withMergeCommit(
                                        PullRequestFacts.mergeCommit(
                                                graphQl(base), token, address));
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
                        new PullRequestFacts(rest(base))
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
        final URI base = base(api);
        final PullRequestFacts.Address address = address(base, url);
        final String host = PullRequestFacts.webHost(base);
        return reading(
                () -> {
                    final GitHubClient rest = rest(base);
                    final PullRequestFacts.Facts facts =
                            new PullRequestFacts(rest).read(token, address);
                    final Conversation.Read read =
                            Conversation.read(
                                    host,
                                    new ConversationSource(rest, graphQl(base))
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
        final JsonNode results = run.path("results");
        if (!results.isArray() || results.size() != 1) {
            throw new ReviewFailure(
                    ReviewFailure.Kind.NOT_IN_PROFILE,
                    "a post carries exactly one result",
                    null,
                    "runs[0].results");
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
                    rest(base), token, address.owner(), address.repo(), address.number(), call);
        } catch (GitHubException ex) {
            throw ReviewFailure.of(
                    ex,
                    true,
                    call.replyTo() == null
                            ? ReviewFailure.Kind.NO_SUCH_PULL_REQUEST
                            : ReviewFailure.Kind.NO_SUCH_THREAD);
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

    private GitHubClient rest(URI base) {
        return new GitHubClient(
                base,
                URI.create("https://" + PullRequestFacts.webHost(base) + "/"),
                http.get(),
                Strictness.LENIENT,
                requestTimeout,
                GitHubClient.DEFAULT_RATE_LIMIT_DELAY);
    }

    private GitHubGraphQl graphQl(URI base) {
        return new GitHubGraphQl(
                graphQlBase(base),
                http.get(),
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
