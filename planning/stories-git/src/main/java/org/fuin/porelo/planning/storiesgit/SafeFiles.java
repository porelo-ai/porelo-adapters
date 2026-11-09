package org.fuin.porelo.planning.storiesgit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class SafeFiles {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private final Path root;

    SafeFiles(Path root) {
        try {
            if (Files.isSymbolicLink(root))
                throw Refusals.failed("Expected a regular clone directory; got a symbolic link");
            this.root = root.toRealPath();
        } catch (IOException ex) {
            throw Refusals.failed(
                    "Expected an existing clone directory; got an unreadable directory");
        }
    }

    static Path relative(String name) {
        if (name.isBlank() || name.length() > 1024 || name.startsWith("/") || name.contains("\\"))
            throw Refusals.failed("Expected a relative store path; got " + name);
        for (final String part : name.split("/", -1)) {
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                    || part.equals(".git")
                    || part.contains(".."))
                throw Refusals.failed("Expected a contained store path; got " + name);
        }
        return Path.of(name);
    }

    String read(String name) {
        return optional(name)
                .orElseThrow(
                        () ->
                                Refusals.failed(
                                        "Expected readable file " + name + "; got a missing file"));
    }

    Optional<String> optional(String name) {
        final Path relative = relative(name);
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(root)) {
            // Directory descriptors keep parent replacements from redirecting an open to the host.
            if (!(opened instanceof SecureDirectoryStream<Path> secure))
                throw Refusals.failed(
                        "Expected secure directory access for "
                                + name
                                + "; got an unsupported filesystem");
            return Optional.of(readAt(secure, relative, 0));
        } catch (NoSuchFileException ex) {
            return Optional.empty();
        } catch (IOException ex) {
            throw Refusals.failed(
                    "Expected readable regular file "
                            + name
                            + "; got an unsafe or unreadable file");
        }
    }

    private String readAt(SecureDirectoryStream<Path> directory, Path path, int index)
            throws IOException {
        if (index < path.getNameCount() - 1) {
            try (SecureDirectoryStream<Path> child =
                    directory.newDirectoryStream(path.getName(index), LinkOption.NOFOLLOW_LINKS)) {
                return readAt(child, path, index + 1);
            }
        }
        final Path full = root.resolve(path);
        if (!full.toRealPath().startsWith(root)
                || !Files.isRegularFile(full, LinkOption.NOFOLLOW_LINKS))
            throw Refusals.failed(
                    "Expected contained regular file " + path + "; got a link or non-file");
        try (SeekableByteChannel channel =
                directory.newByteChannel(
                        path.getName(index),
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final ByteBuffer chunk = ByteBuffer.allocate(8192);
            while (channel.read(chunk) != -1) {
                if (output.size() + chunk.position() > MAX_BYTES)
                    throw Refusals.failed(
                            "Expected file at most "
                                    + MAX_BYTES
                                    + " bytes; got oversized file "
                                    + path);
                output.write(chunk.array(), 0, chunk.position());
                chunk.clear();
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    List<String> markdown(String name) {
        final Path path = relative(name);
        final List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(root)) {
            if (!(opened instanceof SecureDirectoryStream<Path> secure))
                throw Refusals.failed(
                        "Expected secure directory access; got an unsupported filesystem");
            listAt(secure, path, 0, found, new int[] {0});
        } catch (IOException ex) {
            throw Refusals.failed(
                    "Expected readable directory " + name + "; got a missing or unsafe directory");
        }
        return found.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private void listAt(
            SecureDirectoryStream<Path> parent,
            Path path,
            int index,
            List<String> found,
            int[] count)
            throws IOException {
        if (index < path.getNameCount()) {
            try (SecureDirectoryStream<Path> child =
                    parent.newDirectoryStream(path.getName(index), LinkOption.NOFOLLOW_LINKS)) {
                listAt(child, path, index + 1, found, count);
            }
            return;
        }
        for (final Path entry : parent) {
            if (++count[0] > MAX_ENTRIES)
                throw Refusals.failed(
                        "Expected at most "
                                + MAX_ENTRIES
                                + " store entries; got a larger directory");
            final String name = entry.getFileName().toString();
            if (name.equals(".git") || name.endsWith(".sub")) continue;
            final Path nested = path.resolve(name);
            relative(nested.toString());
            if (Files.isDirectory(root.resolve(nested), LinkOption.NOFOLLOW_LINKS)) {
                if (nested.getNameCount() > 12)
                    throw Refusals.failed("Expected shallow store directories; got " + nested);
                try (SecureDirectoryStream<Path> child =
                        parent.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS)) {
                    listAt(child, nested, nested.getNameCount(), found, count);
                }
            } else if (name.endsWith(".md")) found.add(nested.toString());
        }
    }
}
