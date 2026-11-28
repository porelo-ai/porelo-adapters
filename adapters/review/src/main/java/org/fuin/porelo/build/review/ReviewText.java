package org.fuin.porelo.build.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;

/**
 * The text rules of Porelo's review profile: what a message becomes on a write, and which
 * characters no string of a document may hold. What is code, HTML or text is the CommonMark
 * parser's answer, with GitHub's tables, never a guess of this class.
 */
public final class ReviewText {

    /** A body as the forge holds it, braces single: at most GitHub's most characters. */
    public static final int BODY_LIMIT = 65_536;

    /** A display name, at most this many Unicode scalar values. */
    public static final int DISPLAY_NAME_LIMIT = 128;

    /**
     * GitHub's logins and Forgejo's, which may start with an underscore and hold, never end in,
     * dots.
     */
    private static final Pattern MENTION =
            Pattern.compile(
                    "(?<![A-Za-z0-9@])@[A-Za-z0-9_](?:[A-Za-z0-9_.-]{0,38}[A-Za-z0-9_-])?"
                            + "(?:/[A-Za-z0-9_](?:[A-Za-z0-9_.-]{0,98}[A-Za-z0-9_-])?)?");

    private static final Pattern CHARACTER_REFERENCE =
            Pattern.compile("&(?:#[0-9]{1,7}|#[xX][0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});");

    /** GitHub's comments render tables and footnotes beside CommonMark; the parser is immutable. */
    private static final Parser PARSER =
            Parser.builder()
                    .extensions(List.of(TablesExtension.create(), FootnotesExtension.create()))
                    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                    .build();

    /** Blocks alone: a fence needs no inline parse, whose cost grows with the square of "<". */
    private static final Parser BLOCKS =
            Parser.builder()
                    .extensions(List.of(TablesExtension.create(), FootnotesExtension.create()))
                    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                    .inlineParserFactory(context -> (lines, node) -> {})
                    .build();

    private ReviewText() {}

    /** Pushes a node's children so that they pop in document order. */
    private static void pushChildren(Node node, Deque<Node> pending) {
        for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
            pending.push(child);
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
        final String body = undoubleBraces(message);
        if (overLimit(body)) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.TOO_LARGE,
                    "over " + BODY_LIMIT + " characters",
                    "message.text");
        }
        final List<int[]> text = new ArrayList<>();
        refuseMarkup(parse(body), body, text);
        // Longer than every backtick run of the body, so no stray backtick pairs with it.
        final String marks = "`".repeat(longestBacktickRun(body) + 1);
        final List<Edit> edits = new ArrayList<>();
        for (final int[] range : merged(text)) {
            escapeMentions(body, range[0], range[1], marks, edits);
        }
        final String out = apply(body, edits);
        if (overLimit(out)) {
            throw new ReviewRefusal(
                    ReviewRefusal.Reason.TOO_LARGE,
                    "over " + BODY_LIMIT + " characters once its mentions are code spans",
                    "message.text");
        }
        if (leftMention(parse(out))) {
            throw violation("a mention that a code span cannot hold");
        }
        return out;
    }

    /** Refuses what GitHub would render as markup, and collects the text's ranges in order. */
    private static void refuseMarkup(Node root, String body, List<int[]> text) {
        final Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            final Node node = pending.pop();
            if (refuseMarkupOne(node, body, text)) {
                pushChildren(node, pending);
            }
        }
    }

    /** Refuses one node's markup; whether its children are read. */
    private static boolean refuseMarkupOne(Node node, String body, List<int[]> text) {
        if (node instanceof HtmlBlock || node instanceof HtmlInline) {
            throw violation("HTML; code goes in a code span");
        }
        if (node instanceof FencedCodeBlock fence && isSuggestion(fence.getInfo())) {
            throw violation("a suggestion fence; a fix goes in fixes");
        }
        if (node instanceof Link && body.charAt(start(node)) == '<') {
            throw violation("an autolink; code goes in a code span");
        }
        if (node instanceof Image) {
            throw violation("an image, which GitHub fetches from its address");
        }
        // GitHub decodes character references in these, which are no text nodes.
        if (node instanceof Link link) {
            refuseDecoded(link.getDestination(), link.getTitle());
        }
        if (node instanceof LinkReferenceDefinition definition) {
            refuseDecoded(definition.getDestination(), definition.getTitle());
        }
        if (node instanceof FencedCodeBlock fence) {
            refuseDecoded(fence.getInfo(), null);
        }
        if (node instanceof Code
                || node instanceof FencedCodeBlock
                || node instanceof IndentedCodeBlock) {
            return false;
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
        return true;
    }

    private static void refuseDecoded(@Nullable String first, @Nullable String second) {
        for (final String value : new String[] {first, second}) {
            final String refused = value == null ? null : refusedCharacter(value);
            if (refused != null) {
                throw violation("a character reference to " + refused);
            }
        }
    }

    private static boolean isSuggestion(@Nullable String info) {
        return info != null && info.strip().split("[ \t]", 2)[0].equalsIgnoreCase("suggestion");
    }

    /** Whether text GitHub renders still holds a mention, read across adjacent text nodes. */
    private static boolean leftMention(Node root) {
        final Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            final Node node = pending.pop();
            final StringBuilder run = new StringBuilder();
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Text plain) {
                    run.append(plain.getLiteral());
                    continue;
                }
                // A forge strips emphasis before it looks for mentions, so its text joins the run.
                if (child instanceof Emphasis || child instanceof StrongEmphasis) {
                    emphasized(child, run);
                    continue;
                }
                if (MENTION.matcher(run).find()) {
                    return true;
                }
                run.setLength(0);
                if (!(child instanceof Code
                        || child instanceof FencedCodeBlock
                        || child instanceof IndentedCodeBlock)) {
                    pending.push(child);
                }
            }
            if (MENTION.matcher(run).find()) {
                return true;
            }
        }
        return false;
    }

    /** Appends emphasized text as a forge reads it, a code span as a break no mention spans. */
    private static void emphasized(Node node, StringBuilder run) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text plain) {
                run.append(plain.getLiteral());
            } else if (child instanceof Emphasis || child instanceof StrongEmphasis) {
                emphasized(child, run);
            } else {
                run.append(' ');
            }
        }
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
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0xFDD0 && cp <= 0xFDEF)
                || (cp & 0xFFFE) == 0xFFFE;
    }

    private static boolean overLimit(String body) {
        return body.codePointCount(0, body.length()) > BODY_LIMIT;
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
    public static List<int[]> fences(String text) {
        final List<int[]> out = new ArrayList<>();
        final Node root = BLOCKS.parse(text);
        for (Node child = root.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock) {
                out.add(new int[] {start(child), end(child)});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------- the parse

    private static Document parse(String text) {
        return (Document) PARSER.parse(text);
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
