package org.fuin.porelo.build.github.review;

/**
 * A document or a write the review profile refuses; its reason is the varlink error that carries
 * it. The words name the rule and the path, never a body or a token.
 */
public final class ReviewRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why, as the review contract names its refusals. */
    public enum Reason {
        /** Outside the profile, its limits, the submitter's column or the action's fields. */
        PROFILE_VIOLATION("ProfileViolation"),
        /** A daemon-owned or authority field supplied. */
        FORBIDDEN_FIELD("ForbiddenField"),
        /** Over a byte, result or depth limit. */
        TOO_LARGE("TooLarge"),
        /** A refused character. */
        UNSAFE_TEXT("UnsafeText"),
        /** The forge cannot carry it. */
        NOT_SUPPORTED("NotSupported"),
        /** A fix that is not one replacement of its anchor. */
        FIX_OUTSIDE_ANCHOR("FixOutsideAnchor"),
        /** A fix on the old side. */
        OLD_SIDE_FIX("OldSideFix");

        private final String error;

        Reason(String error) {
            this.error = error;
        }

        /**
         * Returns the error's name as the contract spells it.
         *
         * @return Name, such as {@code ProfileViolation}.
         */
        public String error() {
            return error;
        }
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
