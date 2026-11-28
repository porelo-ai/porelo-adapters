package org.fuin.porelo.build.forgejo.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.build.api.ReviewReader;
import org.fuin.porelo.build.api.review.ProfileCheck;
import org.fuin.porelo.build.api.review.SarifReading;
import org.fuin.porelo.build.api.review.SarifRefused;
import org.fuin.porelo.build.http.CallDeadline;
import org.fuin.porelo.build.review.ProfileResult;
import org.fuin.porelo.build.review.ReviewPage;
import org.fuin.porelo.build.review.ReviewRefusal;
import org.fuin.porelo.forge.forgejo.Delivery;
import org.fuin.porelo.forge.forgejo.ForgejoClient;
import org.fuin.porelo.forge.forgejo.ForgejoException;
import org.fuin.porelo.forge.forgejo.Page;
import org.fuin.porelo.forge.forgejo.RateLimit;
import org.fuin.porelo.forge.forgejo.Strictness;
import org.fuin.porelo.forge.forgejo.model.PRBranchInfo;
import org.fuin.porelo.forge.forgejo.model.PullRequest;
import org.fuin.porelo.forge.forgejo.model.PullReview;
import org.fuin.porelo.forge.forgejo.model.User;
import org.jspecify.annotations.Nullable;

/**
 * Answers Porelo's review contract for Forgejo: a pull request's facts, the open one of a branch,
 * its conversation as profile 2 pages and one released result written as a single-line comment.
 * Forgejo has no call to resolve a conversation, so {@code Resolve} answers {@code
 * ResolveUnsupported}. Every call takes the instance's API base and the token lent for it, keeps
 * neither, sends nothing before its arguments are checked, ends within its own length, and reaches
 * no verdict.
 */
public final class ForgejoReviewReader implements ReviewReader {

    /** At most this many reviews per page Forgejo is asked for. */
    static final int PER_PAGE = 50;

