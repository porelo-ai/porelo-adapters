package org.fuin.porelo.planning.storiesgit;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.fuin.porelo.planning.api.Issue;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.contract.Board;
import org.fuin.porelo.planning.contract.GitHttpFixture;
import org.fuin.porelo.wire.Json;

final class StoriesGitFixture extends GitHttpFixture {
    StoriesGitFixture(Path root) throws Exception {
        super(root);
    }

    @Override
    protected Map<String, Object> settings(Map<String, URI> urls) {
        final String planning = urls.get("planning.git").toString();
        return Map.of(
                "repository",
                planning,
                "storiesDirectory",
                "stories",
                "issueRepositoryPattern",
                planning.replace("planning.git", "{repository}.git"),
                "linkPattern",
                "https://example.org/{repository}/{path}",
                "credentialKind",
                "token");
    }

    @Override
    protected Map<String, Map<String, String>> files(Board board) {
        return encode(board);
    }

    static Map<String, Map<String, String>> encode(Board board) {
        final Map<String, Map<String, String>> repositories = new LinkedHashMap<>();
        final Map<String, String> planning = new LinkedHashMap<>();
        planning.put("stories/workflow.yml", workflow(board));
        final StringBuilder index = new StringBuilder("# Stories\n\n");
        for (final Story story : board.stories()) {
            final String path = story.id() + "-Story.md";
            index.append("## ")
                    .append(story.state().isEmpty() ? "Queued" : story.state())
                    .append(
                            "\n\n"
                                    + "| Story | Priority | Issues | What a user gains |\n"
                                    + "|---|---|---|---|\n"
                                    + "| <a id=\"")
                    .append(story.id().toLowerCase(Locale.ROOT))
                    .append("\"></a>[")
                    .append(story.id())
                    .append("](")
                    .append(path)
                    .append(") ")
                    .append(story.title())
                    .append(" | ")
                    .append(story.priority() == null ? "-" : cell(story.priority()))
                    .append(" | - | ")
                    .append(cell(story.description()))
                    .append(" |\n\n");
            planning.put(
                    "stories/" + path,
                    story(story, board.issues().getOrDefault(story.id(), List.of())));
        }
        planning.put("stories/README.md", index.toString());
        repositories.put("planning.git", planning);
        for (final String key : List.of("one", "two")) {
            final Map<String, String> files = new LinkedHashMap<>();
            final StringBuilder issues = new StringBuilder("# Issues\n\n");
            final List<Issue> entries =
                    board.issues().values().stream()
                            .flatMap(List::stream)
                            .filter(issue -> issue.repository().equals(key))
                            .toList();
            for (final var state : board.workflow().states()) {
                final List<Issue> group =
                        entries.stream()
                                .filter(issue -> issue.state().equals(state.name()))
                                .toList();
                if (group.isEmpty()) continue;
                issues.append("## ")
                        .append(state.name())
                        .append(
                                "\n\n"
                                        + "| # | Size | Blocked by | What it covers |\n"
                                        + "|---|---|---|---|\n");
                for (final Issue issue : group) {
                    issues.append("| <a id=\"")
                            .append(issue.id().toLowerCase(Locale.ROOT))
                            .append("\"></a>[")
                            .append(issue.id())
                            .append("](")
                            .append(issue.id())
                            .append("-Issue.md) | ")
                            .append(issue.size())
                            .append(" | ");
                    if (issue.blockedBy().isEmpty()) issues.append('-');
                    else
                        issues.append(
                                String.join(
                                        ", ",
                                        issue.blockedBy().stream()
                                                .map(
                                                        id ->
                                                                "["
                                                                        + id
                                                                        + "](README.md#"
                                                                        + id.toLowerCase(
                                                                                Locale.ROOT)
                                                                        + ")")
                                                .toList()));
                    issues.append(" | ").append(cell(issue.title())).append(" |\n");
                }
                issues.append('\n');
            }
            files.put("issues/README.md", issues.toString());
            for (final Issue issue : entries) {
                files.put("issues/" + issue.id() + "-Issue.md", issue(issue));
                for (final var agent : issue.subAgents())
                    files.put(
                            "issues/" + issue.id() + ".sub/" + agent.name() + ".md", agent.brief());
            }
            repositories.put(key + ".git", files);
        }
        return repositories;
    }

    @Override
    protected Map.Entry<String, String> issueLocation() {
        return Map.entry("one.git", "issues/I1-Issue.md");
    }

    @Override
    protected String briefReference(String path) {
        return Path.of(path).getParent().getFileName() + "/" + Path.of(path).getFileName();
    }

    private static String workflow(Board board) {
        final StringBuilder yaml = new StringBuilder("states:\n");
        for (final var state : board.workflow().states())
            yaml.append("  - name: ")
                    .append(Json.write(state.name()))
                    .append("\n    closed: ")
                    .append(state.kind() == org.fuin.porelo.planning.api.PlanState.Kind.CLOSED)
                    .append("\n    measured: false\n");
        yaml.append("phases:\n");
        for (final var phase : board.workflow().phases())
            yaml.append("  - name: ")
                    .append(Json.write(phase.name()))
                    .append("\n    states: ")
                    .append(Json.write(phase.states()))
                    .append('\n');
        return yaml.toString();
    }

    private static String story(Story story, List<Issue> issues) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", story.id());
        data.put("title", story.title());
        data.put("state", story.state());
        data.put("gain", story.description());
        data.put("test", "");
        data.put("priority", story.priority());
        data.put(
                "issues",
                issues.stream().map(issue -> issue.repository() + "#" + issue.id()).toList());
        data.put("tested", null);
        return "# "
                + story.id()
                + " — "
                + story.title()
                + "\n\n**Priority:** "
                + (story.priority() == null ? "-" : story.priority())
                + " · **State:** "
                + story.state()
                + "\n\n**What a user gains.** "
                + story.description()
                + "\n\n**The test.**\n\n## Data\n\n```json\n"
                + Json.write(data)
                + "\n```\n";
    }

    private static String issue(Issue issue) {
        final StringBuilder text =
                new StringBuilder(
                        "# "
                                + issue.id()
                                + " — "
                                + issue.title()
                                + "\n\n**Status:** "
                                + issue.state()
                                + ".\n**Size:** "
                                + issue.size()
                                + "\n\n**What must be true.** "
                                + issue.whatMustBeTrue()
                                + "\n");
        if (!issue.subAgents().isEmpty()) {
            text.append(
                    "## Sub-agents\n\n"
                            + "| Name | Kind | Agent | Model | Brief |\n"
                            + "|---|---|---|---|---|\n");
            for (final var agent : issue.subAgents())
                text.append("| ")
                        .append(agent.name())
                        .append(" | ")
                        .append(agent.kind().name().toLowerCase(Locale.ROOT))
                        .append(" | ")
                        .append(cell(agent.agent()))
                        .append(" | ")
                        .append(cell(agent.model()))
                        .append(" | [Brief](")
                        .append(issue.id())
                        .append(".sub/")
                        .append(agent.name())
                        .append(".md) |\n");
        }
        return text.toString();
    }

    private static String cell(String text) {
        return text.strip().replace("|", "\\|").replace("\n", " ");
    }
}
