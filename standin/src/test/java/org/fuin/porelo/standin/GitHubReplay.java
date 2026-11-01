package org.fuin.porelo.standin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;

/**
 * GitHub as the forge clients recorded it, answered by path for the pull requests a test sets up,
 * each over commits of a local repository that a fetch reaches instead of GitHub.
 */
final class GitHubReplay {

    static final String REPOSITORY = "fixture-org/fixture-repo";

    static final URI API = URI.create("https://api.github.invalid/");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A recorded review comment, as the tests need to name it. */
    record Recorded(String id, String nodeId, String reviewId) {}

    /** The recorded thread's first comment, which the stand-in's sent comment is taken for. */
    static final Recorded ROOT;

    /** The recorded reply to {@link #ROOT}. */
    static final Recorded REPLY;

    /** The path the recorded comments are on. */
    static final String PATH;

    private static final String RECORDED_HEAD;

    private static final String RECORDED_BASE;

    // The forge clients re-record against a live repository, so every ID and SHA is read here.
    static {
        try {
            final JsonNode pull = JSON.readTree(recorded("pull"));
            RECORDED_HEAD = pull.at("/head/sha").asText();
            RECORDED_BASE = pull.at("/base/sha").asText();
            final JsonNode comments = JSON.readTree(recorded("reviewComments"));
            final JsonNode root = comments.path(0);
            final JsonNode reply = comments.path(1);
            PATH = root.path("path").asText();
            final boolean shaped =
                    comments.size() == 2
                            && !root.hasNonNull("in_reply_to_id")
                            && reply.path("in_reply_to_id")
                                    .asText()
                                    .equals(root.path("id").asText())
                            && reply.path("path").asText().equals(PATH)
                            && root.path("line").asInt() == 1
                            && reply.path("line").asInt() == 1
                            && "Fixture reply".equals(reply.path("body").asText())
                            && RECORDED_HEAD.matches("[0-9a-f]{40}")
                            && RECORDED_BASE.matches("[0-9a-f]{40}")
                            && !PATH.isEmpty();
            if (!shaped) {
                throw new IllegalStateException(
                        "The recorded pull request is not one root comment on line 1 and its reply"
                                + " 'Fixture reply': head="
                                + RECORDED_HEAD
                                + " base="
                                + RECORDED_BASE
                                + " comments="
                                + comments);
            }
            ROOT = recordedComment(root);
            REPLY = recordedComment(reply);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Recorded recordedComment(JsonNode comment) {
        return new Recorded(
                comment.path("id").asText(),
                comment.path("node_id").asText(),
                comment.path("pull_request_review_id").asText());
    }

    /** One pull request as GitHub answers it now; a test changes it between calls. */
    static final class Pull {
        volatile String head;
        volatile String base;
        volatile String reviews;
        volatile String comments;
        volatile String issueComments;
        volatile String threads;

        Pull(String head, String base) {
            this.head = head;
            this.base = base;
            this.reviews = recorded("reviews");
            this.comments = recorded("reviewComments");
            this.issueComments = recorded("issueComments");
            this.threads = recorded("reviewThreads");
        }

        /** No review, comment or thread at all. */
        Pull empty() {
            reviews = "[]";
            comments = "[]";
            issueComments = "[]";
            threads =
                    "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{\"nodes\":[],"
                            + "\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}}}}";
            return this;
        }

        private String shas(String body) {
            return body.replace(RECORDED_HEAD, head).replace(RECORDED_BASE, base);
        }
    }

    final ReplayHttpClient http = new ReplayHttpClient();

    final Git remote;

    final Map<Long, Pull> pulls = new ConcurrentHashMap<>();

    /** Sees every request before it is answered; a test may hold it there. */
    volatile Consumer<HttpRequest> seen = request -> {};

    final MutableClock clock = new MutableClock();

    GitHubReplay(Path remoteDir) {
        this.remote = Git.open(remoteDir);
        http.otherwise(this::answer);
    }

    /**
     * Makes a pull request: its base, and a head adding the recorded comments' file.
     *
     * @param number The pull request.
     * @param baseParents The base's parents.
     * @return The pull request.
     */
    Pull pull(long number, List<String> baseParents) {
        final String base =
                remote.commitTree(Map.of("README", "base\n"), baseParents, "base " + number);
        final String head =
                remote.commitTree(
                        Map.of("README", "base\n", PATH, "fixture-complete\nsecond\n"),
                        List.of(base),
                        "head " + number);
        return put(number, head, base);
    }

    Pull put(long number, String head, String base) {
        remote.updateRef("refs/pull/" + number + "/head", head);
        remote.updateRef("refs/heads/base-" + number, base);
        final Pull pull = new Pull(head, base);
        pulls.put(number, pull);
        return pull;
    }

    /** The stand-in's clone, fetching from the local repository instead of GitHub. */
    Git mirror(Path dir) {
        return Git.open(dir)
                .fetchingInsteadOf(
                        "https://github.com/" + REPOSITORY + ".git",
                        remote.dir().toUri().toString());
    }

    GitHubSource source(Git mirror, String token) {
        return new GitHubSource(
                new GitHubClient(API, http),
                new GitHubGraphQl(API, http),
                token,
                REPOSITORY,
                "github.com",
                mirror,
                clock);
    }

    /** Every write the stand-in made to GitHub. */
    List<HttpRequest> writes() {
        return http.requests().stream()
                .filter(r -> !"GET".equals(r.method()))
                .filter(r -> !isQuery(r))
                .toList();
    }

    private ReplayHttpClient.Answer answer(HttpRequest request) {
        seen.accept(request);
        final String[] path = request.uri().getPath().split("/");
        if ("graphql".equals(path[path.length - 1])) {
            return graphQl(request);
        }
        // /repos/<owner>/<repo>/<kind>/<n>[/<what>]
        final long number = Long.parseLong(path[5]);
        final Pull pull = pulls.get(number);
        if (pull == null) {
            return new ReplayHttpClient.Answer(404, Map.of(), "{\"message\":\"Not Found\"}");
        }
        final String what = path.length > 6 ? path[6] : "";
        final boolean get = "GET".equals(request.method());
        if ("issues".equals(path[4])) {
            return ok(pull.shas(pull.issueComments));
        }
        return switch (what) {
            case "" -> ok(pull.shas(recorded("pull")));
            case "reviews" -> ok(pull.shas(pull.reviews));
            case "comments" ->
                    get
                            ? ok(pull.shas(pull.comments))
                            : new ReplayHttpClient.Answer(
                                    201,
                                    Map.of("content-type", List.of("application/json")),
                                    pull.shas(
                                            path.length > 8
                                                    ? recorded("replyReviewComment")
                                                    : recorded("createReviewComment")));
            default -> new ReplayHttpClient.Answer(404, Map.of(), "{\"message\":\"Not Found\"}");
        };
    }

    private ReplayHttpClient.Answer graphQl(HttpRequest request) {
        final JsonNode body = body(request);
        final String query = body.path("query").asText();
        if (query.contains("reviewThreads")) {
            final Pull pull = pulls.get(body.path("variables").path("number").asLong());
            if (pull == null) {
                return ok("{\"data\":{\"repository\":{\"pullRequest\":null}}}");
            }
            return ok(pull.shas(pull.threads));
        }
        if (query.contains("resolveReviewThread")) {
            return ok(recorded("resolveReviewThread"));
        }
        return ok(recorded("threadComments"));
    }

    private static boolean isQuery(HttpRequest request) {
        if (!request.uri().getPath().endsWith("/graphql")) {
            return false;
        }
        return !body(request).path("query").asText().strip().startsWith("mutation");
    }

    private static ReplayHttpClient.Answer ok(String body) {
        return new ReplayHttpClient.Answer(
                200, Map.of("content-type", List.of("application/json; charset=utf-8")), body);
    }

    /** A recorded answer's body, as GitHub sent it. */
    static String recorded(String name) {
        try {
            return ReplayHttpClient.recorded(name).get(0).body();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A recorded list with one item changed. */
    static String changed(String list, int index, String field, int value) {
        try {
            final ArrayNode items = (ArrayNode) JSON.readTree(list);
            ((com.fasterxml.jackson.databind.node.ObjectNode) items.get(index)).put(field, value);
            return JSON.writeValueAsString(items);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static JsonNode body(HttpRequest request) {
        final var publisher = request.bodyPublisher().orElseThrow();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        publisher.subscribe(
                new Flow.Subscriber<ByteBuffer>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        final byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        out.writeBytes(bytes);
                    }

                    @Override
                    public void onError(Throwable throwable) {}

                    @Override
                    public void onComplete() {}
                });
        try {
            return JSON.readTree(out.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A clock a test moves, so a read is stale when the test says. */
    static final class MutableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
