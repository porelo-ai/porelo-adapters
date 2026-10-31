package org.fuin.porelo.standin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.fuin.porelo.build.github.review.GitHubReviewRead;
import org.fuin.porelo.build.github.review.ReviewDocument;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.PullRequest;
import org.jspecify.annotations.Nullable;

/**
 * Reads the configured pull requests through the {@code github} client into pushes: the head
 * fetched into the stand-in's clone, the merge base taken from it, the reviews and comments
 * converted by the GitHub adapter's conversion. A read never writes to GitHub.
 */
final class GitHubSource {

    /** GitHub answers at most this many items a page. */
    static final int PER_PAGE = 100;

    /** A list longer than this many pages is not read to its end, and the run says so. */
    static final int PAGE_LIMIT = 30;

    /** How long a read is reused before GitHub is asked again. */
    static final Duration FRESH = Duration.ofSeconds(30);

    private final GitHubClient client;

    private final GitHubGraphQl graphQl;

    private final String token;

    private final String owner;

    private final String repo;

    private final String forgeInstance;

    private final Git git;

    private final Clock clock;

    private volatile Instant readAt = Instant.EPOCH;

    GitHubSource(
            GitHubClient client,
            GitHubGraphQl graphQl,
            String token,
            String repository,
            String forgeInstance,
            Git git,
            Clock clock) {
        final String[] parts = repository.split("/", -1);
        if (parts.length != 2
                || !parts[0].matches("[A-Za-z0-9-]{1,39}")
                || !parts[1].matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("--repository is <owner>/<name>");
        }
        this.client = client;
        this.graphQl = graphQl;
        this.token = token;
        this.owner = parts[0];
        this.repo = parts[1];
        this.forgeInstance = forgeInstance;
        this.git = git;
        this.clock = clock;
    }

    /**
     * Returns the repository's web URI, as a run names it.
     *
     * @return The URI.
     */
    String repositoryUri() {
        return "https://" + forgeInstance + "/" + owner + "/" + repo;
    }

    /**
     * Returns the client, for a write.
     *
     * @return The client.
     */
    GitHubClient client() {
        return client;
    }

    /**
     * Returns the token, for a write.
     *
     * @return The token.
     */
    String token() {
        return token;
    }

    String owner() {
        return owner;
    }

    String repo() {
        return repo;
    }

    /**
     * Reads a pull request's head as GitHub has it now.
     *
     * @param number The pull request.
     * @return Its head commit.
     */
    String head(long number) {
        return client.pull(token, owner, repo, number).head().sha();
    }

    /**
     * Reads a pull request's merge base as GitHub has it now.
     *
     * @param number The pull request.
     * @return Its merge base.
     */
    String mergeBase(long number) {
        final PullRequest pull = client.pull(token, owner, repo, number);
        return client.compare(token, owner, repo, pull.base().sha(), pull.head().sha())
                .mergeBaseCommit()
                .sha();
    }

    /**
     * Reads the pull requests again unless the last read is fresh.
     *
     * @param pushes Where the pushes are kept.
     * @param numbers The pull requests.
     * @param force Read even if fresh.
     */
    synchronized void refresh(Pushes pushes, List<Long> numbers, boolean force) {
        if (!force && clock.instant().isBefore(readAt.plus(FRESH))) {
            return;
        }
        for (final long number : numbers) {
            read(pushes, number);
        }
        readAt = clock.instant();
    }

