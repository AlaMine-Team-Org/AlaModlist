package day.alacraft.alamodlist.hash;

import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.RawMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModHashingTest {

    @TempDir
    Path folder;

    private Path jar(String name, int size) throws IOException {
        byte[] content = new byte[size];
        new Random(name.hashCode()).nextBytes(content);
        Path file = folder.resolve(name);
        Files.write(file, content);
        return file;
    }

    private static String sha1(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
    }

    private static RawMod mod(String id, ModKind kind, String parent, Path file) {
        return new RawMod(id, id, "1", kind, parent, null, file, null, List.of());
    }

    private static ModHashing.Outcome run(List<RawMod> mods, HashCache cache) {
        return ModHashing.run(mods, cache, false, 3, new Progress(), new CancelToken());
    }

    @Test
    void hashesEveryDistinctFileOnceWithTheRightHash() throws Exception {
        List<RawMod> mods = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            mods.add(mod("mod" + i, ModKind.MOD, null, jar("m" + i + ".jar", 1000 + i * 5000)));
        }
        // Two mods in one jar: one file, hashed once.
        mods.add(mod("second", ModKind.MOD, null, folder.resolve("m3.jar")));

        ModHashing.Outcome outcome = run(mods, HashCache.inMemory());

        assertEquals(12, outcome.stats().files());
        assertEquals(0, outcome.stats().failed());
        assertEquals(12, outcome.facts().size());
        for (RawMod mod : mods) {
            ModHashing.FileFacts facts = outcome.facts().get(ModHashing.keyOf(mod.file()));
            assertEquals(sha1(mod.file()), facts.hashes().sha1());
            assertEquals(Files.size(mod.file()), facts.size());
        }
    }

    /**
     * A jar inside another mod's jar is not a file anything indexes: a hash of it would match nothing and cost
     * a read. The loader adapter may well hand over a real file for it; the stage must still refuse.
     */
    @Test
    void aNestedJarIsNeverHashedEvenWhenItHasAFile() throws Exception {
        Path outer = jar("outer.jar", 2000);
        Path inner = jar("inner.jar", 3000);
        List<RawMod> mods = List.of(
                mod("outer", ModKind.MOD, null, outer),
                mod("inner", ModKind.LIBRARY, "outer", inner));

        ModHashing.Outcome outcome = run(mods, HashCache.inMemory());

        assertEquals(1, outcome.stats().files());
        assertTrue(outcome.facts().containsKey(ModHashing.keyOf(outer)));
        assertFalse(outcome.facts().containsKey(ModHashing.keyOf(inner)));
        assertFalse(ModHashing.hashable(mods.get(1)));
    }

    @Test
    void aBuiltInComponentAFolderAndAMissingFileAreNotHashed() throws Exception {
        Path directory = Files.createDirectory(folder.resolve("classes"));
        List<RawMod> mods = List.of(
                mod("minecraft", ModKind.BUILTIN, null, jar("client.jar", 500)),
                mod("dev", ModKind.MOD, null, directory),
                mod("missing", ModKind.MOD, null, folder.resolve("nowhere.jar")),
                mod("nofile", ModKind.MOD, null, null));

        ModHashing.Outcome outcome = run(mods, HashCache.inMemory());

        assertEquals(0, outcome.stats().files());
        assertTrue(outcome.facts().isEmpty());
    }

    @Test
    void theSecondRunReadsNothingBecauseTheCacheAnswers() throws Exception {
        List<RawMod> mods = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            mods.add(mod("mod" + i, ModKind.MOD, null, jar("m" + i + ".jar", 40_000 + i)));
        }
        Path cacheFile = folder.resolve("data/hash-cache.json");

        ModHashing.Outcome first = run(mods, HashCache.open(cacheFile));
        ModHashing.Outcome second = run(mods, HashCache.open(cacheFile));

        assertEquals(0, first.stats().cacheHits());
        assertTrue(first.stats().bytesHashed() > 0);
        assertEquals(8, second.stats().cacheHits());
        assertEquals(0, second.stats().bytesHashed());
        assertEquals(first.facts(), second.facts());
    }

    @Test
    void aFileThatChangedIsHashedAgain() throws Exception {
        Path file = jar("m.jar", 5000);
        List<RawMod> mods = List.of(mod("m", ModKind.MOD, null, file));
        Path cacheFile = folder.resolve("hash-cache.json");
        ModHashing.Outcome before = run(mods, HashCache.open(cacheFile));

        Files.write(file, new byte[5001]);
        ModHashing.Outcome after = run(mods, HashCache.open(cacheFile));

        assertEquals(0, after.stats().cacheHits());
        assertEquals(sha1(file), after.facts().get(ModHashing.keyOf(file)).hashes().sha1());
        assertFalse(before.facts().get(ModHashing.keyOf(file)).hashes().sha1()
                .equals(after.facts().get(ModHashing.keyOf(file)).hashes().sha1()));
    }

    @Test
    void theFingerprintIsComputedOnlyWhenAsked() throws Exception {
        Path file = jar("m.jar", 5000);
        List<RawMod> mods = List.of(mod("m", ModKind.MOD, null, file));

        ModHashing.Outcome plain = ModHashing.run(mods, HashCache.inMemory(), false, 2, new Progress(), new CancelToken());
        ModHashing.Outcome both = ModHashing.run(mods, HashCache.inMemory(), true, 2, new Progress(), new CancelToken());

        assertNull(plain.facts().get(ModHashing.keyOf(file)).hashes().murmur2());
        assertTrue(both.facts().get(ModHashing.keyOf(file)).hashes().murmur2() > 0);
    }

    @Test
    void progressReachesTheTotals() throws Exception {
        List<RawMod> mods = List.of(
                mod("a", ModKind.MOD, null, jar("a.jar", 10_000)),
                mod("b", ModKind.MOD, null, jar("b.jar", 20_000)));
        Progress progress = new Progress();

        ModHashing.run(mods, HashCache.inMemory(), false, 2, progress, new CancelToken());

        assertEquals(2, progress.filesTotal());
        assertEquals(2, progress.filesDone());
        assertEquals(30_000, progress.bytesTotal());
        assertEquals(30_000, progress.bytesDone());
    }

    @Test
    void aCancelledTokenStopsTheStage() throws Exception {
        List<RawMod> mods = List.of(mod("a", ModKind.MOD, null, jar("a.jar", 10_000)));
        CancelToken cancel = new CancelToken();
        cancel.cancel();

        assertThrows(CancellationException.class,
                () -> ModHashing.run(mods, HashCache.inMemory(), false, 2, new Progress(), cancel));
    }

    @Test
    void oneUnreadableJarLeavesTheOthersHashedAndIsCounted() throws Exception {
        Path bad = jar("bad.jar", 1000);
        List<RawMod> mods = List.of(
                mod("a", ModKind.MOD, null, jar("a.jar", 1000)),
                mod("bad", ModKind.MOD, null, bad),
                mod("c", ModKind.MOD, null, jar("c.jar", 1000)));
        Progress progress = new Progress();

        ModHashing.Outcome outcome = ModHashing.run(mods, HashCache.inMemory(), false, 2, progress, new CancelToken(),
                (file, murmur2, p, cancel) -> {
                    if (file.getFileName().toString().equals("bad.jar")) {
                        throw new IOException("locked by another program");
                    }
                    return FileHasher.hash(file, murmur2, p, cancel);
                });

        assertEquals(3, outcome.stats().files());
        assertEquals(1, outcome.stats().failed());
        assertEquals(2, outcome.facts().size());
        assertFalse(outcome.facts().containsKey(ModHashing.keyOf(bad)));
        assertEquals(3, progress.filesDone());
    }

    /** A reader that never finishes by itself: it stops only when told to, the way a stalled disk does. */
    private static FileHasher.Digest stall(CancelToken cancel) throws IOException {
        try {
            while (true) {
                cancel.throwIfCancelled();
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /**
     * @implements TC-PREVIEW-001-FUN09 - when the time for reading runs out the files read are kept and the stage ends normally
     * @covers R-GUI-04
     */
    @Test
    void theSoftLimitKeepsWhatWasReadAndLeavesTheRestWithoutHashesAndEndsNormally() throws Exception {
        Path slow = jar("slow.jar", 20_000);
        List<RawMod> mods = List.of(
                mod("a", ModKind.MOD, null, jar("a.jar", 1000)),
                mod("slow", ModKind.MOD, null, slow),
                mod("c", ModKind.MOD, null, jar("c.jar", 1000)));
        Progress progress = new Progress();

        ModHashing.Outcome outcome = ModHashing.run(mods, HashCache.inMemory(), false, 2, progress, new CancelToken(),
                (file, murmur2, p, cancel) -> file.getFileName().toString().equals("slow.jar")
                        ? stall(cancel) : FileHasher.hash(file, murmur2, p, cancel),
                java.time.Duration.ofMillis(150));

        assertEquals(3, outcome.stats().files());
        assertEquals(1, outcome.stats().timedOut());
        assertEquals(0, outcome.stats().failed(), "running out of time is not an unreadable file");
        assertEquals(2, outcome.facts().size());
        assertFalse(outcome.facts().containsKey(ModHashing.keyOf(slow)));
        assertEquals(3, progress.filesDone(), "the bar still reaches the end");
    }

    @Test
    void thePlayersCancelStillThrowsWhileTheSoftLimitIsFarAway() throws Exception {
        List<RawMod> mods = List.of(mod("slow", ModKind.MOD, null, jar("slow.jar", 20_000)));
        CancelToken cancel = new CancelToken();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                // The test ends either way.
            }
            cancel.cancel();
        });
        canceller.start();
        long started = System.nanoTime();

        assertThrows(CancellationException.class, () -> ModHashing.run(mods, HashCache.inMemory(), false, 1,
                new Progress(), cancel, (file, murmur2, p, token) -> stall(token), java.time.Duration.ofMinutes(5)));

        assertTrue(System.nanoTime() - started < 5_000_000_000L, "and promptly, not at the end of the limit");
        canceller.join();
    }
}
