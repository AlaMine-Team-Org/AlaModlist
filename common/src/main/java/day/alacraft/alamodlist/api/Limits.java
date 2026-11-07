package day.alacraft.alamodlist.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the service accepts and for how long it keeps a list, read from {@code GET /limits}.
 *
 * <p>Asked for rather than hardcoded, so that raising a limit on the site does not need a release of the mod. The
 * numbers are camelCase and in seconds (unlike the other answers). The values in {@link #DEFAULTS} are used only
 * when the request has not landed, and they are <b>not more generous than the live service</b>: a fallback that
 * promised more than production once made every send fail silently in a sibling mod. A test reads the recorded
 * live answer and requires it to equal the defaults, so a change on the site shows up as a red test.
 *
 * <p>The numbers come from a server the player may have pointed the mod at, so they are untrusted: a value that is
 * missing, not a positive whole number, or absurd is replaced by the default or cut to a ceiling. An unbounded
 * "maximum" once led a sibling mod to allocate until the game ran out of memory.
 *
 * @param storageTime  how long a list survives, counted from the last view
 * @param maxBytes     the largest request body, before compression
 * @param maxEntries   the most entries in one list of the request
 * @param maxPlugins   the most plugins
 * @param idLength     the length of a public id
 * @param fieldLengths the longest text per field, by the service's name for the field (for example {@code name})
 * @param server       limits of the {@code server} category
 */
public record Limits(Duration storageTime, long maxBytes, int maxEntries, int maxPlugins, int idLength,
                     Map<String, Integer> fieldLengths, Server server) {

    /** Ceilings for values read from the network. */
    static final long MAX_BYTES_CEILING = 16L * 1024 * 1024;
    static final int MAX_ENTRIES_CEILING = 10_000;
    static final int FIELD_LENGTH_CEILING = 16_384;
    static final int ID_LENGTH_CEILING = 64;
    static final Duration STORAGE_CEILING = Duration.ofDays(3650);

    /** The limits of the {@code server} category. */
    public record Server(int maxChannels, int maxNamespaces, int maxFeatureFlags, int maxMods, int maxCommandReplies,
                         int maxCommandRoots, Map<String, Integer> fieldLengths) {

        public Server {
            fieldLengths = Collections.unmodifiableMap(new LinkedHashMap<>(fieldLengths));
        }
    }

    public Limits {
        fieldLengths = Collections.unmodifiableMap(new LinkedHashMap<>(fieldLengths));
    }

    /** The published values of the service: 90 days, 1 MiB, 2000 entries. */
    public static final Limits DEFAULTS = new Limits(
            Duration.ofSeconds(7_776_000L), 1_048_576L, 2000, 500, 12,
            table("id", 128, "name", 160, "version", 64, "fileName", 160, "title", 80, "note", 500, "source", 64,
                    "license", 128, "author", 80, "authors", 16, "packTitle", 160, "packFormat", 16,
                    "shaderName", 160, "launcherBrand", 64, "launcherVersion", 64, "launcherInstance", 80,
                    "loaderVersion", 64, "mcVersion", 32, "pluginName", 128, "pluginVersion", 64),
            new Server(500, 200, 64, 1000, 4, 300,
                    table("brand", 64, "pingVersion", 64, "channelId", 128, "channelVersion", 32, "namespace", 64,
                            "featureFlag", 128, "modFileName", 160, "modId", 128, "modVersion", 64,
                            "commandText", 8192, "commandRoot", 64)));

    /** Days a list survives without being viewed - the number a player understands. */
    public long storageDays() {
        return storageTime.toDays();
    }

    /**
     * What is wrong with sending a body of {@code bodyBytes} whose longest list has {@code largestList} entries,
     * or empty when nothing is. The service would refuse it too; saying so here costs no request.
     */
    public Optional<ApiErrorCode> refusal(long bodyBytes, int largestList) {
        if (bodyBytes > maxBytes) {
            return Optional.of(ApiErrorCode.TOO_LARGE);
        }
        if (largestList > maxEntries) {
            return Optional.of(ApiErrorCode.TOO_MANY_ENTRIES);
        }
        return Optional.empty();
    }

    static Limits from(JsonObject json) {
        Limits d = DEFAULTS;
        JsonObject server = Json.object(json, "server");
        Server ds = d.server();

        return new Limits(
                seconds(json, "storageTime", d.storageTime()),
                number(json, "maxBytes", d.maxBytes(), MAX_BYTES_CEILING),
                (int) number(json, "maxEntries", d.maxEntries(), MAX_ENTRIES_CEILING),
                (int) number(json, "maxPlugins", d.maxPlugins(), MAX_ENTRIES_CEILING),
                (int) number(json, "idLength", d.idLength(), ID_LENGTH_CEILING),
                lengths(Json.object(json, "fieldLengths"), d.fieldLengths()),
                server == null ? ds : new Server(
                        (int) number(server, "maxChannels", ds.maxChannels(), MAX_ENTRIES_CEILING),
                        (int) number(server, "maxNamespaces", ds.maxNamespaces(), MAX_ENTRIES_CEILING),
                        (int) number(server, "maxFeatureFlags", ds.maxFeatureFlags(), MAX_ENTRIES_CEILING),
                        (int) number(server, "maxMods", ds.maxMods(), MAX_ENTRIES_CEILING),
                        (int) number(server, "maxCommandReplies", ds.maxCommandReplies(), MAX_ENTRIES_CEILING),
                        (int) number(server, "maxCommandRoots", ds.maxCommandRoots(), MAX_ENTRIES_CEILING),
                        lengths(Json.object(server, "fieldLengths"), ds.fieldLengths())));
    }

    /** A positive whole number, at most {@code ceiling}; anything else is {@code fallback}. */
    private static long number(JsonObject json, String key, long fallback, long ceiling) {
        long value = Json.longValue(json, key, fallback);

        return value <= 0 ? fallback : Math.min(value, ceiling);
    }

    private static Duration seconds(JsonObject json, String key, Duration fallback) {
        long value = Json.longValue(json, key, fallback.toSeconds());

        return value <= 0 ? fallback : Duration.ofSeconds(Math.min(value, STORAGE_CEILING.toSeconds()));
    }

    /** The per-field table: the default for a key the answer does not carry, the answer's value (cut) otherwise. */
    private static Map<String, Integer> lengths(JsonObject json, Map<String, Integer> defaults) {
        Map<String, Integer> out = new LinkedHashMap<>(defaults);

        if (json != null) {
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (defaults.containsKey(entry.getKey())) {
                    out.put(entry.getKey(), (int) number(json, entry.getKey(), defaults.get(entry.getKey()),
                            FIELD_LENGTH_CEILING));
                }
            }
        }

        return out;
    }

    private static Map<String, Integer> table(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }

        return out;
    }
}
