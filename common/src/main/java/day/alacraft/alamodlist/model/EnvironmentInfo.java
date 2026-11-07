package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * The game and its surroundings. No operating system version and no machine name: they identify a machine.
 *
 * @param loader   {@code fabric} or {@code neoforge}
 * @param osFamily {@code windows}, {@code macos} or {@code linux}
 */
public record EnvironmentInfo(
        String mcVersion,
        Integer protocol,
        String loader,
        String loaderVersion,
        Integer javaMajor,
        String osFamily) {

    public EnvironmentInfo {
        mcVersion = WireSchema.clean(EnvironmentInfo.class, "mc_version", mcVersion);
        protocol = WireSchema.clean(EnvironmentInfo.class, "protocol", protocol);
        loader = WireSchema.clean(EnvironmentInfo.class, "loader", loader);
        loaderVersion = WireSchema.clean(EnvironmentInfo.class, "loader_version", loaderVersion);
        javaMajor = WireSchema.clean(EnvironmentInfo.class, "java_major", javaMajor);
        osFamily = WireSchema.clean(EnvironmentInfo.class, "os_family", osFamily);
    }
}
