package org.fuin.porelo.build.github.review;

/**
 * A document or a write the review profile refuses, and why. The words name the rule and the path,
 * never a body or a token.
 */
public final class ReviewRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the profile refuses it. */
    public enum Reason {
        /** Outside the profile, its limits, the submitter's column or the action's fields. */
        PROFILE_VIOLATION,
        /** Over a byte, result or depth limit. */
        TOO_LARGE,
        /** A refused character. */
        UNSAFE_TEXT,
        /** The forge cannot carry it. */
        NOT_SUPPORTED,
        /** A fix that is not one replacement of its anchor. */
        FIX_OUTSIDE_ANCHOR,
        /** A fix on the old side. */
        OLD_SIDE_FIX
    }

    private final transient Reason reason;

    private final String path;

    /**
     * Constructor.
     *
     * @param reason Why.
     * @param detail Which rule, in words.
     * @param path Where in the document, such as {@code runs[0].results[0].fixes}.
     */
    public ReviewRefusal(Reason reason, String detail, String path) {
        super(detail);
        this.reason = reason;
        this.path = path;
    }

    /**
     * Returns why.
     *
     * @return Reason.
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns where in the document.
     *
     * @return Path.
     */
    public String path() {
        return path;
    }
}
