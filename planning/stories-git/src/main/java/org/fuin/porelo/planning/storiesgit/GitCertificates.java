package org.fuin.porelo.planning.storiesgit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Gives Git the host's configured authorities alongside the system root bundle. */
final class GitCertificates {
    private static final Pattern PEM =
            Pattern.compile(
                    "-----BEGIN CERTIFICATE-----\\s*[A-Za-z0-9+/=\\r"
                            + "\\n"
                            + "]+-----END CERTIFICATE-----");
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private GitCertificates() {}

    static @Nullable Path prepare(Path directory, Map<String, String> environment) {
        try {
            final Path file = file(environment);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            final String added = read(file);
            if (!PEM.matcher(added).replaceAll("").isBlank())
                throw Refusals.failed(
                        "Expected PEM authorities in ca-certificates.pem; got malformed certificate"
                                + " text");
            validate(added);
            final Path system =
                    List.of(
                                    Path.of("/etc/ssl/certs/ca-certificates.crt"),
                                    Path.of("/etc/pki/tls/certs/ca-bundle.crt"),
                                    Path.of("/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem"))
                            .stream()
                            .filter(Files::isRegularFile)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            Refusals.failed(
                                                    "Expected a system root bundle for"
                                                            + " ca-certificates.pem; got no readable"
                                                            + " bundle"));
            final String roots = read(system);
            validate(roots);
            final Path combined = directory.resolve("ca-certificates.pem");
            final Path pending =
                    Files.createTempFile(
                            directory.resolve("tmp"),
                            "certificates-",
                            ".pem",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(pending, roots + "\n" + added, StandardCharsets.US_ASCII);
                Files.move(pending, combined, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(pending);
            }
            return combined;
        } catch (IOException | GeneralSecurityException | java.nio.file.InvalidPathException ex) {
            throw Refusals.failed(
                    "Expected readable PEM authorities in ca-certificates.pem; got an unsafe,"
                            + " unreadable or invalid certificate file");
        }
    }

    private static Path file(Map<String, String> environment) {
        final String configured = environment.get("XDG_CONFIG_HOME");
        final Path base;
        if (configured != null && Path.of(configured).isAbsolute()) base = Path.of(configured);
        else {
            final String home = environment.get("HOME");
            if (home == null || !Path.of(home).isAbsolute())
                throw Refusals.failed(
                        "Expected an absolute HOME for ca-certificates.pem; got no configuration"
                                + " directory");
            base = Path.of(home).resolve(".config");
        }
        return base.resolve("porelo/ca-certificates.pem");
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Not a certificate file");
        try (var input = Files.newInputStream(file)) {
            final byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Certificate file exceeds bound");
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }

    private static void validate(String text) throws GeneralSecurityException {
        final var certificates =
                CertificateFactory.getInstance("X.509")
                        .generateCertificates(
                                new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
        if (certificates.isEmpty())
            throw Refusals.failed(
                    "Expected PEM authorities in ca-certificates.pem; got no certificates");
    }
}
