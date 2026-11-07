package day.alacraft.alamodlist.privacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.testsupport.Canaries;
import day.alacraft.alamodlist.testsupport.Canaries.Canary;
import day.alacraft.alamodlist.testsupport.SnapshotSeed;
import day.alacraft.alamodlist.wire.SnapshotJson;
import day.alacraft.alamodlist.wire.WireSchema;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-test of the privacy gate: planted values in every free field, proof that they reached the input before
 * anything is said about the output, and a negative control - a leaking writer that must turn the judge red.
 *
 * <p>A gate that cannot fail is not a gate. Every claim below is made against a value that was demonstrably there.
 */
class PrivacyGateTest {

    private static final KnownValues KNOWN = Canaries.known();

    // ---------------------------------------------------------------------------------------------
    // Armed: the planted values really are in the input, in every field, before any output is judged

    /** Every free-text and identifier field of the schema carries at least one planted value.
     *
     * @implements TC-PRIVACY-001-GATE02 - every string field of the schema carries a planted value before the output is judged
     * @covers R-PRV-04
     */
    @Test
    void everyStringFieldOfTheSchemaCarriesAPlantedValue() {
        Snapshot seeded = seedEverywhere();
        Map<String, List<String>> strings = SnapshotSeed.strings(seeded);

        Set<String> expected = new LinkedHashSet<>(WireSchema.freeTextPaths());
        for (String path : WireSchema.identifierPaths()) {
            Kind kind = SnapshotSeed.fieldAt(path).kind();
            if (kind == Kind.IDENTIFIER || kind == Kind.FILE_NAME) {
                expected.add(path);
            }
        }
        assertFalse(expected.isEmpty(), "the schema has string fields");
        assertEquals(expected, strings.keySet(), "every string field is filled, and only those");

        for (String path : expected) {
            for (String value : strings.get(path)) {
                assertTrue(Canaries.ALL.stream().anyMatch(c -> value.contains(c.value())),
                        "no planted value reached " + path + " - a field the self-test cannot see is a blind spot");
            }
        }
    }

    /** Every planted value reaches the input somewhere (the scoped one only where it belongs). */
    @Test
    void everyPlantedValueReachesTheInput() {
        Map<String, List<String>> strings = SnapshotSeed.strings(seedEverywhere());
        for (Canary canary : Canaries.ALL) {
            List<String> where = new ArrayList<>();
            strings.forEach((path, values) -> {
                if (values.stream().anyMatch(v -> v.contains(canary.value()))) {
                    where.add(path);
                }
            });
            assertFalse(where.isEmpty(), "planted value of " + canary.label() + " is in no field of the input");
            if (canary.scope() != null) {
                assertEquals(List.of(canary.scope()), where, "a scoped value is planted only in its own field");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The gate finds each planted value in each field it may be planted in

    /**
     * @implements TC-PRIVACY-001-GATE01 - the gate finds every planted value in every field it fits in
     * @covers R-PRV-02
     */
    @Test
    void theGateFindsEveryPlantedValueInEveryFieldItFitsIn() {
        int checked = 0;
        for (String path : stringPaths()) {
            Field field = SnapshotSeed.fieldAt(path);
            for (Canary canary : Canaries.ALL) {
                if (!Canaries.fits(canary, path, field.max(), field.kind() == Kind.FILE_NAME)) {
                    continue;
                }
                Snapshot snapshot = SnapshotSeed.build(p -> p.equals(path) ? canary.value() : null);
                // Armed: the value is in the input before the output is judged.
                assertTrue(SnapshotSeed.strings(snapshot).get(path).stream().anyMatch(v -> v.contains(canary.value())),
                        canary.label() + " did not reach " + path);

                Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(snapshot), KNOWN);

                assertFalse(verdict.errored(), "the gate failed on " + path);
                String concrete = path.replace("[]", "[0]");
                Finding found = verdict.findings().stream()
                        .filter(f -> f.label().equals(canary.label()) && f.path().equals(concrete))
                        .findFirst().orElse(null);
                assertNotNull(found, "the gate missed " + canary.label() + " in " + concrete);
                assertEquals(field.kind().isFreeText(), found.redactable(),
                        concrete + ": only free text can be repaired");
                checked++;
            }
        }
        assertTrue(checked > 300, "the matrix is suspiciously small: " + checked);
    }

    /** The name of another player is a known value in a command reply only - nowhere else. */
    @Test
    void anotherPlayersNameIsLookedForInCommandRepliesOnly() {
        for (String path : stringPaths()) {
            if (path.equals(Canaries.NEIGHBOUR_SCOPE)) {
                continue;
            }
            Snapshot snapshot = SnapshotSeed.build(p -> p.equals(path) && SnapshotSeed.fieldAt(path).max() >= 11
                    ? Canaries.NEIGHBOUR : null);
            Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(snapshot), KNOWN);
            assertTrue(verdict.findings().stream().noneMatch(f -> f.label().equals(KnownValues.OTHER_PLAYERS)),
                    "another player's name was reported in " + path);
        }
    }

    /** A number is searched too: a world seed in a numeric field. */
    @Test
    void aSeedInANumberIsFound() {
        Snapshot base = SnapshotSeed.benign();
        ModEntry template = base.mods().get(0);
        ModEntry withSeed = new ModEntry(template.id(), template.name(), template.version(), template.kind(),
                template.parentId(), template.side(), template.fileName(), Canaries.SEED, template.hashes(),
                template.license(), template.authors());
        Snapshot snapshot = new Snapshot(base.source(), base.locale(), base.title(), base.note(), base.environment(),
                List.of(withSeed), base.resourcePacks(), base.shaderPacks(), base.dataPacks(), base.launcher(),
                base.server(), base.plugins());

        Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(snapshot), KNOWN);

        Finding found = verdict.findings().stream()
                .filter(f -> f.label().equals(KnownValues.WORLD_SEED)).findFirst().orElse(null);
        assertNotNull(found, "the seed was not found in a number");
        assertEquals("mods[0].file_size", found.path());
        assertFalse(found.redactable(), "a number cannot be repaired by replacing text");
    }

