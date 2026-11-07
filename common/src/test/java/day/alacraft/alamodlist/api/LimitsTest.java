package day.alacraft.alamodlist.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.testsupport.LiveFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /limits}: the recorded answer is the oracle for the built-in fallback, and the numbers of a server the
 * player may have pointed the mod at are not trusted.
 */
class LimitsTest {

    private static JsonObject recorded() {
        return JsonParser.parseString(LiveFixtures.load("limits-200").body()).getAsJsonObject();
    }

    /**
     * The fallback must equal what the service says now. A fallback that promises more than the service accepts
     * makes every send fail silently (it happened to a sibling mod); one that has fallen behind goes unnoticed
     * unless something compares it. When the service changes, record the answer again and this test says what moved.
     */
    @Test
    void theBuiltInFallbackIsTheRecordedAnswer() {
        Limits live = Limits.from(recorded());

        assertEquals(Limits.DEFAULTS, live);
        assertEquals(Duration.ofDays(90), live.storageTime());
        assertEquals(90, live.storageDays());
    }

    @Test
    void everyKeyOfTheRecordedAnswerIsRead() {
        JsonObject json = recorded();
        Limits live = Limits.from(json);

        assertEquals(json.getAsJsonObject("fieldLengths").keySet(), live.fieldLengths().keySet(),
                "a key the recording has and the client ignores (or the other way round) is a field nobody checks");
        assertEquals(json.getAsJsonObject("server").getAsJsonObject("fieldLengths").keySet(),
                live.server().fieldLengths().keySet());
        for (String key : json.getAsJsonObject("fieldLengths").keySet()) {
            assertEquals(json.getAsJsonObject("fieldLengths").get(key).getAsInt(), live.fieldLengths().get(key), key);
        }
        assertEquals(json.get("maxBytes").getAsLong(), live.maxBytes());
        assertEquals(json.get("maxEntries").getAsInt(), live.maxEntries());
        assertEquals(json.get("maxPlugins").getAsInt(), live.maxPlugins());
        assertEquals(json.get("idLength").getAsInt(), live.idLength());
        assertEquals(json.getAsJsonObject("server").get("maxMods").getAsInt(), live.server().maxMods());
        assertEquals(json.getAsJsonObject("server").get("maxCommandReplies").getAsInt(),
                live.server().maxCommandReplies());
    }

    @Test
    void numbersFromTheNetworkAreCutToACeiling() {
        JsonObject hostile = JsonParser.parseString("""
                {"success": true, "storageTime": 9000000000000, "maxBytes": 9000000000, "maxEntries": 2000000000,
                 "maxPlugins": 5000000, "idLength": 4000,
                 "fieldLengths": {"name": 9000000000, "title": 99999},
                 "server": {"maxMods": 800000, "fieldLengths": {"commandText": 7000000}}}
                """).getAsJsonObject();

        Limits limits = Limits.from(hostile);

        assertEquals(Limits.MAX_BYTES_CEILING, limits.maxBytes());
        assertEquals(Limits.MAX_ENTRIES_CEILING, limits.maxEntries());
        assertEquals(Limits.MAX_ENTRIES_CEILING, limits.maxPlugins());
        assertEquals(Limits.ID_LENGTH_CEILING, limits.idLength());
        assertEquals(Limits.STORAGE_CEILING, limits.storageTime());
        assertEquals(Limits.FIELD_LENGTH_CEILING, limits.fieldLengths().get("name"));
        assertEquals(Limits.FIELD_LENGTH_CEILING, limits.fieldLengths().get("title"));
        assertEquals(Limits.MAX_ENTRIES_CEILING, limits.server().maxMods());
        assertEquals(Limits.FIELD_LENGTH_CEILING, limits.server().fieldLengths().get("commandText"));
    }

    @Test
    void nonsenseFallsBackToThePublishedValue() {
        JsonObject nonsense = JsonParser.parseString("""
                {"maxBytes": -1, "maxEntries": 0, "maxPlugins": "many", "idLength": null, "storageTime": 0,
                 "fieldLengths": {"name": -160, "title": "wide", "unknown_field": 5},
                 "server": "none"}
                """).getAsJsonObject();

        Limits limits = Limits.from(nonsense);

        assertEquals(Limits.DEFAULTS, limits, "every bad value is the default; a key nobody asked about is ignored");
    }

    @Test
    void anEmptyAnswerIsTheDefaults() {
        assertEquals(Limits.DEFAULTS, Limits.from(new JsonObject()));
    }

    @Test
    void aBodyOverTheLimitIsRefusedByNameAndOneWithinIsNot() {
        Limits limits = Limits.DEFAULTS;

        assertEquals(Optional.empty(), limits.refusal(limits.maxBytes(), limits.maxEntries()), "at the limit is allowed");
        assertEquals(Optional.of(ApiErrorCode.TOO_LARGE), limits.refusal(limits.maxBytes() + 1, 1));
        assertEquals(Optional.of(ApiErrorCode.TOO_MANY_ENTRIES), limits.refusal(10, limits.maxEntries() + 1));
        assertTrue(limits.refusal(0, 0).isEmpty());
    }
}
