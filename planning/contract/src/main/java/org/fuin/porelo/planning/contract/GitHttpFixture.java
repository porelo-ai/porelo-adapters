package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Real smart-HTTP store controls for Git adapters. A subclass maps the shared board to its files;
 * override settings only when the adapter uses different settings keys. Not thread safe: controls
 * are used by the test thread while the HTTP server independently serves requests.
 */
public abstract class GitHttpFixture implements Fixture {
    private final Path root;
    private final String token = UUID.randomUUID().toString();
    private final GitRepositories repositories;
    private final GitHttpServer server;
    private @Nullable GitHttpServer redirected;
    private Board board = Board.standard();
    private Map<String, Map<String, String>> files = Map.of();
    private boolean seeded;

    /** Creates owned repositories and an authenticated loopback server beneath an existing root. */
    protected GitHttpFixture(Path root) throws Exception {
        this.root = root;
        repositories = new GitRepositories(Files.createDirectory(root.resolve("remotes")));
        try {
            server = new GitHttpServer(root.resolve("remotes"));
            server.accept(token);
        } catch (Exception | Error ex) {
            repositories.close();
            throw ex;
        }
    }

    /**
     * Maps the board to planning.git, one.git and two.git, each with relative filenames and exact
     * UTF-8 text. I1's state changes exactly one issue file, and its listed briefs live under .sub.
     */
    protected abstract Map<String, Map<String, String>> files(Board board);

    /**
     * Maps clone URLs to settings. The default uses repository for the planning repository,
     * repositories for issue repository keys, and credentialKind=token.
     */
    protected Map<String, Object> settings(Map<String, URI> urls) {
        final Map<String, String> issueRepositories = new LinkedHashMap<>();
        urls.forEach(
                (name, uri) -> {
                    if (!name.equals("planning.git"))
                        issueRepositories.put(name.substring(0, name.length() - 4), uri.toString());
                });
        if (!urls.containsKey("planning.git"))
            throw new IllegalArgumentException(
                    "Expected planning.git for default settings; got " + urls.keySet());
        return Map.of(
                "repository",
                urls.get("planning.git").toString(),
                "repositories",
                issueRepositories,
                "credentialKind",
                "token");
    }

    /** Seeds only the supplied board in independently observable bare repositories. */
    @Override
    public final void seed(Board board, Path directory) throws Exception {
        this.board = board;
        files = files(board);
        if (!files.keySet().equals(Set.of("planning.git", "one.git", "two.git")))
            throw new IllegalArgumentException(
                    "Expected planning.git, one.git and two.git; got " + files.keySet());
        for (final var repository : files.entrySet())
            repositories.create(repository.getKey(), repository.getValue());
        seeded = true;
    }

    /** Returns credential-free URLs for the current fixture's remotes. */
    @Override
    public final Map<String, Object> settings() {
        requireSeed();
        final Map<String, URI> urls = new LinkedHashMap<>();
        files.keySet().forEach(name -> urls.put(name, server.uri(name)));
        return settings(Map.copyOf(urls));
    }

    /** Returns the in-memory accepted token. */
    @Override
    public final String token() {
        return token;
    }

    /** Returns controls provided by the HTTP backend and the file mapping. */
    @Override
    public Set<Ability> abilities() {
        return Set.of(
                Ability.gitStore,
                Ability.changeBehindTheBack,
                Ability.refuseCredential,
                Ability.refusePush,
                Ability.stall,
                Ability.redirect,
                Ability.credentialKind,
                Ability.issueSymlink,
                Ability.missingBrief,
                Ability.symlinkBrief,
                Ability.outsideBrief,
                Ability.unlistedBrief);
    }

    /** Returns every authoritative remote head, including the planning repository. */
    @Override
    public final Map<String, String> snapshot() throws Exception {
        requireSeed();
        final Map<String, String> result = new LinkedHashMap<>();
        for (final String name : files.keySet()) result.put(name, repositories.head(name));
        return Map.copyOf(result);
    }

