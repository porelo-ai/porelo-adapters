package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class FixturePaths {
    private FixturePaths() {}

    static String repository(final String name) {
        if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.git") || name.contains("..")) {
            throw new IllegalArgumentException(
                    "Expected a simple bare repository name ending in .git; got " + name);
        }
        return name;
    }

    static Path inside(final Path root, final String name) throws IOException {
        final Path relative = Path.of(name);
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IOException("Expected a relative fixture path; got " + name);
        }
        Path current = root;
        for (final Path part : relative) {
            final String text = part.toString();
            if (text.isEmpty()
                    || text.equals(".")
                    || text.equals("..")
                    || text.equalsIgnoreCase(".git")) {
                throw new IOException(
                        "Expected a contained fixture path outside .git; got " + name);
            }
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException(
                        "Expected a fixture path without symbolic links; got " + current);
            }
        }
        return current;
    }

    static void delete(final Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
