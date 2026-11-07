package day.alacraft.alamodlist.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One installed component as a loader reports it: what the loader knows, before anything is hashed or cleaned.
 *
 * <p>This is a raw type, not a wire type: it holds a {@link Path}, which must never reach the request. The
 * assembler turns it into a {@code ModEntry}, keeping only the bare file name.
 *
 * @param parentId the id of the mod this one is packed inside (a nested jar), or null
 * @param side     what the mod declares, or null when the loader has no such notion
 * @param file     the jar on disk, or null when there is none to read (a nested jar, a built-in component, a
 *                 folder in a development environment)
 * @param license  the license text as the mod wrote it, or null
 * @param authors  author names, possibly empty
 */
public record RawMod(
        String id,
        String name,
        String version,
        ModKind kind,
        String parentId,
        Side side,
        Path file,
        String license,
        List<String> authors) {

    public RawMod {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    /**
     * Whether the component lives inside another mod's jar. A nested jar is not a file on its own: it is not
     * indexed by the sites that match files, so it is never hashed.
     */
    public boolean isNested() {
        return parentId != null;
    }
}
