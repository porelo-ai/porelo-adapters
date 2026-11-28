package org.fuin.porelo.build.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.InstalledReviewReader;
import org.fuin.porelo.build.contract.JavaExecutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Speaks the review contract to Forgejo's adapter as Porelo does: an executable on a socket. */
class ForgejoReviewProcessTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef01234567";

    // Port 9 answers nothing: whatever reaches Forgejo here fails as unreachable.
    private static final ForgeAccess ACCESS = new ForgeAccess("https://127.0.0.1:9/api/v1", TOKEN);

    private static final String PULL = "https://127.0.0.1:9/core/probe/pulls/1";

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
            assertThat(reader.forge()).as("the handshake named the forge").isEqualTo("forgejo");
        }
    }

    @Test
    void aResolveIsAnsweredUnsupportedOverTheSocket() throws IOException {
        try (InstalledReviewReader reader = reader()) {
            assertThatThrownBy(() -> reader.resolve(ACCESS, PULL, "2"))
                    .as("Forgejo's API has no call to resolve a conversation")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            e ->
                                    assertThat(e.reason())
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason.RESOLVE_UNSUPPORTED));
        }
    }

    @Test
    void aReadThatReachesNoForgejoIsUnreachableAndNamesNoToken() throws IOException {
        try (InstalledReviewReader reader = reader()) {
            assertThatThrownBy(() -> reader.pullRequest(ACCESS, PULL))
                    .as("nothing listens on port 9")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            e -> {
                                assertThat(e.reason())
                                        .isEqualTo(ForgeReviewRefused.Reason.UNREACHABLE);
                                assertThat(String.valueOf(e.getMessage())).doesNotContain(TOKEN);
                            });
        }
    }

    private InstalledReviewReader reader() throws IOException {
        return new InstalledReviewReader(executable(), "forgejo", scratch.resolve("s"));
    }

    /**
     * The adapter on the test class path, or the native image {@value #NATIVE} names once it is
     * built, so the same calls run against what ships.
     */
    private Path executable() throws IOException {
        final String named = System.getProperty(NATIVE, "");
        if (named.isEmpty()) {
            return JavaExecutable.write(scratch, ForgejoReader.class);
        }
        final Path image = Path.of(named).toAbsolutePath();
        assumeTrue(Files.isExecutable(image), "the native image is not built yet");
        return image;
    }
}
