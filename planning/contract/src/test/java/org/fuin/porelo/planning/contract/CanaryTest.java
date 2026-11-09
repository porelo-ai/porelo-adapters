package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanaryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"hook", "helper", "fsmonitor"})
    void hostGitConfigurationLeavesEvidenceWhenItIsAllowedToRun(final String mechanism)
            throws Exception {
        // An inert canary could let host hooks, credential helpers, or monitors execute unnoticed.
        try (Canary canary = new Canary(directory.resolve("host home's"));
                GitClient git = new GitClient(directory.resolve("client"))) {
            final Path work = directory.resolve("work");
            git.run(directory, null, "init", "--initial-branch=main", work.toString());
            final ProcessBuilder builder =
                    switch (mechanism) {
                        case "hook" ->
                                git.command(
                                        work,
                                        null,
                                        "-c",
                                        "core.fsmonitor=false",
                                        "commit",
                                        "--allow-empty",
                                        "-m",
                                        "Exercise hook");
                        case "helper" -> git.command(work, null, "credential", "reject");
                        case "fsmonitor" -> git.command(work, null, "status", "--porcelain");
                        default ->
                                throw new AssertionError("Unknown canary mechanism: " + mechanism);
                    };
            canary.applyTo(builder);
            // This one self-test deliberately permits only the fixture's own hostile global config.
            builder.environment().remove("GIT_CONFIG_GLOBAL");
            if (mechanism.equals("helper")) {
                builder.redirectInput(
                        Files.writeString(
                                        directory.resolve("credential-input"),
                                        "protocol=https\nhost=fixture.example.invalid\n\n")
                                .toFile());
            }
            git.run(builder, Duration.ofSeconds(10));

            assertThat(canary.marker())
                    .as("the %s canary proves that Git executed host configuration", mechanism)
                    .exists();
            assertThatThrownBy(canary::verifyUntouched)
                    .as("the suite rejects evidence of host Git execution")
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void disablingGlobalGitConfigurationLeavesTheHostHomeUntouched() throws Exception {
        // Adapter Git invocations must not execute a person's hooks, helpers, or filesystem
        // monitor.
        try (Canary canary = new Canary(directory.resolve("host"));
                GitClient git = new GitClient(directory.resolve("client"))) {
            final Path work = directory.resolve("work");
            git.run(directory, null, "init", "--initial-branch=main", work.toString());
            git.run(
                    canary.applyTo(
                            git.command(
                                    work,
                                    null,
                                    "commit",
                                    "--allow-empty",
                                    "-m",
                                    "Isolated commit")),
                    Duration.ofSeconds(10));
            git.run(
                    canary.applyTo(git.command(work, null, "status", "--porcelain")),
                    Duration.ofSeconds(10));
            final Path input =
                    Files.writeString(
                            directory.resolve("credential-input"),
                            "protocol=https\nhost=fixture.example.invalid\n\n");
            git.run(
                    canary.applyTo(git.command(work, null, "credential", "reject"))
                            .redirectInput(input.toFile()),
                    Duration.ofSeconds(10));

            canary.verifyUntouched();
            assertThat(canary.marker())
                    .as("all isolated Git operations leave the marker absent")
                    .doesNotExist();
        }
    }

    @Test
    void creatingAndRemovingTheMarkerCannotHideHostExecution() throws Exception {
        // Checking only final absence would miss a hook that removes the evidence it created.
        try (Canary canary = new Canary(directory.resolve("host"))) {
            Files.writeString(canary.marker(), "executed");
            Files.delete(canary.marker());

            assertThatThrownBy(canary::verifyUntouched)
                    .as("write events survive removal of the canary file")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must receive no writes");
        }
    }
}
