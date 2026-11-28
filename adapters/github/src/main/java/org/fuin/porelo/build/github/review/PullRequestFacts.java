package org.fuin.porelo.build.github.review;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.build.review.ProfileResult;
import org.fuin.porelo.build.review.ReviewText;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.PullRequest;
import org.fuin.porelo.forge.github.model.PullRequestReview;
import org.fuin.porelo.forge.github.model.PullRequestSimple;
import org.jspecify.annotations.Nullable;

/**
 * A pull request's facts as GitHub answers them, read only: its repository, head, base, merge base,
 * state and the review verdict GitHub shows at its head. Nothing here writes a review or a verdict.
 */
public final class PullRequestFacts {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private static final Pattern PULL =
            Pattern.compile(
                    "/([A-Za-z0-9._-]{1,100})/([A-Za-z0-9._-]{1,100})/pull/([1-9][0-9]{0,8})");

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    private static final Pattern MERGE_COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final Pattern REPOSITORY =
            Pattern.compile("/([A-Za-z0-9._-]{1,100})/([A-Za-z0-9._-]{1,100})/?");

    private final GitHubClient client;

    /**
     * Constructor.
     *
     * @param client The REST client.
     */
    public PullRequestFacts(GitHubClient client) {
        this.client = client;
    }

    /**
     * A pull request's place on GitHub.
     *
     * @param owner The repository's owner.
     * @param repo The repository's name.
     * @param number The pull request's number.
     */
    public record Address(String owner, String repo, long number) {}

    /**
     * What a pull request is.
     *
     * @param repository The repository's https URL.
     * @param head The head commit.
     * @param base The base branch's commit.
     * @param mergeBase The merge base of base and head.
     * @param state {@code open}, {@code merged} or {@code closed}.
     * @param approved Whether GitHub shows the head approved.
     * @param mergeCommit The merge commit, once merged and GitHub names one.
     * @param mergedHead The branch the merge went onto, once merged.
     * @param decidedAt When it was merged, or closed without a merge; {@code null} while open.
     * @param account The account the token stands for, once read; {@code null} before.
     */
    public record Facts(
            String repository,
            String head,
            String base,
            String mergeBase,
            String state,
            boolean approved,
            @Nullable String mergeCommit,
            @Nullable String mergedHead,
            java.time.@Nullable Instant decidedAt,
            @Nullable Account account) {

        /**
         * Returns these facts with the token's account.
         *
         * @param account The account.
         * @return The facts.
         */
        public Facts with(Account account) {
            return new Facts(
                    repository,
                    head,
                    base,
                    mergeBase,
                    state,
                    approved,
                    mergeCommit,
                    mergedHead,
                    decidedAt,
                    account);
        }

        /**
         * Returns these facts with the merge commit.
         *
         * @param commit The merge commit.
         * @return The facts.
         */
        public Facts withMergeCommit(String commit) {
            return new Facts(
                    repository,
                    head,
                    base,
                    mergeBase,
                    state,
                    approved,
                    commit,
                    mergedHead,
                    decidedAt,
                    account);
        }
    }

    /**
     * A GitHub account.
     *
     * @param id Its numeric id, in decimal.
     * @param login Its login.
     */
    public record Account(String id, String login) {}

    /**
     * Reads the account the token stands for.
     *
     * @param token The token lent for this call.
     * @return Its account.
     */
    public Account account(String token) {
        final var user = client.user(token);
        final String login;
        final Long id;
        if (user instanceof org.fuin.porelo.forge.github.model.PrivateUser own) {
            login = own.login();
            id = own.id();
        } else if (user instanceof org.fuin.porelo.forge.github.model.PublicUser other) {
            login = other.login();
            id = other.id();
        } else {
            throw new IllegalStateException("GitHub answered GET /user with no account");
        }
        if (id == null || id <= 0 || login == null || !ProfileResult.isName(login)) {
            throw new IllegalStateException(
                    "GitHub answered GET /user with no id or login of its form");
        }
        return new Account(Long.toString(id), login);
    }

