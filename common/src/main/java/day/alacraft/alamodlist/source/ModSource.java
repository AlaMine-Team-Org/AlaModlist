package day.alacraft.alamodlist.source;

import java.util.List;

/**
 * The port that lists what is installed. Each loader has an adapter; everything after it is plain Java.
 *
 * <p>Safe to call from a worker thread: the loaders keep their module lists as ordinary data once the game has
 * started.
 */
public interface ModSource {

    /** Every component, in no particular order. */
    List<RawMod> mods();
}