    private void read(Pushes pushes, long number) {
        final PullRequest pull = client.pull(token, owner, repo, number);
        final String name = "pr-" + number;
        final String ref = "refs/porelo/incoming/" + name;
        git.fetch(
                "https://" + forgeInstance + "/" + owner + "/" + repo + ".git",
                token,
                "+refs/pull/" + number + "/head:" + ref,
                "+" + pull.base().sha() + ":refs/porelo/base/" + name);
        final String head = git.commit(ref);
        if (!head.equals(pull.head().sha())) {
            throw new IllegalStateException(
                    "The fetched head of pull request " + number + " is not the one GitHub named");
        }
        final String mergeBase = git.mergeBase(pull.base().sha(), head);
        final Push push =
                pushes.github(
                        name,
                        number,
                        pull.title(),
                        pull.updatedAt().toInstant().toString(),
                        head,
                        mergeBase);
        final Paged<?> reviews =
                all(page -> client.reviews(token, owner, repo, number, page, PER_PAGE));
        final Paged<?> comments =
                all(page -> client.reviewComments(token, owner, repo, number, page, PER_PAGE));
        final Paged<?> issueComments =
                all(page -> client.issueComments(token, owner, repo, number, null, page, PER_PAGE));
        final List<GitHubReviewRead.Thread> threads = new ArrayList<>();
        final boolean threadsComplete = threads(number, threads);
        @SuppressWarnings("unchecked")
        final GitHubReviewRead.Native answers =
                new GitHubReviewRead.Native(
                        (List<org.fuin.porelo.forge.github.model.PullRequestReview>)
                                reviews.items(),
                        (List<org.fuin.porelo.forge.github.model.PullRequestReviewComment>)
                                comments.items(),
                        (List<org.fuin.porelo.forge.github.model.IssueComment>)
                                issueComments.items(),
                        threads,
                        reviews.complete()
                                && comments.complete()
                                && issueComments.complete()
                                && threadsComplete,
                        head);
        final GitHubReviewRead.Read read = GitHubReviewRead.read(forgeInstance, answers);
        final ReviewDocument.Run run =
                new ReviewDocument.Run(
                        forgeInstance,
                        repositoryUri(),
                        pull.base().repo().id(),
                        number,
                        head,
                        mergeBase,
                        fromFork(pull));
        final ObjectNode shell =
                ReviewDocument.document(run, List.of(), read.completeness(), read.verdict());
        push.putRun(forgeInstance, "forge", 1, shell, read.results(), read.held());
    }

    private static String fromFork(PullRequest pull) {
        return pull.head().repo().id().equals(pull.base().repo().id()) ? "false" : "true";
    }

    /** Reads every page of the threads and of each thread's comments. */
    private boolean threads(long number, List<GitHubReviewRead.Thread> into) {
        @Nullable String after = null;
        for (int page = 0; page < PAGE_LIMIT; page++) {
            final GitHubGraphQl.ThreadsData data =
                    graphQl.reviewThreads(token, owner, repo, Math.toIntExact(number), after)
                            .data();
            if (data.repository() == null || data.repository().pullRequest() == null) {
                throw new IllegalStateException("GitHub names no pull request " + number);
            }
            final GitHubGraphQl.Threads threads = data.repository().pullRequest().reviewThreads();
            for (final GitHubGraphQl.ReviewThread thread : threads.nodes()) {
                into.add(new GitHubReviewRead.Thread(thread, comments(thread)));
            }
            if (!threads.pageInfo().hasNextPage()) {
                return true;
            }
            after = threads.pageInfo().endCursor();
        }
        return false;
    }

    private List<GitHubGraphQl.Comment> comments(GitHubGraphQl.ReviewThread thread) {
        final List<GitHubGraphQl.Comment> out = new ArrayList<>(thread.comments().nodes());
        GitHubGraphQl.PageInfo info = thread.comments().pageInfo();
        for (int page = 0; info.hasNextPage() && page < PAGE_LIMIT; page++) {
            final GitHubGraphQl.CommentsData data =
                    graphQl.threadComments(token, thread.id(), info.endCursor()).data();
            if (data.node() == null) {
                break;
            }
            out.addAll(data.node().comments().nodes());
            info = data.node().comments().pageInfo();
        }
        return out;
    }

    /** Every page of a list, and whether it was read to its end. */
    private record Paged<T>(List<T> items, boolean complete) {}

    private static <T> Paged<T> all(IntFunction<List<T>> page) {
        final List<T> out = new ArrayList<>();
        for (int n = 1; n <= PAGE_LIMIT; n++) {
            final List<T> items = page.apply(n);
            out.addAll(items);
            if (items.size() < PER_PAGE) {
                return new Paged<>(out, true);
            }
        }
        return new Paged<>(out, false);
    }
}
