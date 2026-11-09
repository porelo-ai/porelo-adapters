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
    private static final Pattern ANCHOR = Pattern.compile("<a id=\"([^\"]*)\"></a>");

    private IssueIndex() {}

    record Entry(IssueSummary summary, Optional<String> path) {}

    static Map<String, Entry> read(
            SafeFiles files, String repository, Settings settings, Workflow workflow) {
        final List<String> paths = files.markdown("issues");
        final Map<String, Entry> entries = new LinkedHashMap<>();
        readIndex(
                files,
                repository,
                settings,
                workflow,
                "issues/README.md",
                paths,
                entries,
                new HashSet<>());
        return java.util.Collections.unmodifiableMap(entries);
    }

    private static void readIndex(
            SafeFiles files,
            String repository,
            Settings settings,
            Workflow workflow,
            String index,
            List<String> paths,
            Map<String, Entry> entries,
            Set<String> visited) {
        if (!visited.add(index) || visited.size() > 1000 || Path.of(index).getNameCount() > 12)
            throw Refusals.failed(
                    "Expected unique bounded issue indexes; got repeated or nested " + index);
        final String text = files.read(index);
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
            final Optional<String> path =
                    entry.path().map(target -> folder(index).resolve(target).toString());
            if (path.isPresent()) {
                final Path linked = SafeFiles.relative(path.orElseThrow());
                if (!filename.matcher(linked.getFileName().toString()).matches())
                    throw Refusals.failed(
                            "Expected issue filename matching " + row.id() + "; got " + linked);
                files.read(linked.toString());
            } else if (!candidates.isEmpty()) {
                throw Refusals.failed(
                        "Expected a linked issue file for " + row.id() + "; got an unlinked file");
            }
            final String title =
                    path.flatMap(p -> Markdown.title(files.read(p), row.id())).orElse(row.title());
            StoryFile.checkState(workflow, row.id(), row.state(), false);
            final String linkPath = path.orElse(index + "#" + row.id().toLowerCase(Locale.ROOT));
            final IssueSummary summary =
                    new IssueSummary(
                            row.id(),
                            repository,
                            title,
                            row.state(),
                            row.size(),
                            row.blockedBy(),
                            settings.link(repository, linkPath));
            if (entries.keySet().stream().anyMatch(id -> id.equalsIgnoreCase(row.id())))
                throw Refusals.failed(
                        "Expected unique issue " + row.id() + "; got duplicate index rows");
            entries.put(row.id(), new Entry(summary, path));
        }
        for (final Markdown.Link link : Markdown.links(text)) {
            final String target = link.target().split("#", 2)[0];
            if (target.contains(":")
                    || !(target.equals("README.md") || target.endsWith("/README.md"))) continue;
            final Path nested = SafeFiles.relative(folder(index).resolve(target).toString());
            if (nested.toString().equals(index) && link.target().contains("#")) continue;
            if (!nested.startsWith("issues"))
                throw Refusals.failed("Expected contained issue index; got " + target);
            readIndex(
                    files,
                    repository,
                    settings,
                    workflow,
                    nested.toString(),
                    paths,
                    entries,
                    visited);
        }
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
            if (!line.startsWith("|")) {
                header = List.of();
                continue;
            }
            final List<String> cells = Markdown.cells(line);
            if (cells.getFirst().equals("#")
                    && (cells.contains("Title") || cells.contains("What it covers"))) {
                header = cells;
                continue;
            }
            if (cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) continue;
            final var anchor = ANCHOR.matcher(cells.getFirst());
            if (!anchor.find()) {
                if (header.isEmpty()) continue;
                throw Refusals.failed(
                        "Expected an issue anchor on every data row; got a missing anchor");
            }
            if (state.isBlank() || header.isEmpty() || header.size() != cells.size())
                throw Refusals.failed(
                        "Expected an issue row under a state and matching table header; got an"
                                + " ungrouped or malformed row");
            Settings.id(anchor.group(1));
            final List<Markdown.Link> ownLinks = Markdown.links(cells.getFirst());
            final String id =
                    Settings.id(
                            ownLinks.isEmpty()
                                    ? cells.getFirst()
                                            .substring(anchor.end())
                                            .strip()
                                            .split("\\s+", 2)[0]
                                    : ownLinks.getFirst().text());
            if (!id.equalsIgnoreCase(anchor.group(1)) || ownLinks.size() > 1)
                throw Refusals.failed(
                        "Expected index link matching anchor " + id + "; got " + cells.getFirst());
            final String size = header.contains("Size") ? cells.get(header.indexOf("Size")) : "";
            final List<String> blocked =
                    header.contains("Blocked by")
                            ? blockers(cells.get(header.indexOf("Blocked by")))
                            : List.of();
            final int titleColumn =
                    header.contains("Title")
                            ? header.indexOf("Title")
                            : header.indexOf("What it covers");
            if (titleColumn < 0)
                throw Refusals.failed(
                        "Expected issue table Title or What it covers column; got neither");
            final String title = Markdown.plain(cells.get(titleColumn));
            if (!ownLinks.isEmpty()
                    && (ownLinks.getFirst().target().contains("#")
                            || !ownLinks.getFirst().target().endsWith(".md")))
                throw Refusals.failed(
                        "Expected a direct issue file link for " + id + "; got another target");
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

    private static Path folder(String index) {
        final Path parent = Path.of(index).getParent();
        if (parent == null)
            throw Refusals.failed("Expected an issue index inside a directory; got " + index);
        return parent;
    }

    private static List<String> blockers(String cell) {
        if (cell.isBlank() || cell.equals("-")) return List.of();
        final String residue = Markdown.withoutLinks(cell);
        if (!residue.matches("[,\\s]*") || Markdown.links(cell).isEmpty())
            throw Refusals.failed("Expected linked blocking issue IDs; got unlinked text");
        return Markdown.links(cell).stream().map(link -> Settings.id(link.text())).toList();
    }

    static Issue full(Entry entry, SafeFiles files) {
        final IssueSummary summary = entry.summary();
        final String markdown = entry.path().map(files::read).orElse("");
        final String acceptance = Markdown.acceptance(markdown);
        if (entry.path().isPresent() && acceptance.isBlank())
            throw Refusals.failed(
                    "Expected acceptance text for "
                            + summary.id()
                            + "; got no What must be true paragraph");
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
                acceptance,
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
            for (int column = 1; column <= 3; column++) {
                final String value = Markdown.plain(cells.get(column));
                if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
                    throw Refusals.failed(
                            "Expected nonblank kind, agent and model for "
                                    + id
                                    + " "
                                    + name
                                    + "; got a blank or control character");
            }
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
