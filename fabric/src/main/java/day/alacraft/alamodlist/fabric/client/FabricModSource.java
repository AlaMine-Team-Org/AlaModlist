package day.alacraft.alamodlist.fabric.client;

import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.ModSource;
import day.alacraft.alamodlist.source.RawMod;
import day.alacraft.alamodlist.source.Side;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.Person;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModSource} on Fabric: every container the loader knows, including the jars packed inside other jars and
 * the built-in ones (the game, the Java runtime).
 *
 * <p>Only a container that comes from a single path gets a file. A nested container is reported with its parent
 * and no file: reading its bytes would need the parent's zip file system, and nothing indexes a nested jar anyway.
 */
final class FabricModSource implements ModSource {

    /** The loader itself is reported, but as a built-in component: its version is in the environment. */
    private static final String LOADER_ID = "fabricloader";

    @Override
    public List<RawMod> mods() {
        List<RawMod> out = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            out.add(describe(container));
        }
        return out;
    }

    private static RawMod describe(ModContainer container) {
        ModMetadata meta = container.getMetadata();
        ModOrigin origin = container.getOrigin();
        boolean nested = origin.getKind() == ModOrigin.Kind.NESTED;

        Path file = null;
        if (origin.getKind() == ModOrigin.Kind.PATH) {
            List<Path> paths = origin.getPaths();
            if (paths.size() == 1) {
                file = paths.get(0);
            }
        }

        List<String> authors = new ArrayList<>();
        for (Person person : meta.getAuthors()) {
            authors.add(person.getName());
        }

        return new RawMod(
                meta.getId(),
                meta.getName(),
                meta.getVersion().getFriendlyString(),
                kind(meta, nested),
                nested ? origin.getParentModId() : null,
                Side.declared(meta.getEnvironment().name()),
                file,
                meta.getLicense().isEmpty() ? null : String.join(", ", meta.getLicense()),
                authors);
    }

    private static ModKind kind(ModMetadata meta, boolean nested) {
        if ("builtin".equals(meta.getType()) || LOADER_ID.equals(meta.getId())) {
            return ModKind.BUILTIN;
        }
        if (nested || hasLibraryBadge(meta)) {
            return ModKind.LIBRARY;
        }
        return ModKind.MOD;
    }

    /** Mod Menu's convention: {@code "custom": {"modmenu": {"badges": ["library"]}}}. A hint, not a rule. */
    private static boolean hasLibraryBadge(ModMetadata meta) {
        if (!meta.containsCustomValue("modmenu")) {
            return false;
        }
        CustomValue menu = meta.getCustomValue("modmenu");
        if (menu.getType() != CustomValue.CvType.OBJECT) {
            return false;
        }
        CustomValue badges = menu.getAsObject().get("badges");
        if (badges == null || badges.getType() != CustomValue.CvType.ARRAY) {
            return false;
        }
        for (CustomValue badge : badges.getAsArray()) {
            if (badge.getType() == CustomValue.CvType.STRING && "library".equals(badge.getAsString())) {
                return true;
            }
        }
        return false;
    }
}
