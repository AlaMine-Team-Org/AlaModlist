package day.alacraft.alamodlist.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes one file in a bounded buffer, never reading it whole: jars run to hundreds of megabytes.
 *
 * <p>SHA-1 is always computed, and it is what the service matches on. The CurseForge fingerprint is optional and
 * costs a second pass over the file, because its seed is the length of the file without its whitespace bytes and
 * that length is only known after one full read.
 */
public final class FileHasher {

    /** Size of the read buffer of one worker. */
    public static final int BUFFER_SIZE = 256 * 1024;

    /** The cancel flag is looked at every this many buffers (4 MiB). */
    private static final int CHECK_EVERY = 16;

    private FileHasher() {
    }

    /** Opens the same content again. */
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    /**
     * @param sha1    40 lower-case hexadecimal digits
     * @param murmur2 the CurseForge fingerprint, or null when it was not asked for
     * @param size    the number of bytes read
     */
    public record Digest(String sha1, Long murmur2, long size) {
    }

    /** Hashes a file. */
    public static Digest hash(Path file, boolean murmur2, Progress progress, CancelToken cancel) throws IOException {
        return hash(() -> Files.newInputStream(file), murmur2, progress, cancel);
    }

    /**
     * Hashes content that can be opened twice.
     *
     * @param progress counts the bytes of the first pass; may be null
     * @throws java.util.concurrent.CancellationException when {@code cancel} was set, checked between blocks
     */
    public static Digest hash(Opener opener, boolean murmur2, Progress progress, CancelToken cancel) throws IOException {
        cancel.throwIfCancelled();
        MessageDigest sha1 = newSha1();
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0;
        long normalised = 0;
        try (InputStream in = opener.open()) {
            int blocks = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                sha1.update(buffer, 0, read);
                size += read;
                if (murmur2) {
                    normalised += Murmur2Cf.normalisedLength(buffer, 0, read);
                }
                if (progress != null) {
                    progress.bytes(read);
                }
                if (++blocks % CHECK_EVERY == 0) {
                    cancel.throwIfCancelled();
                }
            }
        }
        Long fingerprint = murmur2 ? fingerprint(opener, normalised, buffer, cancel) : null;
        return new Digest(HexFormat.of().formatHex(sha1.digest()), fingerprint, size);
    }

    private static long fingerprint(Opener opener, long normalised, byte[] buffer, CancelToken cancel) throws IOException {
        Murmur2Cf murmur = new Murmur2Cf(normalised);
        try (InputStream in = opener.open()) {
            int blocks = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                murmur.update(buffer, 0, read);
                if (++blocks % CHECK_EVERY == 0) {
                    cancel.throwIfCancelled();
                }
            }
        }
        return murmur.finish();
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime has to provide SHA-1.
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
