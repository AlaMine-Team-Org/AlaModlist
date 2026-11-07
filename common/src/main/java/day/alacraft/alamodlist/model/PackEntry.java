package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * One resource pack, in the order the game applies them.
 *
 * @param packFormat {@code major} or {@code major.minor}
 * @param source     {@code default}, {@code server} or {@code builtin}
 */
public record PackEntry(
        String id,
        String title,
        Boolean enabled,
        Integer order,
        String packFormat,
        String source,
        String sha1) {

    public PackEntry {
        id = WireSchema.clean(PackEntry.class, "id", id);
        title = WireSchema.clean(PackEntry.class, "title", title);
        enabled = WireSchema.clean(PackEntry.class, "enabled", enabled);
        order = WireSchema.clean(PackEntry.class, "order", order);
        packFormat = WireSchema.clean(PackEntry.class, "pack_format", packFormat);
        source = WireSchema.clean(PackEntry.class, "source", source);
        sha1 = WireSchema.clean(PackEntry.class, "sha1", sha1);
    }
}