    // ---------------------------------------------------------------------------------------------
    // Hide and continue

    /** After "hide and continue" the free-text leaks are gone, nothing is judged clean by emptiness, and ids stay.
     *
     * @implements TC-PRIVACY-001-GATE05 - hiding repairs free text and keeps the rest of the request
     * @covers R-PRV-02
     */
    @Test
    void hidingRepairsFreeTextAndKeepsTheRest() {
        Snapshot seeded = SnapshotSeed.build(path -> {
            Field field = SnapshotSeed.fieldAt(path);
            return field.kind().isFreeText() ? pack(path, field) : null;
        });
        byte[] before = SnapshotJson.toBytes(seeded);
        assertFalse(PrivacyGate.check(before, KNOWN).clean(), "armed: the seeded request is not clean");
        assertThrows(AssertionError.class, () -> Canaries.assertAbsent(before), "armed: the judge sees the plants");

        Snapshot hidden = Redactor.redact(seeded, KNOWN);
        byte[] after = SnapshotJson.toBytes(hidden);

        Verdict verdict = PrivacyGate.check(after, KNOWN);
        assertTrue(verdict.clean(), "findings left after hiding: " + verdict.findings());
        Canaries.assertAbsent(after);
        assertKept(seeded, hidden, after);
    }

    /** An identifier is never repaired: the gate keeps blocking it. */
    @Test
    void anIdentifierWithAKnownValueStaysBlocked() {
        Snapshot seeded = SnapshotSeed.build(path -> path.equals("mods[].id") ? Canaries.NAME : null);
        Snapshot hidden = Redactor.redact(seeded, KNOWN);

        Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(hidden), KNOWN);

