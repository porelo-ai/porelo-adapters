package org.fuin.porelo.planning.storiesgit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class Markdown {
    private static final Pattern LINK = Pattern.compile("\\[([^]\\n]+)]\\(([^)\\s]+)\\)");

    private Markdown() {}

    record Link(String text, String target) {}

    static List<Link> links(String text) {
        final var matcher = LINK.matcher(text);
        final List<Link> links = new ArrayList<>();
        while (matcher.find()) links.add(new Link(matcher.group(1), matcher.group(2)));
        return List.copyOf(links);
    }

    static String plain(String text) {
        return LINK.matcher(text).replaceAll("$1").strip();
    }

    static List<String> cells(String row) {
        final List<String> cells = new ArrayList<>();
        final StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        final String line = row.strip();
        if (!line.startsWith("|") || !line.endsWith("|"))
            throw Refusals.failed("Expected a Markdown table row; got an incomplete row");
        for (int index = 1; index < line.length() - 1; index++) {
            final char ch = line.charAt(index);
            if (escaped) {
                if (ch != '|') cell.append('\\');
                cell.append(ch);
                escaped = false;
            } else if (ch == '\\') escaped = true;
            else if (ch == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else cell.append(ch);
        }
        if (escaped) cell.append('\\');
        cells.add(cell.toString().strip());
        return List.copyOf(cells);
    }

    static String section(String markdown, String heading) {
        final StringBuilder section = new StringBuilder();
        boolean inside = false;
        for (final String line : markdown.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                if (inside) break;
                inside = line.equals("## " + heading);
            } else if (inside) section.append(line).append('\n');
        }
        return section.toString();
    }

    static Optional<String> title(String markdown, String id) {
        for (final String line : markdown.lines().toList()) {
            if (line.startsWith("# ")) {
                final String heading = line.substring(2).strip();
                return Optional.of(
                        heading.replaceFirst("^" + Pattern.quote(id) + "\\s+(?:—|–|-)\\s+", ""));
            }
        }
        return Optional.empty();
    }

    static String acceptance(String markdown) {
        final String label = "**What must be true.**";
        final List<String> lines = markdown.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).startsWith(label)) continue;
            final StringBuilder paragraph =
                    new StringBuilder(lines.get(index).substring(label.length()).stripLeading());
            for (int next = index + 1; next < lines.size() && !lines.get(next).isBlank(); next++)
                paragraph.append('\n').append(lines.get(next));
            return paragraph.toString() + "\n";
        }
        return "";
    }
}
