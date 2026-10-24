package org.fuin.porelo.build.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NullMarkedPackagesTest {

    @Test
    void everyMainPackageEnablesNullAway() throws Exception {
        // An unmarked package bypasses NullAway and can turn unchecked nulls into reader crashes.
        final List<Path> packages;
        try (Stream<Path> paths = Files.walk(Path.of(".."))) {
            packages =
                    paths.filter(path -> path.toString().contains("/src/main/java/"))
                            .filter(path -> path.toString().endsWith(".java"))
                            .map(Path::getParent)
                            .distinct()
                            .sorted()
                            .toList();
        }
        assertThat(packages)
                .as("the suite and adapter source packages are checked")
                .contains(
                        Path.of("../contract/src/main/java/org/fuin/porelo/build/contract"),
                        Path.of("../adapters/github/src/main/java/org/fuin/porelo/build/github"));
        for (final Path pkg : packages) {
            final Path info = pkg.resolve("package-info.java");
            assertThat(info).as("%s declares package null safety", pkg).exists();
            assertThat(Files.readString(info))
                    .as("%s enables NullAway", pkg)
                    .containsPattern("(?m)^@(?:org\\.jspecify\\.annotations\\.)?NullMarked\\b");
        }
    }
}
