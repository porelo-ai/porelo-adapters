package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretScanTest {
    @TempDir Path directory;

    @Test
    void leakedCredentialsAreFoundInHiddenGitFilesAndEnvironmentValues() throws Exception {
        // Skipping .git or the child environment can conceal a credential retained after a call.
        final String secret = UUID.randomUUID().toString();
        final Path config =
                Files.createDirectories(directory.resolve("clone/.git")).resolve("config");
        Files.writeString(config, "[http]\nextraHeader = " + secret + "\n");
        Files.writeString(directory.resolve("clone/story.md"), "Ready\n");

        assertThat(SecretScan.files(secret, directory, directory.resolve("clone")))
                .as("all roots include the hidden Git config without duplicate findings")
                .containsExactly(config.toAbsolutePath());
        assertThat(
                        SecretScan.environment(
                                secret,
                                Map.of("AUTH_HEADER", "Basic " + secret, "HOME", "/fixture/home")))
                .as("environment findings identify the variable without echoing its value")
                .containsExactly("AUTH_HEADER");
        final java.util.List<String> names =
                SecretScan.environment(secret, Map.of("PREFIX_" + secret, "value"));
        assertThat(names.stream().anyMatch(name -> name.contains(secret)))
                .as(
                        "environment findings must not copy a leaked credential into assertion"
                                + " diagnostics")
                .isFalse();
        assertThat(names)
                .as("a credential used as an environment name is found with its value redacted")
                .containsExactly("PREFIX_<redacted>");
    }

    @Test
    void cleanTreesAndEnvironmentsHaveNoFindings() throws Exception {
        // A scan that always reports a leak would make every adapter fail the credential contract.
        final String secret = UUID.randomUUID().toString();
        Files.createDirectories(directory.resolve(".git"));
        Files.writeString(directory.resolve(".git/config"), "[core]\nbare = false\n");
        Files.write(directory.resolve("object.bin"), new byte[] {0, 1, 2, 3});

        assertThat(SecretScan.files(secret, directory))
                .as("every clean fixture file is allowed")
                .isEmpty();
        assertThat(SecretScan.environment(secret, Map.of("HOME", "/fixture/home")))
                .as("a clean child environment is allowed")
                .isEmpty();
    }

    @Test
    void binaryBoundaryMatchesAreFoundWithoutFollowingSymbolicLinks() throws Exception {
        // A split byte match or a followed link could hide a leak or inspect another directory.
        final String secret = UUID.randomUUID().toString();
        final Path root = Files.createDirectory(directory.resolve("scan"));
        final Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("token"), secret);
        final byte[] token = secret.getBytes(StandardCharsets.UTF_8);
        final byte[] bytes = new byte[20_000];
        System.arraycopy(token, 0, bytes, 8192 + token.length - 3, token.length);
        final Path binary = root.resolve("packed-object");
        Files.write(binary, bytes);
        Files.createSymbolicLink(root.resolve("directory-link"), outside);
        Files.createSymbolicLink(root.resolve("file-link"), outside.resolve("token"));
        Files.createSymbolicLink(root.resolve("dangling-link"), outside.resolve("absent"));

        assertThat(SecretScan.files(secret, root))
                .as("only the regular file within the requested tree is scanned")
                .containsExactly(binary.toAbsolutePath());
    }

    @Test
    void aMissingRootFailsInsteadOfProvingTheAbsenceOfLeaks() {
        // Silently skipping an absent scan root could turn a broken setup into a clean security
        // check.
        assertThatThrownBy(() -> SecretScan.files("fixture-token", directory.resolve("missing")))
                .as("all requested scan roots must exist")
                .isInstanceOf(IOException.class)
                .hasMessageContainingAll("Expected an existing directory", "missing");
    }
}
