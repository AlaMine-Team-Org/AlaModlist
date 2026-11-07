package day.alacraft.alamodlist.fabric.gametest.visual;

import java.util.ArrayList;
import java.util.List;

/**
 * Which family a frame belongs to, decided by the prefix of its name. A family gives the frame its group,
 * the {@code R-*} rules of docs/testing/RULES.md it serves and the one sentence a reviewer reads under it.
 *
 * <p>A frame of no family fails the run ({@link ShotRecorder}): a frame with no rule is a picture -
 * captured, archived and looked at by nobody. A new kind of frame adds a row here.
 */
public final class ShotFamilies {

    /** One family: frames whose name starts with {@code prefix}. */
    public record Family(String prefix, String group, List<String> rules, String caption) {
    }

    /** Longest prefix wins, so a narrower family can sit inside a wider one. */
    static final List<Family> TABLE = List.of(
            new Family("gui_", "gui", List.of("R-GUI-02", "R-VIS-01"),
                    "the screen opens over the world, with its title, slots and contents drawn"),
            new Family("screen_", "screen", List.of("R-VIS-01"),
                    "a screen that is not a menu opens over the world and is drawn with its widgets and texts"),
            new Family("world_", "world", List.of("R-VIS-01", "R-VIS-02"),
                    "the block stands in the world with its own textures, lit, no placeholder"));

    private ShotFamilies() {
    }

    /** The family of a frame name, or {@code null}. */
    public static Family of(String name) {
        Family best = null;
        for (Family family : TABLE) {
            if (name.startsWith(family.prefix())
                    && (best == null || family.prefix().length() > best.prefix().length())) {
                best = family;
            }
        }
        return best;
    }

    /** The prefixes, for a failure message. */
    public static List<String> prefixes() {
        List<String> prefixes = new ArrayList<>();
        TABLE.forEach(family -> prefixes.add(family.prefix()));
        return prefixes;
    }
}
