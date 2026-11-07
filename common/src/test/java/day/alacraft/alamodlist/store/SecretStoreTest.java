package day.alacraft.alamodlist.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretStoreTest {

    /** Looks like what it protects; every "never shown" assertion searches for it. */
    private static final String SECRET = "sk-live-SECRET-0123456789";

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("data").resolve(SecretStore.FILE_NAME);
    }

    @Test
    @DisplayName("nothing stored: empty answer, no problem, and a read creates no file")
    void emptyWhenAbsent() {
        SecretStore store = new SecretStore(file());

        assertEquals(Optional.empty(), store.get("account"));
        assertEquals(Optional.empty(), store.problem());
        assertFalse(Files.exists(file()));
    }

    @Test
    void storesReadsAndRemoves() {
        SecretStore store = new SecretStore(file());

        assertTrue(store.put("account", SECRET));
        assertTrue(store.put("delete-1", "d1"));

        assertEquals(Optional.of(SECRET), store.get("account"));
        assertEquals(Optional.of("d1"), new SecretStore(file()).get("delete-1"), "a second store reads it from disk");

        assertTrue(store.remove("delete-1"));
        assertEquals(Optional.empty(), new SecretStore(file()).get("delete-1"));
        assertEquals(Optional.of(SECRET), new SecretStore(file()).get("account"));
        assertTrue(store.remove("never-there"), "removing nothing is not a failure");
    }

    @Test
    @DisplayName("the file is owner-only, and so is the directory made for it")
    void filesAreOwnerOnly() throws IOException {
        SecretStore store = new SecretStore(file());

        store.put("account", SECRET);

        PrivateFilesTest.assertOwnerOnly(file());
        PrivateFilesTest.assertOwnerOnly(file().getParent());
        store.put("account", "second");
        PrivateFilesTest.assertOwnerOnly(file());
    }

    @Test
    @DisplayName("a directory that already existed is not restricted")
    void existingDirectoryIsLeftAlone() throws IOException {
        Path shared = Files.createDirectory(directory.resolve("shared"));
        java.util.Set<?> before = PrivateFilesTest.isPosix() ? Files.getPosixFilePermissions(shared) : null;

        new SecretStore(shared.resolve(SecretStore.FILE_NAME)).put("account", SECRET);

        if (before != null) {
            assertEquals(before, Files.getPosixFilePermissions(shared));
        }
    }

    @Test
    @DisplayName("a damaged file is not a crash: empty answers, a problem without the secret, file untouched")
    void damagedFileDoesNotCrash() throws IOException {
        Files.createDirectories(file().getParent());
        String damaged = "{\"version\": 1, \"secrets\": {\"account\": \"" + SECRET + "\"";   // cut off
        Files.writeString(file(), damaged);
        SecretStore store = new SecretStore(file());

        assertEquals(Optional.empty(), store.get("account"));
        String problem = store.problem().orElseThrow();

        assertTrue(problem.contains(SecretStore.FILE_NAME));
        assertFalse(problem.contains(SECRET), "a problem text quoted the file: " + problem);
        assertEquals(damaged, Files.readString(file()), "reading must not rewrite the file");
    }

    @Test
    @DisplayName("every shape that is not ours is unreadable, not a crash")
    void wrongShapesAreUnreadable() throws IOException {
        List<String> shapes = List.of(
                "", "   ", "null", "[]", "\"" + SECRET + "\"", "{}", "{\"version\": 1}",
                "{\"secrets\": {}}", "{\"version\": 1, \"secrets\": []}",
                "{\"version\": 1, \"secrets\": {\"a\": 5}}",
                "{\"version\": 1, \"secrets\": {\"a\": {\"b\": \"" + SECRET + "\"}}}",
                "{\"version\": 2, \"secrets\": {\"a\": \"b\"}}",
                "\u0000\u0001 not json " + SECRET);
        Files.createDirectories(file().getParent());
        for (String shape : shapes) {
            Files.writeString(file(), shape);
            SecretStore store = new SecretStore(file());

            assertEquals(Optional.empty(), store.get("a"), "shape: " + shape);
            assertTrue(store.problem().isPresent(), "no problem reported for: " + shape);
            assertFalse(store.problem().get().contains(SECRET), "problem quotes the file for: " + shape);
        }
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are unreadable too")
    void notUtf8() throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xFF});
        SecretStore store = new SecretStore(file());

        assertEquals(Optional.empty(), store.get("a"));
        assertTrue(store.problem().isPresent());
    }

    @Test
    @DisplayName("saving over an unreadable file keeps it as .unreadable first")
    void saveKeepsTheUnreadableFile() throws IOException {
        Files.createDirectories(file().getParent());
        String damaged = "{ half a file " + SECRET;
        Files.writeString(file(), damaged);
        SecretStore store = new SecretStore(file());

        assertTrue(store.put("account", "fresh"));

        assertEquals(Optional.empty(), store.problem());
        assertEquals(Optional.of("fresh"), new SecretStore(file()).get("account"));
        assertEquals(damaged, Files.readString(file().resolveSibling(SecretStore.FILE_NAME + ".unreadable")));
    }

    @Test
    @DisplayName("a file written by a newer version is left alone until the player saves")
    void newerVersionIsNotOverwrittenByReading() throws IOException {
        Files.createDirectories(file().getParent());
        String newer = "{\"version\": 2, \"secrets\": {\"account\": \"" + SECRET + "\"}}";
        Files.writeString(file(), newer);
        SecretStore store = new SecretStore(file());

        store.get("account");
        store.problem();
        store.remove("account");

        assertEquals(newer, Files.readString(file()));
    }

    @Test
    @DisplayName("when the disk cannot be written the secret lives in memory only, loudly, and nowhere else")
    void unwritableDiskFallsBackToMemory() throws IOException {
        // A regular file where the directory should be: creating the directory must fail.
        Path blocker = Files.writeString(directory.resolve("blocker"), "i am a file");
        Path impossible = blocker.resolve(SecretStore.FILE_NAME);
        SecretStore store = new SecretStore(impossible);

        assertFalse(store.put("account", SECRET), "put must say the value is not on disk");

        assertEquals(Optional.of(SECRET), store.get("account"), "the session still works");
        String problem = store.problem().orElseThrow();
        assertTrue(problem.contains("memory"));
        assertFalse(problem.contains(SECRET), "problem text carries the secret: " + problem);
        try (Stream<Path> siblings = Files.list(directory)) {
            assertEquals(List.of("blocker"), siblings.map(p -> p.getFileName().toString()).toList(),
                    "the fallback was written somewhere");
        }
        assertEquals("i am a file", Files.readString(blocker));

        assertFalse(store.remove("account"));
        assertEquals(Optional.empty(), store.get("account"), "a removal that could not be saved stays removed");
    }

    @Test
    @DisplayName("once the obstacle is gone the next save reaches the disk and the problem clears")
    void recoversWhenTheDiskDoes() throws IOException {
        Path blocker = Files.writeString(directory.resolve("blocker"), "i am a file");
        SecretStore store = new SecretStore(blocker.resolve(SecretStore.FILE_NAME));
        assertFalse(store.put("account", SECRET));

        Files.delete(blocker);

        assertTrue(store.put("account", SECRET));
        assertEquals(Optional.empty(), store.problem());
        assertEquals(Optional.of(SECRET), new SecretStore(blocker.resolve(SecretStore.FILE_NAME)).get("account"));
    }

    @Test
    @DisplayName("the secret is in neither toString nor an exception message")
    void valuesNeverLeakThroughText() {
        SecretStore store = new SecretStore(file());
        store.put("account", SECRET);

        assertFalse(store.toString().contains(SECRET));
        assertTrue(store.toString().contains(SecretStore.FILE_NAME));

        IllegalArgumentException emptyValue = assertThrows(IllegalArgumentException.class, () -> store.put("account", ""));
        IllegalArgumentException noName = assertThrows(IllegalArgumentException.class, () -> store.put(" ", SECRET));
        IllegalArgumentException nullValue = assertThrows(IllegalArgumentException.class, () -> store.put("account", null));
        for (IllegalArgumentException e : List.of(emptyValue, noName, nullValue)) {
            assertFalse(e.getMessage().contains(SECRET), e.getMessage());
        }
        assertEquals(Optional.of(SECRET), store.get("account"), "a rejected put changes nothing");
    }

    @Test
    @DisplayName("the stored file is JSON of the documented shape")
    void fileShape() throws IOException {
        new SecretStore(file()).put("account", SECRET);

        String text = Files.readString(file(), StandardCharsets.UTF_8);

        assertTrue(text.contains("\"version\": 1"), text);
        assertTrue(text.contains("\"secrets\""), text);
        assertTrue(text.endsWith("\n"));
    }
}
