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
record CaseExpectation(String head, List<Job> jobs, long retryAfter) {
    record Job(String name, String result, byte @Nullable [] log) {
        boolean failed() {
            return List.of("failure", "timed_out", "action_required", "startup_failure")
                    .contains(result);
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
        final Map<?, ?> map =
                object(Json.parse(Files.readString(directory.resolve("expect.json"))));
        final Evidence evidence = new Evidence(exchanges);
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
                            "expect.json retry-after must match the recorded header, or the 60-second fallback when absent")
                    .isEqualTo(delays.isEmpty() ? 60 : Long.parseLong(delays.getFirst()));
            return new CaseExpectation("", List.of(), number.longValue());
        }
        keys(map, Set.of("jobs"));
        if (!(map.get("jobs") instanceof List<?> supplied))
            throw new IOException("expect.json jobs must be an array");
        final List<Job> jobs = new ArrayList<>();
        for (final Object value : supplied) {
            final Map<?, ?> job = object(value);
            keys(
                    job,
                    job.containsKey("log")
                            ? Set.of("name", "result", "nameFrom", "resultFrom", "log")
                            : Set.of("name", "result", "nameFrom", "resultFrom"));
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
                                    : null));
        }
        evidence.verifyJobs();
        return new CaseExpectation("", List.copyOf(jobs), 0);
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

        Evidence(final List<RecordedForge.Exchange> exchanges) {
            this.exchanges = exchanges;
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
                    Json.parse(
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

        void verifyJobs() throws IOException {
            final List<String> expectedOrder = new ArrayList<>();
            final List<String> sources =
                    lists.keySet().stream()
                            .sorted(
                                    java.util.Comparator.comparingInt(
                                            source ->
                                                    exchanges.stream()
                                                            .map(RecordedForge.Exchange::file)
                                                            .toList()
                                                            .indexOf(
                                                                    source.substring(
                                                                            0,
                                                                            source.indexOf('#')))))
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
