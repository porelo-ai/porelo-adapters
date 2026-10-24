package org.fuin.porelo.build.contract;

import java.nio.charset.StandardCharsets;
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

    record HeadAnswer(boolean absent) implements Expected {}

    record Verdict(String value) implements Expected {}

    record Refusal(BuildRefused.Reason reason) implements Expected {}

    static List<ContractCase> all() {
        // A wrong verdict or refusal can send a task down the wrong path.
        return List.of(
                new ContractCase("head", new Head("main"), new HeadAnswer(false)),
                new ContractCase(
                        "absent-branch",
                        new Head("no-such-branch-for-the-suite"),
                        new HeadAnswer(true)),
                build(
                        "no-build",
                        "0000000000000000000000000000000000000000",
                        Build.Logs.FAILURE,
                        Build.UNKNOWN),
                build("queued", COMMIT, Build.Logs.ALL, Build.QUEUED),
                build("running", COMMIT, Build.Logs.ALL, Build.RUNNING),
                build("passed", GREEN, Build.Logs.FAILURE, Build.SUCCESS),
                build("failed", RED, Build.Logs.FAILURE, Build.FAILURE),
                build("cancelled", COMMIT, Build.Logs.FAILURE, Build.CANCELLED),
                build("logs-none", RED, Build.Logs.NONE, Build.FAILURE),
                build("logs-failure", RED, Build.Logs.FAILURE, Build.FAILURE),
                build("logs-all", GREEN, Build.Logs.ALL, Build.SUCCESS),
                build("log-tail", COMMIT, Build.Logs.FAILURE, Build.FAILURE),
                build("many-jobs", COMMIT, Build.Logs.FAILURE, Build.FAILURE),
                build("shared-logs", COMMIT, Build.Logs.ALL, Build.SUCCESS),
                refusal("rate-limited", BuildRefused.Reason.RATE_LIMITED),
                refusal("primary-rate-limited", BuildRefused.Reason.RATE_LIMITED),
                new ContractCase(
                        "credential-refused",
                        new Head("main"),
                        new Refusal(BuildRefused.Reason.CREDENTIAL_REFUSED)),
                new ContractCase(
                        "no-repository",
                        "porelo-ai/no-such-repository-for-the-suite",
                        new Head("main"),
                        new Refusal(BuildRefused.Reason.NO_SUCH_REPOSITORY)),
                refusal("unreachable", BuildRefused.Reason.UNREACHABLE));
    }

    private static ContractCase build(
            final String name, final String commit, final Build.Logs logs, final String verdict) {
        return new ContractCase(name, new Commit(commit, logs), new Verdict(verdict));
    }

    private static ContractCase refusal(final String name, final BuildRefused.Reason reason) {
        return new ContractCase(name, new Commit(COMMIT, Build.Logs.FAILURE), new Refusal(reason));
    }

    static byte[] generatedTail(final int bytes) {
        final byte[] body = new byte[bytes];
        Arrays.fill(body, (byte) 'x');
        final byte[] end = "END\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(end, 0, body, bytes - end.length, end.length);
        return body;
    }
}
