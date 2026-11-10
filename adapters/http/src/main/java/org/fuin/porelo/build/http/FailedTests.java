package org.fuin.porelo.build.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.Build;
import org.jspecify.annotations.Nullable;

/** Reads failed test names from complete Maven test result blocks. */
public final class FailedTests {
    private static final int MOST_LINE = 4096;
    private static final Pattern TIMESTAMP =
            Pattern.compile(
                    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})"
                            + " ");

    private FailedTests() {}

    /**
     * Reads the complete result blocks in a job's retained log.
     *
     * @param log The retained plain-text log bytes.
     * @return Ordered distinct test names, or {@code null} when none is evidenced.
     */
    public static @Nullable List<String> read(final byte[] log) {
        final String text = new String(log, StandardCharsets.UTF_8);
        final Set<String> completed = new LinkedHashSet<>();
        final Set<String> pending = new LinkedHashSet<>();
        boolean block = false;
        boolean failures = false;
        int start = 0;
        while (start < text.length()) {
            final int newline = text.indexOf('\n', start);
            final int end = newline < 0 ? text.length() : newline;
            final int length = end - start;
            final int next = newline < 0 ? text.length() : newline + 1;
            if (length > MOST_LINE) {
                start = next;
                continue;
            }
            final String line = undecorated(text.substring(start, end));
            start = next;
            final String message = message(line).stripTrailing();
            if (message.equals("Results:")) {
                block = true;
                failures = false;
                pending.clear();
            } else if (block && newline >= 0 && closes(message)) {
                for (final String test : pending) {
                    if (completed.size() < Build.MOST_FAILED_TESTS) completed.add(test);
                }
                block = false;
                pending.clear();
            } else if (block && (message.equals("Failures:") || message.equals("Errors:"))) {
                failures = true;
            } else if (block && message.equals("Flakes:")) {
                failures = false;
            } else if (block && failures && pending.size() < Build.MOST_FAILED_TESTS) {
                final String test = entry(line);
                if (test != null) pending.add(test);
            }
        }
        return completed.isEmpty() ? null : List.copyOf(completed);
    }

    /** The text after Maven's level prefix; a line without one is a message's continuation. */
    private static String message(final String line) {
        for (final String prefix : List.of("[INFO] ", "[ERROR] ", "[WARNING] ")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length());
        }
        return "";
    }

    private static boolean closes(final String message) {
        final String prefix = "Tests run: ";
        if (!message.startsWith(prefix)) return false;
        int end = prefix.length();
        while (end < message.length() && message.charAt(end) >= '0' && message.charAt(end) <= '9')
            end++;
        return end > prefix.length() && (end == message.length() || message.charAt(end) == ',');
    }

    private static @Nullable String entry(final String line) {
        if (!line.startsWith("[ERROR]   ")) return null;
        final int colon = line.indexOf(':', 10);
        if (colon <= 10
                || colon + 1 >= line.length()
                || line.charAt(colon + 1) < '0'
                || line.charAt(colon + 1) > '9') return null;
        final String test = line.substring(10, colon);
        final int dot = test.lastIndexOf('.');
        if (dot < 1
                || dot == test.length() - 1
                || test.codePointCount(0, test.length()) > Build.MOST_TEXT
                || test.codePoints()
                        .anyMatch(
                                point ->
                                        Character.isWhitespace(point)
                                                || Character.isSpaceChar(point)
                                                || !BuildFields.printable(point))) return null;
        return test;
    }

    private static String undecorated(final String text) {
        final StringBuilder plain = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char each = text.charAt(index);
            if (each == '\u001b' && index + 1 < text.length()) {
                final char kind = text.charAt(++index);
                if (kind == '[') {
                    while (index + 1 < text.length()) {
                        final char code = text.charAt(++index);
                        if (code >= '@' && code <= '~') break;
                    }
                } else if (kind == ']') {
                    while (index + 1 < text.length()) {
                        final char code = text.charAt(++index);
                        if (code == '\u0007') break;
                        if (code == '\u001b'
                                && index + 1 < text.length()
                                && text.charAt(index + 1) == '\\') {
                            index++;
                            break;
                        }
                    }
                }
            } else {
                plain.append(each);
            }
        }
        String line = plain.toString();
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        final Matcher timestamp = TIMESTAMP.matcher(line);
        return timestamp.find() ? line.substring(timestamp.end()) : line;
    }
}
