package day.alacraft.alamodlist.store;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    @TempDir
    Path directory;

    @Test
    void writesAndReadsBackUnchanged() throws IOException {
        Path file = directory.resolve("plain.json");

        AtomicFiles.write(file, "{\"a\": 1}");

        assertEquals("{\"a\": 1}", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("UTF-8 regardless of what this machine defaults to")
    void writesUtf8() throws IOException {
        Path file = directory.resolve("text.json");

        AtomicFiles.write(file, "genügend ✓");

        assertEquals("genügend ✓", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void createsTheDirectoriesItNeeds() throws IOException {
        Path file = directory.resolve("deep").resolve("deeper").resolve("file.json");

        AtomicFiles.writePrivate(file, "[]");

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void replacesWhatWasThere() throws IOException {
        Path file = directory.resolve("file.json");

        AtomicFiles.writePrivate(file, "first");
        AtomicFiles.writePrivate(file, "second");

        assertEquals("second", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("no scratch file is left after a good write")
    void leavesNoTemporaryBehind() throws IOException {
        Path file = directory.resolve("file.json");

        AtomicFiles.writePrivate(file, "[]");
        AtomicFiles.write(file, "[1]");

        assertFalse(Files.exists(directory.resolve("file.json.tmp")));
    }

    @Test
    @DisplayName("a write that fails before the move leaves the old file exactly as it was")
    void failedWriteKeepsTheOldFile() throws IOException {
        // The scratch name is taken by a non-empty directory: the write into it must fail, which is the
        // closest a test can come to a process dying before the move without breaking the filesystem.
        for (boolean ownerOnly : new boolean[] {false, true}) {
            Path file = directory.resolve(ownerOnly ? "private.json" : "plain.json");
            Files.writeString(file, "old content");
            Path scratch = Files.createDirectory(directory.resolve(file.getFileName() + ".tmp"));
            Files.writeString(scratch.resolve("blocker"), "x");

            assertThrows(IOException.class, () -> {
                if (ownerOnly) {
                    AtomicFiles.writePrivate(file, "NEW content");
                } else {
                    AtomicFiles.write(file, "NEW content");
                }
            });

            assertEquals("old content", Files.readString(file), "the old file must survive a failed write");
        }
    }

    @Test
    @DisplayName("a write that fails at the move (target is a directory) removes its scratch file")
    void failedMoveCleansUp() throws IOException {
        Path blocked = directory.resolve("blocked.json");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("inside"), "x");

        assertThrows(IOException.class, () -> AtomicFiles.write(blocked, "anything"));

        assertFalse(Files.exists(directory.resolve("blocked.json.tmp")));
    }

    @Test
    @DisplayName("a scratch file left by a killed process is consumed by the next write")
    void staleScratchIsConsumed() throws IOException {
        Path file = directory.resolve("file.json");
        Files.writeString(file, "old");
        Files.writeString(directory.resolve("file.json.tmp"), "half of a wri");

        assertEquals("old", Files.readString(file), "a stale scratch file never replaces the target by itself");
        AtomicFiles.writePrivate(file, "new");

        assertEquals("new", Files.readString(file));
        assertFalse(Files.exists(directory.resolve("file.json.tmp")));
    }

    @Test
    @DisplayName("a reader sees the old content or the new one, never a mixture")
    void readerNeverSeesATornFile() throws Exception {
        Path file = directory.resolve("big.json");
        String a = "A".repeat(300_000);
        String b = "B".repeat(300_000);
        AtomicFiles.write(file, a);

        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger torn = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                try {
                    String seen = Files.readString(file, StandardCharsets.UTF_8);
                    reads.incrementAndGet();
                    if (!seen.equals(a) && !seen.equals(b)) {
                        torn.incrementAndGet();
                    }
                } catch (IOException ignored) {
                    // Windows may refuse to open a file for the instant of the rename; that is not a torn read.
                }
                try {
                    // A reader that is open every instant is not a virus scanner: Windows then refuses the
                    // rename (measured: 5 of 120 writes lost at 2 ms between reads, none at 15 ms), and the
                    // retry in AtomicFiles is for handles that close. In-place writing instead of the
                    // rename fails this test: the same reader sees a truncated file in most of its reads.
                    Thread.sleep(15);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 120; i++) {
                AtomicFiles.write(file, i % 2 == 0 ? b : a);
            }
        } finally {
            done.set(true);
            reader.join();
        }

        assertTrue(reads.get() > 0, "the reader never got a read in, so nothing was proven");
        assertEquals(0, torn.get(), "a reader saw a half-written file");
    }

    /** Windows refuses to replace a file another handle holds open; POSIX does not. The tests below need the refusal. */
    private void assumeOpenFilesCannotBeReplaced() throws IOException {
        Path probe = directory.resolve("probe.json");
        Path other = directory.resolve("probe.other");
        Files.writeString(probe, "x");
        Files.writeString(other, "y");
        boolean refused = false;
        try (InputStream open = Files.newInputStream(probe)) {
            Files.move(other, probe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AccessDeniedException e) {
            refused = true;
        }
        Files.deleteIfExists(probe);
        Files.deleteIfExists(other);
        Assumptions.assumeTrue(refused, "this platform replaces an open file; there is no refusal to retry");
    }

    @Test
    @DisplayName("a write waits out a handle that is released late, instead of losing the content")
    void waitsForALateReleasedHandle() throws Exception {
        assumeOpenFilesCannotBeReplaced();
        Path file = directory.resolve("held.json");
        AtomicFiles.write(file, "old");
        InputStream held = Files.newInputStream(file);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(400);
                held.close();
            } catch (InterruptedException | IOException ignored) {
                // the write below fails the test if the handle stays
            }
        });
        releaser.start();
        try {
            AtomicFiles.write(file, "new");
        } finally {
            releaser.join();
            held.close();
        }

        assertEquals("new", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a handle that is never released ends in an error after the ceiling, with the old file intact")
    void reportsAHandleThatNeverLetsGo() throws Exception {
        assumeOpenFilesCannotBeReplaced();
        Path file = directory.resolve("stuck.json");
        AtomicFiles.write(file, "old");
        long millis;
        try (InputStream held = Files.newInputStream(file)) {
            long started = System.nanoTime();
            assertThrows(AccessDeniedException.class, () -> AtomicFiles.write(file, "new"));
            millis = (System.nanoTime() - started) / 1_000_000L;
        }

        assertTrue(millis >= 900, "gave up after only " + millis + " ms");
        assertTrue(millis < 5_000, "kept retrying for " + millis + " ms");
        assertFalse(Files.exists(directory.resolve("stuck.json.tmp")), "the scratch file was left behind");
        assertEquals("old", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a private write leaves a file only its owner can reach, and keeps it so on rewrite")
    void privateWriteIsOwnerOnly() throws IOException {
        Path file = directory.resolve("credentials.json");

        AtomicFiles.writePrivate(file, "first");
        PrivateFilesTest.assertOwnerOnly(file);
        AtomicFiles.writePrivate(file, "second");
        PrivateFilesTest.assertOwnerOnly(file);
    }

    @Test
    @DisplayName("a symlinked target is written through, not replaced by a regular file")
    void followsASymlink() throws IOException {
        Path real = directory.resolve("real.json");
        Files.writeString(real, "old");
        Path link = directory.resolve("link.json");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links are not available here");
        }

        AtomicFiles.write(link, "new");

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(real));
    }
}
