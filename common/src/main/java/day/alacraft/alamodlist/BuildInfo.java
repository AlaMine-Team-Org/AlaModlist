package day.alacraft.alamodlist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Which build of the mod this is, read from a stamp baked into the jar.
 *
 * <p>A version alone cannot answer "which build is this?" during development, where many builds share
 * one version number - so the commit hash and the commit time travel with it. Both come from the commit,
 * never from the clock (see build-logic), so two builds of one commit carry the same stamp and produce
 * the same jar.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/" + AlaModlist.MOD_ID + ".build.json";

    /** What every value reads as when the stamp is missing - a build from the IDE or a unit test. */
    public static final String UNKNOWN = "dev";

    private static String version = UNKNOWN;
    private static String gitHash = UNKNOWN;
    private static String commitTime = UNKNOWN;

    static {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                JsonObject json = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                version = read(json, "version");
                gitHash = read(json, "git");
                commitTime = read(json, "committed");
            }
        } catch (Exception e) {
            // A missing or unreadable stamp must never stop the mod from loading - it only costs
            // traceability, and "dev" says exactly that.
            AlaModlist.LOGGER.warn("Could not read the build stamp; reporting this build as '{}'.", UNKNOWN);
        }
    }

    private BuildInfo() {
    }

    public static String version() {
        return version;
    }

    public static String gitHash() {
        return gitHash;
    }

    public static String commitTime() {
        return commitTime;
    }

    /** One line for logs and the version command: {@code 0.1.0 (a1b2c3d, 2026-09-11T12:00:00+03:00)}. */
    public static String describe() {
        return version + " (" + gitHash + ", " + commitTime + ")";
    }

    private static String read(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            String value = json.get(key).getAsString().trim();
            // An unexpanded "${...}" means the resource was read from the source tree, not a build.
            if (!value.isEmpty() && !value.startsWith("${")) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