        assertEquals(Canaries.NAME, hidden.mods().get(0).id(), "the id was left exactly as it was");
        assertTrue(verdict.findings().stream().anyMatch(f -> f.path().equals("mods[0].id") && !f.redactable()));
    }

    /** A player whose name is the placeholder cannot be hidden: the gate says so again after the repair. */
    @Test
    void aNameThatIsThePlaceholderStaysBlockedAfterHiding() {
        KnownValues odd = KnownValues.builder().playerName("hidden").build();
        Snapshot seeded = SnapshotSeed.build(path -> path.equals("title") ? "Hidden world" : null);

        Snapshot hidden = Redactor.redact(seeded, odd);
        Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(hidden), odd);

        assertFalse(verdict.clean(), "the placeholder is itself the player's name: no way to hide it, so no send");
    }

    // ---------------------------------------------------------------------------------------------
    // Layer one alone

    /** Structural sources: a file reduced to its bare name leaves no directory in the body, with no gate at all. */
    @Test
    void layerOneKeepsDirectoriesOutWithoutTheGate() {
        Path jar = Path.of(Canaries.GAME_DIR, "mods", "sample.jar");
        Snapshot base = SnapshotSeed.benign();
        ModEntry template = base.mods().get(0);
        ModEntry entry = new ModEntry(template.id(), template.name(), template.version(), template.kind(),
                template.parentId(), template.side(), jar.getFileName().toString(), template.fileSize(),
                template.hashes(), template.license(), template.authors());
        Snapshot snapshot = new Snapshot(base.source(), base.locale(), base.title(), base.note(), base.environment(),
                List.of(entry), base.resourcePacks(), base.shaderPacks(), base.dataPacks(), base.launcher(),
                base.server(), base.plugins());

        // No PrivacyGate call here on purpose: the structure alone does the work.
        Canaries.assertAbsent(SnapshotJson.toBytes(snapshot));

        // And the structure refuses what would carry a directory.
        assertThrows(IllegalArgumentException.class, () -> new ModEntry("id", null, null, null, null, null,
                jar.toString(), null, null, null, null));
    }

    // ---------------------------------------------------------------------------------------------
    // Negative control: a leaking writer must turn the judge red

    /**
     * The same assertion that passes on a good body must fail on a body from a writer that leaks a path under a key
     * of its own and the player's name in the title - and the gate must find both. Without this, "green" would only
     * mean that nothing was looked at.
     *
     * @implements TC-PRIVACY-001-GATE03 - a leaking writer turns the judge red and the gate finds the leaks
     * @covers R-PRV-04
     */
    @Test
    void aLeakingWriterTurnsTheJudgeRedAndTheGateFindsBothLeaks(TestReporter reporter) {
        Snapshot clean = SnapshotSeed.benign();
        byte[] good = SnapshotJson.toBytes(clean);
        Canaries.assertAbsent(good);
        assertTrue(PrivacyGate.check(good, KNOWN).clean(), "the good body passes both");

        byte[] leaking = leakingWrite(clean);

        AssertionError red = assertThrows(AssertionError.class, () -> Canaries.assertAbsent(leaking),
                "THE JUDGE DID NOT TURN RED ON A LEAKING WRITER - the self-test proves nothing");
        reporter.publishEntry("negative control: judge on the leaking body", "RED - " + red.getMessage());

        Verdict verdict = PrivacyGate.check(leaking, KNOWN);
        reporter.publishEntry("negative control: gate on the leaking body", verdict.findings().toString());
        assertTrue(verdict.findings().stream().anyMatch(f -> f.kind() == Finding.Kind.UNKNOWN_KEY
                && f.path().equals("mods[0].file_path")), "the extra key was not reported");
        assertTrue(verdict.findings().stream().anyMatch(f -> f.label().equals(KnownValues.HOME_DIR)
                && f.path().equals("mods[0].file_path")), "the path planted under it was not reported");
        assertTrue(verdict.findings().stream().anyMatch(f -> f.label().equals(KnownValues.PLAYER_NAME)
                && f.path().equals("title")), "the name planted in the title was not reported");
        assertFalse(verdict.clean());
    }

    /** The control would be worthless if the gate also cried wolf on a good body: the false-alarm side. */
    @Test
    void aVersionThatLooksLikeAnAddressIsNotAnAlarm() {
        KnownValues addressOnly = KnownValues.builder().serverAddress("26.2.0.67").build();
        Snapshot snapshot = SnapshotSeed.build(path -> path.equals("mods[].version") ? "126.2.0.670" : null);

        Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(snapshot), addressOnly);

        assertTrue(verdict.clean(), "a longer version number was taken for the address: " + verdict.findings());
        Verdict exact = PrivacyGate.check(SnapshotJson.toBytes(
                SnapshotSeed.build(path -> path.equals("mods[].version") ? "26.2.0.67" : null)), addressOnly);
        assertFalse(exact.clean(), "armed: the same address, whole, is found");
    }

    // ---------------------------------------------------------------------------------------------
    // Escaped forms, fail closed

    /** A value written as {@code \\uXXXX} escapes, or doubled backslashes, is found: the gate reads values, not text.
     *
     * @implements TC-PRIVACY-001-GATE06 - a value in escaped or doubled form is found
     * @covers R-PRV-02
     */
    @Test
    void escapedFormsAreFound() {
        StringBuilder escaped = new StringBuilder();
        for (char c : Canaries.NAME.toCharArray()) {
            escaped.append(String.format("\\u%04x", (int) c));
        }
        String json = "{\"title\":\"" + escaped + "\",\"environment\":{\"mc_version\":\"26.3\",\"loader\":\"fabric\"}}";
        assertFalse(new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).contains(Canaries.NAME),
                "armed: the raw text does not hold the name in the clear");

        Verdict verdict = PrivacyGate.check(json.getBytes(StandardCharsets.UTF_8), KNOWN);

        assertTrue(verdict.findings().stream().anyMatch(f -> f.label().equals(KnownValues.PLAYER_NAME)
                && f.path().equals("title")), "an escaped name slipped past");

        String path = "{\"title\":\"" + Canaries.HOME_DIR.replace("\\", "\\\\")
                + "\\\\mods\",\"environment\":{\"mc_version\":\"26.3\",\"loader\":\"fabric\"}}";
        Verdict doubled = PrivacyGate.check(path.getBytes(StandardCharsets.UTF_8), KNOWN);
        assertTrue(doubled.findings().stream().anyMatch(f -> f.label().equals(KnownValues.HOME_DIR)),
                "a path with doubled backslashes slipped past");
    }

    /** A name in non-Latin letters, in the clear and escaped. */
    @Test
    void aNameInAnotherAlphabetIsFound() {
        String name = new String(Character.toChars(0x0417)) + new String(Character.toChars(0x0435)) + new String(Character.toChars(0x0432)) + new String(Character.toChars(0x0441)) + "x";
        KnownValues cyrillic = KnownValues.builder().playerName(name).build();
        String clear = "{\"title\":\"" + name + " world\"}";
        StringBuilder escaped = new StringBuilder();
        for (char c : name.toCharArray()) {
            escaped.append(String.format("\\u%04x", (int) c));
        }
        String hidden = "{\"title\":\"" + escaped.toString().toUpperCase().replace("\\U", "\\u") + " world\"}";

        assertFalse(PrivacyGate.check(clear.getBytes(StandardCharsets.UTF_8), cyrillic).clean());
        assertFalse(PrivacyGate.check(hidden.getBytes(StandardCharsets.UTF_8), cyrillic).clean());
        assertTrue(PrivacyGate.check("{\"title\":\"harmless\"}".getBytes(StandardCharsets.UTF_8), cyrillic).clean());
    }

    /**
     * @implements TC-PRIVACY-001-GATE04 - a gate that cannot do its job does not allow sending
     * @covers R-PRV-03
     */
    @Test
    void theGateFailsClosed() {
        byte[] good = SnapshotJson.toBytes(SnapshotSeed.benign());
        assertTrue(PrivacyGate.check(good, KNOWN).clean(), "armed: a good body passes");

        assertFailsClosed(null, KNOWN);
        assertFailsClosed(good, null);
        assertFailsClosed(new byte[0], KNOWN);
        assertFailsClosed("not json".getBytes(StandardCharsets.UTF_8), KNOWN);
        assertFailsClosed("{\"title\":\"a\"} trailing".getBytes(StandardCharsets.UTF_8), KNOWN);
        assertFailsClosed("{\"title\":\"a\",}".getBytes(StandardCharsets.UTF_8), KNOWN);
        assertFailsClosed(new byte[] {'{', '"', 't', '"', ':', '"', (byte) 0xC3, '"', '}'}, KNOWN);
        assertFailsClosed(("[".repeat(500) + "]".repeat(500)).getBytes(StandardCharsets.UTF_8), KNOWN);
    }

    @Test
    void aKeyWrittenTwiceIsAFindingBecauseTheFirstValueWouldHide() {
        String json = "{\"title\":\"" + Canaries.NAME + "\",\"title\":\"harmless\"}";

        Verdict verdict = PrivacyGate.check(json.getBytes(StandardCharsets.UTF_8), KNOWN);

        assertTrue(verdict.findings().stream().anyMatch(f -> f.kind() == Finding.Kind.DUPLICATE_KEY));
        assertTrue(verdict.findings().stream().anyMatch(f -> f.label().equals(KnownValues.PLAYER_NAME)),
                "the first, hidden value is still read");
    }

    @Test
    void aFindingNeverHoldsTheValue() {
        Snapshot snapshot = SnapshotSeed.build(path -> path.equals("title") ? Canaries.NAME : null);
        Verdict verdict = PrivacyGate.check(SnapshotJson.toBytes(snapshot), KNOWN);
        assertFalse(verdict.findings().isEmpty());
        assertFalse(verdict.toString().contains(Canaries.NAME), "the verdict prints the value");
        assertFalse(KNOWN.toString().contains(Canaries.NAME), "the known values print themselves");
        assertFalse(KNOWN.needles().toString().contains(Canaries.NAME), "a needle prints its value");
    }

    // ---------------------------------------------------------------------------------------------
    // helpers

    private static void assertFailsClosed(byte[] body, KnownValues known) {
        Verdict verdict = PrivacyGate.check(body, known);
        assertTrue(verdict.errored(), "the gate did not fail on bad input");
        assertFalse(verdict.clean(), "a failed gate must not allow sending");
    }

    private static List<String> stringPaths() {
        List<String> paths = new ArrayList<>(WireSchema.freeTextPaths());
        for (String path : WireSchema.identifierPaths()) {
            Kind kind = SnapshotSeed.fieldAt(path).kind();
            if (kind == Kind.IDENTIFIER || kind == Kind.FILE_NAME) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Every string field gets as many planted values as fit. */
    private static Snapshot seedEverywhere() {
        return SnapshotSeed.build(path -> {
            Field field = SnapshotSeed.fieldAt(path);
            return field.kind() == Kind.HEX || field.kind() == Kind.ENUM ? null : pack(path, field);
        });
    }

    private static String pack(String path, Field field) {
        StringBuilder out = new StringBuilder();
        for (Canary canary : Canaries.ALL) {
            if (!Canaries.fits(canary, path, field.max(), field.kind() == Kind.FILE_NAME)) {
                continue;
            }
            String piece = (out.length() == 0 ? "" : " | ") + canary.value();
            if (out.length() + piece.length() <= field.max()) {
                out.append(piece);
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** A writer that does what the real one is built never to do. */
    private static byte[] leakingWrite(Snapshot snapshot) {
        JsonObject root = JsonParser.parseString(SnapshotJson.toJson(snapshot)).getAsJsonObject();
        JsonArray mods = root.getAsJsonArray("mods");
        mods.get(0).getAsJsonObject().addProperty("file_path", Canaries.HOME_DIR + "\\mods\\sample.jar");
        root.addProperty("title", "Build of " + Canaries.NAME);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** What must still arrive: an empty or cut-off body is also free of secrets, so absence proves nothing. */
    private static void assertKept(Snapshot original, Snapshot hidden, byte[] body) {
        JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(original.mods().size(), root.getAsJsonArray("mods").size());
        assertEquals(original.mods().get(0).id(), root.getAsJsonArray("mods").get(0).getAsJsonObject()
                .get("id").getAsString());
        assertEquals(original.mods().get(0).hashes().sha1(), root.getAsJsonArray("mods").get(0).getAsJsonObject()
                .getAsJsonObject("hashes").get("sha1").getAsString());
        assertEquals(original.environment().loader(), root.getAsJsonObject("environment").get("loader").getAsString());
        assertEquals(original.server().commandReplies().size(),
                root.getAsJsonObject("server").getAsJsonArray("command_replies").size());
        assertTrue(hidden.title().contains(Redactor.PLACEHOLDER), "the replacement mark is there: the rule fired");
        Set<String> keys = root.keySet();
        assertTrue(keys.containsAll(List.of("environment", "mods", "resource_packs", "server", "plugins")));
        Function<String, Integer> size = key -> root.getAsJsonArray(key).size();
        assertEquals(original.plugins().size(), size.apply("plugins"));
    }
}
