package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.planning.api.Change;
import org.fuin.porelo.planning.api.PlanningAdapter;
import org.fuin.porelo.planning.api.PlanningProtocol;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.fuin.porelo.planning.api.StateChange;
import org.fuin.porelo.planning.api.StorySummary;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Published Planning1 behavioral contract. Extend with a main class and a fresh store fixture;
 * every test launches the real executable through InstalledPlanning in its own directories. Missing
 * fixture abilities and undeclared optional adapter capabilities are named JUnit skips.
 */
public abstract class PlanningContract {
    private final Class<?> main;
    @TempDir Path scratch;
    private ContractSession session;

    /** Selects the adapter main that accepts describe and serve socket directory. */
    protected PlanningContract(Class<?> main) {
        this.main = java.util.Objects.requireNonNull(main);
    }

    /** Creates a fresh owned store beneath the supplied directory, without seeding it yet. */
    protected abstract Fixture fixture(Path root) throws Exception;

    /** Supplies the same test scenarios using the adapter's representable kinds and web links. */
    protected Board board() {
        return Board.standard();
    }

    /**
     * Supplies nonsecret JVM fault switches for a test adapter; production adapter tests use none.
     */
    protected Map<String, String> adapterProperties() {
        return Map.of();
    }

    @BeforeEach
    final void startAdapter() throws Exception {
        session =
                new ContractSession(
                        scratch,
                        fixture(Files.createDirectory(scratch.resolve("fixture"))),
                        main,
                        adapterProperties(),
                        board());
    }

    @AfterEach
    final void stopAdapter() throws Exception {
        if (session != null) session.close();
    }

    // Harm: invented workflow categories or order can authorize work in the wrong state.
    @Test
    protected final void answersTheWorkflowExactlyAsStored() throws Exception {
        assertThat(call(() -> session.client.getWorkflow(settings(), token())).value())
                .as("workflow states, kinds, renamed state, order and phases must match the store")
                .isEqualTo(session.board.workflow());
    }

    // Harm: computing a story state in the adapter mixes revisions and changes the board contract.
    @Test
    protected final void answersStoriesWithTheirStoredStatesAndDescriptions() throws Exception {
        final var stories = call(() -> session.client.listStories(settings(), token(), null));
        assertThat(
                        stories.value().stream()
                                .map(s -> List.of(s.id(), s.title(), s.state(), s.link()))
                                .toList())
                .as("all listed stories retain their stored states")
                .containsExactlyInAnyOrderElementsOf(
                        session.board.stories().stream()
                                .map(s -> List.of(s.id(), s.title(), s.state(), s.link()))
                                .toList());
        for (final var expected : session.board.stories())
            assertThat(call(() -> session.client.getStory(settings(), token(), expected.id())))
                    .as("GetStory returns the stored description at the listing revision")
                    .isEqualTo(new PlanningAdapter.Result<>(expected, stories.revision()));
        assertThat(call(() -> session.client.listStories(settings(), token(), List.of())).value())
                .as("an empty stored-state filter matches no stories")
                .isEmpty();
        assertThat(
                        call(
                                        () ->
                                                session.client.listStories(
                                                        settings(), token(), List.of("Queued")))
                                .value()
                                .stream()
                                .map(StorySummary::id)
                                .toList())
                .as("stored-state filtering must not compute S1's state from its issues")
                .containsExactly("S2");
    }

    // Harm: missing snapshots can make a board treat stories with issues as independently writable.
    @Test
    protected final void listsEveryStoryWithItsCompleteIssueSnapshot() throws Exception {
        requires("issues");
        final var listing = call(() -> session.client.listStories(settings(), token(), null));
        assertThat(listing.value())
                .as("listing includes every story with all issue summaries at one revision")
                .containsExactlyInAnyOrderElementsOf(session.board.summaries());
        for (final var story : listing.value()) {
            assertThat(story.issues())
                    .as(
                            "issues-capable adapters supply a non-null snapshot, including empty"
                                    + " stories")
                    .isNotNull();
            assertThat(call(() -> session.client.listStoryIssues(settings(), token(), story.id())))
                    .as("ListStoryIssues agrees with the listing's revision and complete summaries")
                    .isEqualTo(
                            new PlanningAdapter.Result<>(
                                    java.util.Objects.requireNonNull(story.issues()),
                                    listing.revision()));
        }
    }

    // Harm: an empty answer for an unknown id can erase the caller's cached story.
    @Test
    protected final void refusesAnUnknownStoryByName() throws Exception {
        assertThat(
                        refused(
                                        Reason.NO_SUCH_STORY,
                                        () ->
                                                session.client.getStory(
                                                        settings(), token(), "absent-story"))
                                .parameters())
                .as("NoSuchStory identifies the requested story")
                .containsEntry("story", "absent-story");
    }

