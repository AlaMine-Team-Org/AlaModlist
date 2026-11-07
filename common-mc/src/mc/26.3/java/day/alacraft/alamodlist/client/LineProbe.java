package day.alacraft.alamodlist.client;

import net.minecraft.client.input.KeyEvent;

/**
 * PROBE - proves that the per-line source directories work: this class exists once per Minecraft line
 * (the build adds the directory of the selected line only) and its body touches an API that really differs
 * between the lines. Nothing in the mod calls it; delete it, with {@code LineProbeTest}, when the mod has a
 * twin of its own, or keep it as the smallest example of one (docs/MC-26.2-API.md, "Lines: twins").
 *
 * <p>THIS COPY IS FOR 26.3; its twin for 26.2 lives in {@code src/mc/26.2/java} with the same public API -
 * change both together ({@code tools/mc_twins_check.py}).
 *
 * <p>What differs (javap, both jars): the shortcut key of a {@link KeyEvent} - the key by the LETTER on its
 * cap, the way vanilla reads its own Ctrl+C and Ctrl+V. On 26.2 that is {@code key()} (a layout-aware GLFW
 * code; {@code isCopy} compares {@code input()} with 67). 26.3 moved input from GLFW to SDL3: {@code key()}
 * became the physical key and the letter moved to {@code shortcutKey()}, which 26.2 does not have. Compare
 * the result with an {@code InputConstants.KEY_*} constant, never a number: the constants were renumbered
 * (Z is 90 on 26.2 and 29 on 26.3) and the compiler copies their values into our bytecode.
 */
public final class LineProbe {

    /** The Minecraft line this copy is compiled for; {@code LineProbeTest} compares it with the line the build selected. */
    public static final String LINE = "26.3";

    private LineProbe() {
    }

    /** The key of {@code event} by the letter on its cap. */
    public static int shortcutKey(KeyEvent event) {
        return event.shortcutKey();
    }
}
