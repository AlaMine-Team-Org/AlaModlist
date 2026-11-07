package day.alacraft.alamodlist;

import net.minecraft.resources.Identifier;

/**
 * The one shared helper of a client-only mod's root package: ids in the mod's namespace.
 *
 * <p>A client mod has no server half, so there is no shared start-up sequence here - the loader
 * entrypoints call {@link day.alacraft.alamodlist.client.AlaModlistClient#init} directly.
 */
public final class AlaModlistMod {

    private AlaModlistMod() {
    }

    /** An id in this mod's namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(AlaModlist.MOD_ID, path);
    }
}