    // Harm: an empty answer for an unknown id can erase the caller's cached issue.
    @Test
    protected final void refusesAnUnknownIssueByName() throws Exception {
        requires("issues");
        assertThat(
                        refused(
                                        Reason.NO_SUCH_ISSUE,
                                        () ->
                                                session.client.getIssue(
                                                        settings(), token(), "absent-issue"))
                                .parameters())
                .as("NoSuchIssue identifies the requested issue")
                .containsEntry("issue", "absent-issue");
    }

    // Harm: undeclared operations can access the store without a negotiated capability.
    @Test
    protected final void refusesUndeclaredCapabilitiesBeforeStoreAccess() throws Exception {
        ability(Fixture.Ability.credentialKind);
        final var capabilities = session.client.describe().capabilities();
        assumeTrue(
                !capabilities.containsAll(List.of("issues", "write", "watch")),
                "Missing ability undeclaredCapability: Describe declares all optional"
                        + " capabilities");
        final String current = revision();
        final long before = session.fixture.accesses();
        if (!capabilities.contains("issues")) {
            unsupported("issues", () -> session.client.getIssue(settings(), token(), "I1"));
            unsupported("issues", () -> session.client.listStoryIssues(settings(), token(), "S1"));
            if (capabilities.contains("write"))
                unsupported(
                        "issues",
                        () ->
                                session.client.setIssueStates(
                                        settings(), token(), List.of(), current));
        }
        if (!capabilities.contains("write")) {
            unsupported(
                    "write",
                    () ->
                            session.client.setStoryState(
                                    settings(), token(), "S2", "Closed", current));
            if (capabilities.contains("issues"))
                unsupported(
                        "write",
                        () ->
                                session.client.setIssueStates(
                                        settings(), token(), List.of(), current));
        }
        if (!capabilities.contains("watch")) {
            unsupported("watch", () -> session.client.refresh(settings(), token()));
            unsupported(
                    "watch",
                    () -> {
                        session.client.watchChanges(settings(), token(), c -> false);
                        return null;
                    });
        }
        assertThat(session.fixture.accesses())
                .as("unsupported capability must cause no store access")
                .isEqualTo(before);
    }

    // Harm: interpreting a credential as another kind can send it to the wrong service.
    @Test
    protected final void refusesAnUndeclaredCredentialKindBeforeStoreAccess() throws Exception {
        ability(Fixture.Ability.credentialKind);
        final String kind = "unsupported-credential";
        assertThat(session.client.describe().credentials())
                .as("the negative credential kind is absent from Describe")
                .doesNotContain(kind);
        revision();
        final long before = session.fixture.accesses();
        final var settings = session.fixture.settingsForCredentialKind(kind);
        refused(Reason.NOT_SUPPORTED, () -> session.client.getWorkflow(settings, token()));
        assertThat(session.fixture.accesses())
                .as("unsupported credential kind must cause no store access")
                .isEqualTo(before);
    }

