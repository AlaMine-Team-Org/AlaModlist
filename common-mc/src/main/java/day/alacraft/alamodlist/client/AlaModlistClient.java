package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.BuildInfo;
import day.alacraft.alamodlist.client.screen.KnobConfigScreen;
import day.alacraft.alamodlist.config.ClientConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The shared start-up sequence of a client-only mod, run by the one entrypoint of each loader.
 *
 * <p>The config directory comes from the seam installed in the first line, so the entrypoint passes only
 * the platform: a client mod has no second entrypoint whose order could matter.
 */
public final class AlaModlistClient {

    private AlaModlistClient() {
    }

    /** Step one of both client entrypoints: install the loader seam and load the client config. */
    public static void init(ClientPlatform platform) {
        ClientPlatform.SEAM.install(platform);
        ClientConfig.config().load(platform.configDirectory().resolve(ClientConfig.FILE_NAME));
        AlaModlist.LOGGER.info("{} {} on {}{}", AlaModlist.MOD_NAME, BuildInfo.describe(), platform.loaderName(),
                platform.isDevelopmentEnvironment() ? " (development environment)" : "");
    }

    /** The config screen, for Mod Menu on Fabric and the mod list on NeoForge. */
    public static Screen configScreen(Screen parent) {
        return new KnobConfigScreen(parent, ClientConfig.config(),
                Component.translatable("config.alamodlist.title"), "config.alamodlist.");
    }
}
