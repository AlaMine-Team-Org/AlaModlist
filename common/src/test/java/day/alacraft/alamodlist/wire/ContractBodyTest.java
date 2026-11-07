package day.alacraft.alamodlist.wire;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.privacy.PrivacyGate;
import day.alacraft.alamodlist.privacy.Verdict;
import day.alacraft.alamodlist.testsupport.Canaries;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request the service accepted, word for word ({@code contract/create-request.json}), against the model and the
 * writer: what the service takes, the model can hold, and what the writer writes is the same request. The names of
 * the fields come from that body, not from memory.
 */
class ContractBodyTest {

    /**
     * @implements TC-PRIVACY-001-STR02 - the writer writes back the request the service accepted, word for word
     * @covers R-PRV-01
     */
    @Test
    void theWriterWritesBackTheBodyTheServiceAccepted() throws IOException {
        JsonObject accepted = body();

        Snapshot snapshot = SchemaReader.read(accepted);
        JsonObject written = JsonParser.parseString(SnapshotJson.toJson(snapshot)).getAsJsonObject();

        assertEquals(normalized(accepted), normalized(written));
        assertTrue(written.getAsJsonArray("mods").size() == 3 && written.has("server") && written.has("launcher"),
                "armed: the body is the full one, not an empty shell");
    }

    /** The reader would pass anything if it never failed: a key the schema does not list must stop it. */
    @Test
    void aKeyOutsideTheSchemaStopsTheRead() throws IOException {
        JsonObject extra = body();
        extra.getAsJsonArray("mods").get(0).getAsJsonObject().getAsJsonObject("hashes").addProperty("sha512", "00");
        AssertionError e = assertThrows(AssertionError.class, () -> SchemaReader.read(extra));
        assertTrue(e.getMessage().contains("sha512"), e.getMessage());

        JsonObject top = body();
        top.addProperty("file_path", "x");
        assertThrows(AssertionError.class, () -> SchemaReader.read(top));
    }

    /** And the writer would pass anything if the comparison never failed: change one value and it must differ. */
    @Test
    void aChangedValueMakesTheComparisonFail() throws IOException {
        JsonObject changed = body();
        changed.addProperty("title", "something else");
        Snapshot snapshot = SchemaReader.read(body());

        JsonObject written = JsonParser.parseString(SnapshotJson.toJson(snapshot)).getAsJsonObject();

        org.junit.jupiter.api.Assertions.assertNotEquals(normalized(changed), normalized(written));
    }

    @Test
    void theAcceptedBodyIsCleanForTheGate() throws IOException {
        byte[] bytes = SnapshotJson.toBytes(SchemaReader.read(body()));

        Verdict verdict = PrivacyGate.check(bytes, Canaries.known());

        assertTrue(verdict.clean(), "a body the service accepted raised: " + verdict.findings());
        Canaries.assertAbsent(bytes);
    }

    @Test
    void theWriterIsDeterministicAndCompact() throws IOException {
        Snapshot snapshot = SchemaReader.read(body());

        String first = SnapshotJson.toJson(snapshot);

        assertEquals(first, SnapshotJson.toJson(snapshot));
        assertTrue(first.startsWith("{\"source\":\"alamodlist/0.1.0\",\"locale\":\"ru\",\"title\":\""), first);
        assertEquals(-1, first.indexOf('\n'), "no raw line break outside a string");
        assertEquals(-1, first.indexOf(": "), "no padding");
    }

    @Test
    void textThatNeedsEscapingRoundTrips() {
        String note = "line one\nline \"two\" \\ with a tab\there";
        Snapshot snapshot = new Snapshot(null, null, "Quote \" and backslash \\", note,
                new day.alacraft.alamodlist.model.EnvironmentInfo("26.3", null, "fabric", null, null, null),
                null, null, null, null, null, null, null);

        JsonObject written = JsonParser.parseString(SnapshotJson.toJson(snapshot)).getAsJsonObject();

        assertEquals("Quote \" and backslash \\", written.get("title").getAsString());
        assertEquals("line one\nline \"two\" \\ with a tab here", written.get("note").getAsString());
        assertEquals(Map.of("title", true, "note", true, "environment", true).keySet(), written.keySet());
    }

    @Test
    void anAbsentCategoryIsLeftOutNotWrittenEmpty() {
        Snapshot snapshot = new Snapshot(null, null, null, null,
                new day.alacraft.alamodlist.model.EnvironmentInfo("26.3", null, "neoforge", null, null, null),
                null, null, null, null, null, null, null);

        assertEquals("{\"environment\":{\"mc_version\":\"26.3\",\"loader\":\"neoforge\"}}",
                SnapshotJson.toJson(snapshot));
    }

    // ---------------------------------------------------------------------------------------------

    private static JsonObject body() throws IOException {
        try (InputStream in = ContractBodyTest.class.getClassLoader().getResourceAsStream("contract/create-request.json")) {
            assertTrue(in != null, "missing fixture");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    /** The service reads a missing list as an empty one, so an empty list and an absent one are the same body. */
    private static JsonElement normalized(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = normalized(entry.getValue());
                boolean empty = value.isJsonNull() || (value.isJsonArray() && value.getAsJsonArray().isEmpty());
                if (!empty) {
                    out.add(entry.getKey(), value);
                }
            }
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            element.getAsJsonArray().forEach(item -> out.add(normalized(item)));
            return out;
        }
        return element;
    }
}