    // Harm: accepting a previously valid revision overwrites a newer write.
    @Test
    protected final void refusesAnOldRevisionWithoutChangingAnything() throws Exception {
        writableIssues();
        final String old = revision();
        final String current = write(List.of(new StateChange("I1", "Building renamed")), old);
        assertThat(current)
                .as("a successful state change publishes a new revision")
                .isNotEqualTo(old);
        final var before = snapshot();
        final var reads = listing();
        final var error =
                refused(
                        Reason.STALE_REVISION,
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        token(),
                                        List.of(new StateChange("I2", "Closed")),
                                        old));
        assertThat(error.parameters())
                .as("stale revision refusal carries the current revision")
                .containsEntry("revision", current);
        unchanged(before, reads);
    }

    // Harm: stale story writes can overwrite a newer independently stored state.
    @Test
    protected final void writesAnIndependentStoryOnlyAgainstItsCurrentRevision() throws Exception {
        requires("write");
        final String old = revision();
        final String current =
                call(
                        () ->
                                session.client.setStoryState(
                                        settings(), token(), "S2", "Building renamed", old));
        assertThat(current)
                .as("an independent story write advances the board revision")
                .isNotEqualTo(old);
        assertThat(call(() -> session.client.getStory(settings(), token(), "S2")).value())
                .as("a successful story write is visible with the complete stored story")
                .isEqualTo(session.board.withStoryState("S2", "Building renamed").stories().get(1));
        final var before = snapshot();
        final var reads = listing();
        assertThat(
                        refused(
                                        Reason.STALE_REVISION,
                                        () ->
                                                session.client.setStoryState(
                                                        settings(), token(), "S2", "Closed", old))
                                .parameters())
                .as("stale story writes carry the current revision")
                .containsEntry("revision", current);
        unchanged(before, reads);
    }

    // Harm: a pre-write fetch that ignores a remote change can overwrite work not in its cache.
    @Test
    protected final void refusesAChangeBehindItsBackOnTheWrittenRepository() throws Exception {
        staleBehindTheBack("I1");
    }

    // Harm: a revision limited to the written repository misses changes elsewhere in the project.
    @Test
    protected final void refusesAChangeBehindItsBackOnAnotherRepository() throws Exception {
        ability(Fixture.Ability.gitStore);
        staleBehindTheBack("I2");
    }

    // Harm: accepting an unknown workflow state can authorize work under invented semantics.
    @Test
    protected final void validatesEveryStateBeforeWritingAnyChange() throws Exception {
        writableIssues();
        final var before = snapshot();
        final var reads = listing();
        final var error =
                refused(
                        Reason.STATE_NOT_ALLOWED,
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        token(),
                                        List.of(
                                                new StateChange("I1", "Closed"),
                                                new StateChange("I2", "not-a-workflow-state")),
                                        reads.revision()));
        assertThat(error.parameters())
                .as("state refusal identifies the invalid second item and target")
                .containsEntry("item", "I2")
                .containsEntry("to", "not-a-workflow-state");
        assertThat(error.parameters().get("reason"))
                .as("state refusal explains why the target is refused")
                .isInstanceOfSatisfying(
                        String.class,
                        text ->
                                assertThat(text)
                                        .as("state refusal reason is nonblank")
                                        .isNotBlank());
        unchanged(before, reads);
    }

    // Harm: incremental validation can publish a batch before an unknown issue fails.
    @Test
    protected final void validatesEveryIssueBeforeWritingAnyChange() throws Exception {
        writableIssues();
        final var before = snapshot();
        final var reads = listing();
        refused(
                Reason.NO_SUCH_ISSUE,
                () ->
                        session.client.setIssueStates(
                                settings(),
                                token(),
                                List.of(
                                        new StateChange("I1", "Closed"),
                                        new StateChange("absent-issue", "Closed")),
                                reads.revision()));
        unchanged(before, reads);
    }

    // Harm: applying an issue twice can lose an earlier transition in a supposedly atomic batch.
    @Test
    protected final void refusesDuplicateIssueIdsWithoutWriting() throws Exception {
        writableIssues();
        final var before = snapshot();
        final var reads = listing();
        final var error =
                refused(
                        Reason.STATE_NOT_ALLOWED,
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        token(),
                                        List.of(
                                                new StateChange("I1", "Closed"),
                                                new StateChange("I1", "Building renamed")),
                                        reads.revision()));
        assertThat(error.parameters())
                .as("duplicate refusal identifies the issue and the specified reason")
                .containsEntry("item", "I1")
                .containsEntry("reason", "Duplicate issue");
        unchanged(before, reads);
    }

    // Harm: independently writing a story with issues bypasses the board's issue-derived state.
    @Test
    protected final void refusesAStoryWithIssuesWithoutWriting() throws Exception {
        writableIssues();
        final var before = snapshot();
        final var reads = listing();
        assertThat(
                        refused(
                                        Reason.STORY_HAS_ISSUES,
                                        () ->
                                                session.client.setStoryState(
                                                        settings(),
                                                        token(),
                                                        "S1",
                                                        "Closed",
                                                        reads.revision()))
                                .parameters())
                .as("a story with issues has no independently writable state")
                .containsEntry("story", "S1");
        unchanged(before, reads);
    }

    // Harm: an empty batch can create a spurious revision or bypass stale revision validation.
    @Test
    protected final void keepsTheRevisionAndStoreForAnEmptyBatch() throws Exception {
        writableIssues();
        final var before = snapshot();
        final String current = revision();
        assertThat(write(List.of(), current))
                .as("empty batch keeps the current revision")
                .isEqualTo(current);
        assertThat(snapshot()).as("empty batch writes no store object or commit").isEqualTo(before);
        final String next = write(List.of(new StateChange("I1", "Closed")), current);
        assertThat(
                        refused(
                                        Reason.STALE_REVISION,
                                        () ->
                                                session.client.setIssueStates(
                                                        settings(), token(), List.of(), current))
                                .parameters())
                .as("even empty batches validate the revision")
                .containsEntry("revision", next);
    }

    // Harm: a refused second push can leave a partial change in remotes or local reads.
    @Test
    protected final void leavesBothRemotesUnchangedWhenTheSecondPushIsRefused() throws Exception {
        writableIssues();
        ability(Fixture.Ability.gitStore, Fixture.Ability.refusePush);
        final var before = snapshot();
        final var reads = listing();
        session.fixture.refuseSecondPush();
        refused(
                Reason.CREDENTIAL_REFUSED,
                () ->
                        session.client.setIssueStates(
                                settings(),
                                token(),
                                List.of(
                                        new StateChange("I1", "Closed"),
                                        new StateChange("I2", "Closed")),
                                reads.revision()));
        session.fixture.verifySecondPushRefused();
        unchanged(before, reads);
        requires("watch");
        call(() -> session.client.refresh(settings(), token()));
        unchanged(before, reads);
    }

    // Harm: simultaneous writes against one revision can both succeed and overwrite each other.
    @Test
    protected final void allowsExactlyOneParallelWriteBesideARefresh() throws Exception {
        writableIssues();
        requires("watch");
        final String current = revision();
        final VarlinkClient first = session.connection();
        final VarlinkClient second = session.connection();
        final VarlinkClient refreshing = session.connection();
        final CountDownLatch ready = new CountDownLatch(3);
        final CountDownLatch go = new CountDownLatch(1);
        final Future<Object> a = session.submit(() -> racedWrite(first, "I1", current, ready, go));
        final Future<Object> b = session.submit(() -> racedWrite(second, "I2", current, ready, go));
        final Future<?> refresh =
                session.submit(
                        () -> {
                            startTogether(ready, go);
                            return refreshing.call(PlanningProtocol.REFRESH, parameters());
                        });
        assertThat(ready.await(ContractSession.BOUND_SECONDS, TimeUnit.SECONDS))
                .as("all three independent callers reach the start barrier")
                .isTrue();
        go.countDown();
        final List<Object> outcomes =
                List.of(
                        ContractSession.await("first parallel write", a),
                        ContractSession.await("second parallel write", b));
        ContractSession.await("parallel Refresh", refresh);
        assertThat(outcomes.stream().filter(String.class::isInstance).count())
                .as("exactly one parallel write wins")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(PlanningRefused.class::isInstance).count())
                .as("exactly one parallel write is stale")
                .isEqualTo(1);
        final PlanningRefused loser =
                (PlanningRefused)
                        outcomes.stream()
                                .filter(PlanningRefused.class::isInstance)
                                .findFirst()
                                .orElseThrow();
        final String winner =
                (String)
                        outcomes.stream()
                                .filter(String.class::isInstance)
                                .findFirst()
                                .orElseThrow();
        assertThat(loser.reason())
                .as("losing concurrent writer gets StaleRevision")
                .isEqualTo(Reason.STALE_REVISION);
        assertThat(loser.parameters())
                .as("loser receives winner's current revision")
                .containsEntry("revision", winner);
        final String winningIssue = outcomes.getFirst() instanceof String ? "I1" : "I2";
        assertThat(listing().value())
                .as("only the winning change is visible")
                .containsExactlyInAnyOrderElementsOf(
                        session.board.withIssueState(winningIssue, "Closed").summaries());
        call(() -> session.client.refresh(settings(), token()));
        assertThat(listing().value())
                .as("authoritative remotes also contain only the winning change")
                .containsExactlyInAnyOrderElementsOf(
                        session.board.withIssueState(winningIssue, "Closed").summaries());
    }

    // Harm: retaining the first call's credential grants later calls access with a refused token.
    @Test
    protected final void usesOnlyTheCredentialOfEachCall() throws Exception {
        writableIssues();
        ability(Fixture.Ability.refuseCredential);
        final String current =
                write(List.of(new StateChange("I1", "Building renamed")), revision());
        final var before = snapshot();
        final var reads = listing();
        final String rejected = rejectedToken();
        final var refusal =
                refused(
                        Reason.CREDENTIAL_REFUSED,
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        rejected,
                                        List.of(new StateChange("I2", "Closed")),
                                        current));
        session.noSecret(refusal.parameters(), rejected);
        unchanged(before, reads);
    }

    // Harm: forwarding Authorization on a redirect discloses a credential to a different host.
    @Test
    protected final void neverForwardsAuthorizationToAnotherHost() throws Exception {
        requires("watch");
        ability(Fixture.Ability.redirect);
        revision();
        session.fixture.redirect();
        fails(() -> session.client.refresh(settings(), token()));
        session.fixture.verifyRedirect();
    }

    // Harm: a successful write can leave a reusable credential in output, helpers or Git files.
    @Test
    protected final void keepsTheTokenOutOfSuccessfulAnswersAndFiles() throws Exception {
        writableIssues();
        final String current = write(List.of(new StateChange("I1", "Closed")), revision());
        final List<Object> answers = new ArrayList<>();
        answers.add(current);
        answers.add(call(() -> session.client.getWorkflow(settings(), token())));
        answers.add(listing());
        answers.add(call(() -> session.client.getStory(settings(), token(), "S1")));
        answers.add(call(() -> session.client.listStoryIssues(settings(), token(), "S1")));
        answers.add(call(() -> session.client.getIssue(settings(), token(), "I1")));
        if (session.client.describe().capabilities().contains("watch")) {
            answers.add(call(() -> session.client.refresh(settings(), token())));
            final Watch watch = watch(token());
            answers.add(take(watch.changes));
        }
        session.noSecret(answers, token());
    }

    // Harm: a refused push can echo remote diagnostics or persist the credential used to retry it.
    @Test
    protected final void keepsTheTokenOutOfRefusedPushesAndFiles() throws Exception {
        writableIssues();
        ability(Fixture.Ability.refusePush);
        final String current = revision();
        final var before = snapshot();
        session.fixture.refusePush("one");
        final PlanningRefused refusal =
                refused(
                        Reason.CREDENTIAL_REFUSED,
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        token(),
                                        List.of(new StateChange("I1", "Closed")),
                                        current));
        session.noSecret(refusal.parameters(), token());
        assertThat(snapshot()).as("a refused push must not publish a change").isEqualTo(before);
    }

    // Harm: path traversal in story ids can read or overwrite files outside the project store.
    @Test
    protected final void refusesUnsafeStoryIdsWithoutTouchingTheCanary() throws Exception {
        final var before = snapshot();
        final String current = revision();
        for (final String id : List.of("..", "S1/child", "../canary", "S1/../../canary")) {
            assertThat(
                            fails(() -> session.client.getStory(settings(), token(), id))
                                    .parameters()
                                    .toString())
                    .as("unsafe story refusal names the requested id")
                    .contains(id);
            if (session.client.describe().capabilities().contains("write"))
                fails(
                        () ->
                                session.client.setStoryState(
                                        settings(), token(), id, "Closed", current));
        }
        assertThat(snapshot()).as("unsafe story ids change no remote").isEqualTo(before);
        session.canary.verifyUntouched();
    }

    // Harm: path traversal in issue ids can read or overwrite files outside the project store.
    @Test
    protected final void refusesUnsafeIssueIdsWithoutTouchingTheCanary() throws Exception {
        requires("issues");
        final var before = snapshot();
        final String current = revision();
        for (final String id : List.of("..", "I1/child", "../canary", "I1/../../canary")) {
            assertThat(
                            fails(() -> session.client.getIssue(settings(), token(), id))
                                    .parameters()
                                    .toString())
                    .as("unsafe issue refusal names the requested id")
                    .contains(id);
            if (session.client.describe().capabilities().contains("write"))
                fails(
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        token(),
                                        List.of(new StateChange(id, "Closed")),
                                        current));
        }
        assertThat(snapshot()).as("unsafe issue ids change no remote").isEqualTo(before);
        session.canary.verifyUntouched();
    }

    // Harm: following an issue symlink can expose or overwrite a host file.
    @Test
    protected final void refusesAnIssueFileThatIsASymbolicLink() throws Exception {
        damaged(Fixture.Damage.issueSymlink);
    }

    // Harm: a missing listed brief can start a sub-agent without its instructions.
    @Test
    protected final void refusesAMissingListedBriefByName() throws Exception {
        damaged(Fixture.Damage.missingBrief);
    }

    // Harm: following a brief symlink can deliver a host file to a sub-agent.
    @Test
    protected final void refusesAListedBriefThatIsASymbolicLink() throws Exception {
        damaged(Fixture.Damage.symlinkBrief);
    }

    // Harm: a listed path outside .sub can disclose unrelated files as sub-agent instructions.
    @Test
    protected final void refusesAListedBriefOutsideItsSubDirectory() throws Exception {
        damaged(Fixture.Damage.outsideBrief);
    }

    // Harm: globbing brief files can deliver private files never approved as sub-agent proposals.
    @Test
    protected final void neverDeliversAnUnlistedBrief() throws Exception {
        requires("issues");
        ability(Fixture.Ability.unlistedBrief);
        session.fixture.damage(Fixture.Damage.unlistedBrief, session.canary.marker());
        refreshIfSupported();
        assertThat(
                        call(() -> session.client.getIssue(settings(), token(), "I1"))
                                .value()
                                .subAgents())
                .as("only explicitly listed proposals are delivered")
                .isEqualTo(session.board.issue("I1").subAgents());
    }

    // Harm: truncated briefs, acceptance text or dependencies can cause incorrectly scoped work.
    @Test
    protected final void deliversCompleteIssueDetailsAndListedBriefs() throws Exception {
        requires("issues");
        for (final String id : List.of("I1", "I2"))
            assertThat(call(() -> session.client.getIssue(settings(), token(), id)).value())
                    .as(
                            "GetIssue preserves size, blockedBy, acceptance text and every complete"
                                    + " brief")
                    .isEqualTo(session.board.issue(id));
    }

    // Harm: a refused credential must not leave a caller blocked indefinitely.
    @Test
    protected final void endsAWritingCallWithARefusedCredentialWithinTheBound() throws Exception {
        writableIssues();
        ability(Fixture.Ability.refuseCredential);
        final String current = revision();
        refused(
                Reason.CREDENTIAL_REFUSED,
                () ->
                        session.client.setIssueStates(
                                settings(),
                                rejectedToken(),
                                List.of(new StateChange("I1", "Closed")),
                                current));
    }

    // Harm: a refused fetch must not block every later refresh or state change.
    @Test
    protected final void endsARefreshWithARefusedCredentialWithinTheBound() throws Exception {
        requires("watch");
        ability(Fixture.Ability.refuseCredential);
        revision();
        refused(
                Reason.CREDENTIAL_REFUSED,
                () -> session.client.refresh(settings(), rejectedToken()));
    }

    // Harm: a stalled pre-write fetch can hold the adapter's write lock forever.
    @Test
    protected final void endsAWritingCallAgainstAStalledRemoteWithinTheBound() throws Exception {
        writableIssues();
        ability(Fixture.Ability.stall);
        final String current = revision();
        session.fixture.stall();
        fails(
                () ->
                        session.client.setIssueStates(
                                settings(),
                                token(),
                                List.of(new StateChange("I1", "Closed")),
                                current));
    }

    // Harm: an unbounded refresh can hold all ordinary calls indefinitely.
    @Test
    protected final void endsARefreshAgainstAStalledRemoteWithinTheBound() throws Exception {
        requires("watch");
        ability(Fixture.Ability.stall);
        revision();
        session.fixture.stall();
        fails(() -> session.client.refresh(settings(), token()));
    }

    // Harm: a refused watch credential must not occupy a lock needed by other callers.
    @Test
    protected final void aRefusedWatchDoesNotBlockOtherCalls() throws Exception {
        requires("watch");
        ability(Fixture.Ability.refuseCredential);
        revision();
        final Watch watch = watch(rejectedToken());
        observeWaitingOrFailure(watch, PlanningRefused.class);
        call(() -> session.client.getWorkflow(settings(), token()));
        if (watch.finished.isDone()) {
            assertThat(ContractSession.await("refused watch", watch.finished))
                    .as("a watch that ends with a refused credential reports an error")
                    .isInstanceOf(PlanningRefused.class);
        }
    }

    // Harm: a stalled watch fetch must not prevent cached reads or lifecycle operations.
    @Test
    protected final void aStalledWatchDoesNotBlockOtherCalls() throws Exception {
        requires("watch");
        ability(Fixture.Ability.stall);
        revision();
        session.fixture.stall();
        final Watch watch = watch(token());
        observeWaitingOrFailure(watch, RuntimeException.class);
        assertThat(call(() -> session.client.getWorkflow(settings(), token())).value())
                .as("cached reads remain available while remote watch traffic stalls")
                .isEqualTo(session.board.workflow());
        if (watch.finished.isDone())
            assertThat(ContractSession.await("stalled watch", watch.finished))
                    .as("a stalled watch reports an error if it ends")
                    .isInstanceOf(RuntimeException.class);
    }

    // Harm: discarding or inventing a watch baseline can leave the board permanently stale.
    @Test
    protected final void startsAWatchWithTheCurrentRevision() throws Exception {
        requires("watch");
        final String current = revision();
        final Watch watch = watch(token());
        assertThat(take(watch.changes).revision())
                .as("the first watch reply carries the current read revision")
                .isEqualTo(current);
    }

    // Harm: missing refresh invalidations can leave an open board showing obsolete work.
    @Test
    protected final void refreshImmediatelyReportsAChangeBehindTheAdaptersBack() throws Exception {
        requires("watch");
        ability(Fixture.Ability.changeBehindTheBack);
        final String current = revision();
        final Watch watch = watch(token());
        assertThat(take(watch.changes).revision())
                .as("watch baseline matches the preceding read")
                .isEqualTo(current);
        session.fixture.changeStory("S2", "Closed");
        final String refreshed = call(() -> session.client.refresh(settings(), token()));
        assertThat(refreshed)
                .as("Refresh publishes a different revision after an external change")
                .isNotEqualTo(current);
        final Change change = take(watch.changes);
        assertThat(change.revision())
                .as("Refresh immediately invalidates at its new revision")
                .isEqualTo(refreshed);
        assertThat(
                        change.stories().isEmpty() && change.issues().isEmpty()
                                || change.stories().contains("S2"))
                .as("notification invalidates the changed story or the whole board")
                .isTrue();
        assertThat(call(() -> session.client.getStory(settings(), token(), "S2")).value().state())
                .as("refreshed reads expose the external change")
                .isEqualTo("Closed");
    }

    // Harm: holding a global lock for WatchChanges can deadlock reads, writes and Refresh.
    @Test
    protected final void answersEveryOtherCallWhileAWatchIsOpen() throws Exception {
        requires("watch");
        final String current = revision();
        final Watch watch = watch(token());
        assertThat(take(watch.changes).revision())
                .as("watch is open before other calls start")
                .isEqualTo(current);
        assertThat(session.client.describe().name())
                .as("Describe remains available beside a watch")
                .isNotBlank();
        call(() -> session.client.getWorkflow(settings(), token()));
        listing();
        call(() -> session.client.getStory(settings(), token(), "S2"));
        if (session.client.describe().capabilities().contains("issues")) {
            call(() -> session.client.listStoryIssues(settings(), token(), "S1"));
            call(() -> session.client.getIssue(settings(), token(), "I1"));
        }
        call(() -> session.client.refresh(settings(), token()));
        if (session.client.describe().capabilities().contains("write")) {
            final String next =
                    call(
                            () ->
                                    session.client.setStoryState(
                                            settings(),
                                            token(),
                                            "S2",
                                            "Building renamed",
                                            current));
            if (session.client.describe().capabilities().contains("issues"))
                write(List.of(new StateChange("I1", "Closed")), next);
        }
    }

    private void staleBehindTheBack(String changedIssue) throws Exception {
        writableIssues();
        ability(Fixture.Ability.changeBehindTheBack);
        final String old = revision();
        session.fixture.changeIssue(changedIssue, "Building renamed");
        final var before = snapshot();
        final var error =
                refused(
                        Reason.STALE_REVISION,
                        () ->
                                session.client.setIssueStates(
                                        settings(),
                                        token(),
                                        List.of(new StateChange("I1", "Closed")),
                                        old));
        final String current = revision();
        assertThat(current)
                .as("external change advances the complete board revision")
                .isNotEqualTo(old);
        assertThat(error.parameters())
                .as("stale refusal reports the refreshed current revision")
                .containsEntry("revision", current);
        assertThat(snapshot()).as("stale write changes no authoritative store").isEqualTo(before);
        assertThat(listing().value())
                .as("only the external change becomes visible")
                .containsExactlyInAnyOrderElementsOf(
                        session.board.withIssueState(changedIssue, "Building renamed").summaries());
    }

    private void damaged(Fixture.Damage damage) throws Exception {
        requires("issues");
        ability(Fixture.Ability.valueOf(damage.name()));
        final Path outside = scratch.resolve("outside-canary");
        final String name = session.fixture.damage(damage, outside);
        final String original = Files.exists(outside) ? Files.readString(outside) : "";
        final var remotes = snapshot();
        // Refresh may parse the damaged store eagerly; either refusal must identify the bad file.
        final PlanningRefused error =
                fails(
                        () -> {
                            refreshIfSupported();
                            return session.client.getIssue(settings(), token(), "I1");
                        });
        assertThat(error.parameters().toString())
                .as("unsafe or missing listed file is named in the refusal")
                .contains(Path.of(name).getFileName().toString());
        assertThat(snapshot())
                .as("refusing a damaged store must not write to a remote")
                .isEqualTo(remotes);
        if (Files.exists(outside))
            assertThat(Files.readString(outside))
                    .as("the readable canary outside the adapter directory stays unchanged")
                    .isEqualTo(original);
        session.canary.verifyUntouched();
    }

    private Object racedWrite(
            VarlinkClient client,
            String issue,
            String revision,
            CountDownLatch ready,
            CountDownLatch go)
            throws Exception {
        startTogether(ready, go);
        final Map<String, Object> parameters = new java.util.LinkedHashMap<>(parameters());
        parameters.put("changes", List.of(new StateChange(issue, "Closed").asMap()));
        parameters.put("revision", revision);
        try {
            final Object answer =
                    client.call(PlanningProtocol.SET_ISSUE_STATES, parameters).get("revision");
            if (!(answer instanceof String current))
                throw new AssertionError(
                        "Expected parallel write's string revision; got a missing or mistyped"
                                + " field");
            return current;
        } catch (VarlinkException ex) {
            assertThat(ex.getErrorName())
                    .as("concurrent write refusal has the named stale error")
                    .isEqualTo(PlanningProtocol.INTERFACE + "." + Reason.STALE_REVISION.wire());
            return new PlanningRefused(
                    Reason.STALE_REVISION,
                    java.util.Objects.requireNonNull(
                            ex.getParameters(), "Expected stale refusal parameters"));
        }
    }

    private static void startTogether(CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(ContractSession.BOUND_SECONDS, TimeUnit.SECONDS))
            throw new AssertionError(
                    "Expected parallel callers released within bound; got a closed barrier");
    }

    private record Watch(BlockingQueue<Change> changes, Future<Object> finished) {}

    private Watch watch(String token) throws Exception {
        final BlockingQueue<Change> changes = new LinkedBlockingQueue<>();
        final CountDownLatch started = new CountDownLatch(1);
        final Future<Object> finished =
                session.submit(
                        () -> {
                            started.countDown();
                            try {
                                session.client.watchChanges(
                                        settings(),
                                        token,
                                        change -> {
                                            changes.add(change);
                                            return true;
                                        });
                                return "watch ended without an error";
                            } catch (RuntimeException ex) {
                                return ex;
                            }
                        });
        assertThat(started.await(ContractSession.BOUND_SECONDS, TimeUnit.SECONDS))
                .as("watch caller starts within bound")
                .isTrue();
        return new Watch(changes, finished);
    }

    private static Change take(BlockingQueue<Change> changes) throws Exception {
        final Change change = changes.poll(ContractSession.BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(change).as("watch delivers a change within the bound").isNotNull();
        return change;
    }

    private static void observeWaitingOrFailure(
            Watch watch, Class<? extends RuntimeException> error) throws Exception {
        // Give the watch a bounded opportunity to finish before probing another connection.
        try {
            assertThat(watch.finished.get(250, TimeUnit.MILLISECONDS))
                    .as("a watch that ends during a store failure reports an error")
                    .isInstanceOf(error);
        } catch (TimeoutException waiting) {
            // Remaining subscribed is allowed; ordinary calls must still make progress.
        }
    }

    private void refreshIfSupported() throws Exception {
        if (session.client.describe().capabilities().contains("watch"))
            call(() -> session.client.refresh(settings(), token()));
    }

    private void unchanged(
            Map<String, String> store, PlanningAdapter.Result<List<StorySummary>> reads)
            throws Exception {
        assertThat(snapshot())
                .as("refused batch must leave every authoritative store unchanged")
                .isEqualTo(store);
        assertThat(listing())
                .as("refused batch must not expose an uncommitted local change")
                .isEqualTo(reads);
        for (final StorySummary story : reads.value()) {
            assertThat(
                            call(() -> session.client.getStory(settings(), token(), story.id()))
                                    .value()
                                    .state())
                    .as("GetStory must not expose a refused state change")
                    .isEqualTo(story.state());
            if (story.issues() != null)
                for (final var issue : story.issues())
                    assertThat(
                                    call(() ->
                                                    session.client.getIssue(
                                                            settings(), token(), issue.id()))
                                            .value()
                                            .summary())
                            .as("GetIssue must not expose a refused state change")
                            .isEqualTo(issue);
        }
    }

    private void unsupported(String capability, java.util.concurrent.Callable<?> call)
            throws Exception {
        assertThat(refused(Reason.NOT_SUPPORTED, call).parameters())
                .as("NotSupported identifies the undeclared capability")
                .containsEntry("capability", capability);
    }

    private PlanningRefused refused(Reason reason, java.util.concurrent.Callable<?> call)
            throws Exception {
        final PlanningRefused error = fails(call);
        assertThat(error.reason())
                .as("asked for refusal %s; got %s", reason, error.reason())
                .isEqualTo(reason);
        return error;
    }

    private PlanningRefused fails(java.util.concurrent.Callable<?> call) throws Exception {
        // catchThrowable keeps the description when the adapter answers instead of refusing.
        final Throwable thrown = catchThrowable(() -> session.within("adapter refusal", call));
        assertThat(thrown)
                .as(
                        "asked for a named adapter refusal; successful answers and timeouts are"
                                + " failures; got %s",
                        thrown == null ? "an answer" : thrown)
                .isInstanceOf(PlanningRefused.class);
        return (PlanningRefused) java.util.Objects.requireNonNull(thrown);
    }

    private void writableIssues() {
        requires("write");
        requires("issues");
    }

    private void requires(String capability) {
        assumeTrue(
                session.client.describe().capabilities().contains(capability),
                "Missing adapter capability " + capability);
    }

    private void ability(Fixture.Ability... abilities) {
        for (final var ability : abilities)
            assumeTrue(
                    session.fixture.abilities().contains(ability),
                    "Missing fixture ability " + ability);
    }

    private Map<String, Object> settings() {
        return session.fixture.settings();
    }

    private Map<String, Object> parameters() {
        return Map.of("settings", settings(), "token", token());
    }

    private String token() {
        return session.fixture.token();
    }

    private static String rejectedToken() {
        return "refused-" + UUID.randomUUID();
    }

    private String revision() throws Exception {
        return call(() -> session.client.getWorkflow(settings(), token())).revision();
    }

    private PlanningAdapter.Result<List<StorySummary>> listing() throws Exception {
        return call(() -> session.client.listStories(settings(), token(), null));
    }

    private Map<String, String> snapshot() throws Exception {
        return session.fixture.snapshot();
    }

    private String write(List<StateChange> changes, String revision) throws Exception {
        return call(() -> session.client.setIssueStates(settings(), token(), changes, revision));
    }

    private <T> T call(java.util.concurrent.Callable<T> call) throws Exception {
        return session.within("adapter call", call);
    }
}
