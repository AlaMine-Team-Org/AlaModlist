package day.alacraft.alamodlist.hash;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHasherTest {

    private static FileHasher.Digest hash(byte[] content, boolean murmur2) throws IOException {
        return FileHasher.hash(() -> new ByteArrayInputStream(content), murmur2, null, new CancelToken());
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void sha1MatchesTheKnownVectors() throws IOException {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hash(new byte[0], false).sha1());
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hash(ascii("abc"), false).sha1());
        assertEquals("2fd4e1c67a2d28fced849ee1bb76e7391b93eb12",
                hash(ascii("The quick brown fox jumps over the lazy dog"), false).sha1());
    }

    /**
     * The expected values come from a separate implementation (written in Python, with the length and the tail
     * handled differently); the real jar below is the check against the value CurseForge's own algorithm gives.
     */
    @Test
    void murmur2MatchesTheVectorsOfAnIndependentImplementation() throws IOException {
        assertEquals(1540447798L, hash(new byte[0], true).murmur2());
        assertEquals(626045324L, hash(ascii("a"), true).murmur2());
        assertEquals(3376380438L, hash(ascii("abcd"), true).murmur2());
        assertEquals(3469237630L, hash(ascii("abcde"), true).murmur2());
        assertEquals(2824650221L, hash(ascii("hello world"), true).murmur2());
        assertEquals(3751777527L, hash(ascii("The quick brown fox jumps over the lazy dog"), true).murmur2());
        byte[] everyByte = new byte[256];
        for (int i = 0; i < 256; i++) {
            everyByte[i] = (byte) i;
        }
        assertEquals(2094645347L, hash(everyByte, true).murmur2());
    }

    @Test
    void theFingerprintIgnoresTabsLineBreaksAndSpaces() throws IOException {
        assertEquals(hash(ascii("\t\n\r "), true).murmur2(), hash(new byte[0], true).murmur2());
        assertEquals(hash(ascii("abcde"), true).murmur2(), hash(ascii("a b\tc\r\nd e "), true).murmur2());
    }

    @Test
    void theFingerprintIsNotComputedUnlessAsked() throws IOException {
        assertNull(hash(ascii("abc"), false).murmur2());
    }

    /** The input is read in blocks; a result must not depend on where the blocks end. */
    @Test
    void aFileLongerThanTheBufferGivesTheSameHashesAsOneBlock() throws IOException {
        byte[] content = new byte[FileHasher.BUFFER_SIZE * 2 + 7];
        new Random(42).nextBytes(content);
        for (int i = 0; i < content.length; i += 97) {
            content[i] = 32;
        }
        FileHasher.Digest digest = hash(content, true);
        assertEquals(content.length, digest.size());
        assertEquals(oneBlockFingerprint(content), digest.murmur2());
    }

    @Test
    void theRealFabricLoaderJarGivesTheFingerprintCurseForgeComputes() throws IOException {
        Path jar = fabricLoaderJar();
        Assumptions.assumeTrue(jar != null, "NOT RUN: fabric-loader-0.19.5.jar is not in the Gradle cache of this machine");
        FileHasher.Digest digest = FileHasher.hash(jar, true, null, new CancelToken());
        assertEquals(4145106695L, digest.murmur2());
        assertEquals(1984980L, digest.size());
        // The Gradle cache names the folder of a file by its SHA-1.
        assertEquals(jar.getParent().getFileName().toString(), digest.sha1());
    }

    @Test
    void aCancelledTokenStopsBeforeReading() {
        CancelToken cancel = new CancelToken();
        cancel.cancel();
        AtomicLong opened = new AtomicLong();
        assertThrows(CancellationException.class, () -> FileHasher.hash(() -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(new byte[10]);
        }, false, null, cancel));
        assertEquals(0, opened.get());
    }

    @Test
    void cancellingWhileReadingStopsWithinAFewBlocks() {
        long total = 64L * 1024 * 1024;
        CancelToken cancel = new CancelToken();
        AtomicLong delivered = new AtomicLong();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                long left = total - delivered.get();
                if (left <= 0) {
                    return -1;
                }
                int count = (int) Math.min(length, left);
                if (delivered.addAndGet(count) >= 8L * 1024 * 1024) {
                    cancel.cancel();
                }
                return count;
            }
        };
        assertThrows(CancellationException.class, () -> FileHasher.hash(() -> endless, false, null, cancel));
        assertTrue(delivered.get() < total / 2, "kept reading after the cancel: " + delivered.get());
    }

    @Test
    void progressCountsTheBytesOfTheFirstPassOnly() throws IOException {
        byte[] content = new byte[FileHasher.BUFFER_SIZE + 1000];
        Progress progress = new Progress();
        FileHasher.hash(() -> new ByteArrayInputStream(content), true, progress, new CancelToken());
        assertEquals(content.length, progress.bytesDone());
    }

    /** The straightforward version over a whole array: the shape the algorithm is usually given in. */
    private static long oneBlockFingerprint(byte[] data) {
        final int m = 0x5bd1e995;
        int n = 0;
        for (byte b : data) {
            if (!(b == 9 || b == 10 || b == 13 || b == 32)) {
                n++;
            }
        }
        int h = 1 ^ n;
        int k = 0;
        int shift = 0;
        for (byte b : data) {
            if (b == 9 || b == 10 || b == 13 || b == 32) {
                continue;
            }
            k |= (b & 0xff) << shift;
            shift += 8;
            if (shift == 32) {
                k *= m;
                k ^= k >>> 24;
                k *= m;
                h *= m;
                h ^= k;
                k = 0;
                shift = 0;
            }
        }
        if (shift > 0) {
            h ^= k;
            h *= m;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return Integer.toUnsignedLong(h);
    }

    /** The jar of the loader this project builds against, found in the Gradle cache; null if it is not there. */
    private static Path fabricLoaderJar() throws IOException {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        Path home = gradleHome != null ? Path.of(gradleHome) : Path.of(System.getProperty("user.home"), ".gradle");
        Path folder = home.resolve("caches/modules-2/files-2.1/net.fabricmc/fabric-loader/0.19.5");
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(folder, 2)) {
            return files.filter(path -> path.getFileName().toString().equals("fabric-loader-0.19.5.jar"))
                    .findFirst().orElse(null);
        }
    }
}
