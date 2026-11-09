package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

class PlanningContractFaultTest {
    // Harm: a stale revision case that always passes permits lost updates downstream.
    @Test
    void detectsAnAdapterThatIgnoresRevisions() {
        provesFailure(
                IgnoresRevision.class,
                "refusesAnOldRevisionWithoutChangingAnything",
                "asked for a named adapter refusal");
    }

    // Harm: an atomicity case that trusts a refusal can miss a first change already published.
    @Test
    void detectsAnAdapterThatWritesBeforeValidatingTheWholeBatch() {
        provesFailure(
                WritesHalf.class,
                "validatesEveryIssueBeforeWritingAnyChange",
                "refused batch must leave every authoritative store unchanged");
    }

    // Harm: credential checks must inspect refusal details rather than trusting their error name.
    @Test
    void detectsAnAdapterThatEchoesTheTokenInARefusal() {
        provesFailure(
                EchoesToken.class,
                "keepsTheTokenOutOfRefusedPushesAndFiles",
                "answer or refusal must not disclose");
    }

    // Harm: checking only listed entries can miss additional private briefs delivered too.
    @Test
    void detectsAnAdapterThatDeliversAnUnlistedBrief() {
        provesFailure(
                DeliversUnlisted.class,
                "neverDeliversAnUnlistedBrief",
                "only explicitly listed proposals are delivered");
    }

    // Harm: watch tests must actually call Refresh while the subscription remains open.
    @Test
    void detectsAnAdapterThatBlocksRefreshBehindAWatch() {
        provesFailure(
                BlocksRefresh.class,
                "answersEveryOtherCallWhileAWatchIsOpen",
                "Expected adapter call within");
    }

    private static void provesFailure(Class<?> adapter, String method, String message) {
        final var listener = new SummaryGeneratingListener();
        LauncherFactory.create()
                .execute(
                        LauncherDiscoveryRequestBuilder.request()
                                .selectors(selectMethod(adapter, method))
                                .configurationParameter(
                                        "junit.jupiter.execution.parallel.enabled", "false")
                                .build(),
                        listener);
        final var summary = listener.getSummary();
        assertThat(summary.getTestsFoundCount())
                .as("the selected inherited contract case must actually be discovered")
                .isEqualTo(1);
        assertThat(summary.getTestsStartedCount())
                .as("the fault proof must execute its case")
                .isEqualTo(1);
        assertThat(summary.getTestsAbortedCount())
                .as("the fault must not turn into an assumption skip")
                .isZero();
        assertThat(summary.getTestsSucceededCount())
                .as("the injected fault must not pass")
                .isZero();
        assertThat(summary.getTestsFailedCount())
                .as("the selected fault must fail exactly its contract case")
                .isEqualTo(1);
        assertThat(summary.getFailures())
                .as("the failure must be an assertion about the intended fault")
                .hasSize(1);
        assertThat(summary.getFailures().getFirst().getException())
                .as("startup and fixture failures are not red proof")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(message);
    }

    abstract static class Faulty extends PlanningContract {
        private final String fault;

        Faulty(String fault) {
            super(ReferencePlanning.class);
            this.fault = fault;
        }

        @Override
        protected Fixture fixture(Path root) {
            return new FileFixture();
        }

        @Override
        protected Map<String, String> adapterProperties() {
            return Map.of("planning.contract.fault", fault);
        }
    }

    static final class IgnoresRevision extends Faulty {
        IgnoresRevision() {
            super("ignore-revision");
        }
    }

    static final class WritesHalf extends Faulty {
        WritesHalf() {
            super("half-batch");
        }
    }

    static final class EchoesToken extends Faulty {
        EchoesToken() {
            super("echo-token");
        }
    }

    static final class DeliversUnlisted extends Faulty {
        DeliversUnlisted() {
            super("unlisted-brief");
        }
    }

    static final class BlocksRefresh extends Faulty {
        BlocksRefresh() {
            super("blocked-refresh");
        }
    }
}
