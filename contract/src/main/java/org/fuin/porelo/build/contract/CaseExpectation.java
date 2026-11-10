package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Values evidenced by a case's exchanges; contract rules never come from this file. */
record CaseExpectation(String head, List<Job> jobs, long retryAfter, String url, List<Run> runs) {
    // A line pointer of the shape #L<n> reads as an issue citation to the shared checks.
    private static final String LINE = "#line=";

    CaseExpectation(final String head, final List<Job> jobs, final long retryAfter) {
        this(head, jobs, retryAfter, "", List.of());
    }

    CaseExpectation(
            final String head, final List<Job> jobs, final long retryAfter, final String url) {
        this(head, jobs, retryAfter, url, List.of());
    }

    record Run(String name, String result, @Nullable String event, @Nullable String id) {}

    record Job(
            String name,
            String result,
            byte @Nullable [] log,
            @Nullable String run,
            @Nullable List<String> failedTests,
            @Nullable String url) {
        boolean failed() {
            return List.of("failure", "timed_out", "startup_failure").contains(result);
        }

        boolean finished() {
            return List.of(
                            "success",
                            "failure",
                            "cancelled",
                            "skipped",
                            "neutral",
                            "timed_out",
                            "action_required",
                            "startup_failure")
                    .contains(result);
        }
    }

    static CaseExpectation read(
            final Path directory,
            final ContractCase test,
            final List<RecordedForge.Exchange> exchanges)
            throws IOException {
        return read(directory, test, exchanges, "forge.example");
    }

    static CaseExpectation read(
            final Path directory,
            final ContractCase test,
            final List<RecordedForge.Exchange> exchanges,
            final String webHost)
            throws IOException {
        final Map<?, ?> map =
                object(Json.parse(Files.readString(directory.resolve("expect.json"))));
        final Evidence evidence = new Evidence(exchanges, webHost);
        if (test.expected() instanceof ContractCase.HeadAnswer expected) {
            keys(map, expected.absent() ? Set.of() : Set.of("head", "headFrom"));
            if (expected.absent()) return new CaseExpectation("", List.of(), 0);
            final String sha = text(map, "head");
            assertThat(sha).as("recorded head SHA").matches("[0-9a-f]{40}");
            assertThat(evidence.value(text(map, "headFrom")))
                    .as("expect.json head must appear in a recorded body")
                    .isEqualTo(sha);
            return new CaseExpectation(sha, List.of(), 0);
        }
        if (test.expected() instanceof ContractCase.PullRequestAnswer) {
            keys(map, Set.of("url", "urlFrom"));
            final String url = text(map, "url");
            assertThat(evidence.value(text(map, "urlFrom")))
                    .as("expect.json url must appear in a recorded body")
                    .isEqualTo(url);
            return new CaseExpectation("", List.of(), 0, url);
        }
        if (test.expected() instanceof ContractCase.Refusal refusal) {
            if (refusal.reason() != BuildRefused.Reason.RATE_LIMITED) {
                keys(map, Set.of());
                return new CaseExpectation("", List.of(), 0);
            }
            keys(map, Set.of("retryAfter"));
            if (!(map.get("retryAfter") instanceof Number number)
                    || number.longValue() < 0
                    || number.doubleValue() != number.longValue()) {
                throw new IOException("expect.json retryAfter must be a nonnegative integer");
            }
            final List<String> delays =
                    exchanges.stream()
                            .flatMap(exchange -> exchange.headers().entrySet().stream())
                            .filter(header -> header.getKey().equalsIgnoreCase("retry-after"))
                            .map(Map.Entry::getValue)
                            .toList();
            assertThat(number.longValue())
                    .as(
                            "expect.json retry-after must match the recorded header, or the"
                                    + " 60-second fallback when absent")
                    .isEqualTo(delays.isEmpty() ? 60 : Long.parseLong(delays.getFirst()));
            return new CaseExpectation("", List.of(), number.longValue());
        }
        final boolean answersRuns =
                !Build.UNKNOWN.equals(((ContractCase.Verdict) test.expected()).value());
        keys(map, answersRuns ? Set.of("jobs", "runs") : Set.of("jobs"));
        final List<Run> runs = answersRuns ? runs(map, evidence) : List.of();
        if (!(map.get("jobs") instanceof List<?> supplied))
            throw new IOException("expect.json jobs must be an array");
        final List<Job> jobs = new ArrayList<>();
        for (final Object value : supplied) {
            final Map<?, ?> job = object(value);
            optionalKeys(
                    job,
                    Set.of("name", "result", "nameFrom", "resultFrom"),
                    Set.of(
                            "log",
                            "run",
                            "runFrom",
                            "failedTests",
                            "failedTestsFrom",
                            "url",
                            "urlFrom"));
            if (!(job.get("nameFrom") instanceof List<?> sources) || sources.isEmpty())
                throw new IOException("Each job needs nonempty nameFrom references");
            final List<String> parts = new ArrayList<>();
            for (final Object source : sources) {
                if (!(source instanceof String reference)
                        || !(evidence.value(reference) instanceof String part))
                    throw new IOException("nameFrom must reference recorded strings");
                parts.add(part);
            }
            final String name = text(job, "name");
            assertThat(name)
                    .as("expect.json job name must appear in a recorded job list")
                    .isEqualTo(String.join(" / ", parts));
            final String nameSource = (String) sources.getLast();
            evidence.job(nameSource, text(job, "resultFrom"));
            final String result = text(job, "result");
            assertThat(evidence.value(text(job, "resultFrom")))
                    .as("expect.json job result must appear in its recorded job")
                    .isEqualTo(result);
            jobs.add(
                    new Job(
                            name,
                            result,
                            job.containsKey("log")
                                    ? evidence.exchange(text(job, "log")).body()
                                    : null,
                            evidence.jobRun(job, nameSource),
                            evidence.failedTests(job),
                            evidence.jobUrl(job, nameSource)));
        }
        evidence.verifyJobs();
        return new CaseExpectation("", List.copyOf(jobs), 0, "", runs);
    }

