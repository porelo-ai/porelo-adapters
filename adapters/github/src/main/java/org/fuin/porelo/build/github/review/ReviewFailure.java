package org.fuin.porelo.build.github.review;

import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;

/**
 * A review call that failed, named by the error the review contract answers it with. Its message
 * carries what GitHub said, never a token or a body.
 */
public final class ReviewFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The contract's errors. */
    public enum Kind {
        /** GitHub's rate limit is spent; try again after its delay. */
        RATE_LIMITED("RateLimited"),
        /** GitHub refused the token lent for the call. */
        CREDENTIAL_REFUSED("CredentialRefused"),
        /** GitHub names no such pull request, or the token may not see it. */
        NO_SUCH_PULL_REQUEST("NoSuchPullRequest"),
        /** GitHub names no such thread. */
        NO_SUCH_THREAD("NoSuchThread"),
        /** GitHub could not be reached; nothing was written. */
        UNREACHABLE("Unreachable"),
        /** A write was sent and no answer came: whether GitHub took it is unknown. */
        WRITE_OUTCOME_UNKNOWN("WriteOutcomeUnknown"),
        /** The document asks for what profile 2 cannot carry. */
        NOT_IN_PROFILE("NotInProfile");

        private final String error;

        Kind(String error) {
            this.error = error;
        }

        /**
         * Returns the error's name as the contract spells it.
         *
         * @return Name, such as {@code RateLimited}.
         */
        public String error() {
            return error;
        }
    }

    private final transient Kind kind;

    private final String field;

    /**
     * Constructor.
     *
     * @param kind The error.
     * @param message What failed, in words.
     * @param cause What failed below, or {@code null}.
     */
    public ReviewFailure(
            Kind kind, String message, @org.jspecify.annotations.Nullable Throwable cause) {
        this(kind, message, cause, "");
    }

    /**
     * Constructor.
     *
     * @param kind The error.
     * @param message What failed, in words.
     * @param cause What failed below, or {@code null}.
     * @param field For {@link Kind#NOT_IN_PROFILE}, the first point of the document refused.
     */
    public ReviewFailure(
            Kind kind,
            String message,
            @org.jspecify.annotations.Nullable Throwable cause,
            String field) {
        super(message, cause);
        this.kind = kind;
        this.field = field;
    }

    /**
     * Returns the first point of the document refused, for {@link Kind#NOT_IN_PROFILE}.
     *
     * @return A path, or "" for another kind.
     */
    public String field() {
        return field;
    }

    /**
     * Returns the error.
     *
     * @return Kind.
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Names a failed GitHub call by the contract's error, or gives it back when none fits.
     *
     * @param failure What the client threw.
     * @param write Whether the call was a write, which is never repeated.
     * @param missing The error a 404 means for this call.
     * @return The failure to answer.
     */
    public static RuntimeException of(GitHubException failure, boolean write, Kind missing) {
        final String said = "GitHub: " + failure.getMessage();
        if (write && unknownWhetherTaken(failure)) {
            return new ReviewFailure(Kind.WRITE_OUTCOME_UNKNOWN, said, failure);
        }
        if (failure.rateLimited()) {
            return new ReviewFailure(Kind.RATE_LIMITED, said, failure);
        }
        if (failure.status() == 401 || failure.status() == 403) {
            return new ReviewFailure(Kind.CREDENTIAL_REFUSED, said, failure);
        }
        if (failure.status() == 404) {
            return new ReviewFailure(missing, said, failure);
        }
        if (failure.status() == 0) {
            return new ReviewFailure(Kind.UNREACHABLE, said, failure);
        }
        return failure;
    }

    /**
     * Whether a write may have been taken: no answer came, GitHub took it and its answer could not
     * be read, or a server error leaves open what happened before it.
     */
    private static boolean unknownWhetherTaken(GitHubException failure) {
        return failure.delivery() == Delivery.SENT_UNANSWERED
                || (failure.delivery() == Delivery.ANSWERED
                        && (failure.status() / 100 == 2 || failure.status() >= 500));
    }
}
