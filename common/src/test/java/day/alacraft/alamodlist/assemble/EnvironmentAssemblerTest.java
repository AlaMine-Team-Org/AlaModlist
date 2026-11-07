package day.alacraft.alamodlist.assemble;

import day.alacraft.alamodlist.model.EnvironmentInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentAssemblerTest {

    @Test
    void theOperatingSystemIsReducedToItsFamily() {
        assertEquals("windows", EnvironmentAssembler.osFamily("Windows 10"));
        assertEquals("windows", EnvironmentAssembler.osFamily("Windows 11"));
        assertEquals("macos", EnvironmentAssembler.osFamily("Mac OS X"));
        assertEquals("macos", EnvironmentAssembler.osFamily("Darwin"));
        assertEquals("linux", EnvironmentAssembler.osFamily("Linux"));
        assertNull(EnvironmentAssembler.osFamily("FreeBSD"));
        assertNull(EnvironmentAssembler.osFamily(null));
    }

    @Test
    void theEnvironmentCarriesTheGivenFacts() {
        EnvironmentInfo environment = EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5", 25, "Windows 10");

        assertEquals("26.3", environment.mcVersion());
        assertEquals(777, environment.protocol());
        assertEquals("fabric", environment.loader());
        assertEquals("0.19.5", environment.loaderVersion());
        assertEquals(25, environment.javaMajor());
        assertEquals("windows", environment.osFamily());
    }

    @Test
    void anUnreadableLoaderVersionIsLeftOut() {
        assertNull(EnvironmentAssembler.create("26.3", 777, "neoforge", null, 25, "Linux").loaderVersion());
    }

    @Test
    void theLiveEnvironmentIsTheRunningJava() {
        EnvironmentInfo environment = EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5");
        assertEquals(Runtime.version().feature(), environment.javaMajor());
        assertTrue(environment.javaMajor() >= 8);
    }
}