    private static List<Run> runs(final Map<?, ?> map, final Evidence evidence) throws IOException {
        if (!(map.get("runs") instanceof List<?> supplied))
            throw new IOException("expect.json runs must be an array");
        final List<Run> runs = new ArrayList<>();
        for (final Object value : supplied) {
            final Map<?, ?> run = object(value);
            optionalKeys(
                    run,
                    Set.of("name", "result", "nameFrom", "resultFrom"),
                    Set.of("event", "eventFrom", "id", "idFrom"));
            final String nameFrom = text(run, "nameFrom");
            final String resultFrom = text(run, "resultFrom");
            evidence.run(nameFrom, resultFrom);
            final String name = text(run, "name");
            assertThat(evidence.value(nameFrom))
                    .as("expect.json run name must appear in its recorded run")
                    .isEqualTo(name);
            final String result = text(run, "result");
            assertThat(evidence.value(resultFrom))
                    .as("expect.json run result must appear in its recorded run")
                    .isEqualTo(result);
            runs.add(
                    new Run(
                            name,
                            result,
                            evidence.field(
                                    run, "event", nameFrom.substring(0, nameFrom.lastIndexOf('/'))),
                            evidence.field(
                                    run, "id", nameFrom.substring(0, nameFrom.lastIndexOf('/')))));
        }
        evidence.verifyRuns();
        return List.copyOf(runs);
    }

    /** The runs an answer carries: the first ones in the forge's order. */
    List<Run> retainedRuns() {
        final List<Run> retained = new ArrayList<>();
        final Set<String> seen = new java.util.HashSet<>();
        for (final Run run : runs) {
            if (retained.size() == Build.MOST_RUNS) break;
            if (run.id() == null || seen.add(run.id())) retained.add(run);
        }
        return List.copyOf(retained);
    }

    List<Job> retainedJobs() {
        final long failures = jobs.stream().filter(Job::failed).count();
        int others = (int) Math.max(0, Build.MOST_JOBS - failures);
        final List<Job> retained = new ArrayList<>();
        for (final Job job : jobs) {
            if (retained.size() == Build.MOST_JOBS) break;
            if (job.failed() || others-- > 0) retained.add(job);
        }
        return List.copyOf(retained);
    }

    static byte[] tail(final byte[] body, final int limit) {
        return Arrays.copyOfRange(body, Math.max(0, body.length - limit), body.length);
    }

