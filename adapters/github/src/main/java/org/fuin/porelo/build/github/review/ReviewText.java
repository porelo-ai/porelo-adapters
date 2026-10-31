package org.fuin.porelo.build.github.review;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The text rules of Porelo's review profile: what a forge's text becomes on a read, what a message
 * becomes on a write, and which characters no string of a document may hold.
 */
public final class ReviewText {

    /** A message body, at most this many UTF-8 bytes. */
    public static final int BODY_LIMIT = 8 * 1024;

    /** A display name, at most this many Unicode scalar values. */
    public static final int DISPLAY_NAME_LIMIT = 128;

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;

    private static final Pattern HTML =
            Pattern.compile(
                    "<!--.*?-->|<[!?][^<>]*>|</?[A-Za-z][A-Za-z0-9-]*(?:[\\s/][^<>]*)?>",
                    Pattern.DOTALL);

    private static final Pattern IMAGE_OR_LINK =
            Pattern.compile("!?\\[([^\\]\\n]*)\\]\\([^)\\n]*\\)");

    private static final Pattern AUTOLINK =
            Pattern.compile("<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\s]*)>");

    private static final Pattern MENTION =
            Pattern.compile(
                    "(?<![A-Za-z0-9@])@[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})(?:/[A-Za-z0-9_.-]+)?");

    private ReviewText() {}

    /** What a forge's text became: the profile's text and what was lost, or why it is held. */
    public record Read(@Nullable String text, Set<String> losses, @Nullable String held) {

        /** Copies the losses. */
        public Read {
            losses = Set.copyOf(losses);
        }

        /**
         * Tells whether the text cannot be carried and its item is held.
         *
         * @return {@code true} if held.
         */
        public boolean isHeld() {
            return held != null;
        }
    }

    /**
     * Converts a forge's prose to the profile's message text: line ends become {@code \n}, HTML and
     * links outside code become their text, an emoji's U+FE0F in prose is dropped, every brace is
     * doubled. Nothing else is shortened or rewritten: a refused character or a body over the limit
     * holds the item.
     *
     * @param body The forge's text.
     * @return The text, its losses, or why it is held.
     */
    public static Read fromForge(String body) {
        final Set<String> losses = new TreeSet<>();
        final String lines = body.replace("\r\n", "\n").replace('\r', '\n');
        final StringBuilder out = new StringBuilder(lines.length());
        for (final Segment segment : segments(lines)) {
            out.append(segment.code() ? segment.text() : prose(segment.text(), losses));
        }
        final String text = out.toString();
        final String refused = refusedCharacter(text);
        if (refused != null) {
            return new Read(null, losses, refused);
        }
        if (text.isBlank()) {
            return new Read(null, losses, "an empty body");
        }
        final String doubled = text.replace("{", "{{").replace("}", "}}");
        // The limit is the message's, which holds the doubled braces.
        if (doubled.getBytes(StandardCharsets.UTF_8).length > BODY_LIMIT) {
            return new Read(null, losses, "a body over " + BODY_LIMIT + " bytes");
        }
        return new Read(doubled, losses, null);
    }

