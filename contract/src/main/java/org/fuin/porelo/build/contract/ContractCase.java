package org.fuin.porelo.build.contract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.InstalledBuildReader;
import org.fuin.porelo.build.api.Target;

record ContractCase(String name, String repository, Question question, Expected expected) {

    ContractCase(final String name, final Question question, final Expected expected) {
        this(name, "porelo-ai/porelo-frontend", question, expected);
    }

    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String RED = "e95e5cc23d1b979120b3ca160c40a32514f1bd8b";
    static final String GREEN = "3b29f5c2cb4f99760a92de863391ed1964e7f179";

    sealed interface Question {
        /** Asks the installed adapter the case's question. */
        Object ask(InstalledBuildReader reader, Target target);
    }

    record Head(String branch) implements Question {
        @Override
        public Object ask(final InstalledBuildReader reader, final Target target) {
            return reader.head(target, branch);
        }
    }

    record Commit(String commit, Build.Logs logs) implements Question {
        @Override
        public Object ask(final InstalledBuildReader reader, final Target target) {
            return reader.build(target, commit, logs);
        }
    }

    sealed interface Expected {}

    record Answer(Object value) implements Expected {}

    record LoggedAnswer(Build value, String sha256) implements Expected {}

    record Refusal(BuildRefused.Reason reason, long retryAfter) implements Expected {}

    static List<ContractCase> all() {
        final List<ContractCase> cases = new ArrayList<>();
        // A stale head would attribute a previous commit's build to a new push.
        cases.add(
                new ContractCase(
                        "head",
                        new Head("main"),
                        new Answer("068ad307633718a7b9fdaf5381f9b8d53c3b87ec")));
        // A missing branch must not be watched as if it still had a head.
        cases.add(
                new ContractCase(
                        "absent-branch", new Head("no-such-branch-for-the-suite"), new Answer("")));
        // A commit without runs must not inherit the previous commit's success.
        cases.add(
                build(
                        "no-build",
                        "0000000000000000000000000000000000000000",
                        Build.Logs.FAILURE,
                        Build.unknown(
                                "no build of 000000000000 at porelo-ai/porelo-frontend yet")));
        // Queued work must not be treated as already executing or finished.
        cases.add(build("queued", Build.Logs.ALL, new Build(Build.QUEUED, "")));
        // Active work must not be treated as queued or finished.
        cases.add(build("running", Build.Logs.ALL, new Build(Build.RUNNING, "")));
        // Only a successful completed build may report success.
        cases.add(
                build(
                        "passed",
                        GREEN,
                        Build.Logs.FAILURE,
                        new Build(Build.SUCCESS, greenJobs(false), "")));
        // A real failed run must retain its failed job's log byte for byte.
        cases.add(recordedFailure("failed"));
        // Cancellation must not be reported as successful completion.
        cases.add(
                build(
                        "cancelled",
                        Build.Logs.FAILURE,
                        new Build(
                                Build.CANCELLED,
                                List.of(new Build.Job("Build / Linux", "cancelled")),
                                "")));
        // An unwanted log download spends credentials and bandwidth without a caller request.
        cases.add(
                build("logs-none", RED, Build.Logs.NONE, new Build(Build.FAILURE, redJobs(), "")));
        // Failure-only logs must not download or expose successful jobs' output.
        cases.add(recordedFailure("logs-failure"));
        // All logs must include successful jobs as well as failed ones.
        cases.add(
                build(
                        "logs-all",
                        GREEN,
                        Build.Logs.ALL,
                        new Build(Build.SUCCESS, greenJobs(true), "")));
        // The retained bytes must include the failure at the end of a large log.
        cases.add(
                build(
                        "log-tail",
                        Build.Logs.FAILURE,
                        new Build(
                                Build.FAILURE,
                                List.of(
                                        new Build.Job(
                                                "Build / unit",
                                                "failure",
                                                generatedTail(Build.TAIL))),
                                "")));
        final List<Build.Job> many = new ArrayList<>();
        for (int id = 1; id < 50; id++) {
            many.add(new Build.Job("Matrix / leg " + id, "success"));
        }
        many.add(job("Matrix / leg 60", "failure", "the last leg failed\n"));
        // Truncating a matrix must not discard the job that failed.
        cases.add(build("many-jobs", Build.Logs.FAILURE, new Build(Build.FAILURE, many, "")));
        final List<Build.Job> shared = new ArrayList<>();
        for (int id = 1; id <= 50; id++) {
            shared.add(new Build.Job("Matrix / leg " + id, "success", generatedTail(41943)));
        }
        // An oversized socket reply can prevent every later build verdict from reaching Porelo.
        cases.add(build("shared-logs", Build.Logs.ALL, new Build(Build.SUCCESS, shared, "")));
        // A secondary rate limit must not send a valid credential for replacement.
        cases.add(refusal("rate-limited", BuildRefused.Reason.RATE_LIMITED, 30));
        // An exhausted primary limit must preserve the delay before retrying.
        cases.add(refusal("primary-rate-limited", BuildRefused.Reason.RATE_LIMITED, 60));
        // A refused credential must stop polling with that credential.
        cases.add(
                new ContractCase(
                        "credential-refused",
                        new Head("main"),
                        new Refusal(BuildRefused.Reason.CREDENTIAL_REFUSED, 0)));
        // An invisible repository must not be reported as an unbuilt commit.
        cases.add(
                new ContractCase(
                        "no-repository",
                        "porelo-ai/no-such-repository-for-the-suite",
                        new Head("main"),
                        new Refusal(BuildRefused.Reason.NO_SUCH_REPOSITORY, 0)));
        // A broken transport must not be mistaken for a build verdict.
        cases.add(refusal("unreachable", BuildRefused.Reason.UNREACHABLE, 0));
        return List.copyOf(cases);
    }

