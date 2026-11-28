package org.fuin.porelo.build.contract;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.fuin.porelo.wire.LimitFactors;

/** A fresh authority trusted only by the executable of one contract case. */
final class ReviewCertificates {

    private final Path directory;
    private final String password = UUID.randomUUID().toString();

    ReviewCertificates(final Path directory) {
        this.directory = directory;
    }

    SSLContext create(final Path configuration) throws Exception {
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
                "CN=Contract authority",
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
                "CN=Contract forge",
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
                "san=ip:127.0.0.1,ip:127.0.0.2,dns:localhost",
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
        Files.createDirectories(configuration.resolve("porelo"));
        Files.copy(
                directory.resolve("ca.pem"), configuration.resolve("porelo/ca-certificates.pem"));
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(directory.resolve("server.p12"))) {
            keys.load(in, password.toCharArray());
        }
        final KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
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
                        "CONTRACT_STORE_PASSWORD",
                        "-noprompt"));
        final ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().put("CONTRACT_STORE_PASSWORD", password);
        final Path output = Files.createTempFile(directory, "keytool-", ".log");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        final Process process = builder.start();
        try {
            if (!process.waitFor(
                    LimitFactors.DEFAULT.machines(Duration.ofSeconds(30)).toMillis(),
                    TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("keytool did not finish: " + arguments[0]);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "keytool failed: "
                                + arguments[0]
                                + ": "
                                + Files.readString(output).replace(password, "[redacted]"));
            }
        } finally {
            process.destroyForcibly().waitFor();
        }
    }
}
