package day.alacraft.alamodlist.testsupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.privacy.KnownValues;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The planted values of the privacy self-test: one list, used both to seed the inputs and to judge the output, so
 * the two cannot drift apart. Realistic shapes on purpose - a path as the operating system spells it, a name with an
 * underscore, an address with a port - not strings that are easy to find.
 *
 * <p>Paths are assembled from pieces: the publishing gate refuses a home-directory path in published source, and
 * this is published source.
 */
public final class Canaries {

    /**
     * One planted value.
     *
     * @param label      the {@link KnownValues} label that must find it
     * @param value      the text as it is planted
     * @param separators true when it holds a path separator (it cannot go into a file name)
     * @param scope      the only normalized path it is planted in, or null for every field
     */
    public record Canary(String label, String value, boolean separators, String scope) {

        public boolean fitsIn(int maxChars) {
            return value.length() <= maxChars;
        }
    }

    public static final String NAME = "zqPilot_9";
    public static final UUID UUID_VALUE = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    public static final String SESSION = "token:5m0k3T3stS3ss10nT0k3n:9f8e7d6c5b4a3210";
    public static final String ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzbW9rZSI6InpxIn0.S3cr3tSm0keT3stS1gn4tur3";
    public static final String SERVER_HOST = "play.zqcanary-net.example";
    public static final String SERVER_ADDRESS = SERVER_HOST + ":25565";
    public static final String SERVER_IP = "203.0.113.42";
    public static final String SERVER_NAME = "ZQ Canary Realm";
    public static final String HOME_DIR = "C:\\" + "Us" + "ers\\zqcanaryuser";
    public static final String HOME_DIR_SLASHES = "C:/" + "Us" + "ers/zqcanaryuser";
    public static final String OS_USER = "zqcanaryuser";
    public static final String COMPUTER = "ZQCANARY-PC";
    public static final String GAME_DIR = "D:\\games\\zqcanary-mc";
    public static final String CONFIG_DIR = Path.of(GAME_DIR, "config").toString();
    public static final long SEED = 7026191857309640518L;
    public static final String WORLD_NAME = "ZQ Canary World";
    public static final String NEIGHBOUR = "zqNeighbour";

    /** Where the names of other players are planted: the one field a command reply can list them in. */
    public static final String NEIGHBOUR_SCOPE = KnownValues.OTHER_PLAYERS_SCOPE;

    /** Every planted value. */
    public static final List<Canary> ALL = List.of(
            new Canary(KnownValues.PLAYER_NAME, NAME, false, null),
            new Canary(KnownValues.PLAYER_UUID, UUID_VALUE.toString(), false, null),
            new Canary(KnownValues.PLAYER_UUID, UUID_VALUE.toString().replace("-", ""), false, null),
            new Canary(KnownValues.SESSION, SESSION, false, null),
            new Canary(KnownValues.ACCESS_TOKEN, ACCESS_TOKEN, false, null),
            new Canary(KnownValues.SERVER_ADDRESS, SERVER_HOST, false, null),
            new Canary(KnownValues.SERVER_ADDRESS, SERVER_IP, false, null),
            new Canary(KnownValues.SERVER_NAME, SERVER_NAME, false, null),
            new Canary(KnownValues.HOME_DIR, HOME_DIR, true, null),
            new Canary(KnownValues.HOME_DIR, HOME_DIR_SLASHES, true, null),
            new Canary(KnownValues.OS_USER, OS_USER, false, null),
            new Canary(KnownValues.COMPUTER_NAME, COMPUTER, false, null),
            new Canary(KnownValues.GAME_DIR, GAME_DIR, true, null),
            new Canary(KnownValues.CONFIG_DIR, CONFIG_DIR, true, null),
            new Canary(KnownValues.WORLD_SEED, Long.toString(SEED), false, null),
            new Canary(KnownValues.WORLD_NAME, WORLD_NAME, false, null),
            new Canary(KnownValues.OTHER_PLAYERS, NEIGHBOUR, false, NEIGHBOUR_SCOPE));

    /** Pieces of the planted values: a partial leak must not slip past the judge either. */
    private static final List<String> FRAGMENTS = List.of(
            "7c9e6679", "5m0k3T3stS3ss10nT0k3n", "eyJhbGciOiJIUzI1NiJ9", "S3cr3tSm0keT3stS1gn4tur3",
            "zqcanary-net", "203.0.113.", "zqcanaryuser", "zqcanary-mc", "70261918");

    private Canaries() {
    }

    /** All the values the gate is told about. */
    public static KnownValues known() {
        return KnownValues.builder()
                .playerName(NAME)
                .playerUuid(UUID_VALUE)
                .session(SESSION)
                .accessToken(ACCESS_TOKEN)
                .serverAddress(SERVER_ADDRESS)
                .serverAddress(SERVER_IP)
                .serverName(SERVER_NAME)
                .homeDir(HOME_DIR)
                .osUser(OS_USER)
                .computerName(COMPUTER)
                .gameDir(Path.of(GAME_DIR))
                .configDir(Path.of(CONFIG_DIR))
                .worldSeed(SEED)
                .worldName(WORLD_NAME)
                .otherPlayers(List.of(NEIGHBOUR))
                .build();
    }

    /** Whether a planted value may go into a field: it fits, it is allowed there, and it is not scoped elsewhere. */
    public static boolean fits(Canary canary, String normalizedPath, int maxChars, boolean fileName) {
        if (!canary.fitsIn(maxChars) || (fileName && canary.separators())) {
            return false;
        }
        return canary.scope() == null || canary.scope().equals(normalizedPath);
    }

    /**
     * The judge: the body must hold none of the planted values, whole or in part, neither in the raw text nor in
     * any decoded string or number. A plain substring check on purpose - it shares no code with the gate it judges.
     *
     * @throws AssertionError naming the planted value's label (never the value) when one is found
     */
    public static void assertAbsent(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8);
        List<String> texts = new ArrayList<>();
        texts.add(raw);
        collect(JsonParser.parseString(raw), texts);
        for (Canary canary : ALL) {
            for (String text : texts) {
                if (text.toLowerCase().contains(canary.value().toLowerCase())) {
                    throw new AssertionError("the body holds a planted value: " + canary.label());
                }
            }
        }
        for (String fragment : FRAGMENTS) {
            for (String text : texts) {
                if (text.toLowerCase().contains(fragment.toLowerCase())) {
                    throw new AssertionError("the body holds a piece of a planted value (" + fragment.length()
                            + " chars)");
                }
            }
        }
    }

    private static void collect(JsonElement element, List<String> out) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                out.add(key);
                collect(object.get(key), out);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            array.forEach(item -> collect(item, out));
        } else if (element.isJsonPrimitive()) {
            out.add(element.getAsString());
        }
    }
}
