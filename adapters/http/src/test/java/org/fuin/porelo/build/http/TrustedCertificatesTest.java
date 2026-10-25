package org.fuin.porelo.build.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against silently ignoring a broken authority file or reading the wrong account's file. */
class TrustedCertificatesTest {

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not a certificate",
                "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----\n"
            })
    void anInvalidCertificateFileStopsTheReaderWithAUsefulMessage(final String contents)
            throws Exception {
        final Path file = Files.writeString(directory.resolve("ca-certificates.pem"), contents);

        assertThatThrownBy(() -> Http.overNetwork(file))
                .as("a broken authority configuration must stop the reader and explain the repair")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("put the authority's PEM certificate there")
                .hasMessageContaining("remove the file");
    }

    @Test
    void aMissingFileKeepsTheDefaultTlsContext() throws Exception {
        assertThat(TrustedCertificates.context(directory.resolve("absent.pem")))
                .as("absent account authorities preserve the default TLS policy")
                .isSameAs(SSLContext.getDefault());
    }

    @Test
    void aDirectoryKeepsTheDefaultTlsContext() throws Exception {
        assertThat(TrustedCertificates.context(directory))
                .as("a directory is not an authority bundle")
                .isSameAs(SSLContext.getDefault());
    }

    @Test
    void anAbsoluteXdgConfigHomeSelectsTheAuthorityFile() {
        assertThat(TrustedCertificates.file(Map.of("XDG_CONFIG_HOME", directory.toString())))
                .as("an absolute XDG config path is used without consulting HOME")
                .isEqualTo(directory.resolve("porelo/ca-certificates.pem"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative", ".", ""})
    void aRelativeOrEmptyXdgConfigHomeFallsBackToHome(final String configured) {
        assertThat(
                        TrustedCertificates.file(
                                Map.of(
                                        "HOME",
                                        directory.toString(),
                                        "XDG_CONFIG_HOME",
                                        configured)))
                .as("a relative XDG path must not load authorities from the working directory")
                .isEqualTo(directory.resolve(".config/porelo/ca-certificates.pem"));
    }

    @Test
    void anAbsentXdgConfigHomeFallsBackToHome() {
        assertThat(TrustedCertificates.file(Map.of("HOME", directory.toString())))
                .as("the account's conventional config directory supplies the authorities")
                .isEqualTo(directory.resolve(".config/porelo/ca-certificates.pem"));
    }
}
