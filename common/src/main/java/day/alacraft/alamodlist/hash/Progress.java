package day.alacraft.alamodlist.hash;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a screen can show while the snapshot is being built. Plain counters the worker threads move and the game
 * thread reads each frame; there are no callbacks from the workers into the interface.
 */
public final class Progress {

    /** The stage the job is in. */
    public enum Stage {
        LISTING, PLANNING, HASHING, ASSEMBLING, DONE
    }

    private volatile Stage stage = Stage.LISTING;
    private volatile int filesTotal;
    private volatile long bytesTotal;
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicLong bytesDone = new AtomicLong();

    public Stage stage() {
        return stage;
    }

    public void stage(Stage next) {
        this.stage = next;
    }

    /** Sets the amount of work of the hashing stage. */
    public void total(int files, long bytes) {
        this.filesTotal = files;
        this.bytesTotal = bytes;
    }

    public int filesTotal() {
        return filesTotal;
    }

    public int filesDone() {
        return filesDone.get();
    }

    public long bytesTotal() {
        return bytesTotal;
    }

    public long bytesDone() {
        return bytesDone.get();
    }

    void fileDone() {
        filesDone.incrementAndGet();
    }

    void bytes(long count) {
        bytesDone.addAndGet(count);
    }
}
