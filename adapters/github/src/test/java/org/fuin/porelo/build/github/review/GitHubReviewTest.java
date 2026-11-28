package org.fuin.porelo.build.github.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.build.review.ReviewPage;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GitHubReviewTest {

    private static final String API = "https://host-1.example.org/api/v3";

    private static final String PULL =
            "https://host-1.example.org/fixture-org/fixture-repo/pull/11";

    private static final String TOKEN = "ghp_lentForThisCallOnly0123456789abcdef";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aRecordedPullRequestIsReadWholeAsOneProfileTwoPage() throws IOException {
        final ReplayHttpClient http =
                recorded("pull", "compare", "reviews", "reviewComments", "reviewThreads");

        final ReviewPage.Page page = new GitHubReview(() -> http).comments(API, TOKEN, PULL, null);

        assertThat(ReviewPageTest.PROFILE_2.validate(page.text(), InputFormat.JSON))
                .as("the page holds to profile 2's schema")
                .isEmpty();
        final JsonNode results = page.document().at("/runs/0/results");
        assertThat(results.size()).as("the root comment and its reply").isEqualTo(2);
        assertThat(results.get(1).at("/properties/porelo~1replyTo").asText())
                .as("the reply names its thread by the root's REST ID")
                .isEqualTo(recordedRoot());
        assertThat(results.get(1).at("/properties/porelo~1forgeAccountName").asText())
                .isEqualTo("fixture-user");
        assertThat(page.document().at("/runs/0/properties/porelo~1pullRequest").asText())
                .isEqualTo(PULL);
        assertThat(page.document().at("/runs/0/versionControlProvenance/0/revisionId").asText())
                .as("the recorded head")
                .isEqualTo(recordedHead());
        assertThat(page.document().at("/runs/0/properties/porelo~1completeness").asText())
                .as("the recording's pending comment of the reading account is no held comment")
                .isEqualTo("complete");
        assertThat(page.cursor()).isNull();
    }

    @Test
    void aPullRequestsFactsCarryGitHubsApprovalAtTheHeadAndNothingWritesOne() throws IOException {
        final ReplayHttpClient http = recorded("pull", "compare", "reviews", "user");

        final PullRequestFacts.Facts facts =
                new GitHubReview(() -> http).pullRequest(API, TOKEN, PULL);

        assertThat(facts.state()).isEqualTo("open");
        assertThat(facts.account())
                .as("the account the token stands for, from GET /user")
                .isEqualTo(new PullRequestFacts.Account("1", "fixture-user"));
        assertThat(facts.approved())
                .as("the one reviewer's last verdict on the head is an approval")
                .isTrue();
        assertThat(http.requests())
                .extracting(HttpRequest::method)
                .as("reading the facts sends nothing but reads")
                .containsOnly("GET");
    }

    @Test
    void aReplyIsWrittenWithExactlyOneCallToItsThread() throws IOException {
        final ReplayHttpClient http = recorded("replyReviewComment");

        final String thread =
                new GitHubReview(() -> http)
                        .post(API, TOKEN, PULL, submission("submitted-reply.sarif.json"));

        assertThat(thread).isEqualTo("4467633645");
        assertThat(http.requests()).hasSize(1);
        assertThat(http.requests().get(0).uri().getPath())
                .endsWith("/pulls/11/comments/4467633645/replies");
    }

    @Test
    void aReadEndsUnreachableWithoutAFurtherRequestOnceItsCallsLengthHasPassed()
            throws IOException {
        final ReplayHttpClient http =
                recorded("pull", "compare", "reviews", "reviewComments", "reviewThreads");
        final Instant start = Instant.parse("2026-11-26T07:00:00Z");
        final Clock clock = new Stepping(start, start, start.plusSeconds(301));

        assertThatThrownBy(
                        () ->
                                new GitHubReview(
                                                () -> http,
                                                Duration.ofSeconds(60),
                                                Duration.ofSeconds(300),
                                                clock)
                                        .comments(API, TOKEN, PULL, null))
                .as("a long conversation or a slow forge cannot hold one call past its length")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.UNREACHABLE);
        assertThat(http.requests()).as("only the request sent in time").hasSize(1);
    }

    @Test
    void aRequestWaitsNoLongerThanItsCallHasLeft() throws IOException {
        final ReplayHttpClient http = recorded("pull", "compare", "reviews", "user");
        final Instant start = Instant.parse("2026-11-26T07:00:00Z");
        final Clock clock = new Stepping(start, start.plusSeconds(290));

        new GitHubReview(() -> http, Duration.ofSeconds(60), Duration.ofSeconds(300), clock)
                .pullRequest(API, TOKEN, PULL);

        assertThat(http.requests().get(0).timeout())
                .as("a request may not outlive the call's end")
                .contains(Duration.ofSeconds(10));
    }

    @Test
    @Timeout(20)
    void aReadWhoseAnswerOutlastsItsCallEndsUnreachableAndItsExchangeIsAborted()
            throws InterruptedException {
        final Stalling http = new Stalling();

        assertThatThrownBy(
                        () ->
                                new GitHubReview(
                                                () -> http,
                                                Duration.ofSeconds(60),
                                                Duration.ofMillis(300),
                                                Clock.systemUTC())
                                        .pullRequest(API, TOKEN, PULL))
                .as("an answer whose body trickles in cannot hold the call past its end")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.UNREACHABLE);
        assertThat(http.aborted.await(5, TimeUnit.SECONDS))
                .as("the exchange is stopped, not left reading after the call answered")
                .isTrue();
    }

    @Test
    @Timeout(20)
    void aWriteWhoseAnswerOutlastsItsCallIsOfUnknownOutcomeAndNotRepeated() {
        final Stalling http = new Stalling();

        assertThatThrownBy(
                        () ->
                                new GitHubReview(
                                                () -> http,
                                                Duration.ofSeconds(60),
                                                Duration.ofMillis(300),
                                                Clock.systemUTC())
                                        .post(
                                                API,
                                                TOKEN,
                                                PULL,
                                                submission("submitted-comment.sarif.json")))
                .as("a write that may have reached GitHub is never taken for one not sent")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.sent).as("one create call, never repeated").isEqualTo(1);
    }

    @Test
    void aWriteIsNotSentOnceItsCallsLengthHasPassed() throws IOException {
        final ReplayHttpClient http = recorded("createReviewComment");
        final Instant start = Instant.parse("2026-11-26T07:00:00Z");
        final Clock clock = new Stepping(start, start.plusSeconds(301));

        assertThatThrownBy(
                        () ->
                                new GitHubReview(
                                                () -> http,
                                                Duration.ofSeconds(60),
                                                Duration.ofSeconds(300),
                                                clock)
                                        .post(
                                                API,
                                                TOKEN,
                                                PULL,
                                                submission("submitted-comment.sarif.json")))
                .as("a write never sent is no write of unknown outcome")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.UNREACHABLE);
        assertThat(http.requests()).as("nothing reached GitHub").isEmpty();
    }

    @Test
    void aWriteGitHubTookButAnsweredUnreadablyIsOfUnknownOutcome() {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(201, Map.of("content-type", List.of("application/json")), "{not json");

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .post(
                                                API,
                                                TOKEN,
                                                PULL,
                                                submission("submitted-comment.sarif.json")))
                .as("the comment exists; sending it again would write it twice")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.requests()).hasSize(1);
    }

    @Test
    void aWriteAnsweredWithAServerErrorIsOfUnknownOutcome() {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(502, Map.of(), "{\"message\":\"Server Error\"}");

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .post(
                                                API,
                                                TOKEN,
                                                PULL,
                                                submission("submitted-comment.sarif.json")))
                .as("a gateway's error does not say whether GitHub took the write")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.requests()).hasSize(1);
    }

    @Test
    void aRefusedTokenNamesNoTokenInItsWords() {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(401, Map.of(), "{\"message\":\"Bad credentials " + TOKEN + "\"}");

        assertThatThrownBy(() -> new GitHubReview(() -> http).pullRequest(API, TOKEN, PULL))
                .isInstanceOf(ReviewFailure.class)
                .satisfies(
                        e -> {
                            assertThat(((ReviewFailure) e).kind())
                                    .isEqualTo(ReviewFailure.Kind.CREDENTIAL_REFUSED);
                            assertThat(trace(e))
                                    .as("no word of the failure holds the token")
                                    .doesNotContain(TOKEN);
                        });
    }

    @Test
    void theTokenIsSentOnlyToTheForgeTheRestBaseNames() throws IOException {
        final ReplayHttpClient http =
                recorded("pull", "compare", "reviews", "reviewComments", "reviewThreads");

        new GitHubReview(() -> http).comments(API, TOKEN, PULL, null);

        assertThat(http.requests())
                .allSatisfy(
                        request ->
                                assertThat(request.uri().getHost())
                                        .as("every request carrying the token")
                                        .isEqualTo("host-1.example.org"));
        assertThat(http.requests())
                .extracting(r -> r.uri().getPath())
                .as("GraphQL on an Enterprise Server lies under /api, not /api/v3")
                .contains("/api/graphql");
    }

    @Test
    void aPullRequestOnAnotherForgeIsRefusedBeforeAnyCall() {
        final ReplayHttpClient http = new ReplayHttpClient();

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .comments(
                                                API,
                                                TOKEN,
                                                "https://github.com/fixture-org/fixture-repo/pull/11",
                                                null))
                .isInstanceOf(GitHubReview.InvalidArgument.class);
        assertThat(http.requests()).as("the token went nowhere").isEmpty();
    }

    @Test
    void aPostNamingThreadsToResolveIsRefusedWholeAndItsCommentIsNotWritten() {
        for (final String name :
                List.of("submitted-comment.sarif.json", "submitted-reply.sarif.json")) {
            final ObjectNode resolving = submission(name);
            ((ObjectNode) resolving.at("/runs/0/properties"))
                    .putArray("porelo/resolveThreads")
                    .add("4467633645");
            final ReplayHttpClient http = new ReplayHttpClient();

            assertThatThrownBy(() -> new GitHubReview(() -> http).post(API, TOKEN, PULL, resolving))
                    .as(name + ": a resolve goes through resolve alone, never beside a post")
                    .isInstanceOf(ReviewFailure.class)
                    .extracting(e -> ((ReviewFailure) e).kind())
                    .isEqualTo(ReviewFailure.Kind.NOT_IN_PROFILE);
            assertThat(http.requests()).as(name + " reached GitHub").isEmpty();
        }
    }

    @Test
    void aJudgmentIsNeverPostedEvenAsAReply() {
        final ObjectNode judging = submission("submitted-reply.sarif.json");
        ((ObjectNode) judging.at("/runs/0/results/0/properties")).put("porelo/judgment", "reject");
        final ReplayHttpClient http = new ReplayHttpClient();

        assertThatThrownBy(() -> new GitHubReview(() -> http).post(API, TOKEN, PULL, judging))
                .as("a reject discards a push at Porelo and never reaches a forge")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.NOT_IN_PROFILE);
        assertThat(http.requests()).isEmpty();
    }

    @Test
    void aReplysIdNamesNoThread() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads");
        final String reply = recordedThreads().at("/0/comments/nodes/1/databaseId").asText();

        assertThatThrownBy(() -> new GitHubReview(() -> http).resolve(API, TOKEN, PULL, reply))
                .as("a thread is named by its root comment alone")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.NO_SUCH_THREAD);
        assertThat(http.requests()).as("only the read").hasSize(1);
    }

    @Test
    void aThreadOfTheReadingAccountsOwnPendingReviewIsNotResolved() throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(
                threads(body -> body.replace("\"state\":\"SUBMITTED\"", "\"state\":\"PENDING\"")));

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .as("a comment nobody else sees opens no thread Porelo was shown")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.NO_SUCH_THREAD);
        assertThat(http.requests()).as("only the read").hasSize(1);
    }

    @Test
    void aThreadIdThatIsNoPositiveDecimalSendsNothing() {
        for (final String id :
                List.of(
                        "",
                        "0",
                        "007",
                        "-7",
                        "7 ",
                        "seven",
                        "PRRT_kwDOVc0D",
                        "9999999999999999999",
                        "99999999999999999999")) {
            final ReplayHttpClient http = new ReplayHttpClient();

            assertThatThrownBy(() -> new GitHubReview(() -> http).resolve(API, TOKEN, PULL, id))
                    .as("thread id '%s'", id)
                    .isInstanceOf(GitHubReview.InvalidArgument.class);
            assertThat(http.requests()).as("the token went nowhere for '%s'", id).isEmpty();
        }
    }

    @Test
    void aThreadNotFoundAmongThreadsNotReadToTheirEndIsNeverNoSuchThread() throws IOException {
        final ReplayHttpClient.Answer more =
                threads(body -> body.replace("\"hasNextPage\":false", "\"hasNextPage\":true"));
        final ReplayHttpClient http = new ReplayHttpClient().otherwise(request -> more);

        assertThatThrownBy(() -> new GitHubReview(() -> http).resolve(API, TOKEN, PULL, "17"))
                .as("the thread may be among those not read")
                .isNotInstanceOf(ReviewFailure.class)
                .isInstanceOf(IllegalStateException.class);
        assertThat(http.requests())
                .as("the page limit's reads and no write")
                .hasSize(ConversationSource.PAGE_LIMIT)
                .allSatisfy(
                        request -> assertThat(body(request)).doesNotContain("resolveReviewThread"));
    }

    @Test
    void aThreadWhoseRootGitHubGivesNoIdForLeavesTheReadIncomplete() throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(
                threads(
                        body ->
                                body.replaceAll(
                                        "\"databaseId\":[0-9]{4,}", "\"databaseId\":null")));

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .as("a thread that shows no root's id may be the one asked for")
                .isNotInstanceOf(ReviewFailure.class)
                .isInstanceOf(IllegalStateException.class);
        assertThat(http.requests()).as("only the read").hasSize(1);
    }

    @Test
    void threadsGitHubNamesTwiceAcrossPagesLeaveTheReadIncomplete() throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        http.answer(threads(body -> body.replace("\"hasNextPage\":false", "\"hasNextPage\":true")));
        http.recording("reviewThreads");

        assertThatThrownBy(() -> new GitHubReview(() -> http).resolve(API, TOKEN, PULL, "17"))
                .as("a moved page cursor that repeats threads may have skipped the one asked for")
                .isNotInstanceOf(ReviewFailure.class)
                .isInstanceOf(IllegalStateException.class);
        assertThat(http.requests()).as("both pages read, nothing written").hasSize(2);
    }

    @Test
    void aResolveWhoseAnswerIsLostIsOfUnknownOutcomeAndNotRepeated() throws IOException {
        final ThenTimingOut http = new ThenTimingOut(recorded("reviewThreads"));

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .as("a mutation that timed out may have resolved the thread")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.sent).as("the read and one mutation, never repeated").isEqualTo(2);
    }

    @Test
    void aResolveAnsweredWithAServerErrorIsOfUnknownOutcome() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads");
        http.answer(502, Map.of(), "{\"message\":\"Server Error\"}");

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.requests()).hasSize(2);
    }

    @Test
    void aResolveGitHubForbidsTheAccountIsARefusedTokenNamingNoToken() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads");
        http.answer(
                200,
                Map.of("content-type", List.of("application/json")),
                "{\"data\":{\"resolveReviewThread\":null},\"errors\":[{\"type\":\"FORBIDDEN\","
                        + "\"path\":[\"resolveReviewThread\"],\"message\":\"fixture-user may not "
                        + TOKEN
                        + "\"}]}");

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .isInstanceOf(ReviewFailure.class)
                .satisfies(
                        e -> {
                            assertThat(((ReviewFailure) e).kind())
                                    .as("refused in GitHub's words, so not taken")
                                    .isEqualTo(ReviewFailure.Kind.CREDENTIAL_REFUSED);
                            assertThat(trace(e))
                                    .as("no word of the failure holds the token")
                                    .doesNotContain(TOKEN);
                        });
    }

    @Test
    void aResolveOfAThreadGoneSinceItWasReadIsNoSuchThread() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads");
        http.answer(
                200,
                Map.of("content-type", List.of("application/json")),
                "{\"data\":{\"resolveReviewThread\":null},\"errors\":[{\"type\":\"NOT_FOUND\","
                        + "\"path\":[\"resolveReviewThread\"],"
                        + "\"message\":\"Could not resolve to a node with the global id\"}]}");

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.NO_SUCH_THREAD);
    }

    @Test
    void aTokenThatMayNotReadTheThreadsIsRefusedAndAPullRequestGitHubDoesNotShowIsMissing() {
        final ReplayHttpClient forbidden = new ReplayHttpClient();
        forbidden.answer(
                200,
                Map.of("content-type", List.of("application/json")),
                "{\"data\":{\"repository\":null},\"errors\":[{\"type\":\"FORBIDDEN\","
                        + "\"path\":[\"repository\"],"
                        + "\"message\":\"Resource not accessible by personal access token\"}]}");

        assertThatThrownBy(() -> new GitHubReview(() -> forbidden).resolve(API, TOKEN, PULL, "17"))
                .as("the person must change the token, which a plain failure would not say")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.CREDENTIAL_REFUSED);

        final ReplayHttpClient missing = new ReplayHttpClient();
        missing.answer(
                200,
                Map.of("content-type", List.of("application/json")),
                "{\"data\":{\"repository\":null},\"errors\":[{\"type\":\"NOT_FOUND\","
                        + "\"path\":[\"repository\"],\"message\":\"Could not resolve to a"
                        + " Repository with the name 'fixture-org/fixture-repo'.\"}]}");

        assertThatThrownBy(() -> new GitHubReview(() -> missing).resolve(API, TOKEN, PULL, "17"))
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.NO_SUCH_PULL_REQUEST);
        assertThat(missing.requests()).as("only the read").hasSize(1);
    }

    @Test
    void aResolveAnsweredWithoutTheThreadsStateIsOfUnknownOutcome() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads");
        http.answer(
                200,
                Map.of("content-type", List.of("application/json")),
                "{\"data\":{\"resolveReviewThread\":null}}");

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .as("GitHub took the request and did not say what became of the thread")
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.requests()).as("one write, never repeated").hasSize(2);
    }

    @Test
    void aResolveGitHubAnswersWithTheThreadStillOpenIsNoSuccess() throws IOException {
        final ReplayHttpClient http = recorded("reviewThreads");
        final ReplayHttpClient.Answer taken =
                ReplayHttpClient.recorded("resolveReviewThread").get(0);
        http.answer(
                taken.status(),
                taken.headers(),
                taken.body().replace("\"isResolved\":true", "\"isResolved\":false"));

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .resolve(API, TOKEN, PULL, recordedThreadRoot()))
                .as("an open thread is never reported resolved")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noMethodReachesAVerdict() {
        assertThat(
                        java.util.Arrays.stream(GitHubReview.class.getMethods())
                                .map(java.lang.reflect.Method::getName)
                                .toList())
                .as("it never approves, requests changes, reopens or merges")
                .noneMatch(
                        name ->
                                name.matches(
                                        "(?i).*(approve|verdict|requestChanges|unresolve|reopen|merge).*"));
    }

    /** The recorded pull request's threads, as GitHub answered them. */
    private static JsonNode recordedThreads() throws IOException {
        return JSON.readTree(ReplayHttpClient.recorded("reviewThreads").get(0).body())
                .at("/data/repository/pullRequest/reviewThreads/nodes");
    }

    /** The REST id of the first recorded thread's root comment. */
    private static String recordedThreadRoot() throws IOException {
        return recordedThreads().at("/0/comments/nodes/0/databaseId").asText();
    }

    /** The recorded threads' answer with its body changed, which must change it. */
    private static ReplayHttpClient.Answer threads(java.util.function.UnaryOperator<String> change)
            throws IOException {
        final ReplayHttpClient.Answer recorded = ReplayHttpClient.recorded("reviewThreads").get(0);
        final String changed = change.apply(recorded.body());
        assertThat(changed)
                .as("the recording holds what the case changes")
                .isNotEqualTo(recorded.body());
        return new ReplayHttpClient.Answer(recorded.status(), recorded.headers(), changed);
    }

    /** What a request carries as its body. */
    private static String body(HttpRequest request) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            @Override
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(java.nio.ByteBuffer item) {
                                final byte[] bytes = new byte[item.remaining()];
                                item.get(bytes);
                                out.writeBytes(bytes);
                            }

                            @Override
                            public void onError(Throwable failure) {
                                throw new IllegalStateException(failure);
                            }

                            @Override
                            public void onComplete() {}
                        });
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The head of the recorded pull request, as GitHub answered it when it was recorded. */
    private static String recordedHead() throws IOException {
        return JSON.readTree(ReplayHttpClient.recorded("pull").iterator().next().body())
                .at("/head/sha")
                .asText();
    }

    /** The REST ID of the recorded thread's root, which its first reply names. */
    private static String recordedRoot() throws IOException {
        for (final JsonNode comment :
                JSON.readTree(
                        ReplayHttpClient.recorded("reviewComments").iterator().next().body())) {
            if (comment.hasNonNull("in_reply_to_id")) {
                return comment.get("in_reply_to_id").asText();
            }
        }
        throw new IllegalStateException("the recording holds no reply");
    }

    private static ReplayHttpClient recorded(String... names) throws IOException {
        final ReplayHttpClient http = new ReplayHttpClient();
        for (final String name : names) {
            http.recording(name);
        }
        return http;
    }

    /** One of porelo's profile 2 submissions, moved onto the recorded pull request. */
    private static ObjectNode submission(String name) {
        final String path = "/org/fuin/porelo/build/api/review/fixtures/" + name;
        try (InputStream in = GitHubReviewTest.class.getResourceAsStream(path)) {
            final ObjectNode document =
                    (ObjectNode) JSON.readTree(Objects.requireNonNull(in, path));
            final ObjectNode run = (ObjectNode) document.at("/runs/0");
            ((ObjectNode) run.get("properties")).put("porelo/pullRequest", PULL);
            ((ObjectNode) run.at("/versionControlProvenance/0"))
                    .put("repositoryUri", "https://host-1.example.org/fixture-org/fixture-repo")
                    .put("revisionId", "ddb2cdaa1ee183a0c5a021a6a9145a3b8a53a7cf");
            final JsonNode reply = run.at("/results/0/properties/porelo~1replyTo");
            if (!reply.isMissingNode()) {
                ((ObjectNode) run.at("/results/0/properties")).put("porelo/replyTo", "4467633645");
            }
            return document;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String trace(Throwable failure) {
        final StringBuilder out = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            out.append(t).append('\n');
        }
        return out.toString();
    }

    /** Says each given time once, in order, and the last one from then on. */
    private static final class Stepping extends Clock {

        private final List<Instant> times;

        private int next;

        Stepping(Instant... times) {
            this.times = List.of(times);
        }

        @Override
        public Instant instant() {
            return times.get(Math.min(next++, times.size() - 1));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Takes each request and never finishes its answer, as GitHub sending headers in time and the
     * body slowly; like the JDK's client, it aborts the exchange when its thread is interrupted.
     */
    private static final class Stalling extends TimingOut {

        private final CountDownLatch aborted = new CountDownLatch(1);

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler)
                throws InterruptedException {
            sent++;
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException ex) {
                aborted.countDown();
                throw ex;
            }
            throw new IllegalStateException("the call's end did not stop the exchange");
        }
    }

    /** Answers its first requests as recorded and times out on the next, as a lost answer. */
    private static final class ThenTimingOut extends TimingOut {

        private final ReplayHttpClient first;

        private final int answered;

        ThenTimingOut(ReplayHttpClient first) {
            this.first = first;
            this.answered = 1;
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            if (sent < answered) {
                sent++;
                return first.send(request, handler);
            }
            return super.send(request, handler);
        }
    }

    /** Sends nothing and times out on every request, as a write whose answer was lost. */
    private static class TimingOut extends java.net.http.HttpClient {

        int sent;

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            sent++;
            throw new HttpTimeoutException("request timed out");
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler,
                java.net.http.HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() {
            return java.util.Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty();
        }
    }
}
