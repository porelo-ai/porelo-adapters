package org.fuin.porelo.planning.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.planning.api.Issue;

final class FileFixture implements Fixture {
    private final String token = UUID.randomUUID().toString();
    private Path directory;

    /** {@inheritDoc} */
    @Override
    public void seed(Board board, Path directory) throws Exception {
        this.directory = directory;
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("workflow", board.workflow().asMap());
        document.put("stories", board.stories().stream().map(s -> s.asMap()).toList());
        final Map<String, Object> issues = new LinkedHashMap<>();
        for (final var story : board.issues().entrySet()) {
            final List<Map<String, Object>> entries = new ArrayList<>();
            for (final Issue issue : story.getValue()) {
                final Map<String, Object> entry = new LinkedHashMap<>(issue.asMap());
                final List<Map<String, Object>> agents = new ArrayList<>();
                for (final var agent : issue.subAgents()) {
                    final Path brief =
                            directory.resolve(issue.id() + ".sub/" + agent.name() + ".md");
                    Files.createDirectories(brief.getParent());
                    Files.writeString(brief, agent.brief());
                    final Map<String, Object> metadata = new LinkedHashMap<>(agent.asMap());
                    metadata.put("brief", directory.relativize(brief).toString());
                    agents.add(metadata);
                }
                entry.put("subAgents", agents);
                entries.add(entry);
            }
            issues.put(story.getKey(), entries);
        }
        document.put("issues", issues);
        FileBoard.write(directory, document);
        Files.writeString(directory.resolve("accesses"), "0");
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> settings() {
        return Map.of("credentialKind", "token");
    }

    /** {@inheritDoc} */
    @Override
    public String token() {
        return token;
    }

    /** {@inheritDoc} */
    @Override
    public Set<Ability> abilities() {
        return Set.of(
                Ability.changeBehindTheBack,
                Ability.refuseCredential,
                Ability.refusePush,
                Ability.credentialKind,
                Ability.missingBrief,
                Ability.symlinkBrief,
                Ability.outsideBrief,
                Ability.unlistedBrief);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, String> snapshot() throws Exception {
        return Map.of("file", Files.readString(directory.resolve("board.json")));
    }

    /** {@inheritDoc} */
    @Override
    public void changeIssue(String issue, String state) throws Exception {
        final Map<String, Object> document = FileBoard.read(directory);
        FileBoard.issues(document).stream()
                .filter(i -> i.get("id").equals(issue))
                .findFirst()
                .orElseThrow()
                .put("state", state);
        FileBoard.write(directory, document);
    }

    /** {@inheritDoc} */
    @Override
    public void changeStory(String story, String state) throws Exception {
        final Map<String, Object> document = FileBoard.read(directory);
        FileBoard.stories(document).stream()
                .filter(s -> s.get("id").equals(story))
                .findFirst()
                .orElseThrow()
                .put("state", state);
        FileBoard.write(directory, document);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> settingsForCredentialKind(String kind) {
        return Map.of("credentialKind", kind);
    }

    /** {@inheritDoc} */
    @Override
    public long accesses() throws Exception {
        return Long.parseLong(Files.readString(directory.resolve("accesses")));
    }

    /** {@inheritDoc} */
    @Override
    public void refusePush(String repository) throws Exception {
        Files.writeString(directory.resolve("refuse-push"), repository);
    }

    /** {@inheritDoc} */
    @Override
    public String damage(Damage damage, Path outside) throws Exception {
        final String listed = "I1.sub/builder.md";
        switch (damage) {
            case missingBrief -> Files.delete(directory.resolve(listed));
            case symlinkBrief -> {
                Files.writeString(outside, Files.readString(directory.resolve(listed)));
                Files.delete(directory.resolve(listed));
                Files.createSymbolicLink(directory.resolve(listed), outside);
            }
            case outsideBrief -> {
                Files.writeString(
                        directory.getParent().resolve("outside.md"),
                        Files.readString(directory.resolve(listed)));
                final var document = FileBoard.read(directory);
                final var issue =
                        FileBoard.issues(document).stream()
                                .filter(i -> i.get("id").equals("I1"))
                                .findFirst()
                                .orElseThrow();
                FileBoard.agents(issue).getFirst().put("brief", "../outside.md");
                FileBoard.write(directory, document);
                return "../outside.md";
            }
            case unlistedBrief -> {
                Files.writeString(
                        directory.resolve("I1.sub/unlisted.md"), "Unlisted private brief.\n");
                return "I1.sub/unlisted.md";
            }
            case issueSymlink ->
                    throw new UnsupportedOperationException("Missing fixture ability issueSymlink");
        }
        return listed;
    }
}
