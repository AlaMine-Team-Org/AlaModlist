package day.alacraft.alamodlist.config;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.api.ModlistApi;

import java.util.List;

/**
 * Client-side settings: {@code config/alamodlist-client.json}. Loaded only by the client entrypoints
 * and edited from the config screen (Mod Menu on Fabric, the mod list on NeoForge).
 */
@Section(id = "display", doc = "How the mod shows things to this player.")
@Section(id = "collect", doc = "What the mod reads when it builds the list.")
@Section(id = "network", doc = "Where the lists are sent.")
public final class ClientConfig {

    public static final String FILE_NAME = AlaModlist.MOD_ID + "-client.json";

    public static final int SCHEMA_VERSION = 1;

    @Knob(section = "display", doc = "Show short messages of the mod above the hotbar (true) or in chat (false).")
    public static boolean answerInActionBar = true;

    @Knob(section = "collect", doc = "Also compute the CurseForge file fingerprint of every jar. Off by default: it reads each jar a second time.")
    public static boolean computeMurmur2 = false;

    @Knob(section = "network", doc = "The address of the site the lists are sent to. Keep the default unless you run your own copy of the site, for example a local one while developing. It must be an http or https address.")
    public static String apiBaseUrl = ModlistApi.DEFAULT_BASE_URL;

    /** Must stay BELOW the knobs: it captures each compiled default by reading its field. */
    private static final KnobConfig CONFIG = KnobConfig.of(ClientConfig.class, SCHEMA_VERSION, List.of());

    private ClientConfig() {
    }

    public static KnobConfig config() {
        return CONFIG;
    }
}
