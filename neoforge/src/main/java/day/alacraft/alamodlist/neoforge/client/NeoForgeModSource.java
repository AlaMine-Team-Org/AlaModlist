package day.alacraft.alamodlist.neoforge.client;

import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.ModSource;
import day.alacraft.alamodlist.source.RawMod;
import day.alacraft.alamodlist.source.Side;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ModSource} on NeoForge: the mods from {@link ModList}, plus the library jars the player put in the
 * {@code mods} folder, which are not mods and so are only in {@link LoadingModList}.
 *
 * <p>Library files outside the {@code mods} folder (the loader's own, the game's) are not listed: they are not
 * something the player installed. NeoForge has no per-mod "runs on" declaration, so {@code side} stays unset.
 */
final class NeoForgeModSource implements ModSource {

    /** The game and the loader are reported as built-in components: their versions are in the environment. */
    private static final Set<String> BUILT_IN = Set.of("minecraft", "neoforge");

    @Override
    public List<RawMod> mods() {
        List<RawMod> out = new ArrayList<>();
        Set<Path> described = new LinkedHashSet<>();
        for (IModInfo info : ModList.get().getMods()) {
            IModFile file = info.getOwningFile().getFile();
            described.add(file.getFilePath());
            out.add(describe(info, file));
        }
        addLibraries(out, described);
        return out;
    }

    private static RawMod describe(IModInfo info, IModFile file) {
        boolean builtIn = BUILT_IN.contains(info.getModId());
        String license = info.getOwningFile().getLicense();
        return new RawMod(
                info.getModId(),
                info.getDisplayName(),
                info.getVersion().toString(),
                builtIn ? ModKind.BUILTIN : ModKind.MOD,
                null,
                // NeoForge has no per-mod declaration of where a mod runs.
                Side.declared(null),
                builtIn ? null : file.getFilePath(),
                license == null || license.isBlank() ? null : license,
                authors(info));
    }

    /** The {@code authors} entry of the mod: a string of names, or (in some files) a list of them. */
    private static List<String> authors(IModInfo info) {
        Optional<Object> value = info.getConfig().getConfigElement("authors");
        List<String> names = new ArrayList<>();
        if (value.isPresent() && value.get() instanceof String text) {
            for (String name : text.split(",")) {
                if (!name.isBlank()) {
                    names.add(name.strip());
                }
            }
        } else if (value.isPresent() && value.get() instanceof Collection<?> items) {
            for (Object item : items) {
                if (item instanceof String name && !name.isBlank()) {
                    names.add(name.strip());
                }
            }
        }
        return names;
    }

    private static void addLibraries(List<RawMod> out, Set<Path> described) {
        Path modsFolder = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        for (IModFileInfo info : FMLLoader.getCurrent().getLoadingModList().getPlugins()) {
            IModFile file = info.getFile();
            Path path = file.getFilePath();
            if (path == null || described.contains(path) || !path.toAbsolutePath().normalize().startsWith(modsFolder)) {
                continue;
            }
            String license = info.getLicense();
            String id = file.getId();
            if (id == null || id.isBlank()) {
                id = path.getFileName().toString().replaceFirst("(?i)\\.jar$", "");
            }
            out.add(new RawMod(id, null, info.versionString(), ModKind.LIBRARY, null, Side.declared(null), path,
                    license == null || license.isBlank() ? null : license, List.of()));
        }
    }
}
