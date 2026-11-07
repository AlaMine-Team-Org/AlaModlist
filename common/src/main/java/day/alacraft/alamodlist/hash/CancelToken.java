package day.alacraft.alamodlist.hash;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A flag a long job reads between blocks of work. Reading a file cannot be interrupted, so the loops look at
 * this instead.
 */
public final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** @throws CancellationException when the job was cancelled */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("cancelled");
        }
    }
}
