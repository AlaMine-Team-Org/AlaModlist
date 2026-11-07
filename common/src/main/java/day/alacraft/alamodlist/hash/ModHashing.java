package day.alacraft.alamodlist.hash;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.model.Hashes;
import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.RawMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The hashing stage of a snapshot: decides which files are hashed, serves what the cache remembers and hashes the
 * rest on a small pool, biggest files first so the pool finishes together.
 *
 * <p>Which files are hashed is a rule of this class and not of the loader adapters: a component is hashed only if
 * it is a real file of its own - not built in and not nested inside another mod's jar. A nested jar is not what a
 * site indexes (it indexes the uploaded file), so hashing one would produce a hash nothing can match.
 */
public final class ModHashing {

    /** Worker threads for the pool: at most four, a quarter of the cores at least one. */
    public static final int DEFAULT_THREADS = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    /**
     * How long the stage may read before it gives up on the rest. A soft limit: files already read are kept, the
     * files not read in time go out without hashes, and the stage still ends normally. Without it a slow disk or a
     * huge modpack would hold the preview screen for as long as the reading takes.
     */
    public static final Duration SOFT_LIMIT = Duration.ofSeconds(120);

    /** How often the waiting loop looks at the player's cancel flag and at the clock. */
    private static final long POLL_MILLIS = 20;

    private ModHashing() {
    }

    /**
     * What is known about one file after the stage.
     *
     * @param hashes the hashes
     * @param size   the size in bytes
     */
    public record FileFacts(Hashes hashes, long size) {
    }

    /**
     * @param files       distinct files that were to be hashed
     * @param cacheHits   of them, served from the cache
     * @param failed      of them, unreadable (no hashes; the snapshot goes on without them)
     * @param bytesHashed bytes actually read from disk
     * @param nanos       how long the stage took
     * @param timedOut    of them, left unread because the soft limit ran out (no hashes either)
     */
    public record Stats(int files, int cacheHits, int failed, long bytesHashed, long nanos, int timedOut) {
    }

    /** The result of the stage, by {@link #keyOf key} of the file. */
    public record Outcome(Map<Path, FileFacts> facts, Stats stats) {
    }

    /** Whether a component is a file this stage reads. */
    public static boolean hashable(RawMod mod) {
        return mod.kind() != ModKind.BUILTIN
                && !mod.isNested()
                && mod.file() != null
                && Files.isRegularFile(mod.file());
    }

    /** How a file is named in {@link Outcome#facts()}. */
    public static Path keyOf(Path file) {
        return file.toAbsolutePath().normalize();
    }

    /**
     * Hashes what is to be hashed.
     *
     * @param murmur2 also compute the CurseForge fingerprint
     * @param threads size of the pool
     * @throws CancellationException when the token was cancelled or the calling thread was interrupted
     */
    public static Outcome run(List<RawMod> mods, HashCache cache, boolean murmur2, int threads, Progress progress,
                              CancelToken cancel) {
        return run(mods, cache, murmur2, threads, progress, cancel, FileHasher::hash);
    }

    /** Reads one file; {@link FileHasher#hash(Path, boolean, Progress, CancelToken)} outside of tests. */
    @FunctionalInterface
    interface Hasher {
        FileHasher.Digest hash(Path file, boolean murmur2, Progress progress, CancelToken cancel) throws IOException;
    }

    static Outcome run(List<RawMod> mods, HashCache cache, boolean murmur2, int threads, Progress progress,
                       CancelToken cancel, Hasher hasher) {
        return run(mods, cache, murmur2, threads, progress, cancel, hasher, SOFT_LIMIT);
    }

