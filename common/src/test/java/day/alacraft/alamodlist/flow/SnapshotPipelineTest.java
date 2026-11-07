package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.assemble.EnvironmentAssembler;
import day.alacraft.alamodlist.hash.CancelToken;
import day.alacraft.alamodlist.hash.HashCache;
import day.alacraft.alamodlist.hash.ModHashing;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.privacy.PrivacyGate;
import day.alacraft.alamodlist.privacy.Verdict;
import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.ModSource;
import day.alacraft.alamodlist.source.RawMod;
import day.alacraft.alamodlist.source.Side;
import day.alacraft.alamodlist.wire.SnapshotJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole background job, with a fake loader and real files, judged by the real privacy gate. */
class SnapshotPipelineTest {

    @TempDir
    Path folder;

    private final EnvironmentInfo environment = EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5");

    /** A game directory shaped like a player's, with the account name in the path. */
    private Path gameDirectory() throws IOException {
        Path game = folder.resolve("Users").resolve("PlayerOneAccount").resolve("instances").resolve("main");
        Files.createDirectories(game.resolve("mods"));
        return game;
    }

    private ModSource source(Path game, String license) throws IOException {
        List<RawMod> mods = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Path jar = game.resolve("mods").resolve("mod-" + i + ".jar");
            byte[] bytes = new byte[2000 + i];
            new Random(i).nextBytes(bytes);
            Files.write(jar, bytes);
            mods.add(new RawMod("mod" + i, "Mod " + i, "1." + i, ModKind.MOD, null, Side.UNIVERSAL, jar, license,
                    List.of("Someone")));
        }
        mods.add(new RawMod("minecraft", "Minecraft", "26.3", ModKind.BUILTIN, null, Side.UNIVERSAL, null, null, List.of()));
        return () -> mods;
    }

    private SnapshotPipeline.Result collect(ModSource source, Path cacheFile, CancelToken cancel) {
        return SnapshotPipeline.collect("alamodlist/test", environment, source, HashCache.open(cacheFile), false,
                2, new Progress(), cancel);
    }

    @Test
    void buildsTheEnvironmentAndTheModsAndNothingElse() throws IOException {
        Path game = gameDirectory();

        SnapshotPipeline.Result result = collect(source(game, "MIT"), folder.resolve("cache.json"), new CancelToken());

        assertEquals(environment, result.snapshot().environment());
        assertEquals(6, result.snapshot().mods().size());
        assertEquals(5, result.hashing().files());
        assertTrue(result.snapshot().resourcePacks().isEmpty());
        assertTrue(result.snapshot().plugins().isEmpty());
        assertEquals(null, result.snapshot().server());
        assertEquals("mod-3.jar", result.snapshot().mods().stream()
                .filter(entry -> entry.id().equals("mod3")).findFirst().orElseThrow().fileName());
    }

    @Test
    void theBytesThatWouldLeaveHoldNoPathOfTheMachine() throws IOException {
        Path game = gameDirectory();
        KnownValues known = KnownValues.builder().gameDir(game).configDir(game.resolve("config"))
                .homeDir(folder.resolve("Users").resolve("PlayerOneAccount").toString())
                .osUser("PlayerOneAccount").build();

        SnapshotPipeline.Result result = collect(source(game, "MIT"), folder.resolve("cache.json"), new CancelToken());
        byte[] body = SnapshotJson.toBytes(result.snapshot());

        assertTrue(PrivacyGate.check(body, known).clean());
        String text = new String(body, StandardCharsets.UTF_8);
        assertFalse(text.contains("PlayerOneAccount"), text);
        assertFalse(text.contains("instances"), text);
    }

    /** A gate that never fails proves nothing: the same wiring must stop a mod that carries the account name. */
    @Test
    void theGateInTheSameWiringStopsAPathThatDidGetIn() throws IOException {
        Path game = gameDirectory();
        KnownValues known = KnownValues.builder().gameDir(game).osUser("PlayerOneAccount").build();

        SnapshotPipeline.Result result = collect(source(game, "Licensed to " + game), folder.resolve("cache.json"),
                new CancelToken());
        Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(result.snapshot()), known);

        assertFalse(verdict.clean());
    }

    @Test
    void theSecondSnapshotComesFromTheCache() throws IOException {
        Path game = gameDirectory();
        ModSource source = source(game, "MIT");
        Path cacheFile = folder.resolve("data/hash-cache.json");

        SnapshotPipeline.Result first = collect(source, cacheFile, new CancelToken());
        SnapshotPipeline.Result second = collect(source, cacheFile, new CancelToken());

        assertEquals(0, first.hashing().cacheHits());
        assertEquals(5, second.hashing().cacheHits());
        assertEquals(0, second.hashing().bytesHashed());
        assertEquals(first.snapshot().mods(), second.snapshot().mods());
    }

    @Test
    void aCancelledJobDoesNotProduceASnapshot() throws IOException {
        Path game = gameDirectory();
        CancelToken cancel = new CancelToken();
        cancel.cancel();

        assertThrows(CancellationException.class, () -> collect(source(game, "MIT"), folder.resolve("cache.json"), cancel));
    }

    @Test
    void progressEndsDone() throws IOException {
        Progress progress = new Progress();
        Path game = gameDirectory();
        SnapshotPipeline.collect("alamodlist/test", environment, source(game, "MIT"), HashCache.inMemory(), false, 2,
                progress, new CancelToken());

        assertEquals(Progress.Stage.DONE, progress.stage());
        assertEquals(progress.filesTotal(), progress.filesDone());
    }

    @Test
    void everyEntryOfTheSnapshotIsOrderedAndUnique() throws IOException {
        SnapshotPipeline.Result result = collect(source(gameDirectory(), "MIT"), folder.resolve("cache.json"), new CancelToken());

        List<String> ids = result.snapshot().mods().stream().map(ModEntry::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertEquals(0, result.skipped());
        assertTrue(ModHashing.DEFAULT_THREADS >= 1 && ModHashing.DEFAULT_THREADS <= 4);
    }
}
