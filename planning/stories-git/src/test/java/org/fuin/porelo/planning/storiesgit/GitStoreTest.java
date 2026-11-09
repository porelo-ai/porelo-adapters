package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitStoreTest {
    @TempDir Path root;

    // Harm: an ambient home, config, helper or trace variable can disclose the forge credential.
    @Test
    void runsChildrenWithAnIsolatedEnvironmentAndOnlyTheCurrentCallsCredential() throws Exception {
        final Path program =
                script(
                        "#!/bin/sh\n"
                                + "set -eu\n"
                                + "env | cut -d= -f1 | sort\n"
                                + "printf 'home=%s\\n"
                                + "' \"$HOME\"\n"
                                + "printf 'tmp=%s\\n"
                                + "' \"$TMPDIR\"\n"
                                + "printf 'global=%s\\n"
                                + "' \"$GIT_CONFIG_GLOBAL\"\n"
                                + "printf 'system=%s\\n"
                                + "' \"$GIT_CONFIG_NOSYSTEM\"\n"
                                + "printf 'prompt=%s\\n"
                                + "' \"$GIT_TERMINAL_PROMPT\"\n"
                                + "password=$(\"$GIT_ASKPASS\" 'Password for remote:')\n"
                                + "[ \"$password\" = \"$PORELO_GIT_TOKEN\" ] && printf"
                                + " 'credential-match\\n"
                                + "'\n"
                                + "digest=$(printf '%s' \"$password\" | sha256sum | cut -d' ' -f1)\n"
                                + "printf 'credential-digest=%s\\n"
                                + "' \"$digest\"\n"
                                + "[ \"$(\"$GIT_ASKPASS\" 'Username for remote:')\" = 'x-access-token'"
                                + " ] && printf 'username-match\\n"
                                + "'\n"
                                + "printf 'argument=%s\\n"
                                + "' \"$@\"\n");
        final GitStore store = new GitStore(root, program, Duration.ofSeconds(5));
        for (final String token : List.of("first-call-credential", "second-call-credential")) {
            store.begin();
            final String output = store.git(root, token, "read-only-probe");
            final List<String> variables =
                    output.lines()
                            .filter(line -> !line.contains("=") && !line.endsWith("-match"))
                            .toList();
            assertThat(variables)
                    .as("the actual child sees only the explicit environment and shell-owned PWD")
                    .containsExactlyInAnyOrder(
                            "GIT_ASKPASS",
                            "GIT_CONFIG_GLOBAL",
                            "GIT_CONFIG_NOSYSTEM",
                            "GIT_TERMINAL_PROMPT",
                            "HOME",
                            "LC_ALL",
                            "PATH",
                            "PORELO_GIT_TOKEN",
                            "PWD",
                            "TMPDIR",
                            "XDG_CONFIG_HOME");
            assertThat(output)
                    .as(
                            "the child uses contained storage and obtains this call's password"
                                    + " through askpass")
                    .contains(
                            "home=" + root.resolve("home"),
                            "tmp=" + root.resolve("tmp"),
                            "global=/dev/null",
                            "system=1",
                            "prompt=0",
                            "credential-match",
                            "username-match",
                            "argument=core.hooksPath=/dev/null",
                            "argument=core.fsmonitor=false",
                            "argument=credential.helper=",
                            "argument=http.followRedirects=false")
                    .doesNotContain(token);
            final String digest =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    token.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
            assertThat(output)
                    .as(
                            "the child receives this call's exact credential, including after a"
                                    + " different preceding call")
                    .contains("credential-digest=" + digest);
            assertThat(new SafeFiles(root).read("git-askpass"))
                    .as("the persisted helper contains no call credential")
                    .doesNotContain(token);
        }
    }

    // Harm: raw Git errors can disclose credentials; guessed success can hide a refused store.
    @Test
    void classifiesFailuresWithoutReturningTheRemoteDiagnosticOrCredential() throws Exception {
        for (final var failure :
                Map.of(
                                "Authentication failed",
                                Reason.CREDENTIAL_REFUSED,
                                "repository not found",
                                Reason.NO_SUCH_STORE,
                                "Failed to connect",
                                Reason.UNREACHABLE,
                                "unexpected response",
                                Reason.FAILED)
                        .entrySet()) {
            final Path directory = Files.createTempDirectory(root, "case-");
            final Path program = directory.resolve("program");
            Files.writeString(
                    program,
                    "#!/bin/sh\nprintf '%s credential=%s\\n' '"
                            + failure.getKey()
                            + "' \"$PORELO_GIT_TOKEN\" >&2\nexit 1\n");
            Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
            final GitStore store = new GitStore(directory, program, Duration.ofSeconds(5));
            store.begin();
            final Throwable thrown =
                    catchThrowable(() -> store.git(directory, "call-credential", "probe"));
            assertThat(thrown)
                    .as("a failed child gets a named planning refusal")
                    .isInstanceOf(PlanningRefused.class);
            final PlanningRefused refused = (PlanningRefused) thrown;
            assertThat(refused.reason())
                    .as("the observed Git failure maps to its contract category")
                    .isEqualTo(failure.getValue());
            assertThat(refused.parameters().toString())
                    .as("safe fields contain neither raw remote output nor the supplied credential")
                    .doesNotContain(failure.getKey(), "call-credential", "credential=");
        }
    }

    // Harm: a stalled child can retain a credential and block the API indefinitely.
    @Test
    void terminatesAndReapsAChildThatExceedsTheBound() throws Exception {
        final Path program = script("#!/bin/sh\nprintf '%s' \"$$\" > child-pid\nexec sleep 30\n");
        final GitStore store = new GitStore(root, program, Duration.ofMillis(100));
        store.begin();
        final Throwable failure = catchThrowable(() -> store.git(root, "call-credential", "probe"));
        assertThat(failure)
                .as("a stalled child is refused within the configured process bound")
                .isInstanceOf(PlanningRefused.class);
        assertThat(((PlanningRefused) failure).reason())
                .as("a process timeout is Unreachable")
                .isEqualTo(Reason.UNREACHABLE);
        final long pid = Long.parseLong(Files.readString(root.resolve("child-pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("the timed-out child is terminated and reaped before returning")
                .isFalse();
    }

    private Path script(String text) throws Exception {
        final Path program = root.resolve("program");
        Files.writeString(program, text);
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        return program;
    }
}
