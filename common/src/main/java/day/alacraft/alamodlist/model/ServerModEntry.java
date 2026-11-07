package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * One jar of the server, from the reply to the NeoForge mods command: its bare file name and the first mod in it.
 *
 * @param modsInFile how many mods the jar holds
 */
public record ServerModEntry(String fileName, String id, String version, Integer modsInFile) {

    public ServerModEntry {
        fileName = WireSchema.clean(ServerModEntry.class, "file_name", fileName);
        id = WireSchema.clean(ServerModEntry.class, "id", id);
        version = WireSchema.clean(ServerModEntry.class, "version", version);
        modsInFile = WireSchema.clean(ServerModEntry.class, "mods_in_file", modsInFile);
    }
}
