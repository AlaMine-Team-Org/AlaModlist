package day.alacraft.alamodlist.store;

import day.alacraft.alamodlist.AlaModlist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writing a file so that a process killed halfway through leaves the old one.
 *
 * <p>The naive write - truncate the file, then fill it - has a window in which the file exists and is
 * empty, and a game client can be closed from the task manager inside that window. So the content is
 * composed beside the target, flushed to the device and moved over it. The move is the one step a
 * filesystem will not show half-finished: a reader sees the previous file or the new one, never a
 * truncated mixture.
 *
 * <p>{@link #writePrivate} is the same for a file that holds credentials: the owner-only permissions are
 * on the temporary file before anything is written into it and travel with it through the rename, so
 * there is no instant at which the secret sits in a file other accounts can read.
 */
public final class AtomicFiles {

    private static final String OWNER_ONLY = "rw-------";

    /**
     * How long to keep retrying the rename. Not for the filesystem's benefit but for the other programs on
     * the machine: Windows refuses to replace a file another handle holds open without share-delete, which
     * is how a real-time virus scanner holds a file it has just seen written, or a reader that is in the
     * middle of loading a large file on a loaded machine. The handle is gone in milliseconds, but on a busy
     * machine "milliseconds" stretches; a fixed handful of short attempts lost writes (measured), so the wait
     * is bounded by time, not by count. Past the ceiling the refusal is real and is reported.
     */
    private static final long MOVE_CEILING_NANOS = 1_000_000_000L;

    private static final long FIRST_PAUSE_MILLIS = 5L;

    private static final long LONGEST_PAUSE_MILLIS = 100L;

    private AtomicFiles() {
    }

    /** Replaces {@code file} with {@code content}, atomically. */
    public static void write(Path file, String content) throws IOException {
        write(file, content, false);
    }

    /** The same, for a file that holds credentials: owner-only from the first byte. */
    public static void writePrivate(Path file, String content) throws IOException {
        write(file, content, true);
    }

    private static void write(Path file, String content, boolean ownerOnly) throws IOException {
        Path target = followLink(file);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Beside the target, not in the system temp directory: a move across filesystems is a copy, and a
        // copy cannot be atomic. One name per target, deliberately: the next write takes the same name and
        // consumes the scratch file a killed process left behind, so no litter stays next to a credentials
        // file. The cost is that two writers of one path must not run at once; callers serialise.
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");

        try {
            if (ownerOnly) {
                createPrivate(temporary);
            }
            writeAndFlush(temporary, content);
            moveOnto(temporary, target);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw e;
        }
        syncDirectory(parent);
    }

    /** Fills the scratch file and asks the device to keep it before the rename makes it the real one. */
    private static void writeAndFlush(Path temporary, String content) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    /** The rename, retried with a growing pause while something else is holding the target open. */
    private static void moveOnto(Path temporary, Path target) throws IOException {
        long deadline = System.nanoTime() + MOVE_CEILING_NANOS;
        long pause = FIRST_PAUSE_MILLIS;
        while (true) {
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // Some network and container filesystems refuse an atomic rename. A plain replace
                    // still beats writing in place: the content was complete before the move started.
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw e;
                }
                try {
                    Thread.sleep(Math.min(pause, Math.max(1L, left / 1_000_000L)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                pause = Math.min(pause * 2, LONGEST_PAUSE_MILLIS);
            }
        }
    }

    /**
     * Creates the scratch file empty and readable only by its owner. On POSIX the permissions are part of
     * the creation; Windows cannot say that in one call, so the file is created and tightened while it is
     * still empty - the same guarantee by a slower route (see {@link PrivateFiles}).
     */
    private static void createPrivate(Path path) throws IOException {
        Files.deleteIfExists(path);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
        } else {
            Files.createFile(path);
            PrivateFiles.restrictToOwner(path);
        }
    }

    /**
     * Where the content has to land. A rename replaces a symlink with a regular file, which would detach a
     * file somebody linked into a shared location (an ordinary arrangement in containers and on panel
     * hosts). Following the link first keeps that working and keeps the write atomic. A link pointing at
     * nothing is left to be replaced.
     */
    private static Path followLink(Path file) {
        try {
            return Files.isSymbolicLink(file) ? file.toRealPath() : file;
        } catch (IOException | SecurityException e) {
            return file;
        }
    }

    /** Best effort: makes the rename itself durable. Not possible everywhere (Windows cannot open a directory). */
    private static void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Durability of the rename is a bonus; the content is already complete.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            AlaModlist.LOGGER.debug("Could not remove {}: {}", path, e.toString());
        }
    }
}
