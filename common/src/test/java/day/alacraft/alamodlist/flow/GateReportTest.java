package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.privacy.Finding;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.privacy.PrivacyGate;
import day.alacraft.alamodlist.privacy.Verdict;
import day.alacraft.alamodlist.testsupport.Canaries;
import day.alacraft.alamodlist.testsupport.SnapshotSeed;
import day.alacraft.alamodlist.wire.SnapshotJson;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the player reads when the gate stops a request: the kind and the place of a find, never the value. */
class GateReportTest {

    @Test
    void aFindingBecomesALineWithItsKindAndItsPlace() {
        byte[] body = SnapshotJson.toBytes(SnapshotSeed.build(path -> path.equals("title") ? Canaries.NAME : null));
        Verdict verdict = PrivacyGate.check(body, Canaries.known());

        GateReport report = GateReport.of(verdict);

        assertFalse(verdict.clean());
        assertEquals(1, report.lines().size());
        assertEquals("screen.alamodlist.gate.found.player_name", report.lines().get(0).labelKey());
        assertEquals("title", report.lines().get(0).path());
        assertEquals(0, report.more());
    }

    @Test
    void noLineHoldsTheValueThatWasFound() {
        byte[] body = SnapshotJson.toBytes(SnapshotSeed.build(path -> path.equals("title") ? Canaries.NAME : null));
        GateReport report = GateReport.of(PrivacyGate.check(body, Canaries.known()));

        for (GateReport.Line line : report.lines()) {
            assertFalse(line.labelKey().contains(Canaries.NAME));
            assertFalse(line.path().contains(Canaries.NAME));
        }
        assertFalse(report.toString().contains(Canaries.NAME));
    }

    @Test
    void anErroredGateIsReportedAsAGateThatCouldNotJudge() {
        GateReport report = GateReport.of(new Verdict(List.of(), "JsonSyntaxException"));

        assertEquals(1, report.lines().size());
        assertEquals("screen.alamodlist.gate.found.gate_error", report.lines().get(0).labelKey());
        assertEquals("JsonSyntaxException", report.lines().get(0).path());
    }

    @Test
    void aLongListIsCutAndTheRestIsCounted() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            findings.add(new Finding(KnownValues.PLAYER_NAME, "mods[" + i + "].name", Finding.Kind.VALUE, true));
        }

        GateReport report = GateReport.of(new Verdict(findings, null));

        assertEquals(GateReport.MAX_LINES, report.lines().size());
        assertEquals(12 - GateReport.MAX_LINES, report.more());
    }

    @Test
    void aPathThatCameFromTheWireIsCleanedBeforeItIsShown() {
        Finding finding = new Finding("unknown_key", "mods[0]." + "§c" + "evil\nkey", Finding.Kind.UNKNOWN_KEY, false);

        String path = GateReport.of(new Verdict(List.of(finding), null)).lines().get(0).path();

        assertFalse(path.contains("§"));
        assertFalse(path.contains("\n"));
    }

    /** Every label the gate can produce has its own sentence: none falls into the generic one by accident. */
    @Test
    void everyLabelTheGateKnowsHasASentenceOfItsOwn() throws Exception {
        List<String> labels = new ArrayList<>();
        for (Field field : KnownValues.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && field.getType() == String.class && !field.getName().endsWith("_SCOPE")) {
                labels.add((String) field.get(null));
            }
        }
        labels.add("unknown_key");
        labels.add("duplicate_key");

        assertTrue(labels.size() >= 16, "the labels were really collected: " + labels);
        for (String label : labels) {
            assertFalse(GateReport.labelKey(label).endsWith(".other"), label + " has no sentence of its own");
        }
        assertEquals("screen.alamodlist.gate.found.other", GateReport.labelKey("a_label_from_the_future"));
    }
}
