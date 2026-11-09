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

    // Harm: short-lived helper arguments can disclose a credential before the call returns.
    @Test
    void detectsChildArguments() {
        provesFailure(
                ChildArguments.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "captured child argument list");
    }

    // Harm: short-lived helper environments can retain credentials missed by post-call scans.
    @Test
    void detectsChildEnvironment() {
        provesFailure(
                ChildEnvironment.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "captured child environment");
    }

    // Harm: inherited OS stderr bypasses Java logging and can disclose credentials.
    @Test
    void detectsChildStderr() {
        provesFailure(
                ChildStderr.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "adapter stdout and stderr");
    }

    // Harm: encoded Authorization headers remain reusable credentials after a call.
    @Test
    void detectsBase64File() {
        provesFailure(
                Base64File.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "no credential may persist");
    }

    // Harm: a clean file body can hide a credential retained in its filename.
    @Test
    void detectsTokenFilename() {
        provesFailure(
                TokenFilename.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "no credential may persist");
    }

    // Harm: a symbolic link target can retain a credential without a regular file.
    @Test
    void detectsTokenLink() {
        provesFailure(
                TokenLink.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "no credential may persist");
    }

    // Harm: an excluded runtime directory can conceal a persisted credential.
    @Test
    void detectsRuntimeToken() {
        provesFailure(
                RuntimeToken.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "no credential may persist");
    }

    // Harm: a missing traversal target can conceal an unguarded read outside the store.
    @Test
    void detectsStoryTraversalRead() {
        provesFailure(
                StoryTraversalRead.class,
                "refusesUnsafeStoryIdsWithoutTouchingTheCanary",
                "asked for a named adapter refusal");
    }

    // Harm: an unguarded issue read can disclose a readable host document.
    @Test
    void detectsIssueTraversalRead() {
        provesFailure(
                IssueTraversalRead.class,
                "refusesUnsafeIssueIdsWithoutTouchingTheCanary",
                "asked for a named adapter refusal");
    }

    // Harm: a refused story change can still overwrite files outside the store.
    @Test
    void detectsStoryTraversalWrite() {
        provesFailure(
                StoryTraversalWrite.class,
                "refusesUnsafeStoryIdsWithoutTouchingTheCanary",
                "unsafe ids create or modify no files");
    }

    // Harm: a refused issue change can still overwrite files outside the store.
    @Test
    void detectsIssueTraversalWrite() {
        provesFailure(
                IssueTraversalWrite.class,
                "refusesUnsafeIssueIdsWithoutTouchingTheCanary",
                "unsafe ids create or modify no files");
    }

    // Harm: read containment alone does not prevent writes through issue symlinks.
    @Test
    void detectsSymlinkWrite() {
        provesFailure(
                SymlinkWrite.class,
                "refusesWritingThroughAnIssueSymbolicLink",
                "readable canary outside");
    }

    // Harm: an unauthenticated watcher can receive private board data.
    @Test
    void detectsWatchCredential() {
        provesFailure(
                WatchCredential.class,
                "aRefusedWatchDoesNotBlockOtherCalls",
                "Expected refused watch within");
    }

    // Harm: an unfinished refused watch can occupy a server worker indefinitely.
    @Test
    void detectsWatchRefusalHang() {
        provesFailure(
                WatchRefusalHang.class,
                "aRefusedWatchDoesNotBlockOtherCalls",
                "Expected refused watch within");
    }

    // Harm: a blocked initial watch fetch can hang its caller indefinitely.
    @Test
    void detectsWatchFetchHang() {
        provesFailure(
                WatchFetchHang.class,
                "aStalledWatchDoesNotBlockOtherCalls",
                "Expected stalled watch baseline or refusal within");
    }

    // Harm: warming a cache can conceal store access before capability validation.
    @Test
    void detectsAccessBeforeCapability() {
        provesFailure(
                AccessBeforeCapability.class,
                "refusesUndeclaredCapabilitiesBeforeStoreAccess",
                "unsupported capability must cause no store access");
    }

    // Harm: warming a cache can conceal store access before credential validation.
    @Test
    void detectsAccessBeforeCredential() {
        provesFailure(
                AccessBeforeCredential.class,
                "refusesAnUndeclaredCredentialKindBeforeStoreAccess",
                "unsupported credential kind must cause no store access");
    }

    // Harm: omitting watch support must not exempt writers from lost-update checks.
    @Test
    void detectsParallelWithoutWatch() {
        provesFailure(
                ParallelWithoutWatch.class,
                "allowsExactlyOneParallelWriteBesideARefresh",
                "exactly one parallel write wins");
    }

    // Harm: omitting watch support must not exempt initial reads from credential containment.
    @Test
    void detectsRedirectWithoutWatch() {
        provesFailure(
                RedirectWithoutWatch.class,
                "neverForwardsAuthorizationToAnotherHost",
                "Authorization must never follow");
    }

    // Harm: other calls succeeding after a watch closes prove no concurrent availability.
    @Test
    void detectsClosedWatch() {
        provesFailure(
                ClosedWatch.class, "answersEveryOtherCallWhileAWatchIsOpen", "watch remains open");
    }

    // Harm: missing write notifications leave subscribers using an obsolete revision.
    @Test
    void detectsSilentWrites() {
        provesFailure(
                SilentWrites.class,
                "answersEveryOtherCallWhileAWatchIsOpen",
                "watch delivers a change");
    }

    // Harm: an issue write based on stale planning data can overwrite newer work.
    @Test
    void detectsIssueIgnoresStory() {
        provesFailure(
                IssueIgnoresStory.class,
                "refusesAnIssueWriteAfterAnExternalStoryChange",
                "asked for a named adapter refusal");
    }

    // Harm: a cached story revision can permit overwriting an external story change.
    @Test
    void detectsStoryIgnoresStory() {
        provesFailure(
                StoryIgnoresStory.class,
                "refusesAStoryWriteAfterAnExternalStoryChange",
                "asked for a named adapter refusal");
    }

    // Harm: a story write still depends on changes in every issue repository.
    @Test
    void detectsStoryIgnoresIssue() {
        provesFailure(
                StoryIgnoresIssue.class,
                "refusesAStoryWriteAfterAnExternalIssueChange",
                "asked for a named adapter refusal");
    }

    // Harm: returning from a call does not prove its helper processes were terminated.
    @Test
    void detectsOrphanChild() {
        provesFailure(
                OrphanChild.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "adapter and every observed descendant must exit");
    }

    // Harm: reason mismatches can disclose credentials before later leak checks run.
    @Test
    void detectsWrongReasonToken() {
        provesFailure(
                WrongReasonToken.class,
                "keepsTheTokenOutOfRefusedPushesAndFiles",
                "answer or refusal must not disclose");
    }

    // Harm: unrecognized refusal details can leak through assertion diagnostics.
    @Test
    void detectsGenericTokenError() {
        provesFailure(
                GenericTokenError.class,
                "keepsTheTokenOutOfRefusedPushesAndFiles",
                "answer or refusal must not disclose");
    }

    // Harm: a read can run a host reference hook even when no commit hook runs.
    @Test
    void detectsHostReferenceHook() {
        provesFailure(
                HostReferenceHook.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "host Git configuration and containment attempts");
    }

    // Harm: repeating a story can overwrite an earlier move inside one atomic batch.
    @Test
    void detectsDuplicateStory() {
        provesFailure(
                DuplicateStory.class,
                "refusesDuplicateStoryIdsWithoutWriting",
                "asked for a named adapter refusal");
    }

    // Harm: mixed resource kinds must not permit publication before validation completes.
    @Test
    void detectsMixedHalfBatch() {
        provesFailure(
                MixedHalfBatch.class,
                "writesStoriesAndIssuesInOneAtomicBatch",
                "refused batch must leave every authoritative store unchanged");
    }

    // Harm: teardown must preserve the process leak assertion even if fixture verification also
    // fails.
    @Test
    void retainsThePrimaryFailureWhenCleanupAlsoFails() {
        provesFailure(
                OrphanWithFailedCleanup.class,
                "keepsTheTokenOutOfSuccessfulAnswersAndFiles",
                "adapter and every observed descendant must exit");
    }

    static final class OrphanWithFailedCleanup extends Faulty {
        OrphanWithFailedCleanup() {
            super("orphan-child");
        }

        @Override
        protected Fixture fixture(Path root) {
            return new FileFixture() {
                @Override
                public void verify() throws Exception {
                    throw new java.io.IOException("Injected cleanup failure");
                }
            };
        }
    }

    // Harm: dropping the watch capability must not skip concurrent write serialization.
    @Test
    void executesParallelWritesWithoutTheWatchCapability() {
        final var listener = new SummaryGeneratingListener();
        LauncherFactory.create()
                .execute(
                        LauncherDiscoveryRequestBuilder.request()
                                .selectors(
                                        selectMethod(
                                                WritesWithoutWatch.class,
                                                "allowsExactlyOneParallelWriteBesideARefresh"))
                                .configurationParameter(
                                        "junit.jupiter.execution.parallel.enabled", "false")
                                .build(),
                        listener);
        final var summary = listener.getSummary();
        assertThat(summary.getTestsFoundCount())
                .as("the write-only parallel case is discovered")
                .isEqualTo(1);
        assertThat(summary.getTestsAbortedCount())
                .as("missing watch never skips parallel writes")
                .isZero();
        assertThat(summary.getTestsSucceededCount())
                .as("a serialized writer passes without watch support")
                .isEqualTo(1);
        assertThat(summary.getFailures())
                .as("the valid no-watch reference has no failures")
                .isEmpty();
    }

    static final class WritesWithoutWatch extends Faulty {
        WritesWithoutWatch() {
            super("");
        }

        @Override
        protected Map<String, String> adapterProperties() {
            return Map.of("planning.contract.capabilities", "write,issues");
        }
    }

    // Harm: a writer without watch must still be checked for partial publication to real remotes.
    @Test
    void detectsPartialPublicationWithoutWatch() {
        provesFailure(
                PartialPublicationWithoutWatch.class,
                "leavesBothRemotesUnchangedWhenTheSecondPushIsRefused",
                "refused batch must leave every authoritative store unchanged");
    }

    static final class PartialPublicationWithoutWatch extends Faulty {
        PartialPublicationWithoutWatch() {
            super("half-push-no-watch");
        }

        @Override
        protected Fixture fixture(Path root) throws Exception {
            return new RefusingGitFixture(root);
        }

        @Override
        protected Map<String, String> adapterProperties() {
            return Map.of(
                    "planning.contract.fault",
                    "half-push-no-watch",
                    "planning.contract.capabilities",
                    "write,issues");
        }
    }

    // Harm: selecting both kinds can move the wrong resource.
    @Test
    void detectsAcceptsBothSelectors() {
        provesFailure(
                AcceptsBothSelectors.class,
                "refusesAmbiguousOrMissingStateChangeSelectorsWithoutWriting",
                "invalid selectors require");
    }

    static final class AcceptsBothSelectors extends Faulty {
        AcceptsBothSelectors() {
            super("accept-both-selectors");
        }
    }

    // Harm: a missing selector can conceal an already published preceding change.
    @Test
    void detectsAcceptsNeitherSelector() {
        provesFailure(
                AcceptsNeitherSelector.class,
                "refusesAmbiguousOrMissingStateChangeSelectorsWithoutWriting",
                "invalid selectors require");
    }

    static final class AcceptsNeitherSelector extends Faulty {
        AcceptsNeitherSelector() {
            super("accept-neither-selectors");
        }
    }

    // Harm: dropping stored priority can change the order in which work is chosen.
    @Test
    void detectsDropsPriority() {
        provesFailure(
                DropsPriority.class,
                "answersStoriesWithTheirStoredStatesAndDescriptions",
                "all listed stories retain");
    }

    static final class DropsPriority extends Faulty {
        DropsPriority() {
            super("drop-priority");
        }
    }

    // Harm: intermediate revisions expose a mixed batch only partly applied.
    @Test
    void detectsSplitsMixedBatch() {
        provesFailure(
                SplitsMixedBatch.class,
                "writesStoriesAndIssuesInOneAtomicBatch",
                "mixed batch publishes its single final revision");
    }

    static final class SplitsMixedBatch extends Faulty {
        SplitsMixedBatch() {
            super("split-batch");
        }
    }

    // Harm: accepting safe redirect refusals must still prove the remote issued a redirect.
    @Test
    void detectsRedirectChecksThatNeverReachTheRemote() {
        provesFailure(
                RedirectWithoutTraffic.class,
                "neverForwardsAuthorizationToAnotherHost",
                "source must actually issue");
    }

    static final class RedirectWithoutTraffic extends Faulty {
        RedirectWithoutTraffic() {
            super("redirect-pre-store-failure");
        }

        @Override
        protected Fixture fixture(Path root) throws Exception {
            return new RemoteFileFixture();
        }
    }

    // Harm: forcing a credential-safe adapter to follow redirects weakens its containment policy.
    @Test
    void acceptsARefusalToFollowAnObservedRedirect() {
        final var listener = new SummaryGeneratingListener();
        LauncherFactory.create()
                .execute(
                        LauncherDiscoveryRequestBuilder.request()
                                .selectors(
                                        selectMethod(
                                                RefusesRedirect.class,
                                                "neverForwardsAuthorizationToAnotherHost"))
                                .build(),
                        listener);
        assertThat(listener.getSummary().getTestsFoundCount())
                .as("the redirect refusal case is discovered")
                .isEqualTo(1);
        assertThat(listener.getSummary().getTestsAbortedCount())
                .as("redirect refusal is tested without skips")
                .isZero();
        assertThat(listener.getSummary().getTestsSucceededCount())
                .as("safe refusal passes after a real redirect")
                .isEqualTo(1);
        assertThat(listener.getSummary().getTotalFailureCount())
                .as("safe redirect refusal has no failures")
                .isZero();
    }

    static final class RefusesRedirect extends Faulty {
        RefusesRedirect() {
            super("refuse-redirect");
        }

        @Override
        protected Fixture fixture(Path root) throws Exception {
            return new RemoteFileFixture();
        }
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
        assertThat(summary.getFailures().size())
                .as("the failure must be an assertion about the intended fault")
                .isEqualTo(1);
        final Throwable failure = summary.getFailures().getFirst().getException();
        assertThat(failure instanceof AssertionError)
                .as(
                        "startup and fixture failures are not red proof; got %s",
                        failure.getClass().getName())
                .isTrue();
        assertThat(failure.getMessage() != null && failure.getMessage().contains(message))
                .as("the fault must fail the intended assertion: %s", message)
                .isTrue();
    }

    abstract static class Faulty extends PlanningContract {
        private final String fault;

        Faulty(String fault) {
            super(ReferencePlanning.class);
            this.fault = fault;
        }

        @Override
        protected Fixture fixture(Path root) throws Exception {
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

    static final class ChildArguments extends Faulty {
        ChildArguments() {
            super("child-arguments");
        }
    }

    static final class ChildEnvironment extends Faulty {
        ChildEnvironment() {
            super("child-environment");
        }
    }

    static final class ChildStderr extends Faulty {
        ChildStderr() {
            super("child-stderr");
        }
    }

    static final class Base64File extends Faulty {
        Base64File() {
            super("base64-file");
        }
    }

    static final class TokenFilename extends Faulty {
        TokenFilename() {
            super("token-filename");
        }
    }

    static final class TokenLink extends Faulty {
        TokenLink() {
            super("token-link");
        }
    }

    static final class RuntimeToken extends Faulty {
        RuntimeToken() {
            super("runtime-token");
        }
    }

    static final class StoryTraversalRead extends Faulty {
        StoryTraversalRead() {
            super("traversal-story-read");
        }
    }

    static final class IssueTraversalRead extends Faulty {
        IssueTraversalRead() {
            super("traversal-issue-read");
        }
    }

    static final class StoryTraversalWrite extends Faulty {
        StoryTraversalWrite() {
            super("traversal-story-write");
        }
    }

    static final class IssueTraversalWrite extends Faulty {
        IssueTraversalWrite() {
            super("traversal-issue-write");
        }
    }

    static final class SymlinkWrite extends Faulty {
        SymlinkWrite() {
            super("symlink-write");
        }
    }

    static final class WatchCredential extends Faulty {
        WatchCredential() {
            super("watch-ignores-credential");
        }
    }

    static final class WatchRefusalHang extends Faulty {
        WatchRefusalHang() {
            super("watch-hangs-on-refusal");
        }
    }

    static final class WatchFetchHang extends Faulty {
        WatchFetchHang() {
            super("watch-fetch-hangs");
        }

        @Override
        protected Fixture fixture(Path root) throws Exception {
            return new RemoteFileFixture();
        }
    }

    static final class AccessBeforeCapability extends Faulty {
        AccessBeforeCapability() {
            super("access-before-capability");
        }

        @Override
        protected Map<String, String> adapterProperties() {
            final Map<String, String> properties =
                    new java.util.LinkedHashMap<>(super.adapterProperties());
            properties.put("planning.contract.capabilities", "write,watch");
            return properties;
        }
    }

    static final class AccessBeforeCredential extends Faulty {
        AccessBeforeCredential() {
            super("access-before-credential");
        }
    }

    static final class ParallelWithoutWatch extends Faulty {
        ParallelWithoutWatch() {
            super("ignore-revision");
        }

        @Override
        protected Map<String, String> adapterProperties() {
            final Map<String, String> properties =
                    new java.util.LinkedHashMap<>(super.adapterProperties());
            properties.put("planning.contract.capabilities", "write,issues");
            return properties;
        }
    }

    static final class RedirectWithoutWatch extends Faulty {
        RedirectWithoutWatch() {
            super("redirect-auth");
        }

        @Override
        protected Fixture fixture(Path root) throws Exception {
            return new RemoteFileFixture();
        }

        @Override
        protected Map<String, String> adapterProperties() {
            final Map<String, String> properties =
                    new java.util.LinkedHashMap<>(super.adapterProperties());
            properties.put("planning.contract.capabilities", "write,issues");
            return properties;
        }
    }

    static final class ClosedWatch extends Faulty {
        ClosedWatch() {
            super("closed-watch");
        }
    }

    static final class SilentWrites extends Faulty {
        SilentWrites() {
            super("silent-writes");
        }
    }

    static final class IssueIgnoresStory extends Faulty {
        IssueIgnoresStory() {
            super("issue-ignores-story");
        }
    }

    static final class StoryIgnoresStory extends Faulty {
        StoryIgnoresStory() {
            super("story-ignores-story");
        }
    }

    static final class StoryIgnoresIssue extends Faulty {
        StoryIgnoresIssue() {
            super("story-ignores-issue");
        }
    }

    static final class OrphanChild extends Faulty {
        OrphanChild() {
            super("orphan-child");
        }
    }

    static final class WrongReasonToken extends Faulty {
        WrongReasonToken() {
            super("wrong-reason-token");
        }
    }

    static final class GenericTokenError extends Faulty {
        GenericTokenError() {
            super("generic-token-error");
        }
    }

    static final class HostReferenceHook extends Faulty {
        HostReferenceHook() {
            super("host-hook");
        }
    }

    static final class DuplicateStory extends Faulty {
        DuplicateStory() {
            super("duplicate-story");
        }
    }

    static final class MixedHalfBatch extends Faulty {
        MixedHalfBatch() {
            super("half-batch");
        }
    }
}
