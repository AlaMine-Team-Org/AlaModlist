package day.alacraft.alamodlist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity of the mod, in the one module that knows nothing about Minecraft.
 *
 * <p>These constants are the only place the mod id is spelled in Java. Everything else - resource
 * paths, registry ids, the config file name - is derived from {@link #MOD_ID}, so a rename is one line
 * here plus gradle.properties.
 */
public final class AlaModlist {

    /** The mod id: registry namespace, resource folder, config file name, command name. */
    public static final String MOD_ID = "alamodlist";

    /** The display name, as players see it in the mod list. */
    public static final String MOD_NAME = "Ala Modlist";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private AlaModlist() {
    }
}
