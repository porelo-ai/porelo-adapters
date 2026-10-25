package org.fuin.porelo.build.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Generates fresh certificate authorities and a signed server key without external services. */
final class TestCertificates {
    private static final String PASSWORD = UUID.randomUUID().toString();
    private final Path directory;

    TestCertificates(final Path directory) {
        this.directory = directory;
    }

    SSLContext serverContext() throws Exception {
        keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keystore",
                "ca.p12",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=Test authority",
                "-ext",
                "bc:c",
                "-validity",
                "2");
        keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-rfc", "-file", "ca.pem");
        keytool(
                "-genkeypair",
                "-alias",
                "server",
                "-keystore",
                "server.p12",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=Local forge",
                "-validity",
                "2");
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-file", "server.csr");
        keytool(
                "-gencert",
                "-alias",
                "ca",
                "-keystore",
                "ca.p12",
                "-infile",
                "server.csr",
                "-outfile",
                "server.pem",
                "-rfc",
                "-ext",
                "san=ip:127.0.0.1",
                "-ext",
                "ku=digitalSignature,keyEncipherment",
                "-ext",
                "eku=serverAuth",
                "-validity",
                "2");
        keytool("-importcert", "-alias", "ca", "-keystore", "server.p12", "-file", "ca.pem");
        keytool(
                "-importcert",
                "-alias",
                "server",
                "-keystore",
                "server.p12",
                "-file",
                "server.pem");

        keytool(
                "-genkeypair",
                "-alias",
                "other",
                "-keystore",
                "other.p12",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=Unrelated authority",
                "-ext",
                "bc:c",
                "-validity",
                "2");
        keytool(
                "-exportcert",
                "-alias",
                "other",
                "-keystore",
                "other.p12",
                "-rfc",
                "-file",
                "other.pem");

        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(directory.resolve("server.p12"))) {
            keys.load(in, PASSWORD.toCharArray());
        }
        final KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, PASSWORD.toCharArray());
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private void keytool(final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        command.addAll(
                List.of(
                        "-storetype",
                        "PKCS12",
                        "-storepass:env",
                        "TEST_STORE_PASSWORD",
                        "-noprompt"));
        final Path output = Files.createTempFile(directory, "keytool-", ".log");
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        builder.environment().put("TEST_STORE_PASSWORD", PASSWORD);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS))
                    .as("keytool finishes without hanging the TLS tests")
                    .isTrue();
            assertThat(process.exitValue())
                    .as("keytool succeeds: %s", Files.readString(output))
                    .isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
