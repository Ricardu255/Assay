package io.assay.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A cross-process exclusive lock backed by one byte of a lock file.
 *
 * <p>A one-byte lock taken with {@link FileChannel#tryLock} covers Windows and POSIX with a single
 * implementation, and — like a freshly opened descriptor on POSIX — it also rejects a second holder
 * inside the same JVM.
 */
public final class ExclusiveFileLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private ExclusiveFileLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Takes the lock, or throws when another process or thread already holds it. */
    public static ExclusiveFileLock acquire(Path path, String description) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel =
                    FileChannel.open(
                            path,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock(0, 1, false);
                if (lock == null) {
                    channel.close();
                    throw new IllegalStateException("already running: " + description);
                }
                return new ExclusiveFileLock(channel, lock);
            } catch (OverlappingFileLockException error) {
                channel.close();
                throw new IllegalStateException("already running: " + description);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Releasing a lock that the OS already dropped is not an error worth surfacing.
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Same.
            }
        }
    }
}
