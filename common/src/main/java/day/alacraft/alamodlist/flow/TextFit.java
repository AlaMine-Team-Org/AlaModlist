package day.alacraft.alamodlist.flow;

import java.util.function.ToIntFunction;

/**
 * Shortens a line of text to a width in pixels, ending it with an ellipsis, so a long name or reason is visibly cut
 * instead of silently clipped. The measuring is handed in (the game's font), so the rule is testable without the game.
 */
public final class TextFit {

    /** What marks a cut. */
    public static final String ELLIPSIS = "…";

    private TextFit() {
    }

    /**
     * @param width measures a string in pixels
     * @return {@code text} when it fits; otherwise its longest beginning that, with the ellipsis, still fits; an empty
     *         string when not even the ellipsis fits
     */
    public static String ellipsize(String text, int maxWidth, ToIntFunction<String> width) {
        if (text == null || text.isEmpty() || width.applyAsInt(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        if (width.applyAsInt(ELLIPSIS) > maxWidth) {
            return "";
        }

        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (width.applyAsInt(cut(text, mid) + ELLIPSIS) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        return cut(text, low) + ELLIPSIS;
    }

    /** Whether {@link #ellipsize} would shorten the text. */
    public static boolean isCut(String text, int maxWidth, ToIntFunction<String> width) {
        return text != null && !text.isEmpty() && width.applyAsInt(text) > maxWidth;
    }

    /** The first {@code length} chars, never ending between the two halves of a surrogate pair. */
    private static String cut(String text, int length) {
        int end = length;
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }

        return text.substring(0, end);
    }
}
