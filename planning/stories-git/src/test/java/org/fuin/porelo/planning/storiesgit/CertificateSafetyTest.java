package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateSafetyTest {
    @TempDir Path root;
    private final String password = java.util.UUID.randomUUID().toString();

    // Harm: dropping the host's private authority prevents access to its planning repositories.
    @Test
    void passesACombinedCaBundleFromTheAdaptersEnvironmentToGit() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config/porelo"));
        certificate(config.resolve("ca-certificates.pem"));
        final Path directory = Files.createDirectory(root.resolve("adapter"));
        final ProcessResult result = invoke(directory, root.resolve("config"));
        assertThat(result.exit())
                .as("a valid environment certificate initializes the adapter")
                .isZero();
        assertThat(result.text())
                .as("the Git child uses the adapter's combined bundle")
                .contains("http.sslCAInfo=" + directory.resolve("ca-certificates.pem"));
        final var factory = CertificateFactory.getInstance("X.509");
        try (var source = Files.newInputStream(config.resolve("ca-certificates.pem"));
                var combined = Files.newInputStream(directory.resolve("ca-certificates.pem"))) {
            final var local = factory.generateCertificates(source);
            final List<java.security.cert.Certificate> certificates =
                    new ArrayList<>(factory.generateCertificates(combined));
            assertThat(certificates)
                    .as("system authorities are retained beside the configured authority")
                    .containsAll(local)
                    .hasSizeGreaterThan(local.size());
        }
    }

    // Harm: malformed trust input must fail loudly instead of silently dropping requested trust.
    @Test
    void refusesMalformedConfiguredCertificates() throws Exception {
        final Path config = Files.createDirectories(root.resolve("config/porelo"));
        Files.writeString(config.resolve("ca-certificates.pem"), "not a certificate\n");
        final ProcessResult result =
                invoke(Files.createDirectory(root.resolve("adapter")), root.resolve("config"));
        assertThat(result.exit()).as("invalid authority input refuses initialization").isNotZero();
        assertThat(result.text())
                .as("the error names the trust file and contains no certificate content")
                .contains("ca-certificates.pem")
                .doesNotContain("not a certificate");
    }

    public static void main(String[] args) throws Exception {
        try {
            probe(args);
        } catch (org.fuin.porelo.planning.api.PlanningRefused failure) {
            System.out.println(failure.parameters());
            System.exit(1);
        }
    }

    private static void probe(String[] args) throws Exception {
        final Path directory = Path.of(args[0]);
        final StoriesGit adapter = new StoriesGit();
        adapter.initialize(directory);
        if ((Object) adapter instanceof AutoCloseable closeable) closeable.close();
        final GitStore store = new GitStore(directory, Path.of(args[1]), Duration.ofSeconds(2));
        try {
            store.begin();
            System.out.print(store.git(directory, "", "probe"));
        } finally {
            if ((Object) store instanceof AutoCloseable closeable) closeable.close();
        }
    }

    private ProcessResult invoke(Path directory, Path config) throws Exception {
        final Path program = root.resolve("program");
        Files.writeString(program, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        final Path output = root.resolve("output");
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                        "-cp",
                                System.getProperty("java.class.path"),
                                        CertificateSafetyTest.class.getName(),
                                directory.toString(), program.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        builder.environment().put("XDG_CONFIG_HOME", config.toString());
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("local certificate probe is bounded")
                    .isTrue();
            return new ProcessResult(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private void certificate(Path pem) throws Exception {
        final List<String> base =
                List.of(
                        Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                        "-keystore",
                        root.resolve("keys.p12").toString(),
                        "-storepass:env",
                        "TEST_STORE_PASSWORD",
                        "-alias",
                        "ca");
        keytool(
                base,
                List.of(
                        "-genkeypair",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=Example authority",
                        "-ext",
                        "bc:c",
                        "-validity",
                        "2"));
        keytool(base, List.of("-exportcert", "-rfc", "-file", pem.toString()));
    }

    private void keytool(List<String> base, List<String> args) throws Exception {
        final List<String> command = new ArrayList<>(base);
        command.addAll(args);
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("TEST_STORE_PASSWORD", password);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("keytool finishes locally")
                    .isTrue();
            assertThat(process.exitValue()).as("the fixture authority was created").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private record ProcessResult(int exit, String text) {}
}
