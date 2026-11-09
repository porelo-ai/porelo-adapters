package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class GitClient implements AutoCloseable {
    private final Path home;
    private final Path askpass;
    private final Path template;
    private final GitProcesses processes;

    GitClient(final Path directory) throws IOException {
        Files.createDirectory(directory);
        home = Files.createDirectory(directory.resolve("home"));
        template = Files.createDirectory(directory.resolve("template"));
        askpass =
                Files.createFile(
                        directory.resolve("askpass"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        // Credentials must not be persisted in a remote URL or exposed as process arguments.
        Files.writeString(askpass, "#!/bin/sh\nprintf '%s\\n' \"$FIXTURE_TOKEN\"\n");
        processes = new GitProcesses(home);
    }

    ProcessBuilder command(
            final Path directory, final @Nullable String token, final String... arguments) {
        final List<String> command =
                new ArrayList<>(List.of("git", "-c", "init.templateDir=" + template));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        GitProcesses.isolate(builder, home);
        builder.environment().put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
        builder.environment().put("FIXTURE_TOKEN", token == null ? "" : token);
        return builder;
    }

    String run(final Path directory, final @Nullable String token, final String... arguments)
            throws IOException {
        return run(command(directory, token, arguments), Duration.ofSeconds(15));
    }

    String run(final ProcessBuilder builder, final Duration timeout) throws IOException {
        return processes.run(builder, timeout);
    }

    /** Stops any test Git command before the temporary directory is removed. */
    @Override
    public void close() throws IOException {
        processes.close();
    }
}
