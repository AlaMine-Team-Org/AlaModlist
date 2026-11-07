package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * Counts for one registry namespace the server synchronised - how many entries, tags and dimensions it adds,
 * and whether this client has a mod by that id. Never the entries themselves.
 */
public record NamespaceEntry(
        String namespace,
        Integer registryEntries,
        Integer tagCount,
        Integer dimensions,
        Boolean clientHasMod) {

    public NamespaceEntry {
        namespace = WireSchema.clean(NamespaceEntry.class, "namespace", namespace);
        registryEntries = WireSchema.clean(NamespaceEntry.class, "registry_entries", registryEntries);
        tagCount = WireSchema.clean(NamespaceEntry.class, "tag_count", tagCount);
        dimensions = WireSchema.clean(NamespaceEntry.class, "dimensions", dimensions);
        clientHasMod = WireSchema.clean(NamespaceEntry.class, "client_has_mod", clientHasMod);
    }
}
