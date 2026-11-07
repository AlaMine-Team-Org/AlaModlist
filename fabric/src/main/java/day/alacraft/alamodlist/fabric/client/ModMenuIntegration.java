package day.alacraft.alamodlist.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import day.alacraft.alamodlist.client.AlaModlistClient;

/**
 * The config-screen button in Mod Menu. Mod Menu finds this class through the {@code modmenu}
 * entrypoint in fabric.mod.json; without Mod Menu installed nothing ever loads it, so the mod needs no
 * {@code isModLoaded} check and no dependency on it.
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AlaModlistClient::configScreen;
    }
}
