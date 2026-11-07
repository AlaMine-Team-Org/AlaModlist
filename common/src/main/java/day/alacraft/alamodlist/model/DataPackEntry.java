package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/** One data pack of a single-player world. */
public record DataPackEntry(String id, Boolean enabled) {

    public DataPackEntry {
        id = WireSchema.clean(DataPackEntry.class, "id", id);
        enabled = WireSchema.clean(DataPackEntry.class, "enabled", enabled);
    }
}