    /**
     * The forge's web host for a REST base URL: {@code github.com} for {@code
     * https://api.github.com}, the host itself for {@code https://<host>/api/v3}.
     *
     * @param api The REST base URL.
     * @return The web host.
     * @throws IllegalArgumentException If the URL is neither form.
     */
    public static String webHost(URI api) {
        if (!"https".equals(api.getScheme())
                || api.getRawUserInfo() != null
                || api.getRawQuery() != null
                || api.getRawFragment() != null
                || api.getHost() == null) {
            throw new IllegalArgumentException(
                    "api is an https URL without user, query or fragment");
        }
        final String host = api.getHost().toLowerCase(Locale.ROOT);
        final String path = api.getRawPath() == null ? "" : api.getRawPath();
        final String port = api.getPort() == -1 ? "" : ":" + api.getPort();
        if ("api.github.com".equals(host) && (path.isEmpty() || "/".equals(path))) {
            return "github.com" + port;
        }
        if ("/api/v3".equals(path) || "/api/v3/".equals(path)) {
            return host + port;
        }
        throw new IllegalArgumentException(
                "api is https://api.github.com or https://<host>/api/v3");
    }

    /**
     * Reads a pull request's URL, which must lie on the forge the REST base URL names.
     *
     * @param api The REST base URL.
     * @param url The pull request's URL, {@code https://<host>/<owner>/<repo>/pull/<number>}.
     * @return Its address.
     * @throws IllegalArgumentException If the URL is no pull request of that forge.
     */
    public static Address address(URI api, String url) {
        final Matcher path = matched(api, url, PULL);
        return new Address(path.group(1), path.group(2), Long.parseLong(path.group(3)));
    }

    /**
     * Reads a repository's URL, which must lie on the forge the REST base URL names.
     *
     * @param api The REST base URL.
     * @param url The repository's URL, {@code https://<host>/<owner>/<repo>}, its name with or
     *     without one trailing {@code .git} and the path with or without a trailing slash, which
     *     git and the forge treat as one repository.
     * @return Its owner and name, as an address of number 0.
     * @throws IllegalArgumentException If the URL is no repository of that forge.
     */
    public static Address repository(URI api, String url) {
        final Matcher path = matched(api, url, REPOSITORY);
        final String name = path.group(2);
        final String repo = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        if (repo.isEmpty() || dots(repo)) {
            throw new IllegalArgumentException("the URL's path is not of the expected form");
        }
        return new Address(path.group(1), repo, 0);
    }

