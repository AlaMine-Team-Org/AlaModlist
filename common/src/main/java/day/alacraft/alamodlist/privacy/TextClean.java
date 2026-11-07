package day.alacraft.alamodlist.privacy;

/**
 * Cleans text that is about to leave the game.
 *
 * <p>Two kinds of field exist in the request, and they are treated differently on purpose:
 * <ul>
 *   <li><b>Free text</b> (names, titles, versions, replies): {@link #line} and {@link #multiline} remove what
 *       must not travel and cut what is too long. Nothing is lost that a reader would miss.</li>
 *   <li><b>Identifiers</b> (ids, file names, hashes): {@link #requireIdentifier} does not change the value.
 *       Cutting an id changes what it names, so a value that would need cleaning is refused instead.</li>
 * </ul>
 *
 * <p>What is removed from free text: the section sign with its formatting code, control characters, invisible
 * format characters (direction overrides, zero-width marks, byte order marks, tag characters), private-use
 * characters and unpaired surrogates. Every kind of space becomes a plain space, runs of spaces collapse and the
 * text is trimmed. The same rules the service applies on its side; doing it here as well means the preview shows
 * what will be stored.
 */
public final class TextClean {

    /** The section sign, U+00A7: what Minecraft reads as the start of a formatting code. Given as a number so the source stays ASCII. */
    private static final int FORMATTING_SIGN = 0xA7;

    private static final int LINE_SEPARATOR = 0x2028;
    private static final int PARAGRAPH_SEPARATOR = 0x2029;
    private static final int NEXT_LINE = 0x85;

    private TextClean() {
    }

    /** Single-line free text: line breaks become spaces. At most {@code maxChars} code points. */
    public static String line(int maxChars, String text) {
        return clean(maxChars, text, false);
    }

    /** Multi-line free text (the note, a command reply): line breaks are kept as {@code \n}. */
    public static String multiline(int maxChars, String text) {
        return clean(maxChars, text, true);
    }

    /** Whether {@link #line} would leave the text as it is (and it is not empty). */
    public static boolean isClean(String text) {
        return text != null && !text.isEmpty() && line(Integer.MAX_VALUE, text).equals(text);
    }

    /**
     * Checks an identifier and returns it unchanged. An identifier is not cut and not repaired: it must be
     * non-empty, no longer than {@code maxChars} code points and already clean.
     *
     * @throws IllegalArgumentException with {@code where} in the message; the value is never quoted, because it
     *                                  may be exactly the thing the privacy gate exists to keep out of messages
     */
    public static String requireIdentifier(String where, int maxChars, String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(where + " is empty");
        }
        if (text.codePointCount(0, text.length()) > maxChars) {
            throw new IllegalArgumentException(where + " is longer than " + maxChars);
        }
        if (!isClean(text)) {
            throw new IllegalArgumentException(where + " contains characters that are not allowed in an identifier");
        }
        return text;
    }

    /** A bare file name: an identifier that holds no directory separator. */
    public static String requireFileName(String where, int maxChars, String text) {
        requireIdentifier(where, maxChars, text);
        if (text.indexOf('/') >= 0 || text.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(where + " is a path, not a file name");
        }
        return text;
    }

    private static String clean(int maxChars, String text, boolean keepBreaks) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), 256));
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            int cp = points[i];
            if (cp == FORMATTING_SIGN) {
                // A formatting code is the sign and the one letter or digit after it: they go together; a lone sign goes alone.
                if (i + 1 < points.length && isFormattingCode(points[i + 1])) {
                    i++;
                }
                continue;
            }
            if (cp == '\r') {
                if (i + 1 < points.length && points[i + 1] == '\n') {
                    i++;
                }
                out.append(keepBreaks ? '\n' : ' ');
                continue;
            }
            if (cp == '\n' || cp == LINE_SEPARATOR || cp == PARAGRAPH_SEPARATOR || cp == NEXT_LINE) {
                out.append(keepBreaks ? '\n' : ' ');
                continue;
            }
            if (cp == '\t' || cp == 0x0B || cp == '\f') {
                out.append(' ');
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.SPACE_SEPARATOR) {
                out.append(' ');
            } else if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE) {
                continue;
            } else {
                out.appendCodePoint(cp);
            }
        }
        String tidy = keepBreaks ? tidyLines(out) : collapseSpaces(out).strip();
        return cut(tidy, maxChars);
    }

    private static boolean isFormattingCode(int cp) {
        return (cp >= '0' && cp <= '9') || (cp >= 'a' && cp <= 'f') || (cp >= 'A' && cp <= 'F')
                || (cp >= 'k' && cp <= 'o') || (cp >= 'K' && cp <= 'O') || cp == 'r' || cp == 'R'
                || cp == 'x' || cp == 'X';
    }

    private static String collapseSpaces(CharSequence text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                if (!space) {
                    out.append(c);
                }
                space = true;
            } else {
                out.append(c);
                space = false;
            }
        }
        return out.toString();
    }

    /** Collapses spaces inside every line, trims every line, drops blank lines at both ends and runs of 3+ breaks. */
    private static String tidyLines(CharSequence text) {
        String[] lines = text.toString().split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int blank = 0;
        for (String raw : lines) {
            String line = collapseSpaces(raw).strip();
            if (line.isEmpty()) {
                blank++;
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
                if (blank > 0) {
                    out.append('\n');
                }
            }
            blank = 0;
            out.append(line);
        }
        return out.toString();
    }

    /** Cuts to {@code maxChars} code points - never through a surrogate pair - and drops a trailing space. */
    private static String cut(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end).stripTrailing();
    }
}
