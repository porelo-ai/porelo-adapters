package org.fuin.porelo.planning.storiesgit;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Keeps clone installation and crash cleanup exclusive across adapter processes. */
final class StoreLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    StoreLock(Path directory) {
        FileChannel opened = null;
        try {
            opened =
                    FileChannel.open(
                            directory.resolve("lock"),
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            final FileLock acquired = opened.tryLock();
            if (acquired == null)
                throw Refusals.failed(
                        "Expected exclusive adapter storage; got a directory already in use");
            channel = opened;
            lock = acquired;
        } catch (IOException | RuntimeException failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof OverlappingFileLockException)
                throw Refusals.failed(
                        "Expected exclusive adapter storage; got a directory already in use");
            if (failure instanceof IOException)
                throw Refusals.failed(
                        "Expected an exclusive directory lock; got an unsafe or unreadable lock");
            throw (RuntimeException) failure;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ex) {
            throw Refusals.failed("Expected directory lock release; got a lock failure");
        } finally {
            try {
                channel.close();
            } catch (IOException ex) {
                throw Refusals.failed(
                        "Expected directory lock channel cleanup; got a close failure");
            }
        }
    }
}
