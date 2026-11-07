package day.alacraft.alamodlist.flow;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFitTest {

    /** One pixel per char: the rule is about the arithmetic, not about the font. */
    private static final ToIntFunction<String> WIDTH = String::length;

    @Test
    void aTextThatFitsIsLeftAlone() {
        assertEquals("Resource packs", TextFit.ellipsize("Resource packs", 14, WIDTH));
        assertFalse(TextFit.isCut("Resource packs", 14, WIDTH));
    }

    @Test
    void aTextThatDoesNotFitEndsWithTheEllipsisAndStaysInsideTheWidth() {
        String cut = TextFit.ellipsize("Coming in one of the next versions", 12, WIDTH);

        assertEquals("Coming in o" + TextFit.ELLIPSIS, cut);
        assertTrue(cut.endsWith(TextFit.ELLIPSIS), "the cut is visible, never a silent clip");
        assertEquals(12, cut.length());
        assertTrue(TextFit.isCut("Coming in one of the next versions", 12, WIDTH));
    }

    @Test
    void whenNotEvenTheEllipsisFitsNothingIsDrawn() {
        assertEquals("", TextFit.ellipsize("abc", 0, WIDTH));
    }

    @Test
    void aSurrogatePairIsNeverSplit() {
        String cut = TextFit.ellipsize("a😀😀😀", 4, WIDTH);

        assertEquals("a😀" + TextFit.ELLIPSIS, cut);
    }
}
