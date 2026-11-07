package day.alacraft.alamodlist.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SideTest {

    @Test
    void theLoadersWordsMapToTheWireWords() {
        assertEquals("client", Side.declared("CLIENT").wire());
        assertEquals("server", Side.declared("SERVER").wire());
        assertEquals("universal", Side.declared("UNIVERSAL").wire());
    }

    @Test
    void nothingDeclaredOrAnUnknownWordIsNoSide() {
        assertNull(Side.declared(null));
        assertNull(Side.declared("BOTH"));
        assertNull(Side.declared(""));
    }
}
