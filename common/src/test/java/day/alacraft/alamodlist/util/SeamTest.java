package day.alacraft.alamodlist.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeamTest {

    @Test
    void readingBeforeInstallFailsAndNamesTheSeam() {
        Seam<String> seam = new Seam<>("Example");
        IllegalStateException error = assertThrows(IllegalStateException.class, seam::get);
        assertTrue(error.getMessage().contains("Example"), error.getMessage());
        assertFalse(seam.isInstalled());
    }

    @Test
    void anInstalledValueIsReturned() {
        Seam<String> seam = new Seam<>("Example");
        seam.install("value");
        assertEquals("value", seam.get());
        assertTrue(seam.isInstalled());
    }

    @Test
    void aSecondInstallIsAWiringMistake() {
        Seam<String> seam = new Seam<>("Example");
        seam.install("first");
        assertThrows(IllegalStateException.class, () -> seam.install("second"));
        assertEquals("first", seam.get());
    }

    @Test
    void nullIsNotAnImplementation() {
        assertThrows(NullPointerException.class, () -> new Seam<String>("Example").install(null));
    }
}
