package day.alacraft.alamodlist.assemble;

import day.alacraft.alamodlist.hash.ModHashing;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.source.RawMod;
import day.alacraft.alamodlist.wire.WireSchema;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns what the loader reported and what was hashed into the entries of the request: one pure function.
 *
 * <p>This is where a {@link Path} stops being a path. The entry carries the bare name of the jar, taken with
 * {@code getFileName()}, and nothing of the directory it lies in.
 */
public final class ModAssembler {

    private static final int MAX_AUTHORS = WireSchema.object("mod").field("authors").max();

    private ModAssembler() {
    }

    /**
     * @param entries the entries, ordered by id
     * @param skipped components left out because their id cannot be sent (too long, or not clean)
     */
    public record Assembled(List<ModEntry> entries, int skipped) {
    }

    /**
     * @param facts the hashing stage's results, by {@link ModHashing#keyOf(Path)}
     */
    public static Assembled assemble(List<RawMod> raw, Map<Path, ModHashing.FileFacts> facts) {
        List<RawMod> ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparing((RawMod mod) -> mod.id().toLowerCase(Locale.ROOT)).thenComparing(RawMod::id));

        List<ModEntry> entries = new ArrayList<>(ordered.size());
        Set<String> seen = new LinkedHashSet<>();
        int skipped = 0;
        for (RawMod mod : ordered) {
            if (!seen.add(mod.id())) {
                continue;
            }
            ModEntry entry = entry(mod, facts);
            if (entry == null) {
                skipped++;
            } else {
                entries.add(entry);
            }
        }
        return new Assembled(List.copyOf(entries), skipped);
    }

    /** The entry, or null if even its id is not sendable. A bad name or file name only costs that field. */
    private static ModEntry entry(RawMod mod, Map<Path, ModHashing.FileFacts> facts) {
        try {
            return build(mod, facts, true);
        } catch (IllegalArgumentException e) {
            try {
                return build(mod, facts, false);
            } catch (IllegalArgumentException again) {
                return null;
            }
        }
    }

    private static ModEntry build(RawMod mod, Map<Path, ModHashing.FileFacts> facts, boolean withFile) {
        ModHashing.FileFacts known = ModHashing.hashable(mod) ? facts.get(ModHashing.keyOf(mod.file())) : null;
        String fileName = null;
        Long fileSize = null;
        if (withFile && ModHashing.hashable(mod)) {
            fileName = mod.file().getFileName().toString();
            fileSize = known == null ? null : known.size();
        }
        List<String> authors = mod.authors().size() > MAX_AUTHORS ? mod.authors().subList(0, MAX_AUTHORS) : mod.authors();
        return new ModEntry(
                mod.id(),
                mod.name(),
                mod.version(),
                mod.kind().wire(),
                mod.parentId(),
                mod.side() == null ? null : mod.side().wire(),
                fileName,
                fileSize,
                known == null ? null : known.hashes(),
                mod.license(),
                authors);
    }
}
