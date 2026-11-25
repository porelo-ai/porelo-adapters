package org.fuin.porelo.build.github.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.build.api.ReviewReader;
import org.fuin.porelo.build.api.review.ProfileCheck;
import org.fuin.porelo.build.api.review.SarifReading;
import org.fuin.porelo.build.api.review.SarifRefused;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.RateLimit;
import org.jspecify.annotations.Nullable;

/**
 * Answers Porelo's review contract for GitHub: holds what Porelo sends to profile 2 before any
 * call, holds its own pages to it before answering, and names each failure by the contract's
 * errors.
 */
public final class GitHubReviewReader implements ReviewReader {

    /** GitHub's own REST base, for an empty {@code api}. */
    public static final String PUBLIC_API = "https://api.github.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GitHubReview review;

    /**
     * Constructor.
     *
     * @param review The calls to GitHub.
     */
    public GitHubReviewReader(GitHubReview review) {
        this.review = review;
    }

    /**
     * Constructor.
     *
     * @param http Gives the client each call sends with.
     */
    public GitHubReviewReader(Supplier<java.net.http.HttpClient> http) {
        this(new GitHubReview(http));
    }

    /**
     * Constructor.
     *
     * @param http Gives the client each call sends with.
     * @param requestTimeout How long one request to GitHub may take.
     */
    public GitHubReviewReader(Supplier<java.net.http.HttpClient> http, Duration requestTimeout) {
        this(new GitHubReview(http, requestTimeout));
    }

    @Override
    public ReviewFacts pullRequest(ForgeAccess access, String url) {
        final PullRequestFacts.Facts facts =
                answering(() -> review.pullRequest(api(access), access.token(), url));
        final ReviewFacts.State state =
                switch (facts.state()) {
                    case "merged" -> ReviewFacts.State.MERGED;
                    case "closed" -> ReviewFacts.State.CLOSED;
                    default -> ReviewFacts.State.OPEN;
                };
        if (state == ReviewFacts.State.MERGED && facts.mergeCommit() == null) {
            throw new IllegalStateException(
                    "a merged pull request's facts were read without its merge commit");
        }
        final PullRequestFacts.Account account = facts.account();
        return new ReviewFacts(
                facts.repository(),
                facts.head(),
                facts.base(),
                facts.mergeBase(),
                state,
                facts.approved(),
                facts.mergeCommit(),
                facts.mergedHead(),
                facts.decidedAt(),
                account == null ? null : new ReviewFacts.Account(account.id(), account.login()));
    }

    @Override
    public @Nullable String pullRequestOf(ForgeAccess access, String upstream, String branch) {
        return answering(() -> review.pullRequestOf(api(access), access.token(), upstream, branch));
    }

    @Override
    public ConversationPage comments(ForgeAccess access, String url, @Nullable String cursor) {
        final ReviewPage.Page page =
                answering(() -> review.comments(api(access), access.token(), url, cursor));
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
        final byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        try {
            ProfileCheck.check(ProfileCheck.Channel.TASK, SarifReading.read(bytes));
        } catch (SarifRefused ex) {
            throw ForgeReviewRefused.notInProfile(
                    ex.path().isEmpty() ? ex.rule().text() : ex.path());
        }
        final JsonNode submission;
        try {
            submission = JSON.readTree(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("a submission SARIF read is no JSON", ex);
        }
        return answering(() -> review.post(api(access), access.token(), url, submission));
    }

    private static String api(ForgeAccess access) {
        return access.api().isEmpty() ? PUBLIC_API : access.api();
    }

    private static <T> T answering(Supplier<T> call) {
        try {
            return call.get();
        } catch (ReviewFailure ex) {
            throw refused(ex);
        } catch (GitHubException ex) {
            // Answered, but with nothing the contract names: a 422 or a 5xx on a read.
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private static ForgeReviewRefused refused(ReviewFailure failure) {
        return switch (failure.kind()) {
            case RATE_LIMITED -> ForgeReviewRefused.rateLimited(retryAfter(failure));
            case NOT_IN_PROFILE -> ForgeReviewRefused.notInProfile(failure.field());
            case CREDENTIAL_REFUSED ->
                    new ForgeReviewRefused(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED);
            case NO_SUCH_PULL_REQUEST ->
                    new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
            case NO_SUCH_THREAD -> new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_THREAD);
            case UNREACHABLE -> new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
            case WRITE_OUTCOME_UNKNOWN ->
                    new ForgeReviewRefused(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN);
        };
    }

    /** What GitHub said to wait, rounded up to seconds; 0 when it said nothing. */
    private static long retryAfter(ReviewFailure failure) {
        if (!(failure.getCause() instanceof GitHubException github)) {
            return 0;
        }
        final RateLimit limit = github.rateLimit();
        if (limit == null) {
            return 0;
        }
        final Duration retry = limit.retryAfter();
        final Instant reset = limit.reset();
        final Duration wait =
                retry != null
                        ? retry
                        : reset != null ? Duration.between(Instant.now(), reset) : Duration.ZERO;
        return Math.max(0, (wait.toMillis() + 999) / 1000);
    }
}
