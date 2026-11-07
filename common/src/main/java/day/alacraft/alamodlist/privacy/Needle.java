package day.alacraft.alamodlist.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One value the mod must not send, and how to recognise it in a text.
 *
 * <p>Matching is by substring, not by pattern. A pattern for "an IPv4 address" flags a version such as
 * {@code 26.2.0.67} that the list deliberately keeps, and a check that cries wolf gets switched off. Short values
 * (a player called {@code Al}) match only as a whole word, so they do not hide inside unrelated words.
 *
 * @param label    what the value is; names the field to the player, never the value
 * @param text     the value
 * @param fold     how texts are compared: as they are, ignoring case, or ignoring case and the kind of path separator
 * @param left     what may stand before a match
 * @param right    what may stand after a match
 * @param fragment true for a part of a value (the first group of a UUID, three octets of an address): a partial
 *                 leak is still a leak, and it is told apart from a whole one
 * @param scope    the normalized path of the only field this needle applies to, or null for every field
 */
public record Needle(String label, String text, Fold fold, Boundary left, Boundary right, boolean fragment,
                     String scope) {

    /** How two texts are compared. */
    public enum Fold {
        /** Exactly as written (secrets). */
        NONE,
        /** Ignoring case. */
        CASE,
        /** Ignoring case, and {@code \} equals {@code /} (paths, in any spelling). */
        PATH
    }

    /** What may stand next to a match. */
    public enum Boundary {
        /** Anything. */
        NONE,
        /** Not a letter or a digit: the match is a whole word. */
        WORD,
        /** Not a digit: the match is a whole number. */
        DIGIT
    }

    public Needle {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a needle for '" + label + "' is empty");
        }
    }

    /** Never prints the value: a needle that ends up in a log or a message would be the leak itself. */
    @Override
    public String toString() {
        return "Needle[" + label + (fragment ? ", fragment" : "") + "]";
    }

    /** Whether this needle applies to the field with this normalized path. */
    public boolean appliesTo(String normalizedPath) {
        return scope == null || scope.equals(normalizedPath);
    }

    /** Every non-overlapping occurrence in {@code haystack}, as {@code [start, end)} pairs. */
    public List<int[]> find(String haystack) {
        List<int[]> hits = new ArrayList<>();
        if (haystack == null || haystack.length() < text.length()) {
            return hits;
        }
        String folded = fold(fold, haystack);
        String wanted = fold(fold, text);
        int from = 0;
        while (from <= folded.length() - wanted.length()) {
            int at = folded.indexOf(wanted, from);
            if (at < 0) {
                break;
            }
            int end = at + wanted.length();
            if (boundaryOk(left, haystack, at, true) && boundaryOk(right, haystack, end, false)) {
                hits.add(new int[] {at, end});
                from = end;
            } else {
                from = at + 1;
            }
        }
        return hits;
    }

    /** The text in comparison form; one char in, one char out, so indexes carry over to the original. */
    static String fold(Fold mode, String text) {
        if (mode == Fold.NONE) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (mode == Fold.PATH && c == '\\') {
                chars[i] = '/';
            } else {
                chars[i] = Character.toLowerCase(c);
            }
        }
        return new String(chars);
    }

    private static boolean boundaryOk(Boundary boundary, String text, int index, boolean before) {
        if (boundary == Boundary.NONE) {
            return true;
        }
        if (before ? index == 0 : index >= text.length()) {
            return true;
        }
        int neighbour = before ? text.codePointBefore(index) : text.codePointAt(index);
        return boundary == Boundary.WORD ? !Character.isLetterOrDigit(neighbour) : !Character.isDigit(neighbour);
    }
}