    private static ContractCase build(
            final String name, final Build.Logs logs, final Build answer) {
        return build(name, COMMIT, logs, answer);
    }

    private static ContractCase build(
            final String name, final String commit, final Build.Logs logs, final Build answer) {
        return new ContractCase(name, new Commit(commit, logs), new Answer(answer));
    }

    private static ContractCase recordedFailure(final String name) {
        return new ContractCase(
                name,
                new Commit(RED, Build.Logs.FAILURE),
                new LoggedAnswer(
                        new Build(Build.FAILURE, redJobs(), ""),
                        "9644b1658189cff86b405a6faeded2c204ac8c077c86735c172c7cec1fcf6ca9"));
    }

    private static List<Build.Job> redJobs() {
        return List.of(
                new Build.Job("Build / Linux", "success"),
                new Build.Job("Build / Whether the leased leg is proven", "success"),
                new Build.Job("Build / Windows", "failure"),
                new Build.Job("Build / Test against a leased machine", "success"),
                new Build.Job(
                        "Build / Prove the packages install (ubuntu:26.04, ubuntu:26.04@sha256:f144425ff09be612d6d9ad965196e9cdc23...",
                        "success"),
                new Build.Job(
                        "Build / Prove the packages install (debian:13, debian:13@sha256:913f6706df59a68922d1dd08f78c2476560a8d367...",
                        "success"),
                new Build.Job(
                        "Build / Prove the packages install (fedora:43, fedora:43@sha256:a651ddf48ea28a06ed4e1e6519f51c9f47e7a5a13...",
                        "success"),
                new Build.Job(
                        "Build / Prove the packages install (fedora:44, fedora:44@sha256:43b29f65a41eb9c35e1cd5323e3bdf3b655c2357a...",
                        "success"),
                new Build.Job("Build / Publish to Artifactory", "success"));
    }

    private static List<Build.Job> greenJobs(final boolean withLogs) {
        return List.of(
                withLogs
                        ? job(
                                "Build / Whether the leased leg is proven",
                                "success",
                                "generated green job 120879192410\n")
                        : new Build.Job("Build / Whether the leased leg is proven", "success"),
                withLogs
                        ? job("Build / Linux", "success", "generated green job 120879192548\n")
                        : new Build.Job("Build / Linux", "success"),
                withLogs
                        ? job("Build / Windows", "success", "generated green job 120879192632\n")
                        : new Build.Job("Build / Windows", "success"),
                withLogs
                        ? job(
                                "Build / Test against a leased machine",
                                "success",
                                "generated green job 120879288863\n")
                        : new Build.Job("Build / Test against a leased machine", "success"),
                withLogs
                        ? job(
                                "Build / Prove the packages install (fedora:44, fedora:44@sha256:43b29f65a41eb9c35e1cd5323e3bdf3b655c2357a...",
                                "success",
                                "generated green job 120881677996\n")
                        : new Build.Job(
                                "Build / Prove the packages install (fedora:44, fedora:44@sha256:43b29f65a41eb9c35e1cd5323e3bdf3b655c2357a...",
                                "success"),
                withLogs
                        ? job(
                                "Build / Prove the packages install (fedora:43, fedora:43@sha256:a651ddf48ea28a06ed4e1e6519f51c9f47e7a5a13...",
                                "success",
                                "generated green job 120881677998\n")
                        : new Build.Job(
                                "Build / Prove the packages install (fedora:43, fedora:43@sha256:a651ddf48ea28a06ed4e1e6519f51c9f47e7a5a13...",
                                "success"),
                withLogs
                        ? job(
                                "Build / Prove the packages install (debian:13, debian:13@sha256:913f6706df59a68922d1dd08f78c2476560a8d367...",
                                "success",
                                "generated green job 120881678014\n")
                        : new Build.Job(
                                "Build / Prove the packages install (debian:13, debian:13@sha256:913f6706df59a68922d1dd08f78c2476560a8d367...",
                                "success"),
                withLogs
                        ? job(
                                "Build / Prove the packages install (ubuntu:26.04, ubuntu:26.04@sha256:f144425ff09be612d6d9ad965196e9cdc23...",
                                "success",
                                "generated green job 120881678049\n")
                        : new Build.Job(
                                "Build / Prove the packages install (ubuntu:26.04, ubuntu:26.04@sha256:f144425ff09be612d6d9ad965196e9cdc23...",
                                "success"),
                withLogs
                        ? job(
                                "Build / Publish to Artifactory",
                                "success",
                                "generated green job 120883125415\n")
                        : new Build.Job("Build / Publish to Artifactory", "success"));
    }

    private static ContractCase refusal(
            final String name, final BuildRefused.Reason reason, final long retryAfter) {
        return new ContractCase(
                name, new Commit(COMMIT, Build.Logs.FAILURE), new Refusal(reason, retryAfter));
    }

    private static Build.Job job(final String name, final String result, final String log) {
        return new Build.Job(name, result, log.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] generatedTail(final int bytes) {
        final byte[] body = new byte[bytes];
        Arrays.fill(body, (byte) 'x');
        final byte[] end = "END\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(end, 0, body, bytes - end.length, end.length);
        return body;
    }
}
