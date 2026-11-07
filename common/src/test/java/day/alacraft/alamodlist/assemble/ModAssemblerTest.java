package day.alacraft.alamodlist.assemble;

import day.alacraft.alamodlist.hash.CancelToken;
import day.alacraft.alamodlist.hash.HashCache;
import day.alacraft.alamodlist.hash.ModHashing;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.source.ModKind;
import day.alacraft.alamodlist.source.RawMod;
import day.alacraft.alamodlist.source.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModAssemblerTest {

    @TempDir
    Path folder;

    private Path jar(String directory, String name, int size) throws IOException {
        Path file = folder.resolve(directory).resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
        return file;
    }

    private static RawMod mod(String id, ModKind kind, String parent, Path file) {
        return new RawMod(id, "Name " + id, "1.0", kind, parent, Side.UNIVERSAL, file, "MIT", List.of("Author"));
    }

    private static ModAssembler.Assembled assemble(List<RawMod> mods) {
        ModHashing.Outcome outcome = ModHashing.run(mods, HashCache.inMemory(), false, 2, new Progress(), new CancelToken());
        return ModAssembler.assemble(mods, outcome.facts());
    }

    /** The entry names the jar and nothing around it; a path in this field is a leak of the user's directories. */
    @Test
    void fileNameIsTheBareNameOfTheJarNeverAPath() throws IOException {
        Path file = jar("deeply/nested/private-account/mods", "Some Mod-1.0+26.3.jar", 1234);

        ModEntry entry = assemble(List.of(mod("somemod", ModKind.MOD, null, file))).entries().get(0);

        assertEquals("Some Mod-1.0+26.3.jar", entry.fileName());
        assertEquals(1234L, entry.fileSize());
        assertEquals(40, entry.hashes().sha1().length());
    }

    @Test
    void aBuiltInComponentHasNoFileAndNoHash() throws IOException {
        Path client = jar("versions", "client.jar", 100);

        ModEntry entry = assemble(List.of(mod("minecraft", ModKind.BUILTIN, null, client))).entries().get(0);

        assertEquals("builtin", entry.kind());
        assertNull(entry.fileName());
        assertNull(entry.fileSize());
        assertNull(entry.hashes());
    }

    @Test
    void aNestedJarKeepsItsParentAndGetsNoFileOrHash() throws IOException {
        Path outer = jar("mods", "outer.jar", 100);
        Path inner = jar("cache", "inner.jar", 100);

        ModAssembler.Assembled assembled = assemble(List.of(
                mod("outer", ModKind.MOD, null, outer),
                mod("inner", ModKind.LIBRARY, "outer", inner)));

        ModEntry nested = assembled.entries().stream().filter(entry -> entry.id().equals("inner")).findFirst().orElseThrow();
        assertEquals("outer", nested.parentId());
        assertEquals("library", nested.kind());
        assertNull(nested.fileName());
        assertNull(nested.hashes());
    }

    @Test
    void entriesAreOrderedByIdAndTheFirstOfADuplicateWins() {
        ModAssembler.Assembled assembled = assemble(List.of(
                new RawMod("zeta", "Zeta", "1", ModKind.MOD, null, null, null, null, List.of()),
                new RawMod("Alpha", "Alpha", "1", ModKind.MOD, null, null, null, null, List.of()),
                new RawMod("beta", "First", "1", ModKind.MOD, null, null, null, null, List.of()),
                new RawMod("beta", "Second", "2", ModKind.MOD, null, null, null, null, List.of())));

        assertEquals(List.of("Alpha", "beta", "zeta"), assembled.entries().stream().map(ModEntry::id).toList());
        assertEquals("First", assembled.entries().get(1).name());
        assertEquals(0, assembled.skipped());
    }

    @Test
    void anIdThatCannotBeSentCostsThatComponentOnly() {
        ModAssembler.Assembled assembled = assemble(List.of(
                new RawMod("fine", "Fine", "1", ModKind.MOD, null, null, null, null, List.of()),
                new RawMod("x".repeat(200), "Long", "1", ModKind.MOD, null, null, null, null, List.of())));

        assertEquals(List.of("fine"), assembled.entries().stream().map(ModEntry::id).toList());
        assertEquals(1, assembled.skipped());
    }

    @Test
    void tooManyAuthorsAreCutAndDirtyTextIsCleaned() {
        List<String> authors = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            authors.add("Author " + i);
        }
        RawMod mod = new RawMod("m", "Line one\nline two\u0007", " 1.0 ", ModKind.MOD, null, null, null, "MIT", authors);

        ModEntry entry = assemble(List.of(mod)).entries().get(0);

        assertEquals(16, entry.authors().size());
        assertTrue(!entry.name().contains("\n") && !entry.name().contains("\u0007"), entry.name());
    }

    @Test
    void sideAndKindAreTheWireWords() {
        ModEntry entry = assemble(List.of(new RawMod("m", "M", "1", ModKind.LIBRARY, null, Side.CLIENT, null, null, null)))
                .entries().get(0);
        assertEquals("library", entry.kind());
        assertEquals("client", entry.side());
        assertEquals(List.of(), entry.authors());
    }

    @Test
    void aComponentWhoseFileCouldNotBeReadStillGetsItsNameButNoHash() throws IOException {
        Path file = jar("mods", "a.jar", 10);
        RawMod mod = mod("a", ModKind.MOD, null, file);

        ModAssembler.Assembled assembled = ModAssembler.assemble(List.of(mod), Map.of());

        ModEntry entry = assembled.entries().get(0);
        assertEquals("a.jar", entry.fileName());
        assertNull(entry.fileSize());
        assertNull(entry.hashes());
    }
}
