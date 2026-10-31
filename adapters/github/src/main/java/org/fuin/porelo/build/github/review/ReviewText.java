package org.fuin.porelo.build.github.review;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;

/**
 * The text rules of Porelo's review profile: what a forge's text becomes on a read, what a message
 * becomes on a write, and which characters no string of a document may hold. What is code, HTML or
 * text is the CommonMark parser's answer, with GitHub's tables, never a guess of this class.
 */
public final class ReviewText {

    /** A message body, at most this many UTF-8 bytes. */
    public static final int BODY_LIMIT = 8 * 1024;

    /** A display name, at most this many Unicode scalar values. */
    public static final int DISPLAY_NAME_LIMIT = 128;

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;

    private static final Pattern MENTION =
            Pattern.compile(
                    "(?<![A-Za-z0-9@])@[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})(?:/[A-Za-z0-9_.-]+)?");

    private static final Pattern CHARACTER_REFERENCE =
            Pattern.compile("&(?:#[0-9]{1,7}|#[xX][0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});");

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
     * Converts a forge's Markdown to the profile's message text: line ends become {@code \n}, HTML
     * and link reference definitions are dropped, a link or image becomes its text, an emoji's
     * U+FE0F in text is dropped, every brace is doubled. Code is never touched. Nothing else is
     * shortened or rewritten: a refused character, also one a character reference stands for, or a
     * body over the limit holds the item.
     *
     * @param body The forge's text.
     * @return The text, its losses, or why it is held.
     */
    public static Read fromForge(String body) {
        final Set<String> losses = new TreeSet<>();
        final String lines = body.replace("\r\n", "\n").replace('\r', '\n');
        String text = lines;
        // Until nothing changes: dropping one tag can join the pieces of another.
        while (true) {
            final List<Edit> edits = new ArrayList<>();
            final String held = reduce(parse(text), text, edits, losses);
            if (held != null) {
                return new Read(null, losses, held);
            }
            final String reduced = apply(text, edits);
            if (reduced.equals(text)) {
                break;
            }
            text = reduced;
        }
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

    /** Collects what a read drops, or says why the body is held. */
    private static @Nullable String reduce(
            Node node, String text, List<Edit> edits, Set<String> losses) {
        if (node instanceof HtmlBlock
                || node instanceof HtmlInline
                || node instanceof LinkReferenceDefinition) {
            losses.add(node instanceof LinkReferenceDefinition ? "link-dropped" : "markup-dropped");
            for (final SourceSpan span : spans(node)) {
                // A block's whole line goes, so a dropped block leaves no empty lines behind.
                final boolean wholeLine =
                        node instanceof HtmlBlock
                                && span.getColumnIndex() == 0
                                && end(span) < text.length()
                                && text.charAt(end(span)) == '\n';
                edits.add(new Edit(span.getInputIndex(), end(span) + (wholeLine ? 1 : 0), ""));
            }
            return null;
        }
        if (node instanceof Code
                || node instanceof FencedCodeBlock
                || node instanceof IndentedCodeBlock) {
            return null;
        }
        if (node instanceof Link || node instanceof Image) {
            losses.add("link-dropped");
            keepOnlyChildren(node, edits);
        }
        if (node instanceof Text plain) {
            // A character reference stands for its character, so the decoded text is checked.
            final String refused = refusedCharacter(withoutEmojiSelectors(plain.getLiteral()));
            if (refused != null) {
                return refused;
            }
            for (final SourceSpan span : spans(node)) {
                dropEmojiVariationSelectors(text, span, edits, losses);
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            final String held = reduce(child, text, edits, losses);
            if (held != null) {
                return held;
            }
        }
        return null;
    }

    /** A link or an image: the brackets and the destination go, its text stays. */
    private static void keepOnlyChildren(Node node, List<Edit> edits) {
        final int start = start(node);
        final int end = end(node);
        final Node first = node.getFirstChild();
        final Node last = node.getLastChild();
        if (first == null || last == null) {
            edits.add(new Edit(start, end, ""));
            return;
        }
        edits.add(new Edit(start, start(first), ""));
        edits.add(new Edit(end(last), end, ""));
    }

    private static String withoutEmojiSelectors(String text) {
        final StringBuilder out = new StringBuilder(text.length());
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            if (!(cp == VARIATION_SELECTOR_16
                    && previous >= 0
                    && Character.isExtendedPictographic(previous))) {
                out.appendCodePoint(cp);
            }
            previous = cp;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static void dropEmojiVariationSelectors(
            String text, SourceSpan span, List<Edit> edits, Set<String> losses) {
        int previous = -1;
        for (int i = span.getInputIndex(); i < end(span); ) {
            final int cp = text.codePointAt(i);
            if (cp == VARIATION_SELECTOR_16
                    && previous >= 0
                    && Character.isExtendedPictographic(previous)) {
                losses.add("variation-selector-dropped");
                edits.add(new Edit(i, i + 1, ""));
            }
            previous = cp;
            i += Character.charCount(cp);
        }
    }

    /**
     * Turns a message's text back into the body a forge receives: doubled braces are undone and a
     * mention in text becomes a code span, so it notifies no one. HTML, an autolink, a character
     * reference in text and a {@code suggestion} fence are refused, and the body is parsed once
     * more to see that no mention is left.
     *
     * @param message The message text, braces doubled.
     * @return The body.
     * @throws ReviewRefusal {@code UnsafeText} for a refused character, {@code ProfileViolation}
     *     for a single brace or markup, {@code TooLarge} over the body limit.
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
        final List<int[]> text = new ArrayList<>();
        refuseMarkup(parse(body), body, text);
        // Longer than every backtick run of the body, so no stray backtick pairs with it.
        final String marks = "`".repeat(longestBacktickRun(body) + 1);
        final List<Edit> edits = new ArrayList<>();
        for (final int[] range : merged(text)) {
            escapeMentions(body, range[0], range[1], marks, edits);
        }
        final String out = apply(body, edits);
        if (leftMention(parse(out))) {
            throw violation("a mention that a code span cannot hold");
        }
        return out;
    }

    /** Refuses what GitHub would render as markup, and collects the text's ranges. */
    private static void refuseMarkup(Node node, String body, List<int[]> text) {
        if (node instanceof HtmlBlock || node instanceof HtmlInline) {
            throw violation("HTML; code goes in a code span");
        }
        if (node instanceof FencedCodeBlock fence && isSuggestion(fence.getInfo())) {
            throw violation("a suggestion fence; a fix goes in fixes");
        }
        if (node instanceof Link && body.charAt(start(node)) == '<') {
            throw violation("an autolink; code goes in a code span");
        }
        if (node instanceof Code
                || node instanceof FencedCodeBlock
                || node instanceof IndentedCodeBlock) {
            return;
        }
        if (node instanceof Text) {
            for (final SourceSpan span : spans(node)) {
                if (CHARACTER_REFERENCE
                        .matcher(body)
                        .region(span.getInputIndex(), end(span))
                        .find()) {
                    throw violation("a character reference; code goes in a code span");
                }
                text.add(new int[] {span.getInputIndex(), end(span)});
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            refuseMarkup(child, body, text);
        }
    }

    private static boolean isSuggestion(@Nullable String info) {
        return info != null && info.strip().split("[ \t]", 2)[0].equalsIgnoreCase("suggestion");
    }

    /** Whether text GitHub renders still holds a mention, read across adjacent text nodes. */
    private static boolean leftMention(Node node) {
        final StringBuilder run = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text plain) {
                run.append(plain.getLiteral());
                continue;
            }
            if (MENTION.matcher(run).find()) {
                return true;
            }
            run.setLength(0);
            if (!(child instanceof Code
                            || child instanceof FencedCodeBlock
                            || child instanceof IndentedCodeBlock)
                    && leftMention(child)) {
                return true;
            }
        }
        return MENTION.matcher(run).find();
    }

    /** Wraps each mention in a range in a code span that touches no backslash or backtick. */
    private static void escapeMentions(
            String body, int from, int to, String marks, List<Edit> edits) {
        final Matcher mention = MENTION.matcher(body).region(from, to).useTransparentBounds(true);
        while (mention.find()) {
            final char before = mention.start() > 0 ? body.charAt(mention.start() - 1) : ' ';
            final String opening = (before == '\\' || before == '`' ? " " : "") + marks;
            final boolean backtickAfter =
                    mention.end() < body.length() && body.charAt(mention.end()) == '`';
            edits.add(new Edit(mention.start(), mention.start(), opening));
            edits.add(new Edit(mention.end(), mention.end(), marks + (backtickAfter ? " " : "")));
        }
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

    /**
     * Finds the fenced code blocks that stand in the document itself, not inside another block.
     *
     * @param text Markdown, line ends {@code \n}.
     * @return Each block's start and end in the text, from its opening line to its closing one.
     */
    static List<int[]> fences(String text) {
        final List<int[]> out = new ArrayList<>();
        for (Node child = parse(text).getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock) {
                out.add(new int[] {start(child), end(child)});
            }
        }
        return out;
    }

    /** CommonMark's blank: spaces and tabs only, no other white space. */
    static boolean onlySpacesAndTabs(String text) {
        return text.chars().allMatch(c -> c == ' ' || c == '\t');
    }

    static int runOf(String text, int i, char c) {
        int n = 0;
        while (i + n < text.length() && text.charAt(i + n) == c) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------- the parse

    private static Document parse(String text) {
        return (Document)
                Parser.builder()
                        .extensions(List.of(TablesExtension.create()))
                        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                        .build()
                        .parse(text);
    }

    /** A node's place in the text; a node the parser cannot place is refused, never guessed. */
    private static List<SourceSpan> spans(Node node) {
        final List<SourceSpan> spans = node.getSourceSpans();
        if (spans.isEmpty()) {
            throw new IllegalStateException(
                    "The parser placed no " + node.getClass().getSimpleName() + " in the text");
        }
        return spans;
    }

    private static int start(Node node) {
        return spans(node).get(0).getInputIndex();
    }

    private static int end(Node node) {
        final List<SourceSpan> spans = spans(node);
        return end(spans.get(spans.size() - 1));
    }

    private static int end(SourceSpan span) {
        return span.getInputIndex() + span.getLength();
    }

    /** One replacement of a range of the text; an insertion when the range is empty. */
    private record Edit(int start, int end, String with) {}

    private static String apply(String text, List<Edit> edits) {
        final List<Edit> sorted =
                edits.stream()
                        .sorted(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end))
                        .toList();
        final StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        for (final Edit edit : sorted) {
            if (edit.start() < at) {
                throw new IllegalStateException("Two edits of one range of the text");
            }
            out.append(text, at, edit.start()).append(edit.with());
            at = edit.end();
        }
        return out.append(text, at, text.length()).toString();
    }

    /** Joins ranges that touch, so a mention split over two text nodes is found whole. */
    private static List<int[]> merged(List<int[]> ranges) {
        final List<int[]> sorted =
                ranges.stream().sorted(Comparator.comparingInt((int[] r) -> r[0])).toList();
        final List<int[]> out = new ArrayList<>();
        for (final int[] range : sorted) {
            final int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                out.add(new int[] {range[0], range[1]});
            }
        }
        return out;
    }

    private static ReviewRefusal violation(String detail) {
        return new ReviewRefusal(ReviewRefusal.Reason.PROFILE_VIOLATION, detail, "message.text");
    }
}
