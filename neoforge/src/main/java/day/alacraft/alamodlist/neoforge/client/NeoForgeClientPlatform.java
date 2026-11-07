package day.alacraft.alamodlist.neoforge.client;

import day.alacraft.alamodlist.client.ClientPlatform;
import day.alacraft.alamodlist.source.ModSource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** {@link ClientPlatform} on NeoForge. */
final class NeoForgeClientPlatform implements ClientPlatform {

    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String loaderVersion() {
        return ModList.get().getMods().stream()
                .filter(info -> "neoforge".equals(info.getModId()))
                .map(info -> info.getVersion().toString())
                .findFirst()
                .orElse(null);
    }

    @Override
    public ModSource modSource() {
        return new NeoForgeModSource();
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }
}
