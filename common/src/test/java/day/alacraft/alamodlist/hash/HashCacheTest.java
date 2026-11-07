package day.alacraft.alamodlist.hash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashCacheTest {

    private static final String SHA1 = "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12";
    private static final String OTHER_SHA1 = "a9993e364706816aba3e25717850c26c9cd0d89d";

    @TempDir
    Path folder;

    private Path jar(String directory, String name) throws IOException {
        Path file = folder.resolve(directory).resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content");
        return file;
    }

    @Test
    void anEntryAnswersWhileTheSizeAndTheModificationTimeAreTheSame() throws IOException {
        Path jar = jar("mods", "a.jar");
        HashCache cache = HashCache.inMemory();
        cache.put(jar, 100, 5000, new HashCache.Entry(SHA1, null));

        assertEquals(Optional.of(new HashCache.Entry(SHA1, null)), cache.lookup(jar, 100, 5000, false));
        assertEquals(Optional.empty(), cache.lookup(jar, 101, 5000, false), "a different size is a different file");
        assertEquals(Optional.empty(), cache.lookup(jar, 100, 5001, false), "a different time is a different file");
        assertEquals(Optional.empty(), cache.lookup(jar.resolveSibling("b.jar"), 100, 5000, false));
    }

    @Test
    void anEntryWithoutTheFingerprintDoesNotAnswerARequestForIt() throws IOException {
        Path jar = jar("mods", "a.jar");
        HashCache cache = HashCache.inMemory();
        cache.put(jar, 100, 5000, new HashCache.Entry(SHA1, null));
        assertTrue(cache.lookup(jar, 100, 5000, false).isPresent());
        assertFalse(cache.lookup(jar, 100, 5000, true).isPresent());

        cache.put(jar, 100, 5000, new HashCache.Entry(SHA1, 12345L));
        assertEquals(12345L, cache.lookup(jar, 100, 5000, true).orElseThrow().murmur2());
    }

    @Test
    void whatIsSavedComesBackFromTheFile() throws IOException {
        Path jar = jar("mods", "a.jar");
        Path file = folder.resolve("data/hash-cache.json");
        HashCache first = HashCache.open(file);
        first.put(jar, 100, 5000, new HashCache.Entry(SHA1, 777L));
        first.save();

        HashCache second = HashCache.open(file);
        assertEquals(Optional.of(new HashCache.Entry(SHA1, 777L)), second.lookup(jar, 100, 5000, true));
    }

    /**
     * The file lives in a folder launchers export with a modpack. A full path in it would carry the name of the
     * account of this machine; only a digest of it may be there.
     */
    @Test
    void theFileHoldsNoPathAndNoFileName() throws IOException {
        Path jar = jar("someone-private-folder/mods", "very-distinct-name.jar");
        Path file = folder.resolve("data/hash-cache.json");
        HashCache cache = HashCache.open(file);
        cache.put(jar, 100, 5000, new HashCache.Entry(SHA1, null));
        cache.save();

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("someone-private-folder"), text);
        assertFalse(text.contains("very-distinct-name"), text);
        assertFalse(text.contains(folder.getFileName().toString()), text);
        assertFalse(text.contains("mods"), text);
        assertTrue(text.contains(SHA1), text);
    }

    @Test
    void onlyTheEntriesUsedByTheLatestSnapshotAreWritten() throws IOException {
        Path kept = jar("mods", "kept.jar");
        Path gone = jar("mods", "gone.jar");
        Path file = folder.resolve("hash-cache.json");
        HashCache first = HashCache.open(file);
        first.put(kept, 1, 1, new HashCache.Entry(SHA1, null));
        first.put(gone, 2, 2, new HashCache.Entry(OTHER_SHA1, null));
        first.save();

        HashCache second = HashCache.open(file);
        assertTrue(second.lookup(kept, 1, 1, false).isPresent());
        second.save();

        HashCache third = HashCache.open(file);
        assertTrue(third.lookup(kept, 1, 1, false).isPresent());
        assertFalse(third.lookup(gone, 2, 2, false).isPresent(), "a mod nobody asked about stays in the file");
    }

    @Test
    void aFileThatIsNotACacheIsIgnoredAndReplaced() throws IOException {
        Path jar = jar("mods", "a.jar");
        Path file = folder.resolve("hash-cache.json");
        Files.writeString(file, "{ not json at all");
        HashCache cache = HashCache.open(file);
        assertFalse(cache.lookup(jar, 100, 5000, false).isPresent());

        cache.put(jar, 100, 5000, new HashCache.Entry(SHA1, null));
        cache.save();
        assertTrue(HashCache.open(file).lookup(jar, 100, 5000, false).isPresent());
    }

    @Test
    void aWrongVersionOrABrokenEntryIsIgnored() throws IOException {
        Path jar = jar("mods", "a.jar");
        String key = HashCache.keyOf(jar);
        Path file = folder.resolve("hash-cache.json");

        Files.writeString(file, "{\"v\":2,\"entries\":{\"" + key + "\":{\"s\":100,\"m\":5000,\"sha1\":\"" + SHA1 + "\"}}}");
        assertFalse(HashCache.open(file).lookup(jar, 100, 5000, false).isPresent());

        Files.writeString(file, "{\"v\":1,\"entries\":{\"" + key + "\":{\"s\":100,\"m\":5000,\"sha1\":\"not-a-hash\"}}}");
        assertFalse(HashCache.open(file).lookup(jar, 100, 5000, false).isPresent());

        Files.writeString(file, "{\"v\":1,\"entries\":{\"" + key + "\":{\"s\":100,\"m\":5000,\"sha1\":\"" + SHA1 + "\"}}}");
        assertTrue(HashCache.open(file).lookup(jar, 100, 5000, false).isPresent());
    }

    @Test
    void aCacheThatCannotBeWrittenStaysInMemoryAndDoesNotThrow() throws IOException {
        Path jar = jar("mods", "a.jar");
        // A directory that cannot be created: its parent is a regular file.
        Path blocker = folder.resolve("blocker");
        Files.writeString(blocker, "x");
        HashCache cache = HashCache.open(blocker.resolve("inside/hash-cache.json"));
        cache.put(jar, 100, 5000, new HashCache.Entry(SHA1, null));
        cache.save();

        assertTrue(cache.lookup(jar, 100, 5000, false).isPresent());
    }
}
