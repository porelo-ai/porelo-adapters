package org.fuin.porelo.planning.storiesgit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class StoryIndex {
    private static final Pattern ANCHOR = Pattern.compile("<a id=\"([^\"]*)\"></a>");

    private StoryIndex() {}

    static List<String> files(String index) {
        final List<String> paths = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        boolean table = false;
        for (final String line : index.lines().toList()) {
            if (!line.startsWith("|")) {
                table = false;
                continue;
            }
            final List<String> cells = Markdown.cells(line);
            final String first = cells.getFirst();
            if (first.equals("Story")) {
                table = true;
                continue;
            }
            if (cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) continue;
            final var anchor = ANCHOR.matcher(first);
            if (!anchor.find()) {
                if (!table) continue;
                throw Refusals.failed(
                        "Expected a story anchor on every data row; got a missing anchor");
            }
            Settings.id(anchor.group(1));
            final List<Markdown.Link> links = Markdown.links(first);
            if (links.isEmpty())
                throw Refusals.failed(
                        "Expected a story file link for anchor "
                                + anchor.group(1)
                                + "; got no link");
            final String id = Settings.id(links.getFirst().text());
            final String path = links.getFirst().target();
            SafeFiles.relative(path);
            if (!id.equalsIgnoreCase(anchor.group(1))
                    || !path.matches(Pattern.quote(id) + "(?:-.*)?\\.md")
                    || !ids.add(id))
                throw Refusals.failed(
                        "Expected one matching story file link for "
                                + id
                                + "; got a duplicate or mismatched link");
            paths.add(path);
        }
        return List.copyOf(paths);
    }
}
