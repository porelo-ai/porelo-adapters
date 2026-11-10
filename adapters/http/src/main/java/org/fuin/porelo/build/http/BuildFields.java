package org.fuin.porelo.build.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.fuin.porelo.build.api.Build;
import org.jspecify.annotations.Nullable;

/** Validates optional forge metadata before it reaches a build answer or request path. */
public final class BuildFields {
    private BuildFields() {}

    /**
     * Returns a positive integral id without floating-point conversion.
     *
     * @param value A field parsed with exact JSON numbers.
     * @return Its decimal digits, or {@code null} for anything else.
     */
    public static @Nullable String id(final @Nullable Object value) {
        return value instanceof Long number && number > 0 ? Long.toString(number) : null;
    }

    /**
     * Returns a trigger only when the forge supplies a bounded printable string.
     *
     * @param value The forge's event field.
     * @return The trigger in lower case, or {@code null}.
     */
    public static @Nullable String event(final @Nullable Object value) {
        if (!(value instanceof String text)
                || text.isBlank()
                || text.codePointCount(0, text.length()) > Build.MOST_ID
                || text.codePoints().anyMatch(point -> !printable(point))) return null;
        final String event = text.strip().toLowerCase(Locale.ROOT);
        return event.codePointCount(0, event.length()) <= Build.MOST_ID ? event : null;
    }

    static boolean printable(final int point) {
        return !Character.isISOControl(point)
                && Character.getType(point) != Character.FORMAT
                && Character.getType(point) != Character.LINE_SEPARATOR
                && Character.getType(point) != Character.PARAGRAPH_SEPARATOR
                && Character.getType(point) != Character.SURROGATE;
    }

    /**
     * Keeps a job page only on the forge's own web host.
     *
     * @param value The forge's job page, or a page derived from its run.
     * @param upstream The repository's configured web or SSH address.
     * @return A bounded HTTPS page without credentials, or {@code null}.
     */
    public static @Nullable String page(final @Nullable Object value, final String upstream) {
        if (!(value instanceof String text)
                || text.isEmpty()
                || text.length() > Build.MOST_URL
                || text.chars().anyMatch(point -> point <= ' ' || point >= 0x7f)) return null;
        try {
            final URI page = new URI(text);
            final String host = webHost(upstream);
            return "https".equals(page.getScheme())
                            && page.getRawUserInfo() == null
                            && page.getHost() != null
                            && page.getHost().equalsIgnoreCase(host)
                    ? text
                    : null;
        } catch (final URISyntaxException ex) {
            return null;
        }
    }

    private static String webHost(final String upstream) throws URISyntaxException {
        final String address = upstream.strip();
        if (address.contains("://")) {
            final String host = new URI(address).getHost();
            return host == null ? "" : host;
        }
        final int at = address.indexOf('@');
        final int colon = address.indexOf(':', at + 1);
        final int slash = address.indexOf('/', at + 1);
        final int end = colon < 0 ? slash : slash < 0 ? colon : Math.min(colon, slash);
        return end < 0 ? "" : address.substring(at + 1, end);
    }

    /**
     * Keeps the first appearance of each valid id, retaining unidentifiable runs for the verdict.
     *
     * @param runs All runs in forge order.
     * @return The runs in that order, without repeated ids.
     */
    public static List<Map<?, ?>> distinct(final List<Map<?, ?>> runs) {
        final Set<String> seen = new HashSet<>();
        final List<Map<?, ?>> distinct = new ArrayList<>();
        for (final Map<?, ?> run : runs) {
            final String id = id(run.get("id"));
            if (id == null || seen.add(id)) distinct.add(run);
        }
        return List.copyOf(distinct);
    }

    /**
     * Selects a bounded number of addressable runs, failures before the others in forge order.
     *
     * @param runs Distinct runs in forge order.
     * @param failed Whether a run failed.
     * @return At most {@link Build#MOST_RUNS} runs whose jobs may be requested.
     */
    public static List<Map<?, ?>> forJobs(
            final List<Map<?, ?>> runs, final Predicate<Map<?, ?>> failed) {
        final List<Map<?, ?>> selected = new ArrayList<>();
        for (final boolean failures : List.of(true, false)) {
            for (final Map<?, ?> run : runs) {
                if (selected.size() == Build.MOST_RUNS) return List.copyOf(selected);
                if (id(run.get("id")) != null && failed.test(run) == failures) selected.add(run);
            }
        }
        return List.copyOf(selected);
    }
}
