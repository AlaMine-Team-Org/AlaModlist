package day.alacraft.alamodlist.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnobConfigTest {

    @TempDir
    Path directory;

    private Path file;

    /** A holder with one knob of each supported type. */
    @Section(id = "general", doc = "General.")
    @Section(id = "limits", doc = "Limits.")
    public static final class Sample {
        @Knob(section = "general", doc = "A switch.")
        public static boolean enabled = true;
        @Knob(section = "limits", doc = "A count.", min = 1, max = 64)
        public static int count = 16;
        @Knob(section = "limits", doc = "A big number.", min = 0)
        public static long budget = 1000L;
        @Knob(section = "limits", doc = "A ratio.", min = 0, max = 1)
        public static double ratio = 0.5;
        @Knob(section = "general", doc = "A name.")
        public static String name = "box";

        static final KnobConfig CONFIG = KnobConfig.of(Sample.class, 1, List.of());
    }

    @BeforeEach
    void reset() {
        Sample.CONFIG.resetToDefaults();
        file = directory.resolve("sample.json");
    }

    @Test
    void anAbsentFileIsWrittenWithTheDefaults() throws IOException {
        assertEquals(KnobConfig.LoadResult.DEFAULTS_WRITTEN, Sample.CONFIG.load(file));
        JsonObject written = read(file);
        assertEquals(1, written.get("schemaVersion").getAsInt());
        assertEquals(16, written.getAsJsonObject("limits").get("count").getAsInt());
        assertEquals(16, written.getAsJsonObject("builtinDefaults").get("count").getAsInt());
        assertTrue(written.getAsJsonObject("limits").has("_comment_count"), "every knob carries its doc");
    }

    @Test
    void valuesFromTheFileBecomeLive() throws IOException {
        write("""
                {"schemaVersion": 1, "general": {"enabled": false, "name": "crate"},
                 "limits": {"count": 32, "budget": 5000000000, "ratio": 0.25}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertFalse(Sample.enabled);
        assertEquals("crate", Sample.name);
        assertEquals(32, Sample.count);
        assertEquals(5_000_000_000L, Sample.budget);
        assertEquals(0.25, Sample.ratio);
    }

    @Test
    void aWrongTypeAbortsTheLoadAndAppliesNothing() throws IOException {
        write("""
                {"schemaVersion": 1, "general": {"enabled": false}, "limits": {"count": "many"}}""");
        assertEquals(KnobConfig.LoadResult.ERROR, Sample.CONFIG.load(file));
        assertTrue(Sample.enabled, "the valid change must not be applied when another key is broken");
        assertEquals(16, Sample.count);
    }

    @Test
    void aFractionIsNotAWholeNumber() throws IOException {
        write("""
                {"schemaVersion": 1, "limits": {"count": 2.5}}""");
        assertEquals(KnobConfig.LoadResult.ERROR, Sample.CONFIG.load(file));
        assertEquals(16, Sample.count);
    }

    /** The fallback lives in memory only (ADR-008): a corrected file would erase what the operator wrote. */
    @Test
    void anOutOfRangeValueFallsBackToTheDefaultButStaysInTheFile() throws IOException {
        write("""
                {"schemaVersion": 1, "limits": {"count": 500}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertEquals(16, Sample.count);
        assertEquals(500, read(file).getAsJsonObject("limits").get("count").getAsInt(), "the file keeps the value");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertEquals(16, Sample.count, "still the default on the next load");
        assertEquals(500, read(file).getAsJsonObject("limits").get("count").getAsInt());
        Sample.CONFIG.save();
        assertEquals(500, read(file).getAsJsonObject("limits").get("count").getAsInt(), "a save keeps it as well");
    }

    @Test
    void aValueChosenInTheScreenReplacesTheOneTheFileKept() throws IOException {
        write("""
                {"schemaVersion": 1, "limits": {"count": 500}}""");
        Sample.CONFIG.load(file);
        Sample.CONFIG.knobs().stream().filter(knob -> knob.key().equals("count")).findFirst().orElseThrow().set(24);
        Sample.CONFIG.save();
        assertEquals(24, read(file).getAsJsonObject("limits").get("count").getAsInt());
    }

    @Test
    void anAbsentKeyTakesItsDefault() throws IOException {
        Sample.count = 40;
        write("""
                {"schemaVersion": 1, "general": {"enabled": true}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertEquals(16, Sample.count);
    }

    @Test
    void aFileFromANewerModIsRefusedAndLeftUntouched() throws IOException {
        String newer = """
                {"schemaVersion": 99, "limits": {"count": 60}}""";
        write(newer);
        Sample.count = 40;
        assertEquals(KnobConfig.LoadResult.SCHEMA_TOO_NEW, Sample.CONFIG.load(file));
        assertEquals(16, Sample.count, "the built-in default is live, not the previous value");
        assertEquals(newer, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aValueStillAtItsRecordedDefaultFollowsTheNewDefault() throws IOException {
        // Written by an older build whose default was 8; this build's default is 16.
        write("""
                {"schemaVersion": 1, "limits": {"count": 8}, "builtinDefaults": {"count": 8}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertEquals(16, Sample.count);
        assertEquals(16, read(file).getAsJsonObject("builtinDefaults").get("count").getAsInt());
    }

    @Test
    void aValueTheOperatorEditedIsKept() throws IOException {
        write("""
                {"schemaVersion": 1, "limits": {"count": 10}, "builtinDefaults": {"count": 8}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertEquals(10, Sample.count);
    }

    @Test
    void unknownKeysAreDroppedByTheCanonicalRewrite() throws IOException {
        write("""
                {"schemaVersion": 1, "limits": {"cuont": 10}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertFalse(read(file).getAsJsonObject("limits").has("cuont"));
    }

    @Test
    void theCanonicalFileIsStableAcrossLoads() throws IOException {
        Sample.CONFIG.load(file);
        String first = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(KnobConfig.LoadResult.LOADED, Sample.CONFIG.load(file));
        assertEquals(first, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(first, Sample.CONFIG.canonicalJson());
    }

    @Test
    void theFileLayoutDoesNotDependOnFieldOrder() {
        List<String> keys = Sample.CONFIG.knobs().stream().map(KnobConfig.KnobView::key).toList();
        // Section order first (general, limits), then key name within a section.
        assertEquals(List.of("enabled", "name", "budget", "count", "ratio"), keys);
    }

    @Test
    void aKnobViewRejectsAValueOutsideItsRange() {
        KnobConfig.KnobView count = Sample.CONFIG.knobs().stream()
                .filter(knob -> knob.key().equals("count")).findFirst().orElseThrow();
        assertFalse(count.accepts(65));
        assertFalse(count.accepts("16"));
        assertThrows(IllegalArgumentException.class, () -> count.set(0));
        count.set(64);
        assertEquals(64, Sample.count);
    }

    @Test
    void saveWritesTheLiveValuesToTheLoadedFile() throws IOException {
        Sample.CONFIG.load(file);
        Sample.count = 48;
        Sample.CONFIG.save();
        assertEquals(48, read(file).getAsJsonObject("limits").get("count").getAsInt());
    }

    // --- The schema ladder ----------------------------------------------------------------------------

    /** Version 2 renamed "size" to "capacity". */
    @Section(id = "box", doc = "Box.")
    public static final class Migrated {
        @Knob(section = "box", doc = "How much fits.", min = 1)
        public static int capacity = 9;

        static final KnobConfig CONFIG = KnobConfig.of(Migrated.class, 2, List.of(
                new KnobConfig.Migration(1, document -> {
                    JsonObject box = document.getAsJsonObject("box");
                    if (box != null && box.has("size")) {
                        box.add("capacity", box.remove("size"));
                    }
                })));
    }

    @Test
    void anOlderFileIsMigratedStepByStep() throws IOException {
        write("""
                {"schemaVersion": 1, "box": {"size": 27}}""");
        assertEquals(KnobConfig.LoadResult.LOADED, Migrated.CONFIG.load(file));
        assertEquals(27, Migrated.capacity);
        assertEquals(2, read(file).get("schemaVersion").getAsInt());
    }

    // --- Declaration mistakes fail when the config is built, never silently -------------------------

    @Section(id = "a", doc = "A.")
    public static final class NotStatic {
        @Knob(section = "a", doc = "Wrong.")
        public boolean flag = true;
    }

    @Section(id = "a", doc = "A.")
    public static final class UndeclaredSection {
        @Knob(section = "b", doc = "Wrong.")
        public static boolean flag = true;
    }

    @Section(id = "a", doc = "A.")
    public static final class DefaultOutOfRange {
        @Knob(section = "a", doc = "Wrong.", max = 10)
        public static int value = 11;
    }

    @Section(id = "a", doc = "A.")
    public static final class UnsupportedType {
        @Knob(section = "a", doc = "Wrong.")
        public static float value = 1f;
    }

    @Test
    void declarationMistakesAreRefused() {
        assertThrows(IllegalStateException.class, () -> KnobConfig.of(NotStatic.class, 1, List.of()));
        assertThrows(IllegalStateException.class, () -> KnobConfig.of(UndeclaredSection.class, 1, List.of()));
        assertThrows(IllegalStateException.class, () -> KnobConfig.of(DefaultOutOfRange.class, 1, List.of()));
        assertThrows(IllegalStateException.class, () -> KnobConfig.of(UnsupportedType.class, 1, List.of()));
        assertThrows(IllegalStateException.class, () -> KnobConfig.of(Sample.class, 2, List.of()),
                "a version bump without a migration step");
    }

    @Test
    void theClientConfigBuilds() {
        assertFalse(ClientConfig.config().knobs().isEmpty());
    }

    private void write(String json) throws IOException {
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
