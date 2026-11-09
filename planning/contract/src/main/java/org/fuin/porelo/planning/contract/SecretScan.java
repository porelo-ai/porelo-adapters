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
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds raw and Base64 credential bytes without excluding hidden Git files or following symbolic
 * links.
 */
public final class SecretScan {
    private SecretScan() {}

    /**
     * Scans file contents, names and link targets, including hidden Git files and binary data.
     *
     * @param secret Nonempty credential to find as UTF-8 bytes.
     * @param directories Existing roots; symbolic links are inspected without following them.
     * @return Sorted absolute paths containing the credential, with matching names redacted.
     * @throws IOException If any root or file cannot be inspected.
     */
    public static List<Path> files(final String secret, final Path... directories)
            throws IOException {
        final List<byte[]> needles = needles(secret);
        final Set<Path> matches = new LinkedHashSet<>();
        for (final Path directory : directories) {
            final Path root = directory.toAbsolutePath().normalize();
            if (contains(secret, root.toString())) matches.add(safePath(secret, root));
            if (Files.isSymbolicLink(root)) {
                if (contains(secret, Files.readSymbolicLink(root).toString()))
                    matches.add(safePath(secret, root));
                continue;
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Expected an existing directory to scan; got " + safePath(secret, root));
            }
            try {
                Files.walkFileTree(
                        root,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                    Path path, BasicFileAttributes attributes) {
                                if (contains(secret, path.toString()))
                                    matches.add(safePath(secret, path));
                                return FileVisitResult.CONTINUE;
                            }

                            // Names and link targets can retain a credential without any regular
                            // file.

                            @Override
                            public FileVisitResult visitFile(
                                    final Path file, final BasicFileAttributes attributes)
                                    throws IOException {
                                if (contains(secret, file.toString())
                                        || attributes.isSymbolicLink()
                                                && contains(
                                                        secret,
                                                        Files.readSymbolicLink(file).toString())
                                        || attributes.isRegularFile() && contains(file, needles))
                                    matches.add(safePath(secret, file));
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException unreadable) {
                // The original exception can include a credential-bearing filename.
                throw new IOException(
                        "Expected readable credential scan paths; got an inspection failure");
            }
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
        needles(secret);
        final List<String> matches = new ArrayList<>();
        for (final Map.Entry<String, String> entry : environment.entrySet()) {
            if (contains(secret, entry.getKey()) || contains(secret, entry.getValue()))
                matches.add(redact(secret, entry.getKey()));
        }
        return matches.stream().sorted().toList();
    }

    static boolean contains(String secret, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (final byte[] needle : needles(secret)) {
            for (int offset = 0; offset <= bytes.length - needle.length; offset++)
                if (Arrays.equals(bytes, offset, offset + needle.length, needle, 0, needle.length))
                    return true;
        }
        return false;
    }

    private static String redact(String secret, String value) {
        String safe = value;
        for (final byte[] needle : needles(secret))
            safe = safe.replace(new String(needle, StandardCharsets.UTF_8), "<redacted>");
        return safe;
    }

    private static Path safePath(String secret, Path path) {
        return Path.of(redact(secret, path.toString()));
    }

    private static List<byte[]> needles(final String secret) {
        if (secret.isEmpty())
            throw new IllegalArgumentException("Expected a nonempty secret to scan");
        final byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        final List<byte[]> result = new ArrayList<>();
        result.add(raw);
        // One full-triple alignment survives any username prefix in Basic authentication.
        for (int offset = 0; offset < 3; offset++) {
            final int end = raw.length - Math.floorMod(raw.length - offset, 3);
            if (end > offset)
                result.add(Base64.getEncoder().encode(Arrays.copyOfRange(raw, offset, end)));
        }
        return result;
    }

    private static boolean contains(final Path file, final List<byte[]> needles)
            throws IOException {
        for (final byte[] needle : needles) if (contains(file, needle)) return true;
        return false;
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
