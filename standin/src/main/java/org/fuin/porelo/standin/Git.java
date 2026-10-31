package org.fuin.porelo.standin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The stand-in's own bare clone, which a person's review fetch reads. Git runs with no
 * configuration of the machine's, and the token, where a fetch needs one, reaches it only through
 * its environment.
 */
final class Git {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final long TIMEOUT_SECONDS = 300;

    private final Path dir;

    private Git(Path dir) {
        this.dir = dir;
    }

    /**
     * Opens the bare clone at a path, making it if it is not there.
     *
     * @param dir Where it is.
     * @return The clone.
     */
    static Git open(Path dir) {
        final Git git = new Git(dir);
        if (!Files.isDirectory(dir.resolve("objects"))) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            git.run(null, null, "init", "--quiet", "--bare");
        }
        return git;
    }

    /**
     * Returns where the clone is.
     *
     * @return Its directory.
     */
    Path dir() {
        return dir;
    }

    /**
     * Fetches refs from a remote over https.
     *
     * @param remote The remote's https URL, with no credential in it.
     * @param token The token, or {@code null} for a public repository.
     * @param refspecs What to fetch.
     */
    void fetch(String remote, @Nullable String token, String... refspecs) {
        if (!remote.startsWith("https://") || remote.contains("@")) {
            throw new IllegalArgumentException("A fetch goes to an https URL without a credential");
        }
        final List<String> args = new ArrayList<>(List.of("fetch", "--quiet", "--no-tags", remote));
        args.addAll(List.of(refspecs));
        run(token, null, args.toArray(String[]::new));
    }

    /**
     * Returns the commit a ref or revision names.
     *
     * @param revision What to resolve.
     * @return The full commit.
     */
    String commit(String revision) {
        final String commit =
                run(null, null, "rev-parse", "--verify", "--quiet", revision + "^{commit}").strip();
        if (!COMMIT.matcher(commit).matches()) {
            throw new IllegalStateException("Not a commit: " + revision);
        }
        return commit;
    }

    /**
     * Returns the merge base of two commits.
     *
     * @param a One.
     * @param b The other.
     * @return Their merge base.
     */
    String mergeBase(String a, String b) {
        return run(null, null, "merge-base", a, b).strip();
    }

    /**
     * Sets a ref.
     *
     * @param ref The ref.
     * @param commit What it points at.
     */
    void updateRef(String ref, String commit) {
        run(null, null, "update-ref", ref, commit);
    }

    /**
     * Makes a commit of the given files on any number of parents, with a fixed author and time, so
     * the same files and parents always give the same commit.
     *
     * @param files Path to content, the whole tree.
     * @param parents The parents, none for a root commit.
     * @param message The message.
     * @return The commit.
     */
    String commitTree(Map<String, String> files, List<String> parents, String message) {
        final StringBuilder index = new StringBuilder();
        for (final Map.Entry<String, String> file : new java.util.TreeMap<>(files).entrySet()) {
            final String blob = run(null, file.getValue(), "hash-object", "-w", "--stdin").strip();
            index.append("100644 ").append(blob).append(' ').append(0).append('\t');
            index.append(file.getKey()).append('\n');
        }
        // update-index --index-info builds nested trees from slashed paths; mktree would not.
        final Path indexFile = dir.resolve("standin-index");
        try {
            Files.deleteIfExists(indexFile);
            runIndexed(indexFile, index.toString(), "update-index", "--index-info");
            final String tree = runIndexed(indexFile, null, "write-tree").strip();
            final List<String> args = new ArrayList<>(List.of("commit-tree"));
            for (final String parent : parents) {
                args.add("-p");
                args.add(parent);
            }
            args.add(tree);
            return run(null, message, args.toArray(String[]::new)).strip();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            try {
                Files.deleteIfExists(indexFile);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * Returns a file's content at a revision, or {@code null} when it is no regular file there.
     *
     * @param revision The commit.
     * @param path The path.
     * @return Its text, or {@code null}.
     */
    @Nullable String file(String revision, String path) {
        final String type = runOrNull("cat-file", "-t", revision + ":" + path);
        if (type == null || !type.strip().equals("blob")) {
            return null;
        }
        final String mode = run(null, null, "ls-tree", revision, "--", path);
        if (!mode.startsWith("100")) {
            return null;
        }
        return run(null, null, "cat-file", "blob", revision + ":" + path);
    }

    /**
     * Runs a read-only git command and returns what it printed.
     *
     * @param args Its arguments.
     * @return Its standard output.
     */
    String read(String... args) {
        return run(null, null, args);
    }

    private @Nullable String runOrNull(String... args) {
        try {
            return run(null, null, args);
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private String runIndexed(Path index, @Nullable String input, String... args) {
        return execute(Map.of("GIT_INDEX_FILE", index.toString()), null, input, args);
    }

    private String run(@Nullable String token, @Nullable String input, String... args) {
        return execute(Map.of(), token, input, args);
    }

    private String execute(
            Map<String, String> extra,
            @Nullable String token,
            @Nullable String input,
            String... args) {
        // Unquoted paths: a quoted one names no file of the push.
        final List<String> command =
                new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "--git-dir=" + dir));
        command.addAll(List.of(args));
        final ProcessBuilder builder = new ProcessBuilder(command);
        final Map<String, String> env = builder.environment();
        env.keySet().removeIf(name -> name.startsWith("GIT_"));
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_AUTHOR_NAME", "Porelo review stand-in");
        env.put("GIT_AUTHOR_EMAIL", "standin@example.org");
        env.put("GIT_AUTHOR_DATE", "2026-01-01T00:00:00Z");
        env.put("GIT_COMMITTER_NAME", "Porelo review stand-in");
        env.put("GIT_COMMITTER_EMAIL", "standin@example.org");
        env.put("GIT_COMMITTER_DATE", "2026-01-01T00:00:00Z");
        env.putAll(extra);
        if (token != null) {
            // The credential goes in the environment, never on the command line or into a file.
            final String basic =
                    Base64.getEncoder()
                            .encodeToString(
                                    ("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        builder.redirectErrorStream(false);
        try {
            final Process process = builder.start();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            final Thread outReader = drain(process.getInputStream(), out);
            final Thread errReader = drain(process.getErrorStream(), err);
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + args[0] + " did not end in time");
            }
            outReader.join();
            errReader.join();
            if (process.exitValue() != 0) {
                // git's own words, never the environment: the token is not in them.
                throw new IllegalStateException(
                        "git "
                                + args[0]
                                + " failed: "
                                + err.toString(StandardCharsets.UTF_8).strip());
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while git ran", ex);
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream into) {
        final Thread thread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (in) {
                                        in.transferTo(into);
                                    } catch (IOException ex) {
                                        // The process is gone; its exit code says what happened.
                                    }
                                });
        return thread;
    }
}