    /** At most this many pages of reviews or pull requests one call reads. */
    static final int PAGES = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,255}");

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private final Supplier<HttpClient> http;

    private final Duration requestTimeout;

    private final Duration callLength;

    private final Clock clock;

    /**
     * Constructor.
     *
     * @param http Gives the client each call sends with: trusting the account's authorities,
     *     following no redirect.
     * @param requestTimeout How long one request to Forgejo may take.
     * @param call How long one call may take, all its requests together.
     * @param clock What time it is, for what a call has left.
     */
    public ForgejoReviewReader(
            Supplier<HttpClient> http, Duration requestTimeout, Duration call, Clock clock) {
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.callLength = call;
        this.clock = clock;
    }

    @Override
    public ReviewFacts pullRequest(ForgeAccess access, String url) {
        final Address at = Address.pullRequest(access.api(), url);
        final ForgejoClient client = client(at.api());
        return answering(
                () -> facts(client, access.token(), at),
                ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
    }

    @Override
    public @Nullable String pullRequestOf(ForgeAccess access, String upstream, String branch) {
        final Address at = Address.repository(access.api(), upstream);
        if (!BRANCH.matcher(branch).matches() || branch.contains("..") || branch.startsWith("/")) {
            throw invalid("branch");
        }
        final ForgejoClient client = client(at.api());
        return answering(
                () -> {
                    for (int page = 1; page <= PAGES; page++) {
                        final Page<PullRequest> pulls =
                                client.pulls(
                                        access.token(),
                                        at.owner(),
                                        at.repo(),
                                        "open",
                                        page,
                                        PER_PAGE);
                        for (final PullRequest pull : pulls.items()) {
                            final PRBranchInfo head = pull.head();
                            if (head != null
                                    && branch.equals(head.ref())
                                    && sameRepository(head, at)
                                    && pull.number() != null
                                    && pull.number() > 0) {
                                return at.web()
                                        + at.owner()
                                        + "/"
                                        + at.repo()
                                        + "/pulls/"
                                        + pull.number();
                            }
                        }
                        if (!pulls.more()) {
                            return null;
                        }
                    }
                    throw new IllegalStateException(
                            "too many open pull requests to read in one call");
                },
                ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
    }

    @Override
    public ConversationPage comments(ForgeAccess access, String url, @Nullable String cursor) {
        final Address at = Address.pullRequest(access.api(), url);
        final ForgejoClient client = client(at.api());
        final ReviewPage.Page page =
                answering(
                        () -> {
                            final ReviewFacts facts = facts(client, access.token(), at);
                            final ForgejoReviewRead.Read read =
                                    ForgejoReviewRead.read(
                                            at.host(), conversation(client, access.token(), at));
                            final ReviewPage.Run run =
                                    new ReviewPage.Run(
                                            facts.repository(),
                                            url,
                                            facts.head(),
                                            facts.mergeBase());
                            try {
                                return ReviewPage.page(
                                        read.results(),
                                        read.complete(),
                                        cursor,
                                        (taken, word) -> ReviewPage.document(run, taken, word));
                            } catch (IllegalArgumentException ex) {
                                throw invalid("cursor");
                            }
                        },
                        ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
        final byte[] document = page.text().getBytes(StandardCharsets.UTF_8);
        try {
            ProfileCheck.check(ProfileCheck.Channel.ADAPTER, SarifReading.read(document));
        } catch (SarifRefused ex) {
            // A page this adapter wrote that profile 2 refuses is its own fault, never sent on.
            throw new IllegalStateException(
                    "the adapter wrote a page profile 2 refuses: " + ex.rule() + " at " + ex.path(),
                    ex);
        }
        return new ConversationPage(document, page.cursor());
    }

    @Override
    public String post(ForgeAccess access, String url, String document) {
        final Address at = Address.pullRequest(access.api(), url);
        final byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        try {
            ProfileCheck.check(ProfileCheck.Channel.TASK, SarifReading.read(bytes));
        } catch (SarifRefused ex) {
            throw ForgeReviewRefused.notInProfile(
                    ex.path().isEmpty() ? ex.rule().text() : ex.path());
        }
        final JsonNode run;
        try {
            run = JSON.readTree(bytes).path("runs").path(0);
        } catch (IOException ex) {
            throw new IllegalStateException("a submission SARIF read is no JSON", ex);
        }
        if (!url.equals(run.path("properties").path("porelo/pullRequest").asText())) {
            throw ForgeReviewRefused.notInProfile("runs[0].properties.porelo/pullRequest");
        }
        // A resolve list posted with the result would leave the threads open without a word.
        if (run.path("properties").has("porelo/resolveThreads")) {
            throw ForgeReviewRefused.notInProfile("runs[0].properties.porelo/resolveThreads");
        }
        final JsonNode results = run.path("results");
        if (!results.isArray() || results.size() != 1) {
            throw ForgeReviewRefused.notInProfile("runs[0].results");
        }
        if (results.get(0).path("properties").has("porelo/judgment")) {
            throw ForgeReviewRefused.notInProfile("runs[0].results[0].properties.porelo/judgment");
        }
        final String commit =
                run.path("versionControlProvenance").path(0).path("revisionId").asText();
        final ForgejoReviewWrite.Call call;
        try {
            call = ForgejoReviewWrite.render(results.get(0), commit);
        } catch (ReviewRefusal ex) {
            throw ForgeReviewRefused.notInProfile("runs[0].results[0]." + ex.path());
        }
        final ForgejoClient client = client(at.api());
        try {
            return ForgejoReviewWrite.send(
                    client, access.token(), at.owner(), at.repo(), at.number(), call);
        } catch (ForgejoException ex) {
            throw refused(ex, true, ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST, clock);
        } catch (IllegalStateException ex) {
            // The comment was written; only its id is unknown, so the write must not be repeated.
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN);
        }
    }

    @Override
    public void resolve(ForgeAccess access, String url, String thread) {
        Address.pullRequest(access.api(), url);
        throw new ForgeReviewRefused(ForgeReviewRefused.Reason.RESOLVE_UNSUPPORTED);
    }

    private static ReviewFacts facts(ForgejoClient client, String token, Address at) {
        final PullRequest pull = client.pull(token, at.owner(), at.repo(), at.number());
        final PRBranchInfo head = pull.head();
        final PRBranchInfo base = pull.base();
        if (head == null || base == null || head.repo() == null) {
            throw new IllegalStateException(
                    "Forgejo answered a pull request without its head or base");
        }
        final String repository = head.repo().htmlUrl();
        if (repository == null
                || !repository.startsWith("https://")
                || repository.length() > 2048) {
            throw new IllegalStateException(
                    "Forgejo answered a head repository that is no https address");
        }
        final String headSha = commit(head.sha(), "head");
        final String baseSha = commit(base.sha(), "base");
        final String mergeBase = commit(pull.mergeBase(), "merge base");
        final boolean merged = Boolean.TRUE.equals(pull.merged());
        final boolean closed = !merged && "closed".equals(pull.state());
        final ReviewFacts.State state =
                merged
                        ? ReviewFacts.State.MERGED
                        : closed ? ReviewFacts.State.CLOSED : ReviewFacts.State.OPEN;
        final OffsetDateTime decided = merged ? pull.mergedAt() : closed ? pull.closedAt() : null;
        if ((merged || closed) && decided == null) {
            throw new IllegalStateException(
                    "Forgejo answered a decided pull request without its time");
        }
        final String mergeCommit = merged ? commit(pull.mergeCommitSha(), "merge commit") : null;
        final String mergedHead = merged ? branch(base.ref()) : null;
        return new ReviewFacts(
                repository,
                headSha,
                baseSha,
                mergeBase,
                state,
                approved(client, token, at, headSha),
                mergeCommit,
                mergedHead,
                decided == null ? null : decided.toInstant().truncatedTo(ChronoUnit.SECONDS),
                account(client.user(token)));
    }

    /**
     * Whether the latest submitted review of every reviewer at the head approves and at least one
     * does; a read cut off answers {@code false}, never a guess.
     */
    private static boolean approved(ForgejoClient client, String token, Address at, String head) {
        final Map<Long, PullReview> latest = new HashMap<>();
        for (int page = 1; page <= PAGES; page++) {
            final Page<PullReview> reviews =
                    client.reviews(token, at.owner(), at.repo(), at.number(), page, PER_PAGE);
            for (final PullReview review : reviews.items()) {
                final User user = review.user();
                final String state = review.state();
                // Forgejo no longer counts a dismissed review, approval or not.
                if (user == null
                        || user.id() == null
                        || state == null
                        || Boolean.TRUE.equals(review.dismissed())
                        || "PENDING".equals(state)
                        || "COMMENT".equals(state)
                        || "REQUEST_REVIEW".equals(state)) {
                    continue;
                }
                latest.merge(user.id(), review, (old, now) -> idOf(now) > idOf(old) ? now : old);
            }
            if (!reviews.more()) {
                return !latest.isEmpty()
                        && latest.values().stream()
                                .allMatch(
                                        r ->
                                                "APPROVED".equals(r.state())
                                                        && head.equals(r.commitId())
                                                        && !Boolean.TRUE.equals(r.stale()));
            }
        }
        return false;
    }

    private static long idOf(PullReview review) {
        return review.id() == null ? 0 : review.id();
    }

    /** Every submitted review and its comments, cut at {@link #PAGES} pages and then partial. */
    private static ForgejoReviewRead.Native conversation(
            ForgejoClient client, String token, Address at) {
        final List<ForgejoReviewRead.Reviewed> reviewed = new ArrayList<>();
        for (int page = 1; page <= PAGES; page++) {
            final Page<PullReview> reviews =
                    client.reviews(token, at.owner(), at.repo(), at.number(), page, PER_PAGE);
            for (final PullReview review : reviews.items()) {
                final Long id = review.id();
                // A pending review is the reading account's own, unseen by anyone else.
                if (id == null || id < 1 || "PENDING".equals(review.state())) {
                    continue;
                }
                reviewed.add(
                        new ForgejoReviewRead.Reviewed(
                                review,
                                client.reviewComments(
                                        token, at.owner(), at.repo(), at.number(), id)));
            }
            if (!reviews.more()) {
                return new ForgejoReviewRead.Native(reviewed, true);
            }
        }
        return new ForgejoReviewRead.Native(reviewed, false);
    }

    private static ReviewFacts.@Nullable Account account(@Nullable User user) {
        if (user == null || user.id() == null || user.id() < 1) {
            return null;
        }
        final String login = user.login();
        if (login == null || !ProfileResult.isName(login)) {
            return null;
        }
        return new ReviewFacts.Account(Long.toString(user.id()), login);
    }

    private static boolean sameRepository(PRBranchInfo head, Address at) {
        return head.repo() != null
                && head.repo().fullName() != null
                && head.repo().fullName().equalsIgnoreCase(at.owner() + "/" + at.repo());
    }

    private static String commit(@Nullable String sha, String what) {
        if (sha == null || !COMMIT.matcher(sha).matches()) {
            throw new IllegalStateException("Forgejo answered no full commit id for the " + what);
        }
        return sha;
    }

    private static String branch(@Nullable String ref) {
        if (ref == null || !BRANCH.matcher(ref).matches()) {
            throw new IllegalStateException(
                    "Forgejo answered a base branch this adapter cannot name");
        }
        return ref;
    }

    private ForgejoClient client(URI api) {
        final HttpClient within =
                new CallDeadline(http.get(), clock, clock.instant().plus(callLength));
        return new ForgejoClient(
                api,
                within,
                Strictness.LENIENT,
                requestTimeout,
                ForgejoClient.DEFAULT_RATE_LIMIT_DELAY);
    }

    private <T> T answering(Supplier<T> call, ForgeReviewRefused.Reason missing) {
        try {
            return call.get();
        } catch (ForgejoException ex) {
            throw refused(ex, false, missing, clock);
        }
    }

    /**
     * Forgejo's refusal as the contract names it; a write that may have landed is never retried.
     */
    static RuntimeException refused(
            ForgejoException ex, boolean write, ForgeReviewRefused.Reason missing, Clock clock) {
        final int status = ex.status();
        if (status == 401 || status == 403 && missingScope(ex)) {
            return new ForgeReviewRefused(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED);
        }
        if (status == 404) {
            return new ForgeReviewRefused(missing);
        }
        if (status == 429) {
            return ForgeReviewRefused.rateLimited(retryAfter(ex.rateLimit(), clock));
        }
        if (ex.delivery() == Delivery.NOT_SENT) {
            return new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        // Answered but undecodable, cut off, or a server error: the comment may stand already.
        if (write && (ex.delivery() == Delivery.SENT_UNANSWERED || status / 100 != 4)) {
            return new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN);
        }
        if (ex.delivery() == Delivery.SENT_UNANSWERED) {
            return new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        return new IllegalStateException("Forgejo answered " + status + ": " + ex.getMessage(), ex);
    }

    /**
     * Whether a 403 refuses the token itself, as Forgejo words a missing scope; a locked pull
     * request or an archived repository refuses the call, never the credential.
     */
    private static boolean missingScope(ForgejoException ex) {
        final String said = ex.forgeMessage();
        return said != null
                && said.startsWith("token does not have at least one of required scope");
    }

    /**
     * What Forgejo said to wait, or until the reset it named, rounded up to seconds; 0 when it said
     * neither, as the contract asks.
     */
    static long retryAfter(@Nullable RateLimit limit, Clock clock) {
        if (limit == null) {
            return 0;
        }
        final Duration said = limit.retryAfter();
        final Instant reset = limit.reset();
        final Duration wait =
                said != null
                        ? said
                        : reset != null ? Duration.between(clock.instant(), reset) : Duration.ZERO;
        return Math.max(0, (wait.toMillis() + 999) / 1000);
    }

    private static InvalidArgument invalid(String parameter) {
        return new InvalidArgument(parameter);
    }

    /**
     * A parameter the call cannot take; its words name the parameter, never its value or a token.
     */
    public static final class InvalidArgument extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param parameter Which parameter.
         */
        public InvalidArgument(String parameter) {
            super(parameter + ": not one this adapter takes");
        }
    }

    /**
     * Where a call goes: the instance's API base, its web root, and for a pull request its owner,
     * repository and number, all on one https host and path prefix.
     */
    record Address(URI api, String web, String host, String owner, String repo, long number) {

        private static final Pattern PULL =
                Pattern.compile("([^/]+)/([^/]+)/pulls/([1-9][0-9]{0,17})");

        private static final Pattern REPOSITORY = Pattern.compile("([^/]+)/([^/]+?)(?:\\.git)?/?");

        static Address pullRequest(String api, String url) {
            final URI base = api(api.isEmpty() ? atOrigin(url, "url") : api);
            final Matcher m = PULL.matcher(under(base, url, "url"));
            if (!m.matches()) {
                throw invalid("url");
            }
            return of(base, m.group(1), m.group(2), Long.parseLong(m.group(3)));
        }

        static Address repository(String api, String upstream) {
            final URI base = api(api.isEmpty() ? atOrigin(upstream, "upstream") : api);
            final Matcher m = REPOSITORY.matcher(under(base, upstream, "upstream"));
            if (!m.matches()) {
                throw invalid("upstream");
            }
            return of(base, m.group(1), m.group(2), 0);
        }

        private static Address of(URI base, String owner, String repo, long number) {
            if (!NAME.matcher(owner).matches()
                    || !NAME.matcher(repo).matches()
                    || owner.startsWith(".")
                    || repo.startsWith(".")) {
                throw invalid("url");
            }
            final String host =
                    base.getHost().toLowerCase(Locale.ROOT)
                            + (base.getPort() < 0 ? "" : ":" + base.getPort());
            return new Address(base, web(base), host, owner, repo, number);
        }

        /**
         * The API base: {@code https://<host>[:port][/<prefix>]/api/v1}, nothing else; the review
         * contract names a repository by https only.
         */
        private static URI api(String api) {
            final URI base;
            try {
                base = new URI(api.endsWith("/") ? api : api + "/");
            } catch (URISyntaxException ex) {
                throw invalid("api");
            }
            final String path = base.getRawPath();
            if (!"https".equals(base.getScheme())
                    || base.getHost() == null
                    || base.getRawUserInfo() != null
                    || base.getRawQuery() != null
                    || base.getRawFragment() != null
                    || path == null
                    || !path.endsWith("/api/v1/")
                    || path.contains("/../")
                    || path.contains("/./")
                    || path.contains("//")) {
                throw invalid("api");
            }
            return base;
        }

        /**
         * The API at an address's own origin: Porelo names no API for a Forgejo, the contract's
         * empty one being the forge's own, and an instance under a path prefix needs it named.
         */
        private static String atOrigin(String address, String parameter) {
            final URI given;
            try {
                given = new URI(address);
            } catch (URISyntaxException ex) {
                throw invalid(parameter);
            }
            if (!"https".equals(given.getScheme())
                    || given.getHost() == null
                    || given.getRawUserInfo() != null) {
                throw invalid(parameter);
            }
            return "https://"
                    + given.getHost()
                    + (given.getPort() < 0 ? "" : ":" + given.getPort())
                    + "/api/v1";
        }

        /** The instance's web root: the API base without {@code api/v1/}. */
        private static String web(URI base) {
            final String text = base.toString();
            return text.substring(0, text.length() - "api/v1/".length());
        }

        /** What follows the web root in an address on the same host and prefix. */
        private static String under(URI base, String address, String parameter) {
            final String root = web(base);
            final URI given;
            try {
                given = new URI(address);
            } catch (URISyntaxException ex) {
                throw invalid(parameter);
            }
            if (given.getRawUserInfo() != null
                    || given.getRawQuery() != null
                    || given.getRawFragment() != null
                    || !address.regionMatches(true, 0, root, 0, root.length())) {
                throw invalid(parameter);
            }
            return address.substring(root.length());
        }
    }
}
