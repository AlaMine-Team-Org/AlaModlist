package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.AlaModlist;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Minecraft line the build selected (-Pmc, gradle/mc/) is the line whose twin was compiled.
 *
 * <p>{@code common-mc/build.gradle} adds the {@code src/mc/<line>} directory of the selected line to the
 * source sets and hands the line to this test as a system property. If the wrong directory were compiled
 * (or none), the twin classes of the shared code would not be the ones of the game the jar runs on - the
 * kind of defect nothing else notices before a player does.
 *
 * <p>{@code LineProbe.LINE} is a compile-time constant, so reading it here copies its value into this test
 * and loads no game class: the unit-test run stays Minecraft-free.
 */
class LineProbeTest {

    @Test
    void theTwinOfTheSelectedLineWasCompiled() {
        String selected = System.getProperty(AlaModlist.MOD_ID + ".mc.line");

        assertNotNull(selected, "the build did not hand over the selected Minecraft line (common-mc/build.gradle, test { })");
        assertEquals(selected, LineProbe.LINE, "the twin compiled into this build is for another Minecraft line");
    }
}
