package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.assemble.EnvironmentAssembler;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.ModSource;
import day.alacraft.alamodlist.source.RawMod;
import day.alacraft.alamodlist.source.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The worker the preview screen starts: a result, a cancel and a failure, each read the way the screen reads them. */
class CollectJobTest {

    @TempDir
    Path folder;

    private final EnvironmentInfo environment = EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5");

    private static void waitFor(CollectJob job) throws InterruptedException {
        for (int i = 0; i < 1000 && !job.isDone(); i++) {
            Thread.sleep(10);
        }
        assertTrue(job.isDone(), "the job did not finish in ten seconds");
    }

    @Test
    void aFinishedJobGivesTheSnapshotAndTheCountersEndAtTheEnd() throws Exception {
        Path jar = folder.resolve("a.jar");
        Files.write(jar, new byte[3000]);
        ModSource source = () -> List.of(
                new RawMod("a", "A", "1", ModKind.MOD, null, Side.UNIVERSAL, jar, null, List.of()));

        CollectJob job = CollectJob.start("alamodlist/test", environment, source, folder.resolve("cache.json"), false);
        waitFor(job);

        var result = job.resultOrNull();
        assertNotNull(result);
        assertEquals(1, result.snapshot().mods().size());
        assertFalse(job.wasCancelled());
        assertEquals(1, job.progress().filesDone());
    }

    @Test
    void aSourceThatBreaksEndsTheJobWithoutAResultAndWithoutBeingCalledCancelled() throws Exception {
        ModSource source = () -> {
            throw new IllegalStateException("the loader is gone");
        };

        CollectJob job = CollectJob.start("alamodlist/test", environment, source, folder.resolve("cache.json"), false);
        waitFor(job);

        assertNull(job.resultOrNull());
        assertFalse(job.wasCancelled());
    }

    /**
     * @implements TC-PREVIEW-001-FUN08 - a cancel ends the collection as cancelled, not as broken
     * @covers R-GUI-04
     */
    @Test
    void aCancelEndsTheJobAsCancelled() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ModSource source = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };

        CollectJob job = CollectJob.start("alamodlist/test", environment, source, folder.resolve("cache.json"), false);
        job.cancel();
        release.countDown();
        waitFor(job);

        assertNull(job.resultOrNull());
        assertTrue(job.wasCancelled());
    }

    @Test
    void theAnswerOfAJobStillRunningCannotBeAskedFor() {
        CountDownLatch release = new CountDownLatch(1);
        ModSource source = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        CollectJob job = CollectJob.start("alamodlist/test", environment, source, folder.resolve("cache.json"), false);

        assertThrows(IllegalStateException.class, job::resultOrNull);
        job.cancel();
        release.countDown();
    }
}
