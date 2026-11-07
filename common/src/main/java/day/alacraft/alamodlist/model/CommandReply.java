package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * The server's reply to a command the player ran from the mod, as flat text.
 *
 * @param command one of {@code plugins}, {@code version}, {@code about}, {@code velocity_plugins}, {@code neoforge_mods}
 * @param status  {@code ok}, {@code denied}, {@code unknown_command} or {@code timeout}
 */
public record CommandReply(String command, String text, String status) {

    public CommandReply {
        command = WireSchema.clean(CommandReply.class, "command", command);
        text = WireSchema.clean(CommandReply.class, "text", text);
        status = WireSchema.clean(CommandReply.class, "status", status);
    }
}
