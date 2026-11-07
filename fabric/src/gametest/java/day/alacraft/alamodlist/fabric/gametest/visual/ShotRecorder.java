package day.alacraft.alamodlist.fabric.gametest.visual;

import day.alacraft.alamodlist.AlaModlist;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Takes every frame of the L3 lane, checks it and writes down what it was for.
 *
 * <p>Each frame is checked the moment it is taken, and a bad one fails the run with its name:
 * <ul>
 *   <li>the file is a real PNG bigger than a cleared framebuffer;</li>
 *   <li>it is not flat - a solid black frame of 1280x720 still weighs ~2.7 KB, so size alone says nothing;</li>
 *   <li>it carries no missing-texture placeholder (a model, blockstate or texture that does not resolve);</li>
 *   <li>its name belongs to a {@link ShotFamilies family}, which gives it its rules and a caption.</li>
 * </ul>
 * A pixel comparison between two frames ({@link #compare}) is the only check that says the RIGHT thing was
 * drawn, so the manifest records it per frame ({@code applied}, {@code depth}).
 *
 * <p>{@link #finish} writes {@code shots-manifest.json} beside the frames; {@code tools/shot_manifest_check.py}
 * reads it after the run and compares it with the frames the sources promise. The state is static because
 * a client game test is one run in one process.
 */
public final class ShotRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(AlaModlist.MOD_ID + "-gametest");

    /** A real frame compresses to several KiB; a cleared framebuffer to hundreds of bytes. */
    private static final long MIN_FRAME_BYTES = 2 * 1024L;

    public static final String FILE_SIZE = "file-size";
    public static final String NOT_BLANK = "not-blank";
    public static final String NO_MISSING_TEXTURE = "no-missing-texture";
    public static final String PIXEL_DIFF = "pixel-diff";

    private static ClientGameTestContext context;
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static final Set<String> NAMES = new LinkedHashSet<>();
    private static Path directory;

    private ShotRecorder() {
    }

    /** One captured frame as the manifest lists it; {@code applied} grows when the frame is compared. */
    private record Shot(int index, String file, Path path, String name, ShotFamilies.Family family,
                        String menu, String screen, Set<String> applied, long bytes) {
    }

    /** Attaches the recorder to the lane, after proving the frame checks can fail ({@link Frame#selfTest}). */
    public static void begin(ClientGameTestContext ctx) {
        Frame.selfTest();
        context = ctx;
    }

    /** A world or free frame; its family comes from its name. */
    public static Path capture(String name) {
        return captureNamed(name, null);
    }

    /**
     * The frame of a screen that is not a menu, named {@code screen_<last word of the class name, without
     * "Screen">_<state>} (a {@code KnobConfigScreen} in state {@code "open"} is {@code screen_config_open}).
     * Two screens whose names end in the same word give the same frame name, and the duplicate-name check
     * of {@link #captureNamed} fails the run - rename one of them. {@code tools/shot_manifest_check.py}
     * reads the same name from the call, so the class must be written as a literal ({@code X.class}).
     * A menu's screen goes through {@code MenuShots.captureScreen} instead.
     */
    public static Path captureScreen(Class<? extends Screen> screen, String state) {
        return captureNamed("screen_" + screenSlug(screen) + "_" + state, null, screen.getSimpleName());
    }

    /** The middle of a screen frame name: the last upper-case word of the class name, minus "Screen". */
    static String screenSlug(Class<? extends Screen> screen) {
        String name = screen.getSimpleName();
        if (name.endsWith("Screen") && name.length() > "Screen".length()) {
            name = name.substring(0, name.length() - "Screen".length());
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("[SHOT] " + screen + " has no simple name (anonymous class?) - "
                    + "a screen frame is named after its class");
        }
        int start = 0;
        for (int i = name.length() - 1; i > 0; i--) {
            if (Character.isUpperCase(name.charAt(i))) {
                start = i;
                break;
            }
        }
        return name.substring(start).toLowerCase(Locale.ROOT);
    }

    /**
     * Takes a frame under a ready name; {@code menuId} is the id of the menu whose screen it shows, or
     * {@code null}. The one door every capture goes through - {@link #capture}, {@link #captureScreen(Class, String)}
     * and {@code MenuShots} - so the checks and the manifest cannot differ between them.
     */
    static Path captureNamed(String name, String menuId) {
        return captureNamed(name, menuId, null);
    }

    /**
     * The same door with the simple name of the screen class a {@code screen_} frame shows ({@code null} for
     * every other frame); the manifest records it, and {@code tools/shot_manifest_check.py} holds it against
     * the class the sources name in {@code captureScreen(X.class, ...)}.
     */
    static Path captureNamed(String name, String menuId, String screenClass) {
        if (context == null) {
            throw new IllegalStateException("ShotRecorder.begin(context) was never called");
        }
        if (!NAMES.add(name)) {
            throw new AssertionError("[SHOT] duplicate frame name '" + name + "' - the second would replace "
                    + "the first in every report keyed by name");
        }
        ShotFamilies.Family family = ShotFamilies.of(name);
        if (family == null) {
            throw new AssertionError("[SHOT] frame '" + name + "' belongs to no family (known prefixes: "
                    + String.join(", ", ShotFamilies.prefixes()) + ") - add a row to ShotFamilies.TABLE");
        }
        context.runOnClient(mc -> mc.gui.toastManager().clear());
        context.waitTicks(1);
        Path path = context.takeScreenshot(name);
        long bytes = size(name, path);
        Frame frame = Frame.read(path);
        if (frame.isBlank()) {
            throw new AssertionError("[SHOT] '" + name + "' is a flat image (" + frame.distinctColours(Frame.MIN_DISTINCT_COLOURS)
                    + " colours in " + frame.width() + "x" + frame.height() + "): the renderer drew nothing");
        }
        int[] placeholder = frame.findMissingTexture();
        if (placeholder != null) {
            throw new AssertionError("[SHOT] '" + name + "' shows the missing-texture placeholder at x=" + placeholder[0]
                    + " y=" + placeholder[1] + " " + placeholder[2] + "x" + placeholder[3] + " (" + placeholder[4]
                    + " magenta px): a model, blockstate or texture does not resolve. Open " + path.getFileName());
        }
        if (directory == null) {
            directory = path.getParent();
        }
        Set<String> applied = new LinkedHashSet<>(List.of(FILE_SIZE, NOT_BLANK, NO_MISSING_TEXTURE));
        SHOTS.add(new Shot(SHOTS.size(), path.getFileName().toString(), path, name, family, menuId, screenClass, applied, bytes));
        LOG.info("[SHOT] {} ({}) {} bytes -> {}", name, family.group(), bytes, path.getFileName());
        return path;
    }

    /**
     * How many pixels of {@code first} and {@code second} differ, and the record that both frames were
     * compared. A gate on the number is the caller's: "with the object" against "without it".
     */
    public static int compare(String first, String second) {
        Shot a = shot(first);
        Shot b = shot(second);
        a.applied().add(PIXEL_DIFF);
        b.applied().add(PIXEL_DIFF);
        int differing = Frame.read(a.path()).differingPixels(Frame.read(b.path()));
        LOG.info("[SHOT] {} vs {}: {} pixels differ", first, second, differing);
        return differing;
    }

    /** Writes {@code shots-manifest.json}; with no frame it writes nothing and says so. */
    public static void finish() {
        if (SHOTS.isEmpty() || directory == null) {
            LOG.warn("[SHOT] no frames were captured - no manifest written");
            return;
        }
        Path target = directory.resolve("shots-manifest.json");
        try {
            Files.writeString(target, manifest(), StandardCharsets.UTF_8);
            LOG.info("[SHOT] manifest of {} frames -> {}", SHOTS.size(), target.toAbsolutePath());
        } catch (IOException e) {
            throw new AssertionError("[SHOT] could not write " + target, e);
        }
    }

    private static Shot shot(String name) {
        for (Shot shot : SHOTS) {
            if (shot.name().equals(name)) {
                return shot;
            }
        }
        throw new AssertionError("[SHOT] no frame named '" + name + "' was captured - the comparison and the "
                + "capture drifted apart");
    }

    private static long size(String name, Path path) {
        try {
            long bytes = Files.exists(path) ? Files.size(path) : -1;
            if (bytes < MIN_FRAME_BYTES) {
                throw new AssertionError("[SHOT] '" + name + "' is " + (bytes < 0 ? "missing" : bytes + " bytes")
                        + " at " + path + " - not a rendered frame");
            }
            return bytes;
        } catch (IOException e) {
            throw new AssertionError("[SHOT] could not read the size of " + path, e);
        }
    }

    private static String manifest() {
        StringBuilder json = new StringBuilder(2048);
        json.append("{\n  \"schema\": 1,\n  \"run\": {\n");
        json.append("    \"mod\": ").append(quote(AlaModlist.MOD_ID)).append(",\n");
        json.append("    \"modVersion\": ").append(quote(version(AlaModlist.MOD_ID))).append(",\n");
        json.append("    \"minecraftVersion\": ").append(quote(version("minecraft"))).append(",\n");
        json.append("    \"loader\": \"fabric\",\n");
        json.append("    \"shotCount\": ").append(SHOTS.size()).append("\n  },\n  \"shots\": [\n");
        for (int i = 0; i < SHOTS.size(); i++) {
            Shot shot = SHOTS.get(i);
            json.append("    {\"index\": ").append(shot.index())
                    .append(", \"file\": ").append(quote(shot.file()))
                    .append(", \"name\": ").append(quote(shot.name()))
                    .append(", \"family\": ").append(quote(shot.family().prefix()))
                    .append(", \"group\": ").append(quote(shot.family().group()))
                    .append(", \"menu\": ").append(shot.menu() == null ? "null" : quote(shot.menu()))
                    .append(", \"screen\": ").append(shot.screen() == null ? "null" : quote(shot.screen()))
                    .append(", \"rules\": ").append(list(shot.family().rules()))
                    .append(", \"caption\": ").append(quote(shot.family().caption()))
                    .append(", \"applied\": ").append(list(List.copyOf(shot.applied())))
                    .append(", \"depth\": ").append(quote(shot.applied().contains(PIXEL_DIFF) ? "compared" : "smoke"))
                    .append(", \"bytes\": ").append(shot.bytes())
                    .append('}').append(i + 1 < SHOTS.size() ? "," : "").append('\n');
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String list(List<String> values) {
        List<String> quoted = new ArrayList<>();
        values.forEach(value -> quoted.add(quote(value)));
        return "[" + String.join(", ", quoted) + "]";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
