package org.fuin.porelo.standin;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * Starts the review stand-in: {@code --socket <path> --state <dir> [--fixtures] [--repository
 * <owner>/<repo> --pull <n>...]}. The GitHub token comes from {@code PORELO_STANDIN_GITHUB_TOKEN}
 * and from nowhere else.
 */
public final class Main {

    /** The environment variable the token comes from. */
    public static final String TOKEN_VARIABLE = "PORELO_STANDIN_GITHUB_TOKEN";

    private static final URI API = URI.create("https://api.github.com/");

    private Main() {}

    /**
     * Runs the stand-in until it is stopped.
     *
     * @param args The command line.
     */
    public static void main(String[] args) {
        try (VarlinkServer server =
                start(
                        args,
                        System.getenv(),
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                        API,
                        System.err)) {
            System.err.println(
                    "The review stand-in serves "
                            + StandIn.INTERFACE
                            + " on "
                            + server.socketPath());
            server.run();
        } catch (IllegalArgumentException ex) {
            System.err.println("REFUSED: " + ex.getMessage());
            System.exit(64);
        }
    }

    /**
     * Binds the socket and registers the calls, without serving yet.
     *
     * @param args The command line.
     * @param env The environment, where the token is.
     * @param http The transport the GitHub client uses; it never follows a redirect.
     * @param api GitHub's API base.
     * @param log Where the stand-in says what it answered; never a token.
     * @return The server, to run.
     */
    static VarlinkServer start(
            String[] args, Map<String, String> env, HttpClient http, URI api, PrintStream log) {
        Path socket = null;
        Path state = null;
        String repository = null;
        boolean fixtures = false;
        final List<Long> pulls = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--socket" -> socket = Path.of(value(args, ++i, "--socket"));
                case "--state" -> state = Path.of(value(args, ++i, "--state"));
                case "--repository" -> repository = value(args, ++i, "--repository");
                case "--pull" -> pulls.add(pull(value(args, ++i, "--pull")));
                case "--fixtures" -> fixtures = true;
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (socket == null || state == null) {
            throw new IllegalArgumentException("--socket and --state are required");
        }
        if (pulls.isEmpty() != (repository == null)) {
            throw new IllegalArgumentException("--repository and --pull go together");
        }
        if (pulls.isEmpty() && !fixtures) {
            throw new IllegalArgumentException("Nothing to serve: give --pull or --fixtures");
        }
        ownDirectory(socket);
        final @Nullable String token = pulls.isEmpty() ? null : token(env);
        final Git git = Git.open(state.resolve("mirror.git"));
        final Pushes pushes = new Pushes();
        GitHubSource github = null;
        if (repository != null) {
            github =
                    new GitHubSource(
                            new GitHubClient(api, http),
                            new GitHubGraphQl(api, http),
                            java.util.Objects.requireNonNull(token),
                            repository,
                            "github.com",
                            git,
                            Clock.systemUTC());
        }
        if (fixtures) {
            FixtureSource.load(
                    git,
                    pushes,
                    github == null
                            ? "porelo://" + StandIn.PROJECT + "/" + StandIn.REPOSITORY
                            : github.repositoryUri());
        }
        final StandIn standIn =
                new StandIn(pushes, git, github, pulls, token, Clock.systemUTC(), log);
        return standIn.register(new VarlinkServer(socket, StandIn.INTERFACE));
    }

    private static String token(Map<String, String> env) {
        final String token = env.get(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    TOKEN_VARIABLE + " is not set; a pull request is read with it");
        }
        return token.strip();
    }

    /** The socket's directory must be the caller's own, readable by nobody else. */
    private static void ownDirectory(Path socket) {
        try {
            final Path dir = VarlinkServer.directoryOf(socket.toAbsolutePath());
            final PosixFileAttributes attributes =
                    Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            final Path home = Path.of(System.getProperty("user.home"));
            final String owner = Files.getOwner(home).getName();
            if (!attributes.isDirectory()
                    || !attributes.owner().getName().equals(owner)
                    || !attributes
                            .permissions()
                            .equals(
                                    Set.of(
                                            PosixFilePermission.OWNER_READ,
                                            PosixFilePermission.OWNER_WRITE,
                                            PosixFilePermission.OWNER_EXECUTE))) {
                throw new IllegalArgumentException(
                        "The socket's directory " + dir + " must be the caller's own, mode 0700");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "The socket's directory cannot be read: " + ex.getMessage(), ex);
        }
    }

    private static long pull(String text) {
        if (!text.matches("[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("--pull takes a pull request's number");
        }
        return Long.parseLong(text);
    }

    private static String value(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new IllegalArgumentException(name + " needs a value");
        }
        return args[i];
    }
}
