package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.junit.jupiter.api.Test;

class SettingsTest {
    // Harm: settings can inject git options, credentials or paths outside adapter storage.
    @Test
    void refusesMalformedSettingsWithoutEchoingTheCredential() {
        for (final Map<String, Object> settings :
                List.of(
                        Map.<String, Object>of(),
                        changed("repository", "--upload-pack=program"),
                        changed("storiesDirectory", "../stories"),
                        changed("repository", "https://secret-value@example.org/planning.git"),
                        changed("issueRepositoryPattern", "https://example.org/no-placeholder.git"),
                        changed("linkPattern", "https://example.org/{repository}"),
                        changed("repository", List.of("https://example.org/planning.git")))) {
            final StoriesGit adapter = new StoriesGit();
            final Throwable failure =
                    catchThrowable(() -> adapter.getWorkflow(settings, "secret-value"));
            assertThat(failure)
                    .as("missing or malformed settings get a named refusal")
                    .isInstanceOf(PlanningRefused.class);
            final PlanningRefused refused = (PlanningRefused) failure;
            assertThat(refused.reason())
                    .as("malformed settings use InvalidSettings")
                    .isEqualTo(Reason.INVALID_SETTINGS);
            assertThat(refused.parameters().toString())
                    .as("safe details never contain the supplied credential")
                    .doesNotContain("secret-value");
        }
    }

    // Harm: interpreting an unsupported credential kind can send a credential to the wrong store.
    @Test
    void refusesAnUnsupportedCredentialKindBeforeInitializingStorage() {
        final StoriesGit adapter = new StoriesGit();
        final Throwable failure =
                catchThrowable(
                        () ->
                                adapter.getWorkflow(
                                        changed("credentialKind", "unknown"), "secret-value"));
        assertThat(failure)
                .as("unsupported credentials are refused before storage access")
                .isInstanceOf(PlanningRefused.class);
        assertThat(((PlanningRefused) failure).reason())
                .as("unsupported credential kind uses NotSupported")
                .isEqualTo(Reason.NOT_SUPPORTED);
    }

    private static Map<String, Object> changed(String key, Object value) {
        final Map<String, Object> settings =
                new LinkedHashMap<>(
                        Map.of(
                                "repository",
                                "https://example.org/planning.git",
                                "issueRepositoryPattern",
                                "https://example.org/{repository}.git",
                                "linkPattern",
                                "https://example.org/{repository}/{path}"));
        settings.put(key, value);
        return settings;
    }
}
