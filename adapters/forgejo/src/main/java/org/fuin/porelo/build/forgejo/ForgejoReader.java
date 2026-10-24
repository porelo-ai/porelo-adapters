package org.fuin.porelo.build.forgejo;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildMain;
import org.fuin.porelo.build.api.BuildReader;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.build.http.Http;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/** Reads Forgejo Actions through an explicitly configured API, without following redirects. */
public final class ForgejoReader implements BuildReader {
    private static final Pattern UPSTREAM =
            Pattern.compile(
                    "^(?:[a-z+]+://)?(?:[^@/]+@)?[^:/]+[:/](?:\\d+/)?([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final List<String> OUTSTANDING = List.of("waiting", "blocked", "running");
    private static final List<String> STATUSES =
            List.of("waiting", "blocked", "running", "failure", "cancelled", "success", "skipped");
    private static final int PER_PAGE = 50;
    private static final int MOST_PAGES = 20;
    private final Http http;

    /**
     * Creates a reader with an HTTP transport.
     *
     * @param http How each request is sent; redirects must not be followed.
     */
    public ForgejoReader(final Http http) {
        this.http = http;
    }

    /**
     * Runs the build protocol server.
     *
     * @param arguments The build executable's command and socket path.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(new ForgejoReader(Http.overNetwork()), arguments);
    }

    @Override
    public String forge() {
        return "forgejo";
    }

    @Override
    public String head(final Target target, final String branch) {
        requireApi(target);
        final Repository repository = repository(target);
        if (!BRANCH.matcher(branch).matches() || branch.contains("..")) {
            throw new IllegalArgumentException("'" + branch + "' is not a branch Porelo names");
        }
        final String path = repository.path() + "/branches/" + branch;
        final Http.Response response = api(target, path, 0);
        if (response.status() == 404) {
            // Forgejo uses the same 404 for an absent branch and an invisible repository.
            final Http.Response confirmation = api(target, repository.path(), 0);
            refuse(confirmation);
            json(confirmation);
            return "";
        }
        refuse(response);
        final Map<?, ?> found = object(json(response));
        final Map<?, ?> commit = object(found.get("commit"));
        final String sha = text(commit.get("id"));
        if (!sha.matches("[0-9a-f]{40}")) throw unreachable("Forgejo returned no branch commit id");
        return sha;
    }

    @Override
    public Build build(final Target target, final String commit, final Build.Logs logs) {
        requireApi(target);
        final Repository repository = repository(target);
        if (!commit.matches("[0-9a-f]{40}"))
            return Build.unknown("'" + commit + "' is not a full commit sha");
        final List<Map<?, ?>> runs = runs(target, repository, commit);
        if (runs.isEmpty())
            return Build.unknown(
                    "no build of " + commit.substring(0, 12) + " at " + repository + " yet");
        boolean failed = false;
        boolean outstanding = false;
        boolean running = false;
        boolean cancelled = false;
        for (final Map<?, ?> run : runs) {
            final String status = text(run.get("status"));
            if (!STATUSES.contains(status))
                return Build.unknown("unknown Forgejo status: " + status);
            failed |= status.equals("failure");
            outstanding |= OUTSTANDING.contains(status);
            running |= status.equals("running");
            cancelled |= status.equals("cancelled");
        }
        if (!failed && outstanding) return new Build(running ? Build.RUNNING : Build.QUEUED, "");
        final Build.Logs wanted = logs == Build.Logs.ALL && outstanding ? Build.Logs.FAILURE : logs;
        final List<Build.Job> jobs = jobs(target, repository, runs, wanted);
        return new Build(
                failed ? Build.FAILURE : cancelled ? Build.CANCELLED : Build.SUCCESS,
                jobs,
                failed && jobs.stream().noneMatch(job -> job.result().equals("failure"))
                        ? "no single job failed; the workflow did"
                        : "");
    }

    private List<Map<?, ?>> runs(
            final Target target, final Repository repository, final String commit) {
        final List<Map<?, ?>> runs = new ArrayList<>();
        final String path =
                repository.path()
                        + "/actions/runs?head_sha="
                        + commit
                        + "&limit="
                        + PER_PAGE
                        + "&page=";
        for (int page = 1; page <= MOST_PAGES; page++) {
            final Http.Response response = api(target, path + page, 0);
            refuse(response);
            final Map<?, ?> result = object(json(response));
            final long total = nonnegative(result.get("total_count"));
            final List<Map<?, ?>> entries = entries(result.get("workflow_runs"));
            runs.addAll(entries);
            if (runs.size() == total) return runs;
            // A short page can have a successor; only total_count says the list is whole.
            if (entries.isEmpty() || runs.size() > total)
                throw unreachable("Forgejo returned an incomplete run list");
        }
        throw unreachable(
                "Forgejo run list exceeds " + MOST_PAGES + " pages; its verdict is incomplete");
    }

    private List<Build.Job> jobs(
            final Target target,
            final Repository repository,
            final List<Map<?, ?>> runs,
            final Build.Logs logs) {
        final List<ListedJob> all = new ArrayList<>();
        for (final Map<?, ?> run : runs) {
            final Http.Response response =
                    api(
                            target,
                            repository.path() + "/actions/runs/" + id(run.get("id")) + "/jobs",
                            0);
            refuse(response);
            for (final Map<?, ?> job : entries(json(response))) {
                all.add(
                        new ListedJob(
                                text(run.get("workflow_id")) + " / " + text(job.get("name")),
                                text(job.get("status")),
                                id(job.get("id"))));
            }
        }
        final List<Build.Job> jobs = new ArrayList<>();
        for (final ListedJob job : kept(all)) {
            if (logs == Build.Logs.NONE
                    || OUTSTANDING.contains(job.result())
                    || logs == Build.Logs.FAILURE && !job.failed()) {
                jobs.add(new Build.Job(job.name(), job.result()));
                continue;
            }
            final Http.Response response =
                    api(
                            target,
                            repository.path() + "/actions/jobs/" + job.id() + "/logs",
                            Build.TAIL);
            if (response.status() == 404 && unexecuted(response)) {
                jobs.add(new Build.Job(job.name(), job.result()));
            } else {
                refuse(response);
                jobs.add(new Build.Job(job.name(), job.result(), response.body()));
            }
        }
        return jobs;
    }

    private static List<ListedJob> kept(final List<ListedJob> all) {
        if (all.size() <= Build.MOST_JOBS) return all;
        final long failed = all.stream().filter(ListedJob::failed).count();
        final long others = Math.max(0, Build.MOST_JOBS - failed);
        long keptFailed = 0;
        long keptOthers = 0;
        final List<ListedJob> kept = new ArrayList<>();
        for (final ListedJob job : all) {
            if (job.failed() && keptFailed < Build.MOST_JOBS) {
                kept.add(job);
                keptFailed++;
            } else if (!job.failed() && keptOthers < others) {
                kept.add(job);
                keptOthers++;
            }
        }
        return kept;
    }

    private record ListedJob(String name, String result, long id) {
        boolean failed() {
            return result.equals("failure");
        }
    }

    private Http.Response api(final Target target, final String path, final int tail) {
        final String base = requireApi(target);
        try {
            // Every URL is constructed here; external response URLs never receive credentials.
            return http.get(
                    URI.create(base + path),
                    Map.of(
                            "Authorization",
                            "token " + target.token(),
                            "Accept",
                            "application/json"),
                    tail);
        } catch (IOException ex) {
            throw unreachable("Forgejo did not answer: " + ex.getClass().getSimpleName());
        }
    }

    private static String requireApi(final Target target) {
        if (target.api().isBlank())
            throw new IllegalArgumentException(
                    "Target.api is required: Forgejo has no public default API");
        final URI uri = URI.create(target.api());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Target.api must name an HTTP or HTTPS API without credentials, query, or fragment");
        }
        final String base = target.api();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static void refuse(final Http.Response response) {
        final int status = response.status();
        if (status >= 200 && status < 300) return;
        if (status == 401 || status == 403)
            throw new BuildRefused(
                    BuildRefused.Reason.CREDENTIAL_REFUSED,
                    "Forgejo refused this credential (" + status + ")");
        if (status == 404 || status >= 300 && status < 400)
            throw new BuildRefused(
                    BuildRefused.Reason.NO_SUCH_REPOSITORY,
                    "Forgejo shows no repository at this address (" + status + ")");
        if (status == 429) {
            final String after = response.header("retry-after");
            long seconds = 60;
            try {
                if (after.matches("[0-9]+")) seconds = Long.parseLong(after);
            } catch (NumberFormatException ex) {
                throw unreachable("Forgejo returned an invalid Retry-After");
            }
            throw new BuildRefused(
                    BuildRefused.Reason.RATE_LIMITED, "Forgejo asks to slow down (429)", seconds);
        }
        throw unreachable("Forgejo answered HTTP " + status);
    }

    private static boolean unexecuted(final Http.Response response) {
        return json(response) instanceof Map<?, ?> map
                && "job has not been executed yet".equals(map.get("message"));
    }

    private static @Nullable Object json(final Http.Response response) {
        try {
            return Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (JsonException ex) {
            throw unreachable("Forgejo answered with something that is not JSON");
        }
    }

    private static Map<?, ?> object(final @Nullable Object value) {
        if (value instanceof Map<?, ?> object) return object;
        throw unreachable("Forgejo returned a JSON value where an object was required");
    }

    private static List<Map<?, ?>> entries(final @Nullable Object value) {
        if (!(value instanceof List<?> list))
            throw unreachable("Forgejo returned a JSON value where an array was required");
        final List<Map<?, ?>> result = new ArrayList<>();
        for (final Object entry : list) result.add(object(entry));
        return result;
    }

    private static String text(final @Nullable Object value) {
        if (value instanceof String text) return text;
        throw unreachable("Forgejo returned a non-text field");
    }

    private static long nonnegative(final @Nullable Object value) {
        if (value instanceof Number number
                && number.longValue() >= 0
                && number.doubleValue() == number.longValue()) return number.longValue();
        throw unreachable("Forgejo returned an invalid count or id");
    }

    private static long id(final @Nullable Object value) {
        final long id = nonnegative(value);
        if (id == 0) throw unreachable("Forgejo returned a zero id");
        return id;
    }

    private static BuildRefused unreachable(final String detail) {
        return new BuildRefused(BuildRefused.Reason.UNREACHABLE, detail);
    }

    private static Repository repository(final Target target) {
        final Matcher matcher = UPSTREAM.matcher(target.upstream().strip());
        if (!matcher.matches()
                || List.of(".", "..").contains(matcher.group(1))
                || List.of(".", "..").contains(matcher.group(2))) {
            throw new IllegalArgumentException(
                    "The upstream names no Forgejo repository as owner/name");
        }
        return new Repository(matcher.group(1), matcher.group(2));
    }

    private record Repository(String owner, String name) {
        String path() {
            return "/repos/" + owner + "/" + name;
        }

        @Override
        public String toString() {
            return owner + "/" + name;
        }
    }
}
