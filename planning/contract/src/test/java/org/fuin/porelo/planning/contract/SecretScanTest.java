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

    // Harm: Basic authentication encodes the token at an alignment chosen by the username.
    @Test
    void findsEveryBase64AlignmentInFilesAndEnvironments() throws Exception {
        final String secret = UUID.randomUUID().toString();
        for (final String user : java.util.List.of("a", "ab", "abc")) {
            final String header =
                    "Basic "
                            + java.util.Base64.getEncoder()
                                    .encodeToString(
                                            (user + ":" + secret).getBytes(StandardCharsets.UTF_8));
            final Path config = directory.resolve("config-" + user);
            Files.writeString(config, "[http]\nextraHeader = Authorization: " + header);
            assertThat(SecretScan.files(secret, directory))
                    .as("each Basic alignment is found in hidden store configuration")
                    .contains(config);
            assertThat(SecretScan.environment(secret, Map.of("HEADER", header)))
                    .as("each encoded environment value is found")
                    .containsExactly("HEADER");
        }
    }

    // Harm: credentials in names and link targets persist even when regular file contents are
    // clean.
    @Test
    void findsNamesAndLinkTargetsWithoutEchoingTheCredential() throws Exception {
        final String secret = UUID.randomUUID().toString();
        final Path nested = Files.createDirectory(directory.resolve(secret));
        Files.writeString(nested.resolve("clean"), "safe");
        Files.writeString(directory.resolve("file-" + secret), "safe");
        Files.createSymbolicLink(directory.resolve("link-" + secret), Path.of("missing"));
        Files.createSymbolicLink(directory.resolve("link-target"), Path.of(secret));
        final var found = SecretScan.files(secret, directory);
        assertThat(found)
                .as("directory, nested file, filename, link name and link target are all inspected")
                .hasSize(5);
        assertThat(found.stream().anyMatch(path -> path.toString().contains(secret)))
                .as("finding paths redact every credential-bearing name")
                .isFalse();
        final String encoded =
                java.util.Base64.getEncoder()
                        .encodeToString(("git:" + secret).getBytes(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve(encoded), "safe");
        final var encodedNames = SecretScan.files(secret, directory);
        assertThat(encodedNames).as("encoded filenames are also found").hasSize(6);
        assertThat(
                        encodedNames.stream()
                                .anyMatch(path -> SecretScan.contains(secret, path.toString())))
                .as("encoded names are redacted before returning diagnostics")
                .isFalse();
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
