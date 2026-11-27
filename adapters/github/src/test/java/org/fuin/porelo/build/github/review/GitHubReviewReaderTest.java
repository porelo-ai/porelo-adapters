package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

class GitHubReviewReaderTest {

    private static final ForgeAccess ACCESS =
            new ForgeAccess("https://host-1.example.org/api/v3", "ghp_lentForThisCallOnly0123");

    private static final String PULL =
            "https://host-1.example.org/fixture-org/fixture-repo/pull/11";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aRecordedConversationIsAnsweredAsAPageProfileTwoChecksForAnAdapter() throws IOException {
        final ReplayHttpClient http =
                recorded("pull", "compare", "reviews", "reviewComments", "reviewThreads");

        final ConversationPage page =
                new GitHubReviewReader(() -> http).comments(ACCESS, PULL, null);

        assertThat(JSON.readTree(page.document()).at("/properties/porelo~1documentKind").asText())
                .as("the page went through porelo's own reading and check")
                .isEqualTo("page");
        assertThat(page.cursor()).isNull();
    }

    @Test
    void anOpenPullRequestsFactsAreTheContractsFacts() throws IOException {
        final ReplayHttpClient http = recorded("pull", "compare", "reviews", "user");

        final ReviewFacts facts = new GitHubReviewReader(() -> http).pullRequest(ACCESS, PULL);

        assertThat(facts.state()).isEqualTo(ReviewFacts.State.OPEN);
        assertThat(facts.mergeCommit()).as("given exactly when merged").isNull();
        assertThat(facts.mergedHead()).isNull();
        assertThat(facts.decidedAt()).as("absent while open").isNull();
        assertThat(facts.account())
                .as("the account the token stands for")
                .isEqualTo(new ReviewFacts.Account("1", "fixture-user"));
        assertThat(facts.head()).as("the recorded head").isEqualTo(recordedHead());
    }

    @Test
    void aClosedPullRequestSaysWhenGitHubClosedIt() throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        for (final ReplayHttpClient.Answer answer : ReplayHttpClient.recorded("pull")) {
            final ObjectNode pull = (ObjectNode) JSON.readTree(answer.body());
            pull.put("state", "closed").put("closed_at", "2026-11-05T09:30:00Z");
            http.answer(answer.status(), answer.headers(), JSON.writeValueAsString(pull));
        }
        http.recording("compare").recording("reviews").recording("user");

        final ReviewFacts facts = new GitHubReviewReader(() -> http).pullRequest(ACCESS, PULL);

