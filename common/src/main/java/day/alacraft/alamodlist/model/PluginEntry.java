package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * One server plugin.
 *
 * @param enabled whether the name was shown as enabled in the reply, when the reply says
 * @param origin  {@code reply} (the server answered the player's command) or {@code pasted}
 */
public record PluginEntry(String name, String version, Boolean enabled, String origin) {

    public PluginEntry {
        name = WireSchema.clean(PluginEntry.class, "name", name);
        version = WireSchema.clean(PluginEntry.class, "version", version);
        enabled = WireSchema.clean(PluginEntry.class, "enabled", enabled);
        origin = WireSchema.clean(PluginEntry.class, "origin", origin);
    }
}
