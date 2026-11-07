package day.alacraft.alamodlist.assemble;

import day.alacraft.alamodlist.model.EnvironmentInfo;

import java.util.Locale;

/**
 * The {@code environment} category: the game, the loader, the Java runtime and the family of the operating
 * system. The version of the operating system is deliberately not here: together with the rest it would single
 * out a machine.
 */
public final class EnvironmentAssembler {

    private EnvironmentAssembler() {
    }

    /**
     * @param mcVersion     the game version, such as {@code 26.3}
     * @param protocol      the network protocol number of that version
     * @param loader        {@code fabric} or {@code neoforge}
     * @param loaderVersion the loader's version, or null if it cannot be read
     */
    public static EnvironmentInfo create(String mcVersion, int protocol, String loader, String loaderVersion) {
        return create(mcVersion, protocol, loader, loaderVersion, Runtime.version().feature(),
                System.getProperty("os.name"));
    }

    /** The same with the runtime facts given, for tests. */
    static EnvironmentInfo create(String mcVersion, int protocol, String loader, String loaderVersion,
                                  int javaMajor, String osName) {
        return new EnvironmentInfo(mcVersion, protocol, loader, loaderVersion, javaMajor, osFamily(osName));
    }

    /** {@code windows}, {@code macos} or {@code linux} from the {@code os.name} property; null for anything else. */
    static String osFamily(String osName) {
        if (osName == null) {
            return null;
        }
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return "windows";
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return "macos";
        }
        if (name.startsWith("linux")) {
            return "linux";
        }
        return null;
    }
}
