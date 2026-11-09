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
    private static final List<String> TITLES =
            List.of("title", "what it covers", "what was built", "what it covered");
    private static final Set<String> NONE = Set.of("", "-", "\u2014");
    private static final Pattern UNLINKED_ID = Pattern.compile("\\b[A-Za-z]{1,4}[0-9]+\\b");

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
        // A subgroup may link its siblings; an index reached again has been read already.
        if (!visited.add(index)) return;
        if (visited.size() > 1000 || Path.of(index).getNameCount() > 12)
            throw Refusals.failed(
                    "Expected bounded issue indexes; got too many or nested " + index);
        final String text = files.read(index);
        for (final Entry entry : parse(text, index)) {
            final IssueSummary row = entry.summary();
            final Pattern filename = Pattern.compile(Pattern.quote(row.id()) + "(?:-.*)?\\.md");
            final List<String> candidates =
                    paths.stream()
                            .filter(
                                    path ->
                                            filename.matcher(Path.of(path).getFileName().toString())
                                                    .matches())
                            .toList();
            final Optional<String> path = entry.path().map(target -> within(index, target));
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
            // A README elsewhere, such as the stories', is a cross-reference and is never read.
            final String resolved = within(index, target);
            if (!Path.of(resolved).startsWith("issues")) continue;
            final Path nested = SafeFiles.relative(resolved);
            if (nested.toString().equals(index) && link.target().contains("#")) continue;
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
        return parse(text, "issues/README.md");
    }

    static List<Entry> parse(String text, String index) {
        final List<Entry> rows = new ArrayList<>();
        String state = "";
        List<String> header = List.of();
        for (final String line : text.split("\\R")) {
            // One repository heads its state groups one level deeper.
            if (line.startsWith("## ") || line.startsWith("### ")) {
                state = line.substring(line.indexOf(' ') + 1).strip();
                header = List.of();
                continue;
            }
            if (!line.startsWith("|")) {
                header = List.of();
                continue;
            }
            final List<String> cells = Markdown.cells(line);
            final var anchor = ANCHOR.matcher(cells.getFirst());
            if (!anchor.find()) {
                if (header.isEmpty()) header = issueHeader(cells);
                else if (!cells.stream().allMatch(cell -> cell.matches(":?-+:?")))
                    throw Refusals.failed(
                            "Expected an issue anchor on every row of " + index + "; got " + line);
                continue;
            }
            final String id = Settings.id(anchor.group(1));
            if (state.isBlank() || header.isEmpty())
                throw Refusals.failed(
                        "Expected issue "
                                + id
                                + " of "
                                + index
                                + " in a table headed # and a title under a state; got another"
                                + " table");
            rows.add(row(cells, header, state, index, anchor));
        }
        return List.copyOf(rows);
    }

    private static List<String> issueHeader(List<String> cells) {
        final List<String> header =
                cells.stream().map(cell -> cell.toLowerCase(Locale.ROOT)).toList();
        return header.getFirst().equals("#") && TITLES.stream().anyMatch(header::contains)
                ? header
                : List.of();
    }

    // GitHub drops a row's cells beyond its header and leaves missing ones empty; so does the
    // board.
    private static String cell(List<String> cells, List<String> header, String column) {
        final int at = header.indexOf(column);
        return at >= 0 && at < cells.size() ? cells.get(at) : "";
    }

    private static Entry row(
            List<String> cells,
            List<String> header,
            String state,
            String index,
            java.util.regex.Matcher anchor) {
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
                    "Expected the link of "
                            + id
                            + " in "
                            + index
                            + " to match its anchor; got "
                            + cells.getFirst());
        final Optional<String> path =
                ownLinks.isEmpty() ? Optional.empty() : file(ownLinks.getFirst(), id, index);
        path.ifPresent(SafeFiles::relative);
        final String size = cell(cells, header, "size");
        final String title =
                Markdown.plain(
                        TITLES.stream()
                                .filter(header::contains)
                                .findFirst()
                                .map(column -> cell(cells, header, column))
                                .orElse(""));
        return new Entry(
                new IssueSummary(
                        id,
                        "",
                        title,
                        state,
                        NONE.contains(size) ? "" : size,
                        blockers(cell(cells, header, "blocked by")),
                        ""),
                path);
    }

    // A finished or rejected row links its own anchor once its file is deleted.
    private static Optional<String> file(Markdown.Link link, String id, String index) {
        final String target = link.target();
        final int hash = target.indexOf('#');
        if (hash < 0 && target.endsWith(".md")) return Optional.of(target);
        final String page = hash < 0 ? target : target.substring(0, hash);
        final String fragment = hash < 0 ? "" : target.substring(hash + 1);
        final Path name = Path.of(index).getFileName();
        if ((page.isEmpty() || (name != null && page.equals(name.toString())))
                && fragment.equalsIgnoreCase(id)) return Optional.empty();
        throw Refusals.failed(
                "Expected issue "
                        + id
                        + " in "
                        + index
                        + " to link its file or its own row; got "
                        + target);
    }

    // Lexical: SafeFiles then reads the result without following a link on the way.
    private static String within(String index, String target) {
        return folder(index).resolve(target).normalize().toString();
    }

    private static Path folder(String index) {
        final Path parent = Path.of(index).getParent();
        if (parent == null)
            throw Refusals.failed("Expected an issue index inside a directory; got " + index);
        return parent;
    }

    // Free text names a wait outside the planning, such as an account; an unlinked ID would vanish.
    private static List<String> blockers(String cell) {
        if (NONE.contains(cell)) return List.of();
        if (UNLINKED_ID.matcher(Markdown.withoutLinks(cell)).find())
            throw Refusals.failed("Expected linked blocking issue IDs; got unlinked " + cell);
        return Markdown.links(cell).stream()
                .map(Markdown.Link::text)
                .filter(text -> text.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
                .toList();
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