    private static Matcher matched(URI api, String url, Pattern form) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException ex) {
            throw new IllegalArgumentException("not a URL", ex);
        }
        final String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        if (!"https".equals(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getHost() == null
                || !webHost(api).equals(uri.getHost().toLowerCase(Locale.ROOT) + port)) {
            throw new IllegalArgumentException("the URL is not on the forge api names");
        }
        final Matcher path = form.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!path.matches() || dots(path.group(1)) || dots(path.group(2))) {
            throw new IllegalArgumentException("the URL's path is not of the expected form");
        }
        return path;
    }

    /** A dot segment would name another path once the client builds its request. */
    private static boolean dots(String name) {
        return ".".equals(name) || "..".equals(name);
    }

    /**
     * Reads a pull request's facts.
     *
     * @param token The token lent for this call.
     * @param address The pull request.
     * @return Its facts.
     */
    public Facts read(String token, Address address) {
        final PullRequest pull =
                client.pull(token, address.owner(), address.repo(), address.number());
        final String head = pull.head().sha();
        final String base = pull.base().sha();
        final String mergeBase =
                client.compare(token, address.owner(), address.repo(), base, head)
                        .mergeBaseCommit()
                        .sha();
        final boolean merged = Boolean.TRUE.equals(pull.merged()) || pull.mergedAt() != null;
        final String state =
                merged ? "merged" : pull.state() == PullRequest.StateEnum.OPEN ? "open" : "closed";
        final List<PullRequestReview> reviews = reviews(token, address);
        // The repository the head is read from: a fork's for a pull request from one.
        final var headRepository = pull.head().repo();
        if (headRepository == null) {
            throw new IllegalStateException(
                    "GitHub names no repository for the pull request's head; its fork is gone");
        }
        final String repository = headRepository.htmlUrl().toString();
        // A page built on what GitHub answered must hold to the profile, or the call fails.
        if (!COMMIT.matcher(head).matches()
                || !COMMIT.matcher(base).matches()
                || !COMMIT.matcher(mergeBase).matches()
                || !repository.startsWith("https://")) {
            throw new IllegalStateException(
                    "GitHub answered a pull request whose commits or repository are not of"
                            + " their form");
        }
        return new Facts(
                repository,
                head,
                base,
                mergeBase,
                state,
                approved(reviews, head),
                null,
                merged ? pull.base().ref() : null,
                decidedAt(
                        merged ? pull.mergedAt() : "closed".equals(state) ? pull.closedAt() : null),
                null);
    }

    /**
     * Reads a merged pull request's merge commit.
     *
     * @param graphQl The GraphQL client.
     * @param token The token lent for this call.
     * @param address The pull request.
     * @return The merge commit, written in full.
     * @throws IllegalStateException If GitHub names none or one not of a commit's form.
     */
    public static String mergeCommit(GitHubGraphQl graphQl, String token, Address address) {
        final String commit =
                graphQl.mergeCommit(
                        token, address.owner(), address.repo(), Math.toIntExact(address.number()));
        if (commit == null || !MERGE_COMMIT.matcher(commit).matches()) {
            throw new IllegalStateException(
                    "GitHub names no merge commit of a commit's form for a merged pull request");
        }
        return commit;
    }

    private static java.time.@Nullable Instant decidedAt(java.time.@Nullable OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }

    /**
     * Finds the open pull request from a branch of a repository into it, opening none.
     *
     * @param token The token lent for this call.
     * @param upstream The repository.
     * @param branch The branch's name.
     * @param host The web host, to write the pull request's URL.
     * @return The pull request's URL, or {@code null} when there is none.
     */
    public @Nullable String openOf(String token, Address upstream, String branch, String host) {
        checkBranch(branch);
        if (!NAME.matcher(upstream.owner()).matches()
                || !NAME.matcher(upstream.repo()).matches()
                || dots(upstream.owner())
                || dots(upstream.repo())) {
            throw new IllegalArgumentException("not a repository");
        }
        return openOfChecked(token, upstream, branch, host);
    }

    /**
     * Refuses what cannot be a branch's name.
     *
     * @param branch The name.
     * @throws IllegalArgumentException If it is none.
     */
    public static void checkBranch(String branch) {
        if (branch.isEmpty()
                || branch.length() > 255
                || ReviewText.refusedCharacter(branch) != null
                || branch.chars().anyMatch(c -> c == '\n' || c == '\t' || c == ' ' || c == ':')) {
            throw new IllegalArgumentException("not a branch name");
        }
    }

    private @Nullable String openOfChecked(
            String token, Address upstream, String branch, String host) {
        final List<PullRequestSimple> open =
                client.pullsOfHead(
                                token,
                                upstream.owner(),
                                upstream.repo(),
                                upstream.owner() + ":" + branch,
                                "open",
                                1,
                                2)
                        .items();
        if (open.isEmpty()) {
            return null;
        }
        return "https://"
                + host
                + "/"
                + upstream.owner()
                + "/"
                + upstream.repo()
                + "/pull/"
                + open.get(0).number();
    }

    private List<PullRequestReview> reviews(String token, Address address) {
        final java.util.ArrayList<PullRequestReview> out = new java.util.ArrayList<>();
        for (int page = 1; page <= ConversationSource.PAGE_LIMIT; page++) {
            final var answer =
                    client.reviews(
                            token,
                            address.owner(),
                            address.repo(),
                            address.number(),
                            page,
                            ConversationSource.PER_PAGE);
            out.addAll(answer.items());
            if (!answer.more()) {
                return out;
            }
        }
        // Not every review read: approval is not shown, so the head counts as not approved.
        return List.of();
    }

    /**
     * Whether GitHub shows the head approved: each reviewer's last review on the head that is not a
     * comment is APPROVED, at least one is, and none requests changes or was dismissed.
     */
    static boolean approved(List<PullRequestReview> reviews, String head) {
        final Map<String, String> latest = new LinkedHashMap<>();
        for (final PullRequestReview review : reviews) {
            final String state = review.state();
            if ("PENDING".equals(state)
                    || "COMMENTED".equals(state)
                    || !head.equals(review.commitId())) {
                continue;
            }
            final String reviewer =
                    review.user() == null
                            ? "deleted:" + review.id()
                            : Long.toString(review.user().id());
            latest.put(reviewer, state);
        }
        return !latest.isEmpty() && latest.values().stream().allMatch("APPROVED"::equals);
    }
}
