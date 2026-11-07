package day.alacraft.alamodlist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildInfoTest {

    /** Unit tests run without the processed resources: every value reads as "dev", nothing throws. */
    @Test
    void aMissingStampReadsAsDev() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.version());
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.gitHash());
        assertEquals("dev (dev, dev)", BuildInfo.describe());
    }
}