    /** Commits a state change independently of the adapter's clones. */
    @Override
    public final void changeIssue(String issue, String state) throws Exception {
        replace(board.withIssueState(issue, state));
    }

    /** Commits a stored story state change independently of the adapter's clones. */
    @Override
    public final void changeStory(String story, String state) throws Exception {
        replace(board.withStoryState(story, state));
    }

    private void replace(Board changed) throws Exception {
        final Map<String, Map<String, String>> replacements = files(changed);
        for (final var entry : replacements.entrySet())
            if (!entry.getValue().equals(files.get(entry.getKey())))
                repositories.commit(entry.getKey(), entry.getValue());
        files = replacements;
        board = changed;
    }

    /** Selects a credential kind using the default settings convention. */
    @Override
    public Map<String, Object> settingsForCredentialKind(String kind) {
        final Map<String, Object> result = new LinkedHashMap<>(settings());
        result.put("credentialKind", kind);
        return Map.copyOf(result);
    }

    /** Returns all HTTP arrivals, including refused and unauthenticated requests. */
    @Override
    public final long accesses() {
        return server.requests().size();
    }

    /** Refuses only publication to the named issue repository. */
    @Override
    public final void refusePush(String repository) {
        server.refusePushes(repository + ".git");
    }

    /** Refuses whichever repository the adapter pushes second, allowing rollback of the first. */
    @Override
    public final void refuseSecondPush() {
        server.refuseSecondPush();
    }

    /** Requires both repositories to have reached the real receive-pack failure path. */
    @Override
    public final void verifySecondPushRefused() {
        final List<String> firstTwo =
                server.requests().stream()
                        .filter(
                                r ->
                                        r.accepted()
                                                && r.method().equals("POST")
                                                && r.path().endsWith("/git-receive-pack"))
                        .limit(2)
                        .map(GitHttpServer.Request::repository)
                        .toList();
        assertThat(firstTwo)
                .as("both repositories must reach the second-push refusal, in either order")
                .containsExactlyInAnyOrder("one.git", "two.git");
    }

    /** Holds all repositories so neither refresh nor a pre-write fetch can complete normally. */
    @Override
    public final void stall() {
        files.keySet().forEach(server::stall);
    }

    /** Redirects every store request to a recorder on the other loopback host. */
    @Override
    public final void redirect() throws Exception {
        redirected = GitHttpServer.redirectTarget(Files.createDirectory(root.resolve("redirect")));
        for (final String repository : files.keySet()) server.redirect(repository, redirected);
    }

    /** Requires actual redirected traffic and refuses any forwarded Authorization header. */
    @Override
    public final void verifyRedirect() throws Exception {
        final GitHttpServer target =
                java.util.Objects.requireNonNull(
                        redirected, "Expected an enabled redirect recorder");
        assertThat(target.requests())
                .as("redirect target must actually receive a request")
                .isNotEmpty();
        assertThat(target.requests())
                .as("Authorization must never follow a cross-host redirect")
                .noneMatch(GitHttpServer.Request::authorizationPresent);
        target.verify();
    }