    /**
     * Turns a message's text back into the body a forge receives: doubled braces are undone and a
     * mention in prose becomes a code span, so it notifies no one.
     *
     * @param message The message text, braces doubled.
     * @return The body.
     * @throws ReviewRefusal {@code UnsafeText} for a refused character, {@code ProfileViolation}
     *     for a single brace, {@code TooLarge} over the body limit.
     */
    public static String toForge(String message) {
        final String refused = refusedCharacter(message);
        if (refused != null) {
            throw new ReviewRefusal(ReviewRefusal.Reason.UNSAFE_TEXT, refused, "message.text");
        }
        if (message.getBytes(StandardCharsets.UTF_8).length > BODY_LIMIT) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.TOO_LARGE,
                    "over " + BODY_LIMIT + " bytes",
                    "message.text");
        }
        final String body = undoubleBraces(message);
        // Longer than every backtick run of the body, so no stray backtick pairs with it.
        final String marks = "`".repeat(longestBacktickRun(body) + 1);
        final StringBuilder out = new StringBuilder(body.length());
        int at = 0;
        for (final Segment segment : segments(body)) {
            if (segment.code()) {
                out.append(segment.text());
            } else {
                escapeMentions(body, at, at + segment.text().length(), marks, out);
            }
            at += segment.text().length();
        }
        return out.toString();
    }

    /** Copies prose, each mention in it a code span that touches no backslash or backtick. */
    private static void escapeMentions(
            String body, int from, int to, String marks, StringBuilder out) {
        final Matcher mention = MENTION.matcher(body).region(from, to);
        int copied = from;
        while (mention.find()) {
            out.append(body, copied, mention.start());
            final char before = mention.start() > 0 ? body.charAt(mention.start() - 1) : ' ';
            if (before == '\\' || before == '`') {
                out.append(' ');
            }
            out.append(marks).append(mention.group()).append(marks);
            if (mention.end() < body.length() && body.charAt(mention.end()) == '`') {
                out.append(' ');
            }
            copied = mention.end();
        }
        out.append(body, copied, to);
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /**
     * Names the first character no string of a document may hold: a format character, a variation
     * selector, an unpaired surrogate, a noncharacter, or a control other than line feed and tab.
     *
     * @param text What to look at.
     * @return Its words, such as {@code "U+202E at 12"}, or {@code null} when there is none.
     */
    public static @Nullable String refusedCharacter(String text) {
        for (int i = 0; i < text.length(); ) {
            final char unit = text.charAt(i);
            if (Character.isSurrogate(unit)) {
                if (!Character.isHighSurrogate(unit)
                        || i + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return "an unpaired surrogate at " + i;
                }
            }
            final int cp = text.codePointAt(i);
            if (isRefused(cp)) {
                return String.format("U+%04X at %d", cp, i);
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    private static boolean isRefused(int cp) {
        if (cp == '\n' || cp == '\t') {
            return false;
        }
        final int type = Character.getType(cp);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0xFDD0 && cp <= 0xFDEF)
                || (cp & 0xFFFE) == 0xFFFE;
    }

    private static String prose(String text, Set<String> losses) {
        String out = dropEmojiVariationSelectors(text, losses);
        // Until nothing changes: dropping one tag can join the pieces of another.
        String before;
        do {
            before = out;
            out = replace(HTML, out, m -> "", losses, "markup-dropped");
            out = replace(IMAGE_OR_LINK, out, m -> m.group(1), losses, "link-dropped");
            out = replace(AUTOLINK, out, m -> m.group(1), losses, "link-dropped");
        } while (!out.equals(before));
        return out;
    }

    private static String dropEmojiVariationSelectors(String text, Set<String> losses) {
        final StringBuilder out = new StringBuilder(text.length());
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            if (cp == VARIATION_SELECTOR_16
                    && previous >= 0
                    && Character.isExtendedPictographic(previous)) {
                losses.add("variation-selector-dropped");
            } else {
                out.appendCodePoint(cp);
            }
            previous = cp;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String replace(
            Pattern pattern,
            String text,
            java.util.function.Function<java.util.regex.MatchResult, String> with,
            Set<String> losses,
            String loss) {
        final Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        losses.add(loss);
        return matcher.replaceAll(m -> Matcher.quoteReplacement(with.apply(m)));
    }

    private static String undoubleBraces(String message) {
        final StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            final char c = message.charAt(i);
            if (c == '{' || c == '}') {
                if (i + 1 >= message.length() || message.charAt(i + 1) != c) {
                    throw new ReviewRefusal(
                            ReviewRefusal.Reason.PROFILE_VIOLATION,
                            "a single brace at " + i + "; a literal brace is written doubled",
                            "message.text");
                }
                i++;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** A run of text that is code (a fence or a code span) or prose. */
    private record Segment(String text, boolean code, boolean fence) {}

    /**
     * Finds the fenced code blocks that stand in prose, not inside another block.
     *
     * @param text Markdown, line ends {@code \n}.
     * @return Each block's start and end in the text, from its opening line to its closing one.
     */
    static List<int[]> fences(String text) {
        final List<int[]> out = new ArrayList<>();
        int at = 0;
        for (final Segment segment : segments(text)) {
            if (segment.fence()) {
                out.add(new int[] {at, at + segment.text().length()});
            }
            at += segment.text().length();
        }
        return out;
    }

    /** Splits Markdown into code and prose, so rules for prose never touch code. */
    private static List<Segment> segments(String text) {
        final List<Segment> out = new ArrayList<>();
        final StringBuilder prose = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            final int fenceEnd = fenceAt(text, i);
            if (fenceEnd > i) {
                flush(prose, out);
                out.add(new Segment(text.substring(i, fenceEnd), true, true));
                i = fenceEnd;
                continue;
            }
            if (text.charAt(i) == '`') {
                final int run = runOf(text, i, '`');
                final int close = closingRun(text, i + run, run);
                if (close >= 0) {
                    flush(prose, out);
                    out.add(new Segment(text.substring(i, close + run), true, false));
                    i = close + run;
                    continue;
                }
                prose.append(text, i, i + run);
                i += run;
                continue;
            }
            prose.append(text.charAt(i));
            i++;
        }
        flush(prose, out);
        return out;
    }

    /** At the start of a line opening a fence of three or more backticks or tildes, its end. */
    private static int fenceAt(String text, int i) {
        if (i > 0 && text.charAt(i - 1) != '\n') {
            return -1;
        }
        int start = i;
        while (start < text.length() && start - i < 3 && text.charAt(start) == ' ') {
            start++;
        }
        if (start >= text.length() || (text.charAt(start) != '`' && text.charAt(start) != '~')) {
            return -1;
        }
        final char mark = text.charAt(start);
        final int run = runOf(text, start, mark);
        if (run < 3 || (mark == '`' && infoHasBacktick(text, start + run))) {
            return -1;
        }
        int line = text.indexOf('\n', start);
        while (line >= 0) {
            int at = line + 1;
            while (at < text.length() && at - line - 1 < 3 && text.charAt(at) == ' ') {
                at++;
            }
            final int closing = runOf(text, at, mark);
            if (closing >= run) {
                final int end = text.indexOf('\n', at);
                if (onlySpacesAndTabs(
                        text.substring(at + closing, end < 0 ? text.length() : end))) {
                    return end < 0 ? text.length() : end;
                }
            }
            line = text.indexOf('\n', at);
        }
        // An unclosed fence runs to the end, as Markdown has it.
        return text.length();
    }

    /** A backtick fence's info line holds no backtick; with one, the line is a code span. */
    private static boolean infoHasBacktick(String text, int from) {
        final int end = text.indexOf('\n', from);
        return text.substring(from, end < 0 ? text.length() : end).indexOf('`') >= 0;
    }

    /** CommonMark's blank: spaces and tabs only, no other white space. */
    static boolean onlySpacesAndTabs(String text) {
        return text.chars().allMatch(c -> c == ' ' || c == '\t');
    }

    private static int runOf(String text, int i, char c) {
        int n = 0;
        while (i + n < text.length() && text.charAt(i + n) == c) {
            n++;
        }
        return n;
    }

    private static int closingRun(String text, int from, int run) {
        int i = from;
        while (i < text.length()) {
            if (text.charAt(i) == '\n') {
                // A line may start a block that ends the paragraph; a span crossing it is not
                // trusted to be one, so what follows stays prose.
                return -1;
            }
            if (text.charAt(i) == '`') {
                final int n = runOf(text, i, '`');
                if (n == run) {
                    return i;
                }
                i += n;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static void flush(StringBuilder prose, List<Segment> out) {
        if (!prose.isEmpty()) {
            out.add(new Segment(prose.toString(), false, false));
            prose.setLength(0);
        }
    }
}