        assertThat(facts.state()).isEqualTo(ReviewFacts.State.CLOSED);
        assertThat(facts.decidedAt())
                .as("GitHub's closed_at, given exactly when closed")
                .isEqualTo(java.time.Instant.parse("2026-11-05T09:30:00Z"));
    }

    @Test
    void aMergedPullRequestNamesItsMergeCommitAndTheBranchItWentOnto() throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        for (final ReplayHttpClient.Answer answer : ReplayHttpClient.recorded("pull")) {
            final ObjectNode pull = (ObjectNode) JSON.readTree(answer.body());
            pull.put("state", "closed")
                    .put("merged", true)
                    .put("merged_at", "2026-11-05T10:00:00Z")
                    .put("closed_at", "2026-11-05T10:00:00Z");
            http.answer(answer.status(), answer.headers(), JSON.writeValueAsString(pull));
        }
        http.recording("compare").recording("reviews").recording("mergeCommit").recording("user");

        final ReviewFacts facts = new GitHubReviewReader(() -> http).pullRequest(ACCESS, PULL);

        assertThat(facts.state()).isEqualTo(ReviewFacts.State.MERGED);
        assertThat(facts.mergeCommit())
                .as("GitHub's merge commit, read by GraphQL")
                .isEqualTo(recordedMergeCommit());
        assertThat(facts.mergedHead()).as("the base branch the merge went onto").isNotBlank();
        assertThat(facts.decidedAt())
                .as("GitHub's merged_at")
                .isEqualTo(java.time.Instant.parse("2026-11-05T10:00:00Z"));
        assertThat(http.requests().get(3).uri().getPath())
                .as("GraphQL beside the Enterprise Server's REST base")
                .isEqualTo("/api/graphql");
    }

    @Test
    void aMergedPullRequestGitHubNamesNoMergeCommitForFailsClosed() throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        for (final ReplayHttpClient.Answer answer : ReplayHttpClient.recorded("pull")) {
            final ObjectNode pull = (ObjectNode) JSON.readTree(answer.body());
            pull.put("state", "closed")
                    .put("merged", true)
                    .put("merged_at", "2026-11-05T10:00:00Z");
            http.answer(answer.status(), answer.headers(), JSON.writeValueAsString(pull));
        }
        http.recording("compare").recording("reviews").recording("mergeCommitOpen");

        assertThatThrownBy(() -> new GitHubReviewReader(() -> http).pullRequest(ACCESS, PULL))
                .as("merged by REST, not merged by GraphQL: no guess")
                .isNotInstanceOf(ForgeReviewRefused.class);
    }

    @Test
    void aSubmissionProfileTwoRefusesIsNotInProfileAndNothingIsSent() {
        for (final String name :
                List.of(
                        "replacement-too-large",
                        "replacement-long-line",
                        "replacement-too-many-lines",
                        "fix-other-file",
                        "text-control")) {
            final ReplayHttpClient http = new ReplayHttpClient();

            assertThatThrownBy(
                            () ->
                                    new GitHubReviewReader(() -> http)
                                            .post(ACCESS, PULL, adversarial(name)))
                    .as(name)
                    .isInstanceOf(ForgeReviewRefused.class)
                    .extracting(e -> ((ForgeReviewRefused) e).reason())
                    .isEqualTo(ForgeReviewRefused.Reason.NOT_IN_PROFILE);
            assertThat(http.requests()).as(name + " reached GitHub").isEmpty();
        }
    }

    @Test
    void aSubmissionOnlyProfileTwoRefusesIsRefusedByPorelosCheckBeforeAnyCall() {
        final ReplayHttpClient http = recorded();

        assertThatThrownBy(
                        () ->
                                new GitHubReviewReader(() -> http)
                                        .post(
                                                ACCESS,
                                                PULL,
                                                resource(
                                                        "/org/fuin/porelo/build/api/review/fixtures/failing/submission-with-guid.sarif.json")))
                .as("a guid GitHub's writer would ignore; only ProfileCheck refuses it")
                .isInstanceOf(ForgeReviewRefused.class)
                .satisfies(
                        e -> {
                            final ForgeReviewRefused refused = (ForgeReviewRefused) e;
                            assertThat(refused.reason())
                                    .isEqualTo(ForgeReviewRefused.Reason.NOT_IN_PROFILE);
                            assertThat(refused.field())
                                    .as("a point, not a sentence")
                                    .isNotBlank()
                                    .doesNotContain(" ");
                        });
        assertThat(http.requests()).isEmpty();
    }

    @Test
    void aRateLimitWithoutAWordFromGitHubSaysZero() {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(
                403,
                Map.of("content-type", List.of("application/json")),
                "{\"message\":\"You have exceeded a secondary rate limit.\"}");

        assertThatThrownBy(() -> new GitHubReviewReader(() -> http).pullRequest(ACCESS, PULL))
                .isInstanceOf(ForgeReviewRefused.class)
                .satisfies(
                        e -> {
                            final ForgeReviewRefused refused = (ForgeReviewRefused) e;
                            assertThat(refused.reason())
                                    .isEqualTo(ForgeReviewRefused.Reason.RATE_LIMITED);
                            assertThat(refused.retryAfterSeconds())
                                    .as("the contract's 0 when the forge did not say")
                                    .isZero();
                        });
    }

    @Test
    void aCheckedSubmissionIsWrittenWithOneCall() throws IOException {
        final ReplayHttpClient http = recorded("createReviewComment");

        final String thread =
                new GitHubReviewReader(() -> http)
                        .post(ACCESS, PULL, JSON.writeValueAsString(submission()));

        assertThat(thread).matches("[1-9][0-9]*");
        assertThat(http.requests()).hasSize(1);
    }

    @Test
    void aWriteGitHubForbidsForALockedConversationIsNoRefusedTokenAndCarriesGitHubsWords()
            throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        // GitHub names the endpoint's permissions on every answer, a successful one too.
        http.answer(
                403,
                Map.of(
                        "content-type",
                        List.of("application/json"),
                        "x-accepted-github-permissions",
                        List.of("pull_requests=write")),
                "{\"message\":\"Unable to create comment because issue is locked.\"}");

        assertThatThrownBy(
                        () ->
                                new GitHubReviewReader(() -> http)
                                        .post(ACCESS, PULL, JSON.writeValueAsString(submission())))
                .as("a person told their token was refused would replace a token that works")
                .isNotInstanceOf(ForgeReviewRefused.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Unable to create comment because issue is locked.");
        assertThat(http.requests()).as("one write, never repeated").hasSize(1);
    }

    @Test
    void aForbiddenAnswerSayingTheTokenCannotReachTheResourceIsARefusedToken() throws IOException {
        for (final String message :
                List.of(
                        "Resource not accessible by personal access token",
                        "Resource not accessible by integration",
                        "Resource protected by organization SAML enforcement. You must grant your"
                                + " Personal Access token access to this organization.")) {
            final ReplayHttpClient http = new ReplayHttpClient();
            http.answer(
                    403,
                    Map.of("content-type", List.of("application/json")),
                    "{\"message\":\"" + message + "\"}");

            assertThatThrownBy(
                            () ->
                                    new GitHubReviewReader(() -> http)
                                            .post(
                                                    ACCESS,
                                                    PULL,
                                                    JSON.writeValueAsString(submission())))
                    .as("the person must change or authorize the token: " + message)
                    .isInstanceOf(ForgeReviewRefused.class)
                    .extracting(e -> ((ForgeReviewRefused) e).reason())
                    .isEqualTo(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED);
        }
    }

    @Test
    void aNamedThreadIsResolvedWithOneWriteAndASecondAskForItIsDoneToo() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads", "resolveReviewThread");
        final ReplayHttpClient.Answer threads = ReplayHttpClient.recorded("reviewThreads").get(0);
        http.answer(
                threads.status(),
                threads.headers(),
                threads.body().replaceFirst("\"isResolved\":false", "\"isResolved\":true"));
        final GitHubReviewReader reader = new GitHubReviewReader(() -> http);

        reader.resolve(ACCESS, PULL, recordedThreadRoot());
        reader.resolve(ACCESS, PULL, recordedThreadRoot());

        assertThat(http.requests())
                .as("a read and the one write, then only a read of the resolved thread")
                .hasSize(3);
    }

    @Test
    void aThreadThePullRequestDoesNotHoldIsTheContractsNoSuchThread() {
        final ReplayHttpClient http = recorded("reviewThreads");

        assertThatThrownBy(() -> new GitHubReviewReader(() -> http).resolve(ACCESS, PULL, "17"))
                .isInstanceOf(ForgeReviewRefused.class)
                .extracting(e -> ((ForgeReviewRefused) e).reason())
                .isEqualTo(ForgeReviewRefused.Reason.NO_SUCH_THREAD);
        assertThat(http.requests()).as("nothing written").hasSize(1);
    }

    @Test
    void aThreadNotFoundOnAnIncompleteReadIsAFailureAndNeverNoSuchThread() throws IOException {
        final ReplayHttpClient.Answer recorded = ReplayHttpClient.recorded("reviewThreads").get(0);
        final ReplayHttpClient.Answer more =
                new ReplayHttpClient.Answer(
                        recorded.status(),
                        recorded.headers(),
                        recorded.body().replace("\"hasNextPage\":false", "\"hasNextPage\":true"));
        final ReplayHttpClient http = new ReplayHttpClient().otherwise(request -> more);

        assertThatThrownBy(() -> new GitHubReviewReader(() -> http).resolve(ACCESS, PULL, "17"))
                .as("Porelo told there is no such thread would drop one that exists")
                .isNotInstanceOf(ForgeReviewRefused.class);
    }

    @Test
    void aResolveWithoutAnAnswerOfItsOutcomeIsTheContractsWriteOutcomeUnknown() {
        final ReplayHttpClient http = recorded("reviewThreads");
        http.answer(502, Map.of(), "{\"message\":\"Server Error\"}");

        assertThatThrownBy(
                        () ->
                                new GitHubReviewReader(() -> http)
                                        .resolve(ACCESS, PULL, recordedThreadRoot()))
                .isInstanceOf(ForgeReviewRefused.class)
                .extracting(e -> ((ForgeReviewRefused) e).reason())
                .isEqualTo(ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.requests()).as("one write, never repeated").hasSize(2);
    }

    @Test
    void aResolveGitHubForbidsTheAccountIsARefusedTokenAndAnotherRefusalCarriesGitHubsWords() {
        final ReplayHttpClient forbidden = recorded("reviewThreads");
        forbidden.answer(
                200, Map.of("content-type", List.of("application/json")), refusal("FORBIDDEN"));

        assertThatThrownBy(
                        () ->
                                new GitHubReviewReader(() -> forbidden)
                                        .resolve(ACCESS, PULL, recordedThreadRoot()))
                .as("the account may not resolve: the person must change the token")
                .isInstanceOf(ForgeReviewRefused.class)
                .satisfies(
                        e -> {
                            assertThat(((ForgeReviewRefused) e).reason())
                                    .isEqualTo(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED);
                            assertThat(String.valueOf(e.getMessage()))
                                    .doesNotContain(ACCESS.token());
                        });

        final ReplayHttpClient other = recorded("reviewThreads");
        other.answer(
                200, Map.of("content-type", List.of("application/json")), refusal("UNPROCESSABLE"));

        assertThatThrownBy(
                        () ->
                                new GitHubReviewReader(() -> other)
                                        .resolve(ACCESS, PULL, recordedThreadRoot()))
                .as("a refusal the contract has no name for is a failure in GitHub's words")
                .isNotInstanceOf(ForgeReviewRefused.class)
                .hasMessageContaining("GitHub said no");
    }

    @Test
    void aRateLimitSaysWhenToAskAgain() {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(
                429,
                Map.of("retry-after", List.of("30"), "content-type", List.of("application/json")),
                "{\"message\":\"You have exceeded a secondary rate limit.\"}");

        assertThatThrownBy(() -> new GitHubReviewReader(() -> http).pullRequest(ACCESS, PULL))
                .isInstanceOf(ForgeReviewRefused.class)
                .satisfies(
                        e -> {
                            final ForgeReviewRefused refused = (ForgeReviewRefused) e;
                            assertThat(refused.reason())
                                    .isEqualTo(ForgeReviewRefused.Reason.RATE_LIMITED);
                            assertThat(refused.retryAfterSeconds())
                                    .as("GitHub's retry-after, rounded up to whole seconds")
                                    .isBetween(29L, 30L);
                        });
    }

    @Test
    void anEmptyApiIsGitHubsOwn() {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(404, Map.of(), "{\"message\":\"Not Found\"}");

        assertThatThrownBy(
                        () ->
                                new GitHubReviewReader(() -> http)
                                        .pullRequest(
                                                new ForgeAccess("", "ghp_lent0123456789"),
                                                "https://github.com/example-org/example-repo/pull/3"))
                .isInstanceOf(ForgeReviewRefused.class)
                .extracting(e -> ((ForgeReviewRefused) e).reason())
                .isEqualTo(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
        assertThat(http.requests().get(0).uri().getHost()).isEqualTo("api.github.com");
    }

    /** The head of the recorded pull request, as GitHub answered it when it was recorded. */
    private static String recordedHead() throws IOException {
        return JSON.readTree(ReplayHttpClient.recorded("pull").iterator().next().body())
                .at("/head/sha")
                .asText();
    }

    /** The merge commit GitHub's GraphQL named when it was recorded. */
    private static String recordedMergeCommit() throws IOException {
        return JSON.readTree(ReplayHttpClient.recorded("mergeCommit").iterator().next().body())
                .at("/data/repository/pullRequest/mergeCommit/oid")
                .asText();
    }

    /** The REST id of the first recorded thread's root comment. */
    private static String recordedThreadRoot() {
        try {
            return JSON.readTree(ReplayHttpClient.recorded("reviewThreads").get(0).body())
                    .at(
                            "/data/repository/pullRequest/reviewThreads/nodes/0/comments/nodes/0/databaseId")
                    .asText();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** GitHub's answer to a mutation it refuses: a 200 with one error of that type. */
    private static String refusal(String type) {
        return "{\"data\":{\"resolveReviewThread\":null},\"errors\":[{\"type\":\""
                + type
                + "\",\"path\":[\"resolveReviewThread\"],\"message\":\"GitHub said no\"}]}";
    }

    private static ReplayHttpClient recorded(String... names) {
        final ReplayHttpClient http = new ReplayHttpClient();
        for (final String name : names) {
            try {
                http.recording(name);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return http;
    }

    private static String adversarial(String name) {
        return resource("/org/fuin/porelo/build/api/review/adversarial/" + name + ".sarif.json");
    }

    /** Porelo's submitted comment, moved onto the recorded pull request. */
    private static ObjectNode submission() throws IOException {
        final ObjectNode document =
                (ObjectNode)
                        JSON.readTree(
                                resource(
                                        "/org/fuin/porelo/build/api/review/fixtures/submitted-comment.sarif.json"));
        final ObjectNode run = (ObjectNode) document.at("/runs/0");
        ((ObjectNode) run.get("properties")).put("porelo/pullRequest", PULL);
        ((ObjectNode) run.at("/versionControlProvenance/0"))
                .put("repositoryUri", "https://host-1.example.org/fixture-org/fixture-repo")
                .put("revisionId", "ddb2cdaa1ee183a0c5a021a6a9145a3b8a53a7cf");
        return document;
    }

    private static String resource(String path) {
        try (InputStream in = GitHubReviewReaderTest.class.getResourceAsStream(path)) {
            return new String(
                    Objects.requireNonNull(in, path).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
