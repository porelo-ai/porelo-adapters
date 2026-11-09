package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds literal credential bytes without excluding hidden Git files or following symbolic links.
 */
public final class SecretScan {
    private SecretScan() {}

    /**
     * Scans every regular file under the supplied directories, including .git and binary files.
     *
     * @param secret Nonempty credential to find as UTF-8 bytes.
     * @param directories Existing directory roots; symbolic links are ignored.
     * @return Sorted absolute paths containing the credential, without copying it into diagnostics.
     * @throws IOException If any root or file cannot be inspected.
     */
    public static List<Path> files(final String secret, final Path... directories)
            throws IOException {
        final byte[] needle = needle(secret);
        final Set<Path> matches = new LinkedHashSet<>();
        for (final Path directory : directories) {
            final Path root = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root)) continue;
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected an existing directory to scan; got " + root);
            }
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        /**
                         * Includes every regular file so hidden Git data cannot escape scanning.
                         */
                        @Override
                        public FileVisitResult visitFile(
                                final Path file, final BasicFileAttributes attributes)
                                throws IOException {
                            if (attributes.isRegularFile() && contains(file, needle))
                                matches.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
        return matches.stream().sorted().toList();
    }

    /**
     * Finds a credential in a process environment's names and values.
     *
     * @param secret Nonempty credential to find.
     * @param environment The environment passed to a child process.
     * @return Sorted matching environment names, with any credential in a name redacted.
     */
    public static List<String> environment(
            final String secret, final Map<String, String> environment) {
        needle(secret);
        final List<String> matches = new ArrayList<>();
        for (final Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().contains(secret) || entry.getValue().contains(secret))
                matches.add(entry.getKey().replace(secret, "<redacted>"));
        }
        return matches.stream().sorted().toList();
    }

    private static byte[] needle(final String secret) {
        if (secret.isEmpty())
            throw new IllegalArgumentException("Expected a nonempty secret to scan");
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean contains(final Path file, final byte[] needle) throws IOException {
        // Retaining the overlap catches a credential split across read boundaries.
        final byte[] buffer = new byte[Math.max(8192, needle.length) + needle.length - 1];
        int retained = 0;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer, retained, buffer.length - retained)) != -1) {
                final int length = retained + count;
                for (int offset = 0; offset <= length - needle.length; offset++) {
                    if (Arrays.equals(
                            buffer, offset, offset + needle.length, needle, 0, needle.length))
                        return true;
                }
                retained = Math.min(needle.length - 1, length);
                System.arraycopy(buffer, length - retained, buffer, 0, retained);
            }
        }
        return false;
    }
}
