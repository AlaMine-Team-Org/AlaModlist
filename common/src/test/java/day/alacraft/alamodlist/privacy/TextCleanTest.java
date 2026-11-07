package day.alacraft.alamodlist.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextCleanTest {

    /** A character by its code point: the test source stays ASCII, and the odd characters show up as numbers. */
    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static final String SIGN = cp(0xA7);
    private static final String EMOJI = cp(0x1F600);

    @Test
    void formattingCodesAreRemovedWithTheirSign() {
        assertEquals("Fabric", TextClean.line(64, SIGN + "aFab" + SIGN + "lric"));
        assertEquals("a b", TextClean.line(64, "a " + SIGN + " b"), "a lone sign goes alone");
        assertEquals("zz", TextClean.line(64, SIGN + "rzz" + SIGN));
        assertEquals("Zq", TextClean.line(64, SIGN + "Zq"), "an unknown code letter stays: only real codes go with the sign");
    }

    @Test
    void invisibleAndControlCharactersAreRemoved() {
        assertEquals("ab", TextClean.line(64, "a" + cp(0x202E) + "b"), "a direction override");
        assertEquals("ab", TextClean.line(64, "a" + cp(0x200D) + "b"), "a zero-width joiner");
        assertEquals("ab", TextClean.line(64, "a" + cp(0xFEFF) + "b"), "a byte order mark");
        assertEquals("ab", TextClean.line(64, "a" + cp(0xE0041) + "b"), "a tag character");
        assertEquals("ab", TextClean.line(64, "a" + cp(0xE000) + "b"), "a private-use character");
        assertEquals("ab", TextClean.line(64, "a" + cp(0x07) + "b"), "a bell");
        assertEquals("ab", TextClean.line(64, "a" + (char) 0xDC00 + "b"), "an unpaired surrogate");
        assertEquals("a b", TextClean.line(64, "a\tb"), "a tab is a space, not a gap");
    }

    @Test
    void spacesAreOneKindAndTrimmed() {
        assertEquals("a b c", TextClean.line(64, "  a" + cp(0xA0) + cp(0x2003) + "b   c \n"));
        assertEquals("a b", TextClean.line(64, "a\r\nb"), "a line break in a single line is a space");
        assertEquals("", TextClean.line(64, " " + cp(0x200B) + " " + SIGN));
        assertEquals("", TextClean.line(64, null));
    }

    @Test
    void textIsCutByCodePointsNeverThroughAPair() {
        assertEquals("ab" + EMOJI, TextClean.line(3, "ab" + EMOJI + "cd"));
        assertEquals("ab", TextClean.line(2, "ab" + EMOJI));
        assertEquals("ab", TextClean.line(3, "ab c"), "the space left at the cut goes too");
    }

    @Test
    void multilineKeepsItsBreaksAndTidiesThem() {
        assertEquals("Server Plugins (1):\nLuckPerms", TextClean.multiline(8192, "Server Plugins (1):\r\nLuckPerms\r\n"));
        assertEquals("a\n\nb", TextClean.multiline(64, "\n\na  \n\n\n\n b\n\n"), "no more than one blank line");
        assertEquals("a\nb", TextClean.multiline(64, "a" + cp(0x2028) + "b"));
        assertEquals("a b", TextClean.line(64, "a" + cp(0x2028) + "b"));
    }

    @Test
    void cleaningIsIdempotent() {
        String dirty = SIGN + "a  He" + cp(0x202E) + "llo " + EMOJI + "\n wor" + cp(0x200B) + "ld " + SIGN;
        String once = TextClean.line(40, dirty);
        assertEquals(once, TextClean.line(40, once));
        String lines = TextClean.multiline(40, dirty + "\n\n\n" + dirty);
        assertEquals(lines, TextClean.multiline(40, lines));
    }

    @Test
    void anIdentifierIsCheckedNotChanged() {
        assertEquals("fabric-api", TextClean.requireIdentifier("mod.id", 128, "fabric-api"));
        assertEquals("my mod.jar", TextClean.requireFileName("mod.file_name", 160, "my mod.jar"), "a space is allowed");

        assertThrows(IllegalArgumentException.class, () -> TextClean.requireIdentifier("x", 128, ""));
        assertThrows(IllegalArgumentException.class, () -> TextClean.requireIdentifier("x", 4, "abcde"));
        assertThrows(IllegalArgumentException.class, () -> TextClean.requireIdentifier("x", 128, "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> TextClean.requireIdentifier("x", 128, " a"));
        assertThrows(IllegalArgumentException.class,
                () -> TextClean.requireIdentifier("x", 128, "a" + cp(0x202E) + "b"));
        assertThrows(IllegalArgumentException.class, () -> TextClean.requireIdentifier("x", 128, SIGN + "a"));
        assertThrows(IllegalArgumentException.class, () -> TextClean.requireFileName("x", 160, "a/b.jar"));
        assertThrows(IllegalArgumentException.class, () -> TextClean.requireFileName("x", 160, "a\\b.jar"));
    }

    @Test
    void theMessageOfARefusalNeverQuotesTheValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TextClean.requireIdentifier("mods[].id", 128, "secret-value\n"));
        assertFalse(e.getMessage().contains("secret-value"));
        assertTrue(e.getMessage().contains("mods[].id"));
    }
}
