package org.fuin.porelo.build.github;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildMain;
import org.fuin.porelo.build.api.BuildReader;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Reads what GitHub Actions did with a commit: the verdict across every workflow run of it, and for a failure the
 * failing job's name and the end of its log.
 * <p>
 * A job's log is behind a redirect to a host of GitHub's blob storage; the request there carries no header at all, so
 * the token stays with GitHub's API.
 */
public final class GitHubReader implements BuildReader {

    /** GitHub's own address for its API. */
    public static final String API = "https://api.github.com";

    /** {@code git@host:owner/name(.git)}, {@code ssh://git@host/owner/name(.git)}, {@code https://host/owner/name(.git)}. */
    private static final Pattern UPSTREAM = Pattern.compile(
            "^(?:[a-z+]+://)?(?:[^@/]+@)?[^:/]+[:/](?:\\d+/)?([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");

    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]+");

    /** What GitHub calls a run that failed. */
    private static final List<String> FAILED_RUN = List.of("failure", "timed_out", "startup_failure");

    /** What GitHub calls a job that failed. */
    private static final List<String> FAILED_JOB = List.of("failure", "timed_out");

    private final Http http;

    private final Clock clock;

    /**
     * Constructor.
     *
     * @param http How a GET is sent.
     * @param clock What time it is, for how long a rate limit lasts.
     */
    public GitHubReader(final Http http, final Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    /**
     * Runs the reader as an executable.
     *
     * @param arguments As every reader takes them.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(new GitHubReader(Http.overNetwork(), Clock.systemUTC()), arguments);
    }

    @Override
    public String forge() {
        return "github";
    }

    @Override
    public String head(final Target target, final String branch) {
        final Repository repository = repository(target);
        if (!BRANCH.matcher(branch).matches() || branch.contains("..")) {
            throw new IllegalArgumentException("'" + branch + "' is not a branch Porelo names");
        }
        // Asked of the git refs, which a credential reads with Contents: Read beside Actions: Read. Not of the Actions
        // runs: a push GitHub starts no run for would never be seen, and its "no build" never said.
        final String asked = repository.path() + "/git/ref/heads/" + branch;
        final Http.Response ref = api(target, asked);
        if (ref.status() == 404) {
            // The same 404 for a branch it lacks and for a repository it does not show; asking for the repository
            // tells them apart.
            refuse(api(target, repository.path()), repository, repository.path());
            return "";
        }
        refuse(ref, repository, asked);
        if (json(ref) instanceof Map<?, ?> found && found.get("object") instanceof Map<?, ?> object
                && object.get("sha") instanceof String sha) {
            return sha;
        }
        return "";
    }

    @Override
    public Build look(final Target target, final String commit, final Build.Logs logs) {
        final Repository repository = repository(target);
        if (!commit.matches("[0-9a-f]{40}")) {
            return Build.unknown("'" + commit + "' is not a full commit sha");
        }
        final Http.Response runs = api(target, repository.path() + "/actions/runs?head_sha=" + commit + "&per_page=50");
        refuse(runs, repository, repository.path() + "/actions/runs?head_sha=" + commit);
        final List<Map<?, ?>> found = list(runs, "workflow_runs");
        if (found.isEmpty()) {
            return Build.unknown("no build of " + commit.substring(0, 12) + " at " + repository + " yet");
        }
        boolean running = false;
        boolean queued = true;
        boolean cancelled = false;
        boolean failed = false;
        for (final Map<?, ?> run : found) {
            final String status = lower(run.get("status"));
            final String conclusion = lower(run.get("conclusion"));
            if (!"completed".equals(status)) {
                running = true;
                queued &= List.of("queued", "requested", "waiting", "pending").contains(status);
            } else if (FAILED_RUN.contains(conclusion)) {
                failed = true;
            } else if ("cancelled".equals(conclusion)) {
                cancelled = true;
            }
        }
        if (failed) {
            // A failed run is the verdict even while another still runs: the task can start on it now.
            final List<Build.Job> jobs = jobs(target, repository, found, logs == Build.Logs.ALL && !running);
            return new Build(Build.FAILURE, jobs, jobs.stream().anyMatch(job -> FAILED_JOB.contains(job.result()))
                    ? "" : "no single job failed; the workflow did");
        }
        if (running) {
            return new Build(queued ? Build.QUEUED : Build.RUNNING, "");
        }
        final String verdict = cancelled ? Build.CANCELLED : Build.SUCCESS;
        return new Build(verdict, jobs(target, repository, found, logs == Build.Logs.ALL), "");
    }

    /**
     * Returns every job of every run, in GitHub's order, each with the end of its log when it is wanted: every job's
     * when all are, otherwise each failed one's.
     */
    private List<Build.Job> jobs(final Target target, final Repository repository, final List<Map<?, ?>> runs,
            final boolean everyLog) {
        final List<Build.Job> jobs = new ArrayList<>();
        for (final Map<?, ?> run : runs) {
            final Http.Response listed = api(target, repository.path() + "/actions/runs/" + number(run.get("id"))
                    + "/jobs?per_page=100");
            refuse(listed, repository, repository.path() + "/actions/runs/" + number(run.get("id")) + "/jobs");
            for (final Map<?, ?> job : list(listed, "jobs")) {
                if (jobs.size() >= Build.MOST_JOBS) {
                    return jobs;
                }
                // Finished, what became of it; not yet, how far it is.
                final String result = job.get("conclusion") == null ? lower(job.get("status"))
                        : lower(job.get("conclusion"));
                final String name = name(run.get("name")) + " / " + name(job.get("name"));
                final boolean finished = "completed".equals(lower(job.get("status")));
                if (!finished || !everyLog && !FAILED_JOB.contains(result)) {
                    jobs.add(new Build.Job(name, result));
                    continue;
                }
                final Http.Response log = follow(api(target, repository.path() + "/actions/jobs/"
                        + number(job.get("id")) + "/logs"));
                jobs.add(log.status() == 200 ? new Build.Job(name, result, log.body()) : new Build.Job(name, result));
            }
        }
        return jobs;
    }

    /** Follows a redirect to its target without a single header, so the token stays with GitHub. */
    private Http.Response follow(final Http.Response answer) {
        Http.Response current = answer;
        for (int hop = 0; hop < 5 && current.status() >= 300 && current.status() < 400; hop++) {
            final String location = current.header("location");
            if (location.isEmpty()) {
                return current;
            }
            current = send(URI.create(location), Map.of());
        }
        return current;
    }

    private Http.Response api(final Target target, final String path) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + target.token());
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        final String api = target.api().isEmpty() ? API : target.api();
        return send(URI.create((api.endsWith("/") ? api.substring(0, api.length() - 1) : api) + path), headers);
    }

    private Http.Response send(final URI uri, final Map<String, String> headers) {
        try {
            return http.get(uri, headers);
        } catch (IOException ex) {
            throw new BuildRefused(BuildRefused.Reason.UNREACHABLE, "GitHub did not answer: " + ex.getMessage());
        }
    }

    /**
     * Refuses as GitHub refused - a rate limit, a credential it does not take, a repository it does not show - and
     * returns when it answered.
     */
    void refuse(final Http.Response answer, final Repository repository, final String asked) {
        final int status = answer.status();
        if (status >= 200 && status < 400) {
            return;
        }
        if ((status == 403 || status == 429) && "0".equals(answer.header("x-ratelimit-remaining"))) {
            final String reset = answer.header("x-ratelimit-reset");
            final long seconds = reset.matches("\\d+")
                    ? Math.max(1, Long.parseLong(reset) - clock.instant().getEpochSecond()) : 60;
            throw new BuildRefused(BuildRefused.Reason.RATE_LIMITED, "GitHub's rate limit for this credential is used"
                    + " up" + (reset.matches("\\d+") ? "; it resets at " + Instant.ofEpochSecond(Long.parseLong(reset))
                    : ""), seconds);
        }
        if (status == 429) {
            final String after = answer.header("retry-after");
            throw new BuildRefused(BuildRefused.Reason.RATE_LIMITED, "GitHub asks to slow down (429)",
                    after.matches("\\d+") ? Long.parseLong(after) : 60);
        }
        if (status == 401) {
            throw new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED,
                    "GitHub does not take this credential (401): it is expired, revoked or wrong");
        }
        if (status == 403) {
            // GitHub's own words: a guess at the missing permission was wrong once and sent a person to change a token
            // that was right.
            final String said = json(answer) instanceof Map<?, ?> map && map.get("message") instanceof String message
                    ? message : "no reason given";
            throw new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "GitHub refused this credential " + asked
                    + " (403): " + said + ". A build reader needs Actions: Read and Contents: Read on " + repository);
        }
        if (status == 404) {
            throw new BuildRefused(BuildRefused.Reason.NO_SUCH_REPOSITORY,
                    "GitHub shows no repository " + repository + " to this credential (404)");
        }
        throw new BuildRefused(BuildRefused.Reason.UNREACHABLE, "GitHub answered HTTP " + status);
    }

    /**
     * Returns the repository an upstream address names.
     *
     * @param target What Porelo asked about.
     * @return Its owner and name.
     */
    static Repository repository(final Target target) {
        final Matcher matcher = UPSTREAM.matcher(target.upstream().strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + target.upstream() + "' names no GitHub repository as owner/name");
        }
        return new Repository(matcher.group(1), matcher.group(2));
    }

    /**
     * A repository on GitHub.
     *
     * @param owner Its owner.
     * @param name Its name.
     */
    record Repository(String owner, String name) {

        /** Its path in the API. */
        String path() {
            return "/repos/" + owner + "/" + name;
        }

        @Override
        public String toString() {
            return owner + "/" + name;
        }
    }

    private static @Nullable Object json(final Http.Response answer) {
        return Json.parse(new String(answer.body(), StandardCharsets.UTF_8));
    }

    private static List<Map<?, ?>> list(final Http.Response answer, final String key) {
        final List<Map<?, ?>> found = new ArrayList<>();
        if (json(answer) instanceof Map<?, ?> map && map.get(key) instanceof List<?> entries) {
            for (final Object entry : entries) {
                if (entry instanceof Map<?, ?> each) {
                    found.add(each);
                }
            }
        }
        return found;
    }

    /** A status or a conclusion, compared in lower case. */
    private static String lower(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    /** A name as GitHub spells it: a job's or a workflow's, shown to a person and the agent. */
    private static String name(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(final @Nullable Object value) {
        return value instanceof Number each ? each.longValue() : -1;
    }
}
