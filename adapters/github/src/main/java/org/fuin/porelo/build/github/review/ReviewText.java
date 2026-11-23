package org.fuin.porelo.build.github.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Block;
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
import org.commonmark.node.SoftLineBreak;
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

    /** A body as the forge holds it, braces single: at most GitHub's most characters. */
    public static final int BODY_LIMIT = 65_536;

    /** A display name, at most this many Unicode scalar values. */
    public static final int DISPLAY_NAME_LIMIT = 128;

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;

    private static final Pattern MENTION =
            Pattern.compile(
                    "(?<![A-Za-z0-9@])@[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})(?:/[A-Za-z0-9_.-]+)?");

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
     * shortened or rewritten: a refused character, also one a character reference stands for, an
     * HTML block other than a comment, a body that reads otherwise once its markup is dropped, or a
     * body over the limit holds the item.
     *
     * @param body The forge's text.
     * @return The text, its losses, or why it is held.
     */
    public static Read fromForge(String body) {
        final Set<String> losses = new TreeSet<>();
        final String lines = body.replace("\r\n", "\n").replace('\r', '\n');
        final Document parsed = parse(lines);
        final List<Edit> edits = new ArrayList<>();
        final String held = reduce(parsed, lines, edits, losses);
        if (held != null) {
            return new Read(null, losses, held);
        }
        final String text = apply(lines, edits);
        // Dropping a tag can join the pieces of another, or turn text into code: read it again.
        if (!edits.isEmpty() && !shown(parse(text), false).equals(shown(parsed, true))) {
            return new Read(null, losses, "markup that reads otherwise once its HTML is dropped");
        }
        final String refused = refusedCharacter(text);
        if (refused != null) {
            return new Read(null, losses, refused);
        }
        if (text.isBlank()) {
            return new Read(null, losses, "an empty body");
        }
        if (overLimit(text)) {
            return new Read(null, losses, "a body over " + BODY_LIMIT + " characters");
        }
        return new Read(text.replace("{", "{{").replace("}", "}}"), losses, null);
    }

    /**
     * Collects what a read drops, or says why the body is held, visiting the nodes in document
     * order without recursion, since a body GitHub holds may nest tens of thousands deep.
     */
    private static @Nullable String reduce(
            Node root, String text, List<Edit> edits, Set<String> losses) {
        final Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            final Node node = pending.pop();
            final Visit visit = reduceOne(node, text, edits, losses);
            if (visit.held() != null) {
                return visit.held();
            }
            if (visit.children()) {
                pushChildren(node, pending);
            }
        }
        return null;
    }

    /** What one node of a read says: why it is held, or whether its children are read. */
    private record Visit(@Nullable String held, boolean children) {
        static final Visit CHILDREN = new Visit(null, true);
        static final Visit LEAF = new Visit(null, false);

        static Visit held(String why) {
            return new Visit(why, false);
        }
    }

    /** Pushes a node's children so that they pop in document order. */
    private static void pushChildren(Node node, Deque<Node> pending) {
        for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
            pending.push(child);
        }
    }

    private static Visit reduceOne(Node node, String text, List<Edit> edits, Set<String> losses) {
        if (node instanceof HtmlBlock block && !isComment(block.getLiteral())) {
            return Visit.held("an HTML block, whose text GitHub shows");
        }
        if (node instanceof FencedCodeBlock fence) {
            final String refused = refusedCharacter(String.valueOf(fence.getInfo()));
            if (refused != null) {
                return Visit.held(refused + " in a fence's info");
            }
        }
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
            return Visit.LEAF;
        }
        if (node instanceof Code
                || node instanceof FencedCodeBlock
                || node instanceof IndentedCodeBlock) {
            return Visit.LEAF;
        }
        if (node instanceof Link || node instanceof Image) {
            losses.add("link-dropped");
            keepOnlyChildren(node, edits);
        }
        if (node instanceof Text plain) {
            // A character reference stands for its character, so the decoded text is checked.
            final String refused = refusedCharacter(withoutEmojiSelectors(plain.getLiteral()));
            if (refused != null) {
                return Visit.held(refused);
            }
            int written = 0;
            for (final SourceSpan span : spans(node)) {
                written += count(text.substring(span.getInputIndex(), end(span)));
                dropEmojiVariationSelectors(text, span, edits, losses);
            }
            if (count(plain.getLiteral()) > written) {
                // Only a written U+FE0F is dropped; a character reference to one would stay.
                return Visit.held("a character reference to U+FE0F");
            }
        }
        return Visit.CHILDREN;
    }

    /** A link or an image: the brackets and the destination go, its text stays. */
    private static void keepOnlyChildren(Node node, List<Edit> edits) {
        final int start = start(node);
        final int end = end(node);
        // A line break the parser made between lines has no place in the text.
        Node first = node.getFirstChild();
        while (first != null && first.getSourceSpans().isEmpty()) {
            first = first.getNext();
        }
        Node last = node.getLastChild();
        while (last != null && last.getSourceSpans().isEmpty()) {
            last = last.getPrevious();
        }
        if (first == null || last == null) {
            edits.add(new Edit(start, end, ""));
            return;
        }
        edits.add(new Edit(start, start(first), ""));
        edits.add(new Edit(end(last), end, ""));
    }

    /** HTML comments GitHub hides whole: nothing but white space between or after them. */
    private static boolean isComment(String block) {
        int i = 0;
        while (true) {
            while (i < block.length() && Character.isWhitespace(block.charAt(i))) {
                i++;
            }
            if (i == block.length()) {
                return true;
            }
            if (!block.startsWith("<!--", i)) {
                return false;
            }
            // The earliest end, as a browser takes it, so no text is read as inside a comment.
            final int end = block.indexOf("-->", i + 2);
            final int bang = block.indexOf("--!>", i + 2);
            if (end < 0 && bang < 0) {
                return true;
            }
            i = bang >= 0 && (end < 0 || bang < end) ? bang + 4 : end + 3;
        }
    }

    private static boolean isCode(Node node) {
        return node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock;
    }

    private static int count(String text) {
        return (int) text.codePoints().filter(cp -> cp == VARIATION_SELECTOR_16).count();
    }

    /**
     * What a reader sees of a parse: its blocks, code and text, with HTML, link reference
     * definitions and the wrappers of links left out, and spaces, which dropping a tag moves, too.
     */
    private static String shown(Node root, boolean dropped) {
        final StringBuilder out = new StringBuilder();
        // One buffer and a stack, never a buffer per level: a body may nest tens of thousands deep.
        final Deque<Object> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            final Object next = pending.pop();
            if (next instanceof Close close) {
                close(close, out);
            } else {
                open((Node) next, dropped, out, pending);
            }
        }
        return out.toString();
    }

    /** A wrapped node's end: where its wrapper and its own content begin in the buffer. */
    private record Close(Node node, int wrapper, int content) {}

    /** With {@code dropped}, the HTML is what the read drops; without, HTML left is new. */
    private static void open(Node node, boolean dropped, StringBuilder out, Deque<Object> pending) {
        if (node instanceof HtmlBlock || node instanceof HtmlInline) {
            if (!dropped) {
                out.append("\u0001html\u0001");
            }
            return;
        }
        if (node instanceof LinkReferenceDefinition || node instanceof SoftLineBreak) {
            return;
        }
        if (node instanceof Text plain) {
            out.append(withoutEmojiSelectors(plain.getLiteral()).replaceAll("[ \t]", ""));
            return;
        }
        final boolean wrapped = !(node instanceof Link || node instanceof Image);
        final int wrapper = out.length();
        if (wrapped) {
            out.append('\u0002').append(node.getClass().getSimpleName()).append('\u0002');
        }
        final int content = out.length();
        if (node instanceof Code code) {
            out.append(code.getLiteral());
        } else if (node instanceof FencedCodeBlock fence) {
            out.append(fence.getInfo()).append('\u0003').append(fence.getLiteral());
        } else if (node instanceof IndentedCodeBlock code) {
            out.append(code.getLiteral());
        }
        if (wrapped) {
            pending.push(new Close(node, wrapper, content));
        }
        for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
            pending.push(child);
        }
    }

    private static void close(Close close, StringBuilder out) {
        // A block left empty, a paragraph that held only an image say, is gone from the text.
        if (close.node() instanceof Block
                && !isCode(close.node())
                && out.length() == close.content()) {
            out.setLength(close.wrapper());
        } else {
            out.append('\u0004');
        }
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
    static List<int[]> fences(String text) {
        final List<int[]> out = new ArrayList<>();
        final Node root = BLOCKS.parse(text);
        for (Node child = root.getFirstChild(); child != null; child = child.getNext()) {
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
