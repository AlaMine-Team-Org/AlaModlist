package day.alacraft.alamodlist.fabric.gametest.visual;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * A decoded frame as plain ARGB ints, and the checks every frame of the L3 lane goes through: is it
 * blank, does it carry Minecraft's missing-texture placeholder, how much does it differ from another
 * frame. Pure arithmetic on the pixels (the file is read once, in {@link #read}), so the checks prove they
 * can fail on synthetic frames before the lane trusts them ({@link #selfTest}).
 *
 * @param width  frame width in pixels
 * @param height frame height in pixels
 * @param argb   row-major pixels, {@code width * height} entries, 0xAARRGGBB
 */
public record Frame(int width, int height, int[] argb) {

    /** Fewer distinct colours than this is a flat image: a blank framebuffer, not a rendered frame. */
    static final int MIN_DISTINCT_COLOURS = 16;

    /** A placeholder smaller than this many magenta pixels is ignored (anti-aliasing, a pink flower). */
    static final int MIN_PLACEHOLDER_PIXELS = 400;

    /**
     * A channel difference up to this is drift, not a change: the sky and the light of a still world shift by a
     * few levels between frames seconds apart (measured on 1280x720: 111 080 pixels differ by more than 8,
     * 722 by more than 16, 16 by more than 32), while a block in the frame differs from the floor behind it by
     * far more.
     */
    static final int CHANNEL_TOLERANCE = 32;

    public Frame {
        if (width <= 0 || height <= 0 || argb.length != width * height) {
            throw new IllegalArgumentException("a " + width + "x" + height + " frame needs " + width * height
                    + " pixels, got " + argb.length);
        }
    }

    /** Decodes a PNG; an unreadable file is a broken capture, never a pass. */
    public static Frame read(Path file) {
        try {
            BufferedImage image = Files.exists(file) ? ImageIO.read(file.toFile()) : null;
            if (image == null) {
                throw new AssertionError("[SHOT] " + file + " is missing or not a readable PNG");
            }
            int w = image.getWidth();
            int h = image.getHeight();
            return new Frame(w, h, image.getRGB(0, 0, w, h, null, 0, w));
        } catch (IOException e) {
            throw new AssertionError("[SHOT] could not read " + file, e);
        }
    }

    /** How many distinct colours the frame has, counting up to {@code limit}. */
    public int distinctColours(int limit) {
        Set<Integer> seen = new HashSet<>();
        for (int pixel : argb) {
            if (seen.add(pixel) && seen.size() >= limit) {
                break;
            }
        }
        return seen.size();
    }

    /** {@code true} when the frame is a flat image. */
    public boolean isBlank() {
        return distinctColours(MIN_DISTINCT_COLOURS) < MIN_DISTINCT_COLOURS;
    }

    /**
     * The largest cluster of the placeholder's magenta with the black checker squares next to it, as
     * {@code {x, y, width, height, magentaPixels}}, or {@code null}. Magenta alone is not enough: the black
     * squares are what separates the placeholder from a pink block.
     */
    public int[] findMissingTexture() {
        boolean[] visited = new boolean[argb.length];
        int[] best = null;
        for (int start = 0; start < argb.length; start++) {
            if (visited[start] || !isPlaceholderMagenta(argb[start])) {
                continue;
            }
            int[] cluster = grow(start, visited);
            if (cluster[4] >= MIN_PLACEHOLDER_PIXELS && darkAround(cluster) && (best == null || cluster[4] > best[4])) {
                best = cluster;
            }
        }
        return best;
    }

    /** How many pixels differ from {@code other} by more than {@link #CHANNEL_TOLERANCE} in any channel. */
    public int differingPixels(Frame other) {
        if (other.width != width || other.height != height) {
            throw new AssertionError("[SHOT] frames of different size: " + width + "x" + height + " and "
                    + other.width + "x" + other.height);
        }
        int differing = 0;
        for (int i = 0; i < argb.length; i++) {
            int a = argb[i];
            int b = other.argb[i];
            for (int shift = 0; shift <= 16; shift += 8) {
                if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > CHANNEL_TOLERANCE) {
                    differing++;
                    break;
                }
            }
        }
        return differing;
    }

    /**
     * Proves the checks can fail before the lane relies on them: a flat frame must be blank, a frame with
     * the placeholder must be found, a busy frame without it must pass, and two different frames must
     * differ. A check that cannot fail is indistinguishable from a missing one.
     */
    public static void selfTest() {
        Frame flat = filled(96, 96, 0xFF202020);
        Frame busy = noise(96, 96, 42);
        Frame placeholder = withChecker(busy, 16, 16, 64);
        require(flat.isBlank(), "a flat frame is not reported blank");
        require(!busy.isBlank(), "a busy frame is reported blank");
        require(placeholder.findMissingTexture() != null, "the missing-texture placeholder is not found");
        require(busy.findMissingTexture() == null, "a frame without the placeholder is reported to have it");
        require(busy.differingPixels(placeholder) > 0, "two different frames are reported equal");
        require(busy.differingPixels(busy) == 0, "a frame differs from itself");
        require(busy.differingPixels(brighter(busy, 12)) == 0, "a brightness drift is counted as a change");
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("[SHOT] frame-check self-test failed: " + what
                    + " - every frame verdict of this run would be meaningless");
        }
    }

    static Frame filled(int w, int h, int colour) {
        int[] pixels = new int[w * h];
        Arrays.fill(pixels, colour);
        return new Frame(w, h, pixels);
    }

    /** Greyish noise from a fixed seed: many colours, no magenta. */
    static Frame noise(int w, int h, long seed) {
        java.util.Random random = new java.util.Random(seed);
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int base = 60 + random.nextInt(120);
            pixels[i] = 0xFF000000 | (base << 16) | ((base + random.nextInt(20)) << 8) | (base - random.nextInt(20));
        }
        return new Frame(w, h, pixels);
    }

    /** {@code frame} with every channel raised by {@code levels}: the drift between two frames of a still world. */
    static Frame brighter(Frame frame, int levels) {
        int[] pixels = frame.argb.clone();
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = Math.min(255, ((p >> 16) & 0xFF) + levels);
            int g = Math.min(255, ((p >> 8) & 0xFF) + levels);
            int b = Math.min(255, (p & 0xFF) + levels);
            pixels[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        return new Frame(frame.width, frame.height, pixels);
    }

    /** {@code frame} with vanilla's 2x2 magenta/black checker of {@code size} painted at (x, y). */
    static Frame withChecker(Frame frame, int x, int y, int size) {
        int[] pixels = frame.argb.clone();
        int half = size / 2;
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                boolean magenta = (dx < half) == (dy < half);
                pixels[(y + dy) * frame.width + x + dx] = magenta ? 0xFFF800F8 : 0xFF000000;
            }
        }
        return new Frame(frame.width, frame.height, pixels);
    }

    private static boolean isPlaceholderMagenta(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        if (r < 40 || b < 40) {
            return false;
        }
        int lower = Math.min(r, b);
        return Math.abs(r - b) <= Math.max(12, (int) (lower * 0.08)) && g <= lower * 0.35;
    }

    /** A 4-neighbour flood fill of magenta from {@code start}: {x, y, width, height, pixels}. */
    private int[] grow(int start, boolean[] visited) {
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited[start] = true;
        int count = 0;
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int y = index / width;
            count++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            int[][] neighbours = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
            for (int[] n : neighbours) {
                if (n[0] < 0 || n[1] < 0 || n[0] >= width || n[1] >= height) {
                    continue;
                }
                int next = n[1] * width + n[0];
                if (!visited[next] && isPlaceholderMagenta(argb[next])) {
                    visited[next] = true;
                    queue.add(next);
                }
            }
        }
        return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1, count};
    }

    /**
     * Whether the margin around a magenta cluster is at least 8 % near-black. The checker's magenta squares
     * touch only at corners, so the fill finds ONE square; its black neighbours are around it, not inside.
     */
    private boolean darkAround(int[] cluster) {
        int margin = Math.max(2, Math.min(cluster[2], cluster[3]) / 2);
        int x0 = Math.max(0, cluster[0] - margin);
        int y0 = Math.max(0, cluster[1] - margin);
        int x1 = Math.min(width, cluster[0] + cluster[2] + margin);
        int y1 = Math.min(height, cluster[1] + cluster[3] + margin);
        int dark = 0;
        int total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = argb[y * width + x];
                double luminance = 0.2126 * ((p >> 16) & 0xFF) + 0.7152 * ((p >> 8) & 0xFF) + 0.0722 * (p & 0xFF);
                dark += luminance < 48.0 ? 1 : 0;
                total++;
            }
        }
        return total > 0 && dark >= total * 0.08;
    }
}
