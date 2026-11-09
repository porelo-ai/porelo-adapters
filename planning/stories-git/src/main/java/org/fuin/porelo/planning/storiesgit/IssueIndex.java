package org.fuin.porelo.planning.storiesgit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.SubAgent;
import org.fuin.porelo.planning.api.Workflow;

final class IssueIndex {
    private static final Pattern ANCHOR = Pattern.compile("<a id=\"([a-zA-Z0-9_-]+)\"></a>");

    private IssueIndex() {}

    record Entry(IssueSummary summary, Optional<String> path) {}

    static Map<String, Entry> read(
            SafeFiles files, String repository, Settings settings, Workflow workflow) {
        final String text = files.read("issues/README.md");
        final List<String> paths = files.markdown("issues");
        final Map<String, Entry> entries = new LinkedHashMap<>();
        for (final Entry entry : parse(text)) {
            final IssueSummary row = entry.summary();
            final Pattern filename = Pattern.compile(Pattern.quote(row.id()) + "(?:-.*)?\\.md");
            final List<String> candidates =
                    paths.stream()
                            .filter(
                                    path ->
                                            filename.matcher(Path.of(path).getFileName().toString())
                                                    .matches())
                            .toList();
            if (candidates.size() > 1)
                throw Refusals.failed(
                        "Expected one issue file for " + row.id() + "; got multiple files");
            final Optional<String> path = candidates.stream().findFirst();
            final String title =
                    path.flatMap(p -> Markdown.title(files.read(p), row.id())).orElse(row.title());
            StoryFile.checkState(workflow, row.id(), row.state(), false);
            final String linkPath =
                    path.orElse("issues/README.md#" + row.id().toLowerCase(Locale.ROOT));
            final IssueSummary summary =
                    new IssueSummary(
                            row.id(),
                            repository,
                            title,
                            row.state(),
                            row.size(),
                            row.blockedBy(),
                            settings.link(repository, linkPath));
            if (entries.putIfAbsent(row.id(), new Entry(summary, path)) != null)
                throw Refusals.failed(
                        "Expected unique issue " + row.id() + "; got duplicate index rows");
        }
        return java.util.Collections.unmodifiableMap(entries);
    }

    static List<Entry> parse(String text) {
        final List<Entry> rows = new ArrayList<>();
        String state = "";
        List<String> header = List.of();
        for (final String line : text.split("\\R")) {
            if (line.startsWith("## ")) {
                state = line.substring(3).strip();
                header = List.of();
                continue;
            }
            if (!line.startsWith("|")) continue;
            final List<String> cells = Markdown.cells(line);
            final var anchor = ANCHOR.matcher(cells.getFirst());
            if (!anchor.find()) {
                if (cells.getFirst().equals("#")
                        && (cells.contains("Title") || cells.contains("What it covers")))
                    header = cells;
                continue;
            }
            if (state.isBlank() || header.isEmpty() || header.size() != cells.size())
                throw Refusals.failed(
                        "Expected an issue row under a state and matching table header; got an"
                                + " ungrouped or malformed row");
            final String id = Settings.id(anchor.group(1).toUpperCase(Locale.ROOT));
            final List<Markdown.Link> ownLinks = Markdown.links(cells.getFirst());
            if (!ownLinks.isEmpty() && !ownLinks.getFirst().text().equalsIgnoreCase(id))
                throw Refusals.failed(
                        "Expected index link matching anchor "
                                + id
                                + "; got "
                                + ownLinks.getFirst().text());
            final String size = header.contains("Size") ? cells.get(header.indexOf("Size")) : "";
            final List<String> blocked =
                    header.contains("Blocked by")
                            ? Markdown.links(cells.get(header.indexOf("Blocked by"))).stream()
                                    .map(link -> Settings.id(link.text()))
                                    .toList()
                            : List.of();
            final int titleColumn =
                    header.contains("Title")
                            ? header.indexOf("Title")
                            : header.indexOf("What it covers");
            if (titleColumn < 0)
                throw Refusals.failed(
                        "Expected issue table Title or What it covers column; got neither");
            final String title = Markdown.plain(cells.get(titleColumn));
            final Optional<String> path =
                    ownLinks.stream()
                            .map(Markdown.Link::target)
                            .filter(target -> !target.contains("#") && target.endsWith(".md"))
                            .findFirst();
            path.ifPresent(SafeFiles::relative);
            rows.add(
                    new Entry(
                            new IssueSummary(
                                    id,
                                    "",
                                    title,
                                    state,
                                    size.equals("-") ? "" : size,
                                    blocked,
                                    ""),
                            path));
        }
        return List.copyOf(rows);
    }

    static Issue full(Entry entry, SafeFiles files) {
        final IssueSummary summary = entry.summary();
        final String markdown = entry.path().map(files::read).orElse("");
        final List<SubAgent> agents =
                entry.path()
                        .map(path -> agents(markdown, path, summary.id(), files))
                        .orElse(List.of());
        return new Issue(
                summary.id(),
                summary.repository(),
                summary.title(),
                summary.state(),
                summary.size(),
                summary.blockedBy(),
                summary.link(),
                Markdown.acceptance(markdown),
                agents);
    }

    private static List<SubAgent> agents(
            String markdown, String issuePath, String id, SafeFiles files) {
        final String section = Markdown.section(markdown, "Sub-agents");
        final List<SubAgent> agents = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        boolean header = false;
        for (final String line : section.lines().toList()) {
            if (!line.startsWith("|")) continue;
            final List<String> cells = Markdown.cells(line);
            if (cells.equals(List.of("Name", "Kind", "Agent", "Model", "Brief"))) {
                header = true;
                continue;
            }
            if (cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) continue;
            if (!header || cells.size() != 5)
                throw Refusals.failed(
                        "Expected Sub-agents table Name, Kind, Agent, Model, Brief for "
                                + id
                                + "; got malformed table");
            final String name = Settings.id(cells.getFirst());
            if (!names.add(name))
                throw Refusals.failed(
                        "Expected unique sub-agent names for " + id + "; got " + name + " twice");
            final List<Markdown.Link> links = Markdown.links(cells.get(4));
            if (links.size() != 1)
                throw Refusals.failed(
                        "Expected one listed brief link for "
                                + id
                                + " "
                                + name
                                + "; got no unique link");
            final String target = links.getFirst().target();
            if (!target.equals(id + ".sub/" + name + ".md"))
                throw Refusals.failed(
                        "Expected listed brief " + id + ".sub/" + name + ".md; got " + target);
            final Path parent = Path.of(issuePath).getParent();
            final String briefPath =
                    (parent == null ? Path.of(target) : parent.resolve(target)).toString();
            final SubAgent.Kind kind =
                    switch (cells.get(1).toLowerCase(Locale.ROOT)) {
                        case "builder" -> SubAgent.Kind.BUILDER;
                        case "tester" -> SubAgent.Kind.TESTER;
                        case "reader" -> SubAgent.Kind.READER;
                        default -> SubAgent.Kind.OTHER;
                    };
            agents.add(
                    new SubAgent(
                            name,
                            kind,
                            Markdown.plain(cells.get(2)),
                            Markdown.plain(cells.get(3)),
                            files.read(briefPath)));
        }
        if (!section.isBlank() && !header)
            throw Refusals.failed("Expected Sub-agents table for " + id + "; got no header");
        return List.copyOf(agents);
    }
}
