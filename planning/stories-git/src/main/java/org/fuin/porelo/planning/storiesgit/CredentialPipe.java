package org.fuin.porelo.planning.storiesgit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Delivers one call's credential through an owner-only FIFO, without storing its bytes on disk. */
final class CredentialPipe implements AutoCloseable {
    private final Path scratch;
    private final FileChannel channel;

    CredentialPipe(Path temporary, String token, Map<String, String> environment)
            throws IOException, InterruptedException {
        final byte[] bytes = (token + "\n").getBytes(StandardCharsets.UTF_8);
        // A bounded atomic pipe write cannot block before the Git reader has started.
        if (bytes.length > 4096 || token.codePoints().anyMatch(Character::isISOControl))
            throw Refusals.invalid(
                    "Expected a credential without control characters and at most 4095 UTF-8"
                            + " bytes");
        scratch =
                Files.createTempDirectory(
                        temporary,
                        "credential-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        FileChannel opened = null;
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder("/usr/bin/mkfifo", "--mode=600", "--", path().toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(environment);
            final Process process = builder.start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
                    throw new IOException("Cannot create private credential pipe");
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                if (!process.waitFor(1, TimeUnit.SECONDS))
                    throw new IOException("Credential pipe creator remains alive");
            }
            if (Files.isSymbolicLink(path())
                    || (((int) Files.getAttribute(path(), "unix:mode", LinkOption.NOFOLLOW_LINKS))
                                    & 0170000)
                            != 0010000) throw new IOException("Credential transport is not a FIFO");
            // Linux permits RDWR on a FIFO without a waiting reader; its contents stay in kernel
            // memory.
            opened =
                    FileChannel.open(
                            path(),
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            final ByteBuffer pending = ByteBuffer.wrap(bytes);
            while (pending.hasRemaining()) opened.write(pending);
            channel = opened;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (opened != null)
                try {
                    opened.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            try {
                remove();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    Path path() {
        return scratch.resolve("password");
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            remove();
        }
    }

    private void remove() throws IOException {
        try (var paths = Files.walk(scratch)) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}
