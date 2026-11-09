package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Real local bare remotes for the partial-publication proof without the watch capability. */
final class RefusingGitFixture extends FileFixture {
    private final Path root;
    private final GitRepositories repositories;

    RefusingGitFixture(Path root) throws IOException {
        this.root = root;
        repositories = new GitRepositories(root.resolve("remotes"));
    }

    @Override
    public void seed(Board board, Path directory) throws Exception {
        super.seed(board, directory);
        repositories.create("planning.git", Map.of("planning.md", "Planning\n"));
        repositories.create("one.git", Map.of("I1.md", "Queued\n"));
        repositories.create("two.git", Map.of("I2.md", "Queued\n"));
    }

    @Override
    public Map<String, Object> settings() {
        final Map<String, Object> settings = new LinkedHashMap<>(super.settings());
        settings.put("publicationRemotes", root.resolve("remotes").toString());
        return Map.copyOf(settings);
    }

    @Override
    public Set<Ability> abilities() {
        final Set<Ability> abilities = new HashSet<>(super.abilities());
        abilities.add(Ability.gitStore);
        return Set.copyOf(abilities);
    }

    @Override
    public Map<String, String> snapshot() throws Exception {
        return Map.of(
                "planning",
                repositories.head("planning.git"),
                "one",
                repositories.head("one.git"),
                "two",
                repositories.head("two.git"));
    }

    @Override
    public void refuseSecondPush() throws Exception {
        for (final String repository : java.util.List.of("one.git", "two.git")) {
            final Path hooks =
                    Files.createDirectories(
                            root.resolve("remotes").resolve(repository).resolve("hooks"));
            final String first = quote(root.resolve("first-publication").toString());
            final String second = quote(root.resolve("second-publication").toString());
            Files.writeString(
                    hooks.resolve("pre-receive"),
                    "#!/bin/sh\nif [ ! -e "
                            + first
                            + " ]; then printf '%s' "
                            + quote(repository)
                            + " > "
                            + first
                            + "; exit 0; fi\nif [ \"$(cat "
                            + first
                            + ")\" = "
                            + quote(repository)
                            + " ]; then exit 0; fi\ntouch "
                            + second
                            + "\nexit 1\n");
            Files.setPosixFilePermissions(
                    hooks.resolve("pre-receive"), PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Override
    public void verifySecondPushRefused() {
        assertThat(root.resolve("first-publication"))
                .as("the first real push reached its receive hook")
                .exists();
        assertThat(root.resolve("second-publication"))
                .as("the second real push reached its refusing receive hook")
                .exists();
    }

    @Override
    public void close() throws IOException {
        repositories.close();
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }
}
