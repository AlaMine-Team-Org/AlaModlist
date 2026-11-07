package day.alacraft.alamodlist.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import day.alacraft.alamodlist.client.command.ClientCommandTree;
import net.minecraft.client.Minecraft;

/**
 * Every client event the mod reacts to - the only event facade of a client-only mod. Each client
 * entrypoint subscribes once per method; {@code tools/loader_parity_check.py} checks both do.
 *
 * <p>Adding a hook: a public static method here, then one subscription in {@code AlaModlistFabricClient}
 * and one in {@code AlaModlistNeoForgeClient}.
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    /** End of every client tick. */
    public static void onClientTick(Minecraft minecraft) {
        ModlistController.handleKeys(minecraft);
    }

    /**
     * Client command registration: {@code /alamodlist} and its short alias {@code /aml}. Generic in the source
     * type: Fabric's client commands run on a {@code FabricClientCommandSource}, NeoForge's on a
     * {@code CommandSourceStack}, and the tree needs neither - it reads the game through
     * {@code Minecraft.getInstance()}. Both loaders call this one method, so both get both names.
     */
    public static <S> void registerCommands(CommandDispatcher<S> dispatcher) {
        LiteralCommandNode<S> root = dispatcher.register(ClientCommandTree.<S>build());
        dispatcher.register(ClientCommandTree.<S>alias(root));
    }
}
