package day.alacraft.alamodlist.neoforge.client;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.client.AlaModlistClient;
import day.alacraft.alamodlist.client.ClientHooks;
import day.alacraft.alamodlist.client.ModKeyMappings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entrypoint of a client-only mod - the only {@code @Mod} class, with {@code dist = CLIENT}, so a
 * dedicated server never even loads it. An adapter and nothing else: its counterpart is
 * {@code day.alacraft.alamodlist.fabric.client.AlaModlistFabricClient}, and the two take the same steps in
 * the same order. {@code tools/loader_parity_check.py} fails when one of them grows a step the other lacks.
 */
@Mod(value = AlaModlist.MOD_ID, dist = Dist.CLIENT)
public class AlaModlistNeoForgeClient {

    public AlaModlistNeoForgeClient(IEventBus modBus, ModContainer container) {
        AlaModlistClient.init(new NeoForgeClientPlatform());
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            for (KeyMapping mapping : ModKeyMappings.ALL) {
                event.register(mapping);
            }
        });
        registerHooks();
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parent) -> AlaModlistClient.configScreen(parent));
    }

    /** One subscription per {@link ClientHooks} method. */
    private static void registerHooks() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientHooks.onClientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> ClientHooks.registerCommands(event.getDispatcher()));
    }
}