    private static void keys(final Map<?, ?> map, final Set<String> expected) throws IOException {
        if (!map.keySet().equals(expected))
            throw new IOException(
                    "expect.json fields must be " + expected + "; got " + map.keySet());
    }

    private static void optionalKeys(
            final Map<?, ?> map, final Set<String> required, final Set<String> optional)
            throws IOException {
        final Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        if (!map.keySet().containsAll(required) || !allowed.containsAll(map.keySet()))
            throw new IOException(
                    "expect.json fields must include "
                            + required
                            + " with only "
                            + optional
                            + "; got "
                            + map.keySet());
    }

    private static Map<?, ?> object(final @Nullable Object value) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("expect.json needs JSON objects");
    }

    private static String text(final Map<?, ?> map, final String key) throws IOException {
        if (map.get(key) instanceof String value) return value;
        throw new IOException("expect.json needs string field " + key);
    }

    private static final class Evidence {
        private final List<RecordedForge.Exchange> exchanges;
        private final Map<String, List<String>> lists = new LinkedHashMap<>();
        private final List<String> ordered = new ArrayList<>();
        private final List<String> runs = new ArrayList<>();
        private final Map<String, Integer> runOrder = new LinkedHashMap<>();
        private final String webHost;

        Evidence(final List<RecordedForge.Exchange> exchanges, final String webHost) {
            this.exchanges = exchanges;
            this.webHost = webHost;
        }

        @Nullable String field(final Map<?, ?> expected, final String field, final String item)
                throws IOException {
            if (!paired(expected, field)) {
                if (value(item) instanceof Map<?, ?> recorded) {
                    final @Nullable Object source = recorded.get(field);
                    if (field.equals("id") && source instanceof Long id && id > 0
                            || field.equals("event")
                                    && source instanceof String event
                                    && !event.isBlank()
                                    && event.codePointCount(0, event.length()) <= Build.MOST_ID
                                    && event.codePoints().noneMatch(Evidence::unprintable))
                        throw new IOException(
                                "The recorded run's "
                                        + field
                                        + " needs an expectation and evidence");
                }
                return null;
            }
            final String from = text(expected, field + "From");
            if (!from.equals(item + "/" + field))
                throw new IOException(field + " evidence must come from the same recorded run");
            final String actual = text(expected, field);
            final @Nullable Object source = value(from);
            if (field.equals("id")) {
                if (!(source instanceof Long id) || id <= 0)
                    throw new IOException("Run id evidence must be a positive integer");
                assertThat(actual)
                        .as("the run id retains the recorded integer exactly")
                        .isEqualTo(Long.toString(id));
            } else {
                assertThat(source)
                        .as("the event must appear in the same recorded run")
                        .isEqualTo(actual);
                assertThat(actual.codePointCount(0, actual.length()))
                        .as("a recorded event fits the protocol")
                        .isBetween(1, Build.MOST_ID);
                assertThat(actual).as("a recorded event is one printable line").isNotBlank();
                assertThat(actual.codePoints().anyMatch(Evidence::unprintable))
                        .as("an event cannot forge a status line")
                        .isFalse();
            }
            return actual;
        }

        private static boolean paired(final Map<?, ?> expected, final String field)
                throws IOException {
            if (expected.containsKey(field) != expected.containsKey(field + "From"))
                throw new IOException(field + " and " + field + "From must be supplied together");
            return expected.containsKey(field);
        }

        private static boolean unprintable(final int point) {
            return Character.isISOControl(point)
                    || Character.getType(point) == Character.FORMAT
                    || Character.getType(point) == Character.LINE_SEPARATOR
                    || Character.getType(point) == Character.PARAGRAPH_SEPARATOR
                    || Character.getType(point) == Character.SURROGATE;
        }

        @Nullable String jobRun(final Map<?, ?> expected, final String name) throws IOException {
            if (!paired(expected, "run")) {
                final String file = name.substring(0, name.indexOf('#'));
                if (exchange(file)
                        .path()
                        .matches(".*/actions/runs/[1-9][0-9]{0,18}/jobs(?:\\?.*)?"))
                    throw new IOException("A recorded job list needs run and runFrom evidence");
                return null;
            }
            final String from = text(expected, "runFrom");
            if (!from.endsWith("/id") || !runs.contains(from.substring(0, from.length() - 3)))
                throw new IOException("Job run evidence must name a recorded run's id");
            if (!(value(from) instanceof Long id) || id <= 0)
                throw new IOException("Job run evidence must be a positive integer");
            final String run = text(expected, "run");
            assertThat(run)
                    .as("a job's run id matches its recorded run")
                    .isEqualTo(Long.toString(id));
            final String file = name.substring(0, name.indexOf('#'));
            assertThat(exchange(file).path())
                    .as("the jobs request ties each job to its recorded run")
                    .matches(".*/actions/runs/" + run + "/jobs(?:\\?.*)?");
            final String item = name.substring(0, name.lastIndexOf('/'));
            if (value(item) instanceof Map<?, ?> job && job.containsKey("run_id"))
                assertThat(job.get("run_id"))
                        .as("GitHub's run_id agrees with the listing run")
                        .isEqualTo(id);
            runOrder.put(
                    item.substring(0, item.lastIndexOf('/')),
                    runs.indexOf(from.substring(0, from.length() - 3)));
            return run;
        }

        @Nullable String jobUrl(final Map<?, ?> expected, final String name) throws IOException {
            if (!paired(expected, "url")) {
                final String item = name.substring(0, name.lastIndexOf('/'));
                if (value(item) instanceof Map<?, ?> job && ownPage(job.get("html_url")))
                    throw new IOException("The recorded job page needs url and urlFrom evidence");
                if (expected.containsKey("runFrom")) {
                    final String run = text(expected, "runFrom").replaceFirst("/id$", "");
                    if (value(run) instanceof Map<?, ?> recorded
                            && recorded.containsKey("workflow_id")
                            && ownPage(recorded.get("html_url")))
                        throw new IOException(
                                "The recorded run page needs job url and urlFrom evidence");
                }
                return null;
            }
            final String from = text(expected, "urlFrom");
            final String item = name.substring(0, name.lastIndexOf('/'));
            final @Nullable Object source = value(from);
            if (!(source instanceof String page))
                throw new IOException("Job page evidence must be a string");
            final String recorded;
            if (from.equals(item + "/html_url")) {
                recorded = page;
            } else if (expected.containsKey("runFrom")
                    && from.equals(text(expected, "runFrom").replaceFirst("/id$", "/html_url"))) {
                recorded = page + "/jobs/" + item.substring(item.lastIndexOf('/') + 1);
            } else {
                throw new IOException("Job page evidence must belong to its recorded job or run");
            }
            final String url = text(expected, "url");
            assertThat(url)
                    .as("the job page is evidenced by its job or its run and job index")
                    .isEqualTo(recorded);
            assertThat(new Build.Job("", "", new byte[0], null, null, url).url())
                    .as("the recorded job page satisfies the public URL contract")
                    .isEqualTo(url);
            assertThat(ownPage(url))
                    .as("the recorded job page stays on the forge's web host")
                    .isTrue();
            return url;
        }

        private boolean ownPage(final @Nullable Object source) {
            if (!(source instanceof String page)
                    || new Build.Job("", "", new byte[0], null, null, page).url() == null)
                return false;
            return webHost.equalsIgnoreCase(java.net.URI.create(page).getHost());
        }

        @Nullable List<String> failedTests(final Map<?, ?> expected) throws IOException {
            if (!paired(expected, "failedTests")) {
                if (expected.containsKey("log")
                        && List.of("failure", "timed_out", "startup_failure")
                                .contains(text(expected, "result"))
                        && !testEntries(exchange(text(expected, "log")).body()).isEmpty())
                    throw new IOException(
                            "A recorded failure summary needs failedTests and failedTestsFrom"
                                    + " evidence");
                return null;
            }
            if (!List.of("failure", "timed_out", "startup_failure")
                    .contains(text(expected, "result")))
                throw new IOException("Only a failed job can name failed tests");
            if (!(expected.get("failedTests") instanceof List<?> tests)
                    || !(expected.get("failedTestsFrom") instanceof List<?> sources)
                    || tests.isEmpty()
                    || tests.size() > Build.MOST_FAILED_TESTS
                    || tests.size() != sources.size())
                throw new IOException(
                        "Failed tests need one log-line reference per name, within the public"
                                + " bound");
            final String log = text(expected, "log");
            final Map<Integer, String> entries = testEntries(exchange(log).body());
            final List<String> names = new ArrayList<>();
            final List<Integer> lines = new ArrayList<>();
            for (int index = 0; index < tests.size(); index++) {
                if (!(tests.get(index) instanceof String test)
                        || !(sources.get(index) instanceof String from)
                        || !from.startsWith(log + LINE)
                        || !from.substring(log.length() + LINE.length()).matches("[1-9][0-9]{0,8}"))
                    throw new IOException(
                            "Failed test evidence must name a line in the job's own recorded log");
                final int line = Integer.parseInt(from.substring(log.length() + LINE.length()));
                assertThat(entries.get(line))
                        .as(
                                "a failed test appears in a complete failure or error group at its"
                                        + " evidence line")
                        .isEqualTo(test);
                names.add(test);
                lines.add(line);
            }
            assertThat(names)
                    .as("recorded failed tests are distinct in log order")
                    .doesNotHaveDuplicates();
            assertThat(lines)
                    .as("failed test evidence follows the log's order")
                    .isSorted()
                    .doesNotHaveDuplicates();
            assertThat(names)
                    .as("the expectation includes every distinct bounded failure in the summary")
                    .containsExactlyElementsOf(
                            entries.values().stream()
                                    .distinct()
                                    .limit(Build.MOST_FAILED_TESTS)
                                    .toList());
            return List.copyOf(names);
        }

        private static Map<Integer, String> testEntries(final byte[] log) {
            final Map<Integer, String> completed = new LinkedHashMap<>();
            final Map<Integer, String> pending = new LinkedHashMap<>();
            boolean block = false;
            boolean errors = false;
            final int start = Math.max(0, log.length - Build.TAIL);
            int precedingLines = 0;
            for (int index = 0; index < start; index++) {
                if (log[index] == '\n') precedingLines++;
            }
            final String[] lines =
                    new String(tail(log, Build.TAIL), StandardCharsets.UTF_8).split("\\n", -1);
            final java.util.regex.Pattern entry =
                    java.util.regex.Pattern.compile("^\\[ERROR\\]   ([^\\s:]+\\.[^\\s:]+):[0-9].*");
            for (int index = 0; index < lines.length; index++) {
                if (lines[index].length() > 4096) continue;
                final String line =
                        lines[index]
                                .replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]", "")
                                .replaceFirst("^\\d{4}-\\d{2}-\\d{2}T[^ ]+ ", "")
                                .stripTrailing();
                if (line.equals("[INFO] Results:")) {
                    block = true;
                    errors = false;
                    pending.clear();
                } else if (block
                        && index < lines.length - 1
                        && line.matches("\\[(?:INFO|ERROR)\\] Tests run: [0-9]+(?:,.*)?")) {
                    completed.putAll(pending);
                    block = false;
                    pending.clear();
                } else if (block
                        && (line.equals("[ERROR] Failures:") || line.equals("[ERROR] Errors:"))) {
                    errors = true;
                } else if (block && line.matches("\\[(?:WARNING|ERROR|INFO)\\] Flakes:")) {
                    errors = false;
                } else if (block && errors) {
                    final java.util.regex.Matcher match = entry.matcher(line);
                    if (match.matches()
                            && match.group(1).codePointCount(0, match.group(1).length())
                                    <= Build.MOST_TEXT
                            && match.group(1)
                                    .codePoints()
                                    .noneMatch(
                                            point ->
                                                    unprintable(point)
                                                            || Character.isWhitespace(point)
                                                            || Character.isSpaceChar(point)))
                        pending.put(precedingLines + index + 1, match.group(1));
                }
            }
            return completed;
        }

        RecordedForge.Exchange exchange(final String file) throws IOException {
            for (final RecordedForge.Exchange exchange : exchanges) {
                if (exchange.file().equals(file)) return exchange;
            }
            throw new IOException("expect.json names no recorded exchange: " + file);
        }

        @Nullable Object value(final String reference) throws IOException {
            final int hash = reference.indexOf('#');
            if (hash < 1
                    || (reference.length() > hash + 1
                            && !reference.substring(hash + 1).startsWith("/")))
                throw new IOException("Expected exchange.json#/JSON/pointer: " + reference);
            @Nullable Object value =
                    Json.parseExact(
                            new String(
                                    exchange(reference.substring(0, hash)).body(),
                                    StandardCharsets.UTF_8));
            if (reference.length() == hash + 1) return value;
            for (final String escaped : reference.substring(hash + 2).split("/")) {
                final String part = escaped.replace("~1", "/").replace("~0", "~");
                if (value instanceof Map<?, ?> map && map.containsKey(part)) value = map.get(part);
                else if (value instanceof List<?> list
                        && part.matches("0|[1-9][0-9]*")
                        && Long.parseLong(part) < list.size())
                    value = list.get(Integer.parseInt(part));
                else
                    throw new IOException(
                            "expect.json reference is absent from recorded body: " + reference);
            }
            return value;
        }

        void job(final String name, final String result) throws IOException {
            if (!name.endsWith("/name"))
                throw new IOException("Job name evidence must name its recorded name: " + name);
            final String item = name.substring(0, name.lastIndexOf('/'));
            if (!result.startsWith(item + "/") || result.substring(item.length() + 1).contains("/"))
                throw new IOException("Job result must come from the same recorded job: " + result);
            final String list = item.substring(0, item.lastIndexOf('/'));
            if (!(value(list) instanceof List<?> entries))
                throw new IOException("Job name must come from a recorded job list: " + name);
            lists.computeIfAbsent(list, ignored -> new ArrayList<>());
            lists.get(list).add(item);
            ordered.add(item);
            assertThat(entries).as("recorded job list").isNotEmpty();
        }

        void run(final String name, final String result) throws IOException {
            final String item = item(name, "Run name", Set.of("name", "workflow_id"));
            if (!item.equals(item(result, "Run result", Set.of("status", "conclusion"))))
                throw new IOException("Run result must come from the same recorded run: " + result);
            // A run's state stands for its result only while it has no conclusion.
            if (result.endsWith("/status")
                    && value(item) instanceof Map<?, ?> recorded
                    && recorded.get("conclusion") != null)
                throw new IOException("A finished run's result is its conclusion: " + result);
            runs.add(item);
        }

        void verifyRuns() throws IOException {
            final List<String> recorded = new ArrayList<>();
            for (final RecordedForge.Exchange exchange : exchanges) {
                if (!exchange.path().contains("/actions/runs?")) continue;
                final String list = exchange.file() + "#/workflow_runs";
                if (!(value(list) instanceof List<?> entries))
                    throw new IOException("Expected a recorded run list: " + list);
                for (int index = 0; index < entries.size(); index++)
                    recorded.add(list + "/" + index);
            }
            assertThat(runs)
                    .as("expect.json must cover every recorded run in forge order")
                    .containsExactlyElementsOf(recorded);
        }

        private static String item(
                final String reference, final String what, final Set<String> fields)
                throws IOException {
            final int slash = reference.lastIndexOf('/');
            if (slash < 0 || !fields.contains(reference.substring(slash + 1)))
                throw new IOException(
                        what + " evidence must name one of " + fields + ": " + reference);
            return reference.substring(0, slash);
        }

        void verifyJobs() throws IOException {
            final List<String> expectedOrder = new ArrayList<>();
            final List<String> sources =
                    lists.keySet().stream()
                            .sorted(
                                    java.util.Comparator.<String>comparingInt(
                                                    source -> runOrder.getOrDefault(source, 0))
                                            .thenComparingInt(
                                                    source ->
                                                            exchanges.stream()
                                                                    .map(
                                                                            RecordedForge.Exchange
                                                                                    ::file)
                                                                    .toList()
                                                                    .indexOf(
                                                                            source.substring(
                                                                                    0,
                                                                                    source.indexOf(
                                                                                            '#')))))
                            .toList();
            for (final String source : sources) {
                if (!(value(source) instanceof List<?> entries))
                    throw new IOException("Expected recorded job list: " + source);
                for (int index = 0; index < entries.size(); index++)
                    expectedOrder.add(source + "/" + index);
            }
            assertThat(ordered)
                    .as("expect.json must cover every recorded job in forge order")
                    .containsExactlyElementsOf(expectedOrder);
        }
    }
}
