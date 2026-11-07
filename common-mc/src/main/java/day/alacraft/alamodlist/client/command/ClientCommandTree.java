package day.alacraft.alamodlist.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.client.ModlistController;
import net.minecraft.client.Minecraft;

/**
 * The client command tree: {@code /alamodlist} and its short alias {@code /aml}, one tree for both loaders.
 *
 * <p>Written on raw Brigadier and generic in the source type {@code S}, because the loaders' client command
 * sources differ ({@code FabricClientCommandSource}, NeoForge's {@code CommandSourceStack}). No action reads
 * its source: they ask {@code Minecraft.getInstance()}, which is the same on both. Everything a player sees is
 * a translation key - a client mod has no operator commands whose output scripts read.
 *
 * <p>The alias is a redirect to the registered root, not a second tree: a subcommand added to the root is
 * there under both names, and the two cannot drift apart.
 */
public final class ClientCommandTree {

    /** The short name of the command. */
    public static final String ALIAS = "aml";

    private ClientCommandTree() {
    }

    /**
     * The tree to register with the loader's client command dispatcher.
     *
     * <p>{@code delete <id>} only asks; the deletion itself is behind {@code confirm}, so a mis-click on a chat line
     * cannot delete a list that other people may already have the link to. Only the public id is typed or clicked:
     * the delete token is never part of a command.
     */
    public static <S> LiteralArgumentBuilder<S> build() {
        return LiteralArgumentBuilder.<S>literal(AlaModlist.MOD_ID)
                .executes(open())
                .then(LiteralArgumentBuilder.<S>literal("list")
                        .executes(context -> ModlistController.list(Minecraft.getInstance())))
                .then(LiteralArgumentBuilder.<S>literal("delete")
                        .then(RequiredArgumentBuilder.<S, String>argument("id", StringArgumentType.word())
                                .executes(context -> ModlistController.askDelete(Minecraft.getInstance(),
                                        StringArgumentType.getString(context, "id")))
                                .then(LiteralArgumentBuilder.<S>literal("confirm")
                                        .executes(context -> ModlistController.confirmDelete(Minecraft.getInstance(),
                                                StringArgumentType.getString(context, "id"))))
                                .then(LiteralArgumentBuilder.<S>literal("cancel")
                                        .executes(context -> ModlistController.cancelDelete(Minecraft.getInstance())))));
    }

    /** The alias, to register right after {@link #build()} with the node the registration returned. */
    public static <S> LiteralArgumentBuilder<S> alias(LiteralCommandNode<S> root) {
        return LiteralArgumentBuilder.<S>literal(ALIAS).executes(open()).redirect(root);
    }

    private static <S> Command<S> open() {
        return context -> ModlistController.open(Minecraft.getInstance());
    }
}
