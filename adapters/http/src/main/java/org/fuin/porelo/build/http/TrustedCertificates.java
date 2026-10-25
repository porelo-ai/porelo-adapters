package org.fuin.porelo.build.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Adds the account's certificate authorities to the public TLS roots. */
final class TrustedCertificates {

    private static final String REMEDY =
            "; put the authority's PEM certificate there, or remove the file";

    private TrustedCertificates() {}

    static Path file(final Map<String, String> environment) {
        final String configured = environment.get("XDG_CONFIG_HOME");
        final Path base;
        if (configured != null && Path.of(configured).isAbsolute()) {
            base = Path.of(configured);
        } else {
            base =
                    Path.of(Objects.requireNonNull(environment.get("HOME"), "HOME is not set"))
                            .resolve(".config");
        }
        return base.resolve("porelo/ca-certificates.pem");
    }

    static SSLContext context(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return SSLContext.getDefault();
            }
            final Collection<? extends Certificate> added;
            try (InputStream in = Files.newInputStream(file)) {
                added = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            if (added.isEmpty()) {
                throw new IllegalStateException(file + " holds no certificate" + REMEDY);
            }
            final KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate builtIn : builtIn().getAcceptedIssuers()) {
                store.setCertificateEntry("built-in-" + index++, builtIn);
            }
            for (Certificate each : added) {
                store.setCertificateEntry("added-" + index++, each);
            }
            final TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(store);
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "cannot read the certificates in " + file + ": " + ex.getMessage() + REMEDY,
                    ex);
        }
    }

    private static X509TrustManager builtIn() throws GeneralSecurityException {
        final TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("the default trust store has no X.509 trust manager");
    }
}
