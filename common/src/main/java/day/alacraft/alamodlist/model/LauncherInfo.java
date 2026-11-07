package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * The launcher the game was started from.
 *
 * @param instanceName the name the player gave the instance, where the launcher tells it
 */
public record LauncherInfo(String brand, String version, String instanceName) {

    public LauncherInfo {
        brand = WireSchema.clean(LauncherInfo.class, "brand", brand);
        version = WireSchema.clean(LauncherInfo.class, "version", version);
        instanceName = WireSchema.clean(LauncherInfo.class, "instance_name", instanceName);
    }
}
