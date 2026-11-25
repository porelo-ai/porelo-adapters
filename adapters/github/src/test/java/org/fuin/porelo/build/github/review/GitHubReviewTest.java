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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

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
                .isEqualTo("4467633645");
        assertThat(results.get(1).at("/properties/porelo~1forgeAccountName").asText())
                .isEqualTo("fixture-user");
        assertThat(page.document().at("/runs/0/properties/porelo~1pullRequest").asText())
                .isEqualTo(PULL);
        assertThat(page.document().at("/runs/0/versionControlProvenance/0/revisionId").asText())
                .isEqualTo("ddb2cdaa1ee183a0c5a021a6a9145a3b8a53a7cf");
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
    void aNewCommentIsWrittenWithExactlyOneCall() throws IOException {
        final ReplayHttpClient http = recorded("createReviewComment");

        final String thread =
                new GitHubReview(() -> http)
                        .post(API, TOKEN, PULL, submission("submitted-comment.sarif.json"));

        assertThat(thread).as("the new comment opens its thread").matches("[1-9][0-9]*");
        assertThat(http.requests()).hasSize(1);
        assertThat(http.requests().get(0).method()).isEqualTo("POST");
        assertThat(http.requests().get(0).uri().getPath())
                .isEqualTo("/api/v3/repos/fixture-org/fixture-repo/pulls/11/comments");
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
    void aWriteWhoseAnswerIsLostIsOfUnknownOutcomeAndNotRepeated() {
        final TimingOut http = new TimingOut();

        assertThatThrownBy(
                        () ->
                                new GitHubReview(() -> http)
                                        .post(
                                                API,
                                                TOKEN,
                                                PULL,
                                                submission("submitted-comment.sarif.json")))
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.WRITE_OUTCOME_UNKNOWN);
        assertThat(http.sent).as("one create call, never repeated").isEqualTo(1);
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
    void aGeneralCommentIsNotInTheProfile() {
        final ObjectNode general = submission("submitted-comment.sarif.json");
        ((ObjectNode) general.at("/runs/0/results/0")).remove("locations");
        ((ObjectNode) general.at("/runs/0/results/0/properties"))
                .put("porelo/anchorKind", "general");
        final ReplayHttpClient http = new ReplayHttpClient();

        assertThatThrownBy(() -> new GitHubReview(() -> http).post(API, TOKEN, PULL, general))
                .isInstanceOf(ReviewFailure.class)
                .extracting(e -> ((ReviewFailure) e).kind())
                .isEqualTo(ReviewFailure.Kind.NOT_IN_PROFILE);
        assertThat(http.requests()).isEmpty();
    }

    @Test
    void noMethodReachesAVerdict() {
        assertThat(
                        java.util.Arrays.stream(GitHubReview.class.getMethods())
                                .map(java.lang.reflect.Method::getName)
                                .toList())
                .as("it never approves, requests changes or resolves")
                .noneMatch(
                        name ->
                                name.matches(
                                        "(?i).*(approve|verdict|requestChanges|resolve|merge).*"));
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

    /** Sends nothing and times out on every request, as a write whose answer was lost. */
    private static final class TimingOut extends java.net.http.HttpClient {

        private int sent;

        @Override
        public <T> java.net.http.HttpResponse<T> send(
                HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler)
                throws IOException {
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
