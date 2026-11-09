package org.fuin.porelo.planning.storiesgit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("documents")
class PublicationTest {
    @TempDir Path root;

    // Harm: deploying an executable module can block publication of the contract libraries.
    @Test
    void inheritsTheParentsPublicationExclusions() throws Exception {
        Path module = Path.of("planning/stories-git").toAbsolutePath();
        if (!Files.exists(module.resolve("pom.xml"))) module = Path.of("").toAbsolutePath();
        final Path parent =
                Files.copy(module.getParent().resolve("pom.xml"), root.resolve("parent.xml"));
        final Path child = Files.copy(module.resolve("pom.xml"), root.resolve("child.xml"));
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        final var builder = factory.newDocumentBuilder();
        final var inherited = builder.parse(parent.toFile());
        final var own = builder.parse(child.toFile());
        for (final String name :
                Map.of("maven.deploy.skip", true, "skipPublishing", true).keySet()) {
            final var overrides = own.getElementsByTagName(name);
            final String value =
                    overrides.getLength() == 0
                            ? inherited.getElementsByTagName(name).item(0).getTextContent()
                            : overrides.item(0).getTextContent();
            assertThat(value)
                    .as("%s excludes the adapter executable from Maven publication", name)
                    .isEqualTo("true");
        }
    }
}
