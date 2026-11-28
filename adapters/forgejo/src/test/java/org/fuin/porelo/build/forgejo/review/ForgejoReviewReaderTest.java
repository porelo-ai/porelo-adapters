package org.fuin.porelo.build.forgejo.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.forge.forgejo.RateLimit;
import org.junit.jupiter.api.Test;

/**
 * The review contract over Forgejo's answers, replayed: checks before a request, facts, pages,
 * writes.
 */
class ForgejoReviewReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TOKEN = "0123456789abcdef0123456789abcdef01234567";

    private static final ForgeAccess ACCESS =
            new ForgeAccess("https://forgejo.example.org/api/v1", TOKEN);

    private static final String PULL = "https://forgejo.example.org/core/probe/pulls/1";

    private static final String HEAD = "9f4b530e5ed93571ea9c468722a4d8189acd5c20";

    @Test
    void aResolveIsUnsupportedAndSendsNothing() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();

        assertThatThrownBy(() -> reader(http).resolve(ACCESS, PULL, "2"))
                .as("Forgejo's API has no call to resolve a conversation")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(ForgeReviewRefused.Reason.RESOLVE_UNSUPPORTED));
        assertThat(http.requests).as("an unsupported resolve reaches no forge").isEmpty();
    }

    @Test
    void anApiThatIsNotHttpsIsRefusedBeforeAnyRequestWithoutTheToken() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ForgeAccess plain = new ForgeAccess("http://forgejo.example.org/api/v1", TOKEN);

        assertThatThrownBy(() -> reader(http).pullRequest(plain, PULL))
                .as("the token never travels in the clear")
                .isInstanceOf(ForgejoReviewReader.InvalidArgument.class)
                .hasMessageStartingWith("api")
                .hasMessageNotContaining(TOKEN);
        assertThat(http.requests).as("nothing is sent before the arguments hold").isEmpty();
    }

    @Test
    void aPullRequestOnAnotherHostIsRefusedBeforeAnyRequest() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();

        assertThatThrownBy(
                        () ->
                                reader(http)
                                        .pullRequest(
                                                ACCESS,
                                                "https://elsewhere.example/core/probe/pulls/1"))
                .as("the token goes only to the instance it was lent for")
                .isInstanceOf(ForgejoReviewReader.InvalidArgument.class)
                .hasMessageStartingWith("url");
        assertThat(http.requests).as("nothing is sent to either host").isEmpty();
    }

    @Test
    void anEmptyApiIsTheApiAtThePullRequestsOrigin() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http = recordedFacts();

        final ReviewFacts facts = reader(http).pullRequest(new ForgeAccess("", TOKEN), PULL);

        assertThat(facts.head()).as("the pull request was read").isEqualTo(HEAD);
        assertThat(http.requests)
                .as("Porelo names no API, so the instance's own at the address's origin is asked")
                .allSatisfy(
                        r ->
                                assertThat(r.uri().toString())
                                        .as("every request stays on the pull request's origin")
                                        .startsWith("https://forgejo.example.org/api/v1/"));
    }

    @Test
    void anEmptyApiForAnAddressOnPlainHttpIsRefusedBeforeAnyRequest() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();

        assertThatThrownBy(
                        () ->
                                reader(http)
                                        .pullRequest(
                                                new ForgeAccess("", TOKEN),
                                                "http://forgejo.example.org/core/probe/pulls/1"))
                .as("the token never travels in the clear, whatever names the instance")
                .isInstanceOf(ForgejoReviewReader.InvalidArgument.class)
                .hasMessageNotContaining(TOKEN);
        assertThat(http.requests).as("nothing is sent").isEmpty();
    }

    @Test
    void anEmptyApiFindsTheBranchsPullRequestAtTheUpstreamsOrigin() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(new ReplayedForgejo.Reply(200, "[]"));

        assertThat(
                        reader(http)
                                .pullRequestOf(
                                        new ForgeAccess("", TOKEN),
                                        "https://forgejo.example.org/core/probe.git",
                                        "feature"))
                .as("no open pull request in the list answered")
                .isNull();
        assertThat(http.requests.getFirst().uri().toString())
                .as("the list is asked of the upstream's own instance")
                .startsWith("https://forgejo.example.org/api/v1/repos/core/probe/pulls");
    }

    @Test
    void aReplyIsNotInProfileAndSendsNothing() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ObjectNode document = submission();
        result(document).with("properties").put("porelo/replyTo", "2");

        assertThatThrownBy(() -> reader(http).post(ACCESS, PULL, document.toString()))
                .as("Forgejo has no reply to a review comment")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.field())
                                        .isEqualTo("runs[0].results[0].properties.porelo/replyTo"));
        assertThat(http.requests).as("a held write sends nothing").isEmpty();
    }

    @Test
    void aSubmissionNamingAnotherPullRequestSendsNothing() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ObjectNode document = submission();

        assertThatThrownBy(
                        () ->
                                reader(http)
                                        .post(
                                                ACCESS,
                                                "https://forgejo.example.org/core/probe/pulls/2",
                                                document.toString()))
                .as("a release for one pull request never lands on another")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.field())
                                        .isEqualTo("runs[0].properties.porelo/pullRequest"));
        assertThat(http.requests).as("nothing is written").isEmpty();
    }

    @Test
    void aLostCreateAnswerIsWriteOutcomeUnknownAfterExactlyOneRequest() {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(502, "{\"message\":\"bad gateway\"}"));

        assertThatThrownBy(() -> reader(http).post(ACCESS, PULL, submission().toString()))
                .as("the comment may have landed, so Porelo must not send it again")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(
                                                ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN));
        assertThat(http.requests).as("exactly one create, never repeated").hasSize(1);
    }

    @Test
    void aCreateAnsweredWithoutAReviewIsWriteOutcomeUnknownAfterOneRequest() {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(new ReplayedForgejo.Reply(200, "null"));

        assertThatThrownBy(() -> reader(http).post(ACCESS, PULL, submission().toString()))
                .as("a 200 without a review may still have written the comment")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(
                                                ForgeReviewRefused.Reason.WRITE_OUTCOME_UNKNOWN));
        assertThat(http.requests).as("the create is never repeated").hasSize(1);
    }

    @Test
    void aForgejoOnTheLoopbackAddressIsRefusedSinceTheContractNamesHttpsOnly() {
        final ReplayedForgejo.RecordingHttpClient http = new ReplayedForgejo.RecordingHttpClient();
        final ForgeAccess loopback = new ForgeAccess("http://127.0.0.1:3997/api/v1", TOKEN);

        assertThatThrownBy(
                        () ->
                                reader(http)
                                        .pullRequest(
                                                loopback,
                                                "http://127.0.0.1:3997/core/probe/pulls/1"))
                .as("a review's repository is an https address in the contract")
                .isInstanceOf(ForgejoReviewReader.InvalidArgument.class)
                .hasMessageStartingWith("api");
        assertThat(http.requests).as("nothing is sent").isEmpty();
    }

    @Test
    void theRecordedMissingScopeRefusesTheToken() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(new ReplayedForgejo.Reply(403, body("08")));

        assertThatThrownBy(() -> reader(http).post(ACCESS, PULL, submission().toString()))
                .as("Forgejo 17 refuses a read-only token's write for its missing scope")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED));
    }

    @Test
    void theRecordedReadOnlyTokenIsRefusedForTheReadingAccount() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(
                                200,
                                body("09")
                                        .replace(
                                                "http://127.0.0.1:3997/",
                                                "https://forgejo.example.org/")),
                        reply("10"),
                        new ReplayedForgejo.Reply(403, body("11")));

        assertThatThrownBy(() -> reader(http).pullRequest(ACCESS, PULL))
                .as("Forgejo 17 names the reading account only to a token with read:user")
                .isInstanceOfSatisfying(
                        ForgeReviewRefused.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED));
    }

    @Test
    void aForbiddenCallOnAnArchivedRepositoryDoesNotBlameTheToken() {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        new ReplayedForgejo.Reply(403, "{\"message\":\"repo is archived\"}"));

        assertThatThrownBy(() -> reader(http).post(ACCESS, PULL, submission().toString()))
                .as("a working token must not be replaced for a refusal of the call")
                .isNotInstanceOf(ForgeReviewRefused.class)
                .hasMessageContaining("repo is archived")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void aRateLimitNamingOnlyItsResetWaitsUntilThen() {
        final Instant now = Instant.parse("2026-11-28T06:00:00Z");
        final RateLimit limit = new RateLimit(60L, 0L, now.plusMillis(40_500), null);

        assertThat(ForgejoReviewReader.retryAfter(limit, Clock.fixed(now, ZoneOffset.UTC)))
                .as("Forgejo said when the window opens again, rounded up to a whole second")
                .isEqualTo(41);
    }

    @Test
    void aRateLimitSayingNothingWaitsNoTime() {
        assertThat(ForgejoReviewReader.retryAfter(null, Clock.systemUTC()))
                .as("the contract answers zero when the forge named no wait")
                .isZero();
    }

    @Test
    void aDismissedRequestForChangesNoLongerBlocksAnApproval() throws IOException {
        final String dismissed =
                "{\"id\":7,\"user\":{\"id\":3,\"login\":\"other\"},\"state\":\"REQUEST_CHANGES\","
                        + "\"dismissed\":true,\"commit_id\":\""
                        + HEAD
                        + "\"}";
        final String approved =
                "{\"id\":8,\"user\":{\"id\":2,\"login\":\"reviewer\"},\"state\":\"APPROVED\","
                        + "\"commit_id\":\""
                        + HEAD
                        + "\"}";
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        pullOverHttps(),
                        new ReplayedForgejo.Reply(200, "[" + dismissed + "," + approved + "]"),
                        new ReplayedForgejo.Reply(200, "{\"id\":1,\"login\":\"core\"}"));

        assertThat(reader(http).pullRequest(ACCESS, PULL).approved())
                .as("Forgejo no longer counts a dismissed review")
                .isTrue();
    }

    @Test
    void theRecordedPullRequestsFactsAreRead() throws IOException {
        final ReviewFacts facts = reader(recordedFacts()).pullRequest(ACCESS, PULL);

        assertThat(facts.head()).as("the recorded head").isEqualTo(HEAD);
        assertThat(facts.state())
                .as("the recorded pull request is open")
                .isEqualTo(ReviewFacts.State.OPEN);
        assertThat(facts.approved()).as("no reviewer approved it").isFalse();
        assertThat(facts.decidedAt()).as("an open pull request has no decision time").isNull();
    }

    @Test
    void theRecordedConversationIsOneCompletePage() throws IOException {
        final ReplayedForgejo.RecordingHttpClient http =
                new ReplayedForgejo.RecordingHttpClient(
                        concat(
                                facts(),
                                new ReplayedForgejo.Reply[] {
                                    reply("02"), reply("03"), reply("04")
                                }));

        final ConversationPage page = reader(http).comments(ACCESS, PULL, null);
        final JsonNode run =
                JSON.readTree(new String(page.document(), StandardCharsets.UTF_8))
                        .path("runs")
                        .path(0);

        assertThat(run.path("results").size())
                .as("two roots and a reply; the pending review is not even read")
                .isEqualTo(3);
        assertThat(run.at("/properties/porelo~1completeness").asText())
                .as("everything was read and nothing held")
                .isEqualTo("complete");
        assertThat(page.cursor()).as("one page holds it all").isNull();
    }

    private static ReplayedForgejo.RecordingHttpClient recordedFacts() throws IOException {
        return new ReplayedForgejo.RecordingHttpClient(facts());
    }

    /** The pull request, its reviews for the verdict, and the reading account. */
    private static ReplayedForgejo.Reply[] facts() throws IOException {
        return new ReplayedForgejo.Reply[] {
            pullOverHttps(),
            reply("02"),
            new ReplayedForgejo.Reply(200, "{\"id\":1,\"login\":\"core\"}")
        };
    }

    /** The recorded pull request, as an https instance would name its repository. */
    private static ReplayedForgejo.Reply pullOverHttps() throws IOException {
        return new ReplayedForgejo.Reply(
                200, body("01").replace("http://127.0.0.1:3997/", "https://forgejo.example.org/"));
    }

    private static ReplayedForgejo.Reply[] concat(
            ReplayedForgejo.Reply[] first, ReplayedForgejo.Reply[] second) {
        final ReplayedForgejo.Reply[] all = new ReplayedForgejo.Reply[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static ReplayedForgejo.Reply reply(String file) throws IOException {
        return new ReplayedForgejo.Reply(200, body(file));
    }

    /** Porelo's submitted comment, as one new-side line on the recorded pull request. */
    private static ObjectNode submission() {
        final ObjectNode document;
        try {
            document =
                    (ObjectNode)
                            JSON.readTree(
                                    resource(
                                            "/org/fuin/porelo/build/api/review/fixtures/submitted-comment.sarif.json"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final ObjectNode run = (ObjectNode) document.at("/runs/0");
        ((ObjectNode) run.get("properties")).put("porelo/pullRequest", PULL);
        ((ObjectNode) run.at("/versionControlProvenance/0"))
                .put("repositoryUri", "https://forgejo.example.org/core/probe")
                .put("revisionId", HEAD);
        final ObjectNode result = result(document);
        result.remove("fixes");
        ((ObjectNode) result.at("/locations/0/physicalLocation/region")).put("endLine", 27);
        return document;
    }

    private static ObjectNode result(ObjectNode document) {
        return (ObjectNode) document.path("runs").path(0).path("results").path(0);
    }

    private static ForgejoReviewReader reader(ReplayedForgejo.RecordingHttpClient http) {
        return new ForgejoReviewReader(
                () -> http, Duration.ofSeconds(60), Duration.ofSeconds(300), Clock.systemUTC());
    }

    private static String body(String file) throws IOException {
        return JSON.readTree(resource("/recordings/pull-request-reviews/" + file + ".json"))
                .path("body")
                .asText();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ForgejoReviewReaderTest.class.getResourceAsStream(name)) {
            return new String(
                    Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
