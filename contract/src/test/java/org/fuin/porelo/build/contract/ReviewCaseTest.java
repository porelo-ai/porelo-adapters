package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewCaseTest {

    @TempDir Path scratch;

    @Test
    void everyPublishedReviewGuardIsRegisteredAndItsRecordingsCanBeInstalled() throws Exception {
        final List<ReviewCase> cases = ReviewCase.all();
        assertThat(cases)
                .as("publishing a case must also register the guard for consuming adapters")
                .extracting(ReviewCase::name)
                .containsExactly(
                        "comments-lines",
                        "comments-pending",
                        "comments-held-bidi",
                        "comments-held-zero-width",
                        "comments-cut-off",
                        "comments-outside-profile",
                        "post-line",
                        "post-mention",
                        "post-suggestion",
                        "post-bidi",
                        "post-zero-width",
                        "post-general",
                        "post-file",
                        "post-other-pull-request",
                        "post-lost-answer",
                        "resolve-open",
                        "resolve-already-resolved",
                        "resolve-unknown-complete",
                        "resolve-unknown-partial",
                        "resolve-lost-answer",
                        "token-refused",
                        "token-error",
                        "token-redirect");
        for (final ReviewCase test : cases) {
            final Path directory = scratch.resolve(test.name());
            test.install(directory);
            assertThat(RecordedForge.read(directory))
                    .as("%s installs every recorded exchange", test.name())
                    .extracting(RecordedForge.Exchange::file)
                    .containsExactlyElementsOf(test.exchanges());
        }
    }
}