    static Outcome run(List<RawMod> mods, HashCache cache, boolean murmur2, int threads, Progress progress,
                       CancelToken cancel, Hasher hasher, Duration softLimit) {
        long started = System.nanoTime();
        progress.stage(Progress.Stage.PLANNING);

        // Each distinct file once, however many components live in it. The value is {size, mtime}.
        Map<Path, long[]> stat = new LinkedHashMap<>();
        for (RawMod mod : mods) {
            if (hashable(mod)) {
                stat.computeIfAbsent(keyOf(mod.file()), key -> new long[2]);
            }
        }

        Map<Path, FileFacts> facts = new LinkedHashMap<>();
        List<Path> misses = new ArrayList<>();
        int hits = 0;
        int failed = 0;
        long bytesTotal = 0;
        for (Path key : stat.keySet()) {
            cancel.throwIfCancelled();
            long size;
            long mtime;
            try {
                size = Files.size(key);
                mtime = Files.getLastModifiedTime(key).toMillis();
            } catch (IOException e) {
                failed++;
                continue;
            }
            stat.put(key, new long[] {size, mtime});
            var cached = cache.lookup(key, size, mtime, murmur2);
            if (cached.isPresent()) {
                facts.put(key, new FileFacts(new Hashes(cached.get().sha1(), cached.get().murmur2()), size));
                hits++;
            } else {
                misses.add(key);
                bytesTotal += size;
            }
        }
        misses.sort(Comparator.comparingLong((Path path) -> stat.get(path)[0]).reversed());

        progress.total(stat.size(), bytesTotal);
        for (int i = 0; i < hits + failed; i++) {
            progress.fileDone();
        }
        progress.stage(Progress.Stage.HASHING);

        long bytesRead = 0;
        int timedOut = 0;
        if (!misses.isEmpty()) {
            Hashed hashed = hashAll(misses, murmur2, threads, progress, cancel, hasher, softLimit);
            Map<Path, FileHasher.Digest> done = hashed.digests();
            for (Path key : misses) {
                FileHasher.Digest digest = done.get(key);
                if (digest == null) {
                    if (hashed.limited().contains(key)) {
                        timedOut++;
                    } else {
                        failed++;
                    }
                    continue;
                }
                long[] seen = stat.get(key);
                cache.put(key, seen[0], seen[1], new HashCache.Entry(digest.sha1(), digest.murmur2()));
                facts.put(key, new FileFacts(new Hashes(digest.sha1(), digest.murmur2()), digest.size()));
                bytesRead += digest.size();
            }
        }
        cache.save();
        return new Outcome(facts, new Stats(stat.size(), hits, failed, bytesRead, System.nanoTime() - started, timedOut));
    }

    /** What {@link #hashAll} found: the files read, and the files the soft limit cut off (absent from the first). */
    private record Hashed(Map<Path, FileHasher.Digest> digests, Set<Path> limited) {
    }

    /**
     * The files that could be read; a file that could not is simply absent from the result.
     *
     * <p>The readers watch a token of their own, {@code stage}, and this loop sets it when the player cancels or the
     * soft limit runs out: the loop is the one place that knows both, and a reader needs to know only "stop".
     * Cancelling throws; running out of time does not - the files read so far are kept.
     */
    private static Hashed hashAll(List<Path> files, boolean murmur2, int threads, Progress progress,
                                  CancelToken cancel, Hasher hasher, Duration softLimit) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), new Workers());
        CancelToken stage = new CancelToken();
        long deadline = System.nanoTime() + softLimit.toNanos();
        boolean expired = false;
        try {
            Map<Path, Future<FileHasher.Digest>> futures = new LinkedHashMap<>();
            for (Path file : files) {
                futures.put(file, pool.submit(() -> {
                    FileHasher.Digest digest = hasher.hash(file, murmur2, progress, stage);
                    progress.fileDone();
                    return digest;
                }));
            }
            Map<Path, FileHasher.Digest> done = new LinkedHashMap<>();
            Set<Path> limited = new LinkedHashSet<>();
            for (Map.Entry<Path, Future<FileHasher.Digest>> item : futures.entrySet()) {
                while (true) {
                    if (cancel.isCancelled()) {
                        stage.cancel();
                        throw new CancellationException("cancelled");
                    }
                    if (!expired && System.nanoTime() - deadline >= 0) {
                        expired = true;
                        stage.cancel();
                    }
                    try {
                        done.put(item.getKey(), item.getValue().get(POLL_MILLIS, TimeUnit.MILLISECONDS));
                        break;
                    } catch (TimeoutException e) {
                        // Not done yet: look at the flag and the clock again.
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof CancellationException cancelled) {
                            if (expired && !cancel.isCancelled()) {
                                limited.add(item.getKey());
                                progress.fileDone();
                                break;
                            }
                            throw cancelled;
                        }
                        // One unreadable jar does not stop the snapshot; it goes out without hashes.
                        progress.fileDone();
                        AlaModlist.LOGGER.debug("Could not hash a file: {}", e.getCause().getClass().getSimpleName());
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        cancel.cancel();
                        stage.cancel();
                        throw new CancellationException("interrupted");
                    }
                }
            }
            return new Hashed(done, limited);
        } finally {
            // Normally everything is done by now; after a cancellation this drops the tasks still queued.
            pool.shutdownNow();
        }
    }

    /** Daemon workers named like the mod: they must never keep the game from closing. */
    private static final class Workers implements ThreadFactory {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "AlaModlist hash-" + count.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
