package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.hash.CancelToken;
import day.alacraft.alamodlist.hash.HashCache;
import day.alacraft.alamodlist.hash.ModHashing;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.source.ModSource;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The background job that builds the snapshot, with the two handles a screen needs: the counters it draws and the
 * flag that stops it. It runs on a thread of its own and touches no game object - what the game had to be asked
 * (the environment, the loader's mod source) arrives already read.
 *
 * <p>A screen polls {@link #isDone()} from its tick and then reads the answer; there is no callback from the worker
 * into the interface, so nothing can arrive after the screen is gone.
 */
public final class CollectJob {

    private final Progress progress = new Progress();
    private final CancelToken cancel = new CancelToken();
    private final CompletableFuture<SnapshotPipeline.Result> result = new CompletableFuture<>();

    private CollectJob() {
    }

    /**
     * Starts the job.
     *
     * @param source    who sends it, as {@code client/version}
     * @param cacheFile the file of remembered hashes
     */
    public static CollectJob start(String source, EnvironmentInfo environment, ModSource mods, Path cacheFile,
                                   boolean murmur2) {
        CollectJob job = new CollectJob();
        Thread worker = new Thread(() -> job.run(source, environment, mods, cacheFile, murmur2), "AlaModlist collect");
        worker.setDaemon(true);
        worker.start();

        return job;
    }

    private void run(String source, EnvironmentInfo environment, ModSource mods, Path cacheFile, boolean murmur2) {
        try {
            result.complete(SnapshotPipeline.collect(source, environment, mods, HashCache.open(cacheFile), murmur2,
                    ModHashing.DEFAULT_THREADS, progress, cancel));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    public Progress progress() {
        return progress;
    }

    /** Tells the worker to stop; it does within a few blocks of reading. */
    public void cancel() {
        cancel.cancel();
    }

    public boolean isDone() {
        return result.isDone();
    }

    /** Whether the job ended because it was cancelled (and not because something broke). */
    public boolean wasCancelled() {
        return result.isCompletedExceptionally() && cause() instanceof CancellationException;
    }

    /**
     * The answer of a finished job.
     *
     * @return the result, or {@code null} when the job was cancelled or failed (a failure is logged here)
     * @throws IllegalStateException when the job is still running
     */
    public SnapshotPipeline.Result resultOrNull() {
        if (!result.isDone()) {
            throw new IllegalStateException("The job is not done.");
        }
        if (!result.isCompletedExceptionally()) {
            return result.join();
        }
        Throwable cause = cause();
        if (!(cause instanceof CancellationException)) {
            AlaModlist.LOGGER.warn("Could not collect the mod list", cause);
        }

        return null;
    }

    private Throwable cause() {
        try {
            result.join();
            return null;
        } catch (CompletionException e) {
            return e.getCause();
        } catch (CancellationException e) {
            return e;
        }
    }
}
