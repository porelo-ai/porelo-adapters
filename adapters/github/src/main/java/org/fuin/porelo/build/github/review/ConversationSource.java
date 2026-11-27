package org.fuin.porelo.build.github.review;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.jspecify.annotations.Nullable;

/**
 * Reads a pull request's line comments and review threads through the {@code github} client, every
 * page of each, and says whether every list, each thread's comments included, was read to its end.
 */
public final class ConversationSource {

    /** Items asked for per REST page, GitHub's most. */
    static final int PER_PAGE = 100;

    /** Pages read of one list at most; a list longer than this is read partial. */
    static final int PAGE_LIMIT = 30;

    private final GitHubClient client;

    private final GitHubGraphQl graphQl;

    /**
     * Constructor.
     *
     * @param client The REST client.
     * @param graphQl The GraphQL client.
     */
    public ConversationSource(GitHubClient client, GitHubGraphQl graphQl) {
        this.client = client;
        this.graphQl = graphQl;
    }

    /**
     * Reads one pull request's line comments and threads.
     *
     * @param token The token lent for this call.
     * @param owner The repository's owner.
     * @param repo The repository's name.
     * @param number The pull request's number.
     * @return What GitHub answered.
     * @throws NoSuchPullRequest If GitHub names no such pull request.
     */
    public Conversation.Native read(String token, String owner, String repo, long number) {
        final Paged<PullRequestReviewComment> comments =
                all(page -> client.reviewComments(token, owner, repo, number, page, PER_PAGE));
        final List<Conversation.Thread> threads = new ArrayList<>();
        final boolean threadsComplete = threads(token, owner, repo, number, threads);
        return new Conversation.Native(
                comments.items(), threads, comments.complete() && threadsComplete);
    }

    /**
     * Finds the thread of a pull request that a submitted root comment opened, reading the threads
     * a page at a time until it is found. A thread's root is its first comment, which the thread's
     * first page of comments always holds.
     *
     * @param token The token lent for this call.
     * @param owner The repository's owner.
     * @param repo The repository's name.
     * @param number The pull request's number.
     * @param root The root comment's REST id.
     * @return The thread, or none and whether every thread was read.
     * @throws NoSuchPullRequest If GitHub names no such pull request.
     */
    public Found thread(String token, String owner, String repo, long number, long root) {
        @Nullable String after = null;
        boolean complete = true;
        final Set<String> seen = new HashSet<>();
        for (int page = 0; page < PAGE_LIMIT; page++) {
            final GitHubGraphQl.ThreadsData data =
                    graphQl.reviewThreads(token, owner, repo, Math.toIntExact(number), after)
                            .data();
            if (data.repository() == null || data.repository().pullRequest() == null) {
                throw new NoSuchPullRequest();
            }
            final GitHubGraphQl.Threads threads = data.repository().pullRequest().reviewThreads();
            for (final GitHubGraphQl.ReviewThread thread : threads.nodes()) {
                // A thread named twice, as a moved page cursor may, says another was skipped.
                complete &= seen.add(thread.id());
                final List<GitHubGraphQl.Comment> comments = thread.comments().nodes();
                if (comments.isEmpty() || comments.get(0).databaseId() == null) {
                    // A thread that shows no root's id may be the one asked for.
                    complete = false;
                } else if (opens(comments.get(0), root)) {
                    return new Found(thread, true);
                }
            }
            if (!Boolean.TRUE.equals(threads.pageInfo().hasNextPage())) {
                return new Found(null, complete);
            }
            after = threads.pageInfo().endCursor();
            if (after == null) {
                // More pages without a place to go on from would be the first page again.
                return new Found(null, false);
            }
        }
        return new Found(null, false);
    }

    /** Whether a comment is the submitted one of that REST id; a pending one nobody else sees. */
    private static boolean opens(GitHubGraphQl.Comment first, long root) {
        return Long.valueOf(root).equals(first.databaseId()) && !"PENDING".equals(first.state());
    }

    /**
     * A thread looked for by its root comment.
     *
     * @param thread The thread, or {@code null} when it was not among those read.
     * @param complete Whether every thread was read, so that none found means there is none.
     */
    public record Found(GitHubGraphQl.@Nullable ReviewThread thread, boolean complete) {}

    /** Reads every page of the threads and of each thread's comments. */
    private boolean threads(
            String token, String owner, String repo, long number, List<Conversation.Thread> into) {
        @Nullable String after = null;
        boolean complete = true;
        for (int page = 0; page < PAGE_LIMIT; page++) {
            final GitHubGraphQl.ThreadsData data =
                    graphQl.reviewThreads(token, owner, repo, Math.toIntExact(number), after)
                            .data();
            if (data.repository() == null || data.repository().pullRequest() == null) {
                throw new NoSuchPullRequest();
            }
            final GitHubGraphQl.Threads threads = data.repository().pullRequest().reviewThreads();
            for (final GitHubGraphQl.ReviewThread thread : threads.nodes()) {
                final Paged<GitHubGraphQl.Comment> comments = comments(token, thread);
                complete &= comments.complete();
                into.add(new Conversation.Thread(thread, comments.items()));
            }
            if (!Boolean.TRUE.equals(threads.pageInfo().hasNextPage())) {
                return complete;
            }
            after = threads.pageInfo().endCursor();
        }
        return false;
    }

    /** A thread's comments, every page; a page that names no node leaves the thread partial. */
    private Paged<GitHubGraphQl.Comment> comments(String token, GitHubGraphQl.ReviewThread thread) {
        final List<GitHubGraphQl.Comment> out = new ArrayList<>(thread.comments().nodes());
        GitHubGraphQl.PageInfo info = thread.comments().pageInfo();
        for (int page = 0; Boolean.TRUE.equals(info.hasNextPage()) && page < PAGE_LIMIT; page++) {
            final GitHubGraphQl.CommentsData data =
                    graphQl.threadComments(token, thread.id(), info.endCursor()).data();
            if (data.node() == null) {
                return new Paged<>(out, false);
            }
            out.addAll(data.node().comments().nodes());
            info = data.node().comments().pageInfo();
        }
        return new Paged<>(out, !Boolean.TRUE.equals(info.hasNextPage()));
    }

    /** Every page of a list, and whether it was read to its end. */
    private record Paged<T>(List<T> items, boolean complete) {}

    private static <T> Paged<T> all(IntFunction<Page<T>> pages) {
        final List<T> out = new ArrayList<>();
        for (int n = 1; n <= PAGE_LIMIT; n++) {
            final Page<T> page = pages.apply(n);
            out.addAll(page.items());
            if (!page.more()) {
                return new Paged<>(out, true);
            }
        }
        return new Paged<>(out, false);
    }

    /** GitHub names no such pull request. */
    public static final class NoSuchPullRequest extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Constructor. */
        public NoSuchPullRequest() {
            super("GitHub names no such pull request");
        }
    }
}
