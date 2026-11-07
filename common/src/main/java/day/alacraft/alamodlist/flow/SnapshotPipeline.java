package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.assemble.ModAssembler;
import day.alacraft.alamodlist.hash.CancelToken;
import day.alacraft.alamodlist.hash.HashCache;
import day.alacraft.alamodlist.hash.ModHashing;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.source.ModSource;

import java.util.List;

/**
 * The background part of building a snapshot, for the categories that exist so far: the environment and the mod
 * list. It runs on a worker thread and touches no game object: the environment arrives already read, the mods
 * come through the {@link ModSource} port.
 */
public final class SnapshotPipeline {

    private SnapshotPipeline() {
    }

    /**
     * @param snapshot the request body, not yet judged by the privacy gate
     * @param hashing  what the hashing stage did
     * @param skipped  components left out because their id cannot be sent
     */
    public record Result(Snapshot snapshot, ModHashing.Stats hashing, int skipped) {
    }

    /**
     * @param source who sends it, as {@code client/version}
     * @throws java.util.concurrent.CancellationException when {@code cancel} was set
     */
    public static Result collect(String source, EnvironmentInfo environment, ModSource mods, HashCache cache,
                                 boolean murmur2, int threads, Progress progress, CancelToken cancel) {
        progress.stage(Progress.Stage.LISTING);
        var raw = mods.mods();
        cancel.throwIfCancelled();

        ModHashing.Outcome hashed = ModHashing.run(raw, cache, murmur2, threads, progress, cancel);

        progress.stage(Progress.Stage.ASSEMBLING);
        ModAssembler.Assembled assembled = ModAssembler.assemble(raw, hashed.facts());
        Snapshot snapshot = new Snapshot(source, null, null, null, environment, assembled.entries(),
                List.of(), List.of(), List.of(), null, null, List.of());
        progress.stage(Progress.Stage.DONE);
        return new Result(snapshot, hashed.stats(), assembled.skipped());
    }
}
