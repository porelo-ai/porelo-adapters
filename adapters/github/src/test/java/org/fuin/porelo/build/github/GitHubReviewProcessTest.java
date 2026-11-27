package org.fuin.porelo.build.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.InstalledReviewReader;
import org.fuin.porelo.build.contract.JavaExecutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Speaks the review contract to GitHub's adapter as Porelo does: an executable on a socket. */
class GitHubReviewProcessTest {

    private static final String TOKEN = "ghp_lentToTheProcessOnly0123456789";

    // Port 9 answers nothing: whatever reaches GitHub here fails as unreachable.
    private static final ForgeAccess ACCESS = new ForgeAccess("https://127.0.0.1:9", TOKEN);

    private static final String NATIVE = "porelo.adapter.native";

    private Path scratch;

    @BeforeEach
    void scratch() throws IOException {
        Files.createDirectories(Path.of("target"));
        scratch = Files.createTempDirectory(Path.of("target"), "r-");
    }

    @AfterEach
    void clean() throws IOException {
        try (Stream<Path> paths = Files.walk(scratch)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void theAdapterServesTheReviewContractBesideTheBuildContract() throws IOException {
        try (InstalledReviewReader reader = reader()) {
            assertThat(reader.forge()).as("the handshake named the forge").isEqualTo("github");
        }
    }

    @Test
    void aSubmissionProfileTwoRefusesComesBackAsNotInProfile() throws IOException {
        try (InstalledReviewReader reader = reader()) {
            assertThatThrownBy(
                            () ->
                                    reader.post(
                                            ACCESS,
                                            "https://127.0.0.1:9/example-org/example-repo/pull/1",
                                            adversarial("replacement-too-many-lines")))
                    .isInstanceOf(ForgeReviewRefused.class)
                    .extracting(e -> ((ForgeReviewRefused) e).reason())
                    .as("refused before any call, across the socket")
                    .isEqualTo(ForgeReviewRefused.Reason.NOT_IN_PROFILE);
        }
    }

    @Test
    void anAdapterFailureNamesNoToken() throws IOException {
        try (InstalledReviewReader reader = reader()) {
            assertThatThrownBy(
                            () ->
                                    reader.pullRequest(
                                            ACCESS,
                                            "https://elsewhere.example/" + TOKEN + "/x/pull/1"))
                    .as("a URL on another forge fails in the adapter")
                    .satisfies(
                            e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(TOKEN));
        }
    }

    @Test
    void aResolveIsServedAcrossTheSocketAndAForgeNotReachedIsNoWriteOfUnknownOutcome()
            throws IOException {
        try (InstalledReviewReader reader = reader()) {
            assertThatThrownBy(
                            () ->
                                    reader.resolve(
                                            new ForgeAccess("https://127.0.0.1:9/api/v3", TOKEN),
                                            "https://127.0.0.1:9/example-org/example-repo/pull/1",
                                            "17"))
                    .as("the thread is looked up before any write, and the forge is not there")
                    .isInstanceOf(ForgeReviewRefused.class)
                    .satisfies(
                            e -> {
                                assertThat(((ForgeReviewRefused) e).reason())
                                        .isEqualTo(ForgeReviewRefused.Reason.UNREACHABLE);
                                assertThat(String.valueOf(e.getMessage())).doesNotContain(TOKEN);
                            });
        }
    }

    private InstalledReviewReader reader() throws IOException {
        return new InstalledReviewReader(executable(), "github", scratch.resolve("s"));
    }

    /**
     * The adapter on the test class path, or the native image {@value #NATIVE} names once it is
     * built, so the same calls run against what ships.
     */
    private Path executable() throws IOException {
        final String named = System.getProperty(NATIVE, "");
        if (named.isEmpty()) {
            return JavaExecutable.write(scratch, GitHubReader.class);
        }
        final Path image = Path.of(named).toAbsolutePath();
        assumeTrue(Files.isExecutable(image), "the native image is not built yet");
        return image;
    }

    private static byte[] adversarial(String name) throws IOException {
        final String path = "/org/fuin/porelo/build/api/review/adversarial/" + name + ".sarif.json";
        try (InputStream in = GitHubReviewProcessTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(in, path).readAllBytes();
        }
    }
}
