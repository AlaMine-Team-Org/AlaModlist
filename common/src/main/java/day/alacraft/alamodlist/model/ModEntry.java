package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

import java.util.List;

/**
 * One installed mod, library or built-in component.
 *
 * @param kind     {@code mod}, {@code library} or {@code builtin}
 * @param side     {@code client}, {@code server} or {@code universal}
 * @param fileName the bare name of the jar, never a path
 * @param fileSize the size in bytes
 */
public record ModEntry(
        String id,
        String name,
        String version,
        String kind,
        String parentId,
        String side,
        String fileName,
        Long fileSize,
        Hashes hashes,
        String license,
        List<String> authors) {

    public ModEntry {
        id = WireSchema.clean(ModEntry.class, "id", id);
        name = WireSchema.clean(ModEntry.class, "name", name);
        version = WireSchema.clean(ModEntry.class, "version", version);
        kind = WireSchema.clean(ModEntry.class, "kind", kind);
        parentId = WireSchema.clean(ModEntry.class, "parent_id", parentId);
        side = WireSchema.clean(ModEntry.class, "side", side);
        fileName = WireSchema.clean(ModEntry.class, "file_name", fileName);
        fileSize = WireSchema.clean(ModEntry.class, "file_size", fileSize);
        hashes = WireSchema.clean(ModEntry.class, "hashes", hashes);
        license = WireSchema.clean(ModEntry.class, "license", license);
        authors = WireSchema.clean(ModEntry.class, "authors", authors);
    }
}
