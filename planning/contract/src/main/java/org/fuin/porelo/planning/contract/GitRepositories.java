package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Creates and changes real bare repositories independently of the adapter under test. */
public final class GitRepositories implements AutoCloseable {
    private final Path root;
    private final Path scratch;
    private final Path template;
    private final GitProcesses processes;
    private final Map<String, Path> worktrees = new HashMap<>();
    private boolean closed;

    /**
     * Creates isolated Git working directories and a home beneath the fixture root.
     *
     * @param directory The same repository root used by {@link GitHttpServer}.
     * @throws IOException If scratch directories cannot be created.
     */
    public GitRepositories(final Path directory) throws IOException {
        root = Files.createDirectories(directory).toRealPath();
        scratch = Files.createTempDirectory(root, ".repositories-");
        template = Files.createDirectory(scratch.resolve("template"));
        processes = new GitProcesses(Files.createDirectory(scratch.resolve("home")));
    }

    /**
     * Seeds a new bare repository on main, enabling authenticated HTTP pushes.
     *
     * @param repository Simple bare repository name ending in .git.
     * @param files Relative file paths and their UTF-8 text; .git and symbolic links are refused.
     * @return The bare repository path.
     * @throws IOException If a path is unsafe, Git fails, or a process exceeds its bounded wait.
     */
    public synchronized Path create(final String repository, final Map<String, String> files)
            throws IOException {
        requireOpen();
        final String name = FixturePaths.repository(repository);
        final Path bare = root.resolve(name);
        final Path worktree = scratch.resolve(name);
        for (final String file : files.keySet()) FixturePaths.inside(worktree, file);
        Files.createDirectory(bare);
        processes.git(
                root,
                "init",
                "--bare",
                "--initial-branch=main",
                "--template=" + template,
                bare.toString());
        processes.git(bare, "config", "--local", "http.receivepack", "true");
        processes.git(
                scratch,
                "init",
                "--initial-branch=main",
                "--template=" + template,
                worktree.toString());
        processes.git(worktree, "remote", "add", "origin", bare.toString());
        write(worktree, files);
        commitAndPush(worktree);
        worktrees.put(name, worktree);
        return bare;
    }

    /**
     * Commits and pushes file replacements behind the adapter's back, preserving other files.
     *
     * @param repository Previously seeded bare repository.
     * @param files Relative paths and replacement UTF-8 text.
     * @return The remote's new main commit.
     * @throws IOException If the repository is unknown, a path is unsafe, or Git fails or times
     *     out.
     */
    public synchronized String commit(final String repository, final Map<String, String> files)
            throws IOException {
        final Path worktree = worktree(repository);
        processes.git(worktree, "fetch", "origin", "main");
        processes.git(worktree, "reset", "--hard", "FETCH_HEAD");
        write(worktree, files);
        commitAndPush(worktree);
        return head(repository);
    }

    /**
     * Reads the current remote main commit directly, without consulting a cached working copy.
     *
     * @param repository Previously seeded bare repository.
     * @return Git's full commit identifier.
     * @throws IOException If the repository is unknown or Git fails or times out.
     */
    public synchronized String head(final String repository) throws IOException {
        worktree(repository);
        return processes.git(root.resolve(repository), "rev-parse", "refs/heads/main").strip();
    }

    /**
     * Reads a UTF-8 file from the remote main commit.
     *
     * @param repository Previously seeded bare repository.
     * @param file Contained relative file path outside .git.
     * @return The exact committed text, including its trailing newline if present.
     * @throws IOException If a path is unsafe, the file is absent, or Git fails or times out.
     */
    public synchronized String file(final String repository, final String file) throws IOException {
        worktree(repository);
        FixturePaths.inside(scratch, file);
        return processes.git(root.resolve(repository), "show", "refs/heads/main:" + file);
    }

    private Path worktree(final String repository) throws IOException {
        requireOpen();
        final Path worktree = worktrees.get(FixturePaths.repository(repository));
        if (worktree == null)
            throw new IOException("Expected a seeded repository; got " + repository);
        return worktree;
    }

    private void requireOpen() throws IOException {
        if (closed)
            throw new IOException("Expected open repository fixtures; they have been closed");
    }

    private static void write(final Path worktree, final Map<String, String> files)
            throws IOException {
        final Map<Path, String> contained = new HashMap<>();
        for (final Map.Entry<String, String> file : files.entrySet()) {
            contained.put(FixturePaths.inside(worktree, file.getKey()), file.getValue());
        }
        for (final Map.Entry<Path, String> file : contained.entrySet()) {
            Files.createDirectories(file.getKey().getParent());
            Files.writeString(file.getKey(), file.getValue());
        }
    }

    private void commitAndPush(final Path worktree) throws IOException {
        processes.git(worktree, "add", "--all");
        processes.git(worktree, "commit", "--allow-empty", "-m", "Update fixture files");
        processes.git(worktree, "push", "origin", "HEAD:refs/heads/main");
    }

    /**
     * Stops owned Git processes and removes working copies, retaining the bare fixture
     * repositories.
     *
     * @throws IOException If process shutdown or scratch deletion fails.
     */
    @Override
    public void close() throws IOException {
        // Do not wait for the operation lock before terminating a process it may be waiting on.
        processes.close();
        synchronized (this) {
            if (closed) return;
            closed = true;
            FixturePaths.delete(scratch);
        }
    }
}
