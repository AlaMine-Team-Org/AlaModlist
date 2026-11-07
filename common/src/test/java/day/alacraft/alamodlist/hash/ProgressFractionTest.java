package day.alacraft.alamodlist.hash;

import day.alacraft.alamodlist.flow.PreviewModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bar on the preview screen: it follows bytes when the size is known, files otherwise, and stays inside 0..1. */
class ProgressFractionTest {

    @Test
    void anUnknownTotalIsZero() {
        assertEquals(0.0, PreviewModel.fraction(new Progress()));
    }

    @Test
    void bytesDecideWhenTheirTotalIsKnown() {
        Progress progress = new Progress();
        progress.total(4, 1000);
        progress.bytes(250);
        progress.fileDone();

        assertEquals(0.25, PreviewModel.fraction(progress), 1e-9, "bytes, not the one file of four that is done");
    }

    @Test
    void filesDecideWhenEverythingCameFromTheCache() {
        Progress progress = new Progress();
        progress.total(4, 0);
        progress.fileDone();
        progress.fileDone();

        assertEquals(0.5, PreviewModel.fraction(progress), 1e-9);
    }

    @Test
    void itNeverLeavesZeroToOne() {
        Progress progress = new Progress();
        progress.total(1, 100);
        progress.bytes(900);

        assertEquals(1.0, PreviewModel.fraction(progress));
    }
}
