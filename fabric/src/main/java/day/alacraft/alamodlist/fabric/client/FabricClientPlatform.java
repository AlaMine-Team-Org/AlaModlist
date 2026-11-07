package day.alacraft.alamodlist.fabric.client;

import day.alacraft.alamodlist.client.ClientPlatform;
import day.alacraft.alamodlist.source.ModSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;

/** {@link ClientPlatform} on Fabric. */
final class FabricClientPlatform implements ClientPlatform {

    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String loaderVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getVersion().getFriendlyString())
                .orElse(null);
    }

    @Override
    public ModSource modSource() {
        return new FabricModSource();
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
