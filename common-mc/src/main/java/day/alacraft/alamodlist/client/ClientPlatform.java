package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.source.ModSource;
import day.alacraft.alamodlist.util.Seam;

import java.nio.file.Path;

/**
 * What the shared client code needs from the loader. A client-only mod has exactly one seam, and this is
 * it: installed first by the client entrypoint of each loader ({@link AlaModlistClient#init}).
 */
public interface ClientPlatform {

    Seam<ClientPlatform> SEAM = new Seam<>("ClientPlatform");

    static ClientPlatform get() {
        return SEAM.get();
    }

    /** {@code "fabric"} or {@code "neoforge"}; for logs and the version command, never for branching. */
    String loaderName();

    /** The loader's own version, or null if it cannot be read. */
    String loaderVersion();

    /** The list of installed mods and libraries, read from the loader. */
    ModSource modSource();

    /** The game's config directory: settings files (a {@code @Knob} class) live here. */
    Path configDirectory();

    /** The game directory (the one that holds {@code mods/}, {@code saves/} and {@code logs/}). */
    Path gameDirectory();

    /**
     * The mod's own data directory, {@code <game directory>/<mod id>}: next to the mod's other data, outside
     * {@code config/}. The home of a {@code store.SecretStore} file (ADR-036) - a secret is not a setting,
     * and {@code config/} is what launchers export with a modpack. The directory is not created here.
     */
    default Path dataDirectory() {
        return gameDirectory().resolve(AlaModlist.MOD_ID);
    }

    /** Whether the game runs from a development environment (Gradle run, IDE) and not from a player's install. */
    boolean isDevelopmentEnvironment();
}