    /**
     * Introduces a path attack using the mapping's changed issue file and exact brief text. Layouts
     * with explicit brief paths can override briefReference to encode that reference.
     */
    @Override
    public final String damage(Damage damage, Path outside) throws Exception {
        final Location issue = issueFile();
        final Location brief = briefFile();
        switch (damage) {
            case issueSymlink -> {
                Files.writeString(outside, text(issue));
                repositories.damage(
                        issue.repository(), Map.of(), Map.of(issue.path(), outside), Set.of());
            }
            case missingBrief ->
                    repositories.damage(
                            brief.repository(), Map.of(), Map.of(), Set.of(brief.path()));
            case symlinkBrief -> {
                Files.writeString(outside, text(brief));
                repositories.damage(
                        brief.repository(), Map.of(), Map.of(brief.path(), outside), Set.of());
            }
            case unlistedBrief -> {
                final String unlisted = briefDirectory(brief).resolve("unlisted.md").toString();
                repositories.commit(
                        brief.repository(), Map.of(unlisted, "Unlisted private brief.\n"));
                return unlisted;
            }
            case outsideBrief -> {
                final String oldReference = briefReference(brief.path());
                final String replacement = "../outside.md";
                final String original = text(issue);
                if (!original.contains(oldReference))
                    throw new IllegalArgumentException(
                            "Expected issue metadata containing brief reference "
                                    + oldReference
                                    + "; got no reference");
                final Path parent = briefDirectory(brief).getParent();
                final String outsideBrief =
                        parent == null ? "outside.md" : parent.resolve("outside.md").toString();
                if (!issue.repository().equals(brief.repository()))
                    throw new IllegalArgumentException(
                            "Expected issue and its brief in the same repository");
                repositories.commit(
                        issue.repository(),
                        Map.of(
                                issue.path(),
                                original.replace(oldReference, replacement),
                                outsideBrief,
                                text(brief)));
                return replacement;
            }
        }
        return damage == Damage.issueSymlink ? issue.path() : brief.path();
    }

    /**
     * Returns the literal listed brief reference used in issue metadata; defaults to the filename.
     */
    protected String briefReference(String path) {
        return Path.of(path).getFileName().toString();
    }

    private record Location(String repository, String path) {}

    private void requireSeed() {
        if (!seeded)
            throw new IllegalStateException("Expected a seeded Git fixture; got no seeded board");
    }

    private String text(Location location) {
        requireSeed();
        final Map<String, String> repository = files.get(location.repository());
        if (repository == null || !repository.containsKey(location.path()))
            throw new IllegalArgumentException("Expected mapped fixture file; got " + location);
        return java.util.Objects.requireNonNull(repository.get(location.path()));
    }

    private static Path briefDirectory(Location brief) {
        return java.util.Objects.requireNonNull(
                Path.of(brief.path()).getParent(), "Expected a .sub directory for listed brief");
    }

    private Location issueFile() {
        requireSeed();
        final var changed = files(board.withIssueState("I1", "Closed"));
        final List<Location> matches = new ArrayList<>();
        for (final var repository : files.entrySet()) {
            final var replacements =
                    java.util.Objects.requireNonNull(
                            changed.get(repository.getKey()),
                            "Expected a stable repository mapping");
            for (final var file : repository.getValue().entrySet())
                if (!file.getValue().equals(replacements.get(file.getKey())))
                    matches.add(new Location(repository.getKey(), file.getKey()));
        }
        if (matches.size() != 1)
            throw new IllegalArgumentException("Expected one changed I1 file; got " + matches);
        return matches.getFirst();
    }

    private Location briefFile() {
        final String text = board.issue("I1").subAgents().getFirst().brief();
        final List<Location> matches =
                files.entrySet().stream()
                        .flatMap(
                                repository ->
                                        repository.getValue().entrySet().stream()
                                                .filter(
                                                        file ->
                                                                file.getValue().equals(text)
                                                                        && file.getKey()
                                                                                .contains(".sub/"))
                                                .map(
                                                        file ->
                                                                new Location(
                                                                        repository.getKey(),
                                                                        file.getKey())))
                        .toList();
        if (matches.size() != 1)
            throw new IllegalArgumentException(
                    "Expected one listed .sub brief with complete text; got " + matches);
        return matches.getFirst();
    }

    /** Reports unexpected CGI/backend failures. */
    @Override
    public final void verify() throws Exception {
        server.verify();
        if (redirected != null) redirected.verify();
    }

    /** Stops only the fixture's servers and Git processes. */
    @Override
    public final void close() throws IOException {
        try {
            server.close();
        } finally {
            try {
                if (redirected != null) redirected.close();
            } finally {
                repositories.close();
            }
        }
    }
}
