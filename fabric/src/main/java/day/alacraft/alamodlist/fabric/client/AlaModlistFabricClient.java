package day.alacraft.alamodlist.fabric.client;

import day.alacraft.alamodlist.client.AlaModlistClient;
import day.alacraft.alamodlist.client.ClientHooks;
import day.alacraft.alamodlist.client.ModKeyMappings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Fabric entrypoint of a client-only mod - the only one (no {@code ModInitializer}). An adapter and nothing
 * else: its counterpart is {@code day.alacraft.alamodlist.neoforge.client.AlaModlistNeoForgeClient}, and the
 * two take the same steps in the same order. {@code tools/loader_parity_check.py} fails when one of them grows a
 * step the other lacks.
 */
public class AlaModlistFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AlaModlistClient.init(new FabricClientPlatform());
        for (KeyMapping mapping : ModKeyMappings.ALL) {
            KeyMappingHelper.registerKeyMapping(mapping);
        }
        registerHooks();
    }

    /** One subscription per {@link ClientHooks} method. */
    private static void registerHooks() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientHooks::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> ClientHooks.registerCommands(dispatcher));
    }
}
