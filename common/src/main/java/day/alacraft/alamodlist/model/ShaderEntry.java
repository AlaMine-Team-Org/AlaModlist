package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/** One shader pack and whether it is the active one. */
public record ShaderEntry(String name, Boolean active) {

    public ShaderEntry {
        name = WireSchema.clean(ShaderEntry.class, "name", name);
        active = WireSchema.clean(ShaderEntry.class, "active", active);
    }
}
