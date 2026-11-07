package day.alacraft.alamodlist.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("alamodlist").resolve(UploadHistory.FILE_NAME);
    }

    private static UploadRecord record(String id, Instant at) {
        return new UploadRecord(id, "https://alacraft.day/en/modlists/" + id, at, "Title " + id, 3, 2, 1, 0, 4);
    }

    @Test
    void newestFirstAndItSurvivesARestart() {
        UploadHistory history = new UploadHistory(file());
        history.add(record("older00000a", NOW.minus(Duration.ofHours(2))));
        history.add(record("newer00000b", NOW.minus(Duration.ofHours(1))));
        history.add(record("oldest0000c", NOW.minus(Duration.ofHours(3))));

        assertEquals("newer00000b", history.last().orElseThrow().id());
        assertEquals(List.of("newer00000b", "older00000a", "oldest0000c"),
                history.recent(10).stream().map(UploadRecord::id).toList());

        UploadHistory reloaded = new UploadHistory(file());
        assertEquals(history.recent(10), reloaded.recent(10), "every field comes back, in the same order");
        assertEquals(3, reloaded.size());
        assertEquals(4, reloaded.findById("older00000a").orElseThrow().plugins());
    }

    @Test
    void anEntryWithTheSameIdReplacesTheOldOne() {
        UploadHistory history = new UploadHistory(file());
        history.add(record("same0000000a", NOW.minus(Duration.ofHours(1))));
        history.add(new UploadRecord("same0000000a", "u", NOW, "Replaced", 9, 0, 0, 0, 0));

        assertEquals(1, history.size());
        assertEquals("Replaced", history.last().orElseThrow().title());
    }

    @Test
    void onlyTheNewestEntriesAreKept() {
        UploadHistory history = new UploadHistory(file(), 3, Duration.ofDays(90));
        for (int i = 0; i < 6; i++) {
            history.add(record("list0000000" + i, NOW.minus(Duration.ofMinutes(10L * (6 - i)))));
        }

        assertEquals(3, history.size());
        assertEquals(List.of("list00000005", "list00000004", "list00000003"),
                history.recent(10).stream().map(UploadRecord::id).toList());
        assertEquals(3, new UploadHistory(file(), 3, Duration.ofDays(90)).size());
    }

    @Test
    void entriesOlderThanTheServiceKeepsAListAreDroppedAndReturned() {
        UploadHistory history = new UploadHistory(file());
        history.add(record("fresh0000000", NOW.minus(Duration.ofDays(89))));
        history.add(record("stale0000000", NOW.minus(Duration.ofDays(91))));

        List<UploadRecord> dropped = history.expire(NOW);

        assertEquals(List.of("stale0000000"), dropped.stream().map(UploadRecord::id).toList(),
                "the caller needs them to forget their tokens");
        assertEquals(List.of("fresh0000000"), history.recent(10).stream().map(UploadRecord::id).toList());
        assertEquals(1, new UploadHistory(file()).size(), "and it is written");
        assertTrue(history.expire(NOW).isEmpty(), "nothing more to drop");
    }

    @Test
    void removingForgetsOnlyThatList() {
        UploadHistory history = new UploadHistory(file());
        history.add(record("keep0000000a", NOW));
        history.add(record("gone0000000b", NOW.minus(Duration.ofMinutes(1))));

        assertTrue(history.remove("gone0000000b"));
        assertFalse(history.remove("gone0000000b"), "already gone");
        assertFalse(history.remove(null));
        assertEquals(List.of("keep0000000a"), new UploadHistory(file()).recent(10).stream().map(UploadRecord::id).toList());
    }

    @Test
    void oneDamagedEntryCostsOnlyItself() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"schemaVersion": 1, "entries": [
                  {"id": "good0000000a", "url": "u", "createdAt": "2026-11-07T10:00:00Z", "title": "T",
                   "mods": 5, "resourcePacks": 0, "shaderPacks": 0, "dataPacks": 0, "plugins": 0},
                  "not an object",
                  {"url": "no id"},
                  {"id": "odd00000000b", "createdAt": "yesterday", "mods": "many"}
                ]}
                """, StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());

        assertEquals(List.of("good0000000a", "odd00000000b"),
                history.recent(10).stream().map(UploadRecord::id).toList(),
                "the unreadable date sorts to the bottom instead of costing the entry");
        assertEquals(Instant.EPOCH, history.findById("odd00000000b").orElseThrow().createdAt());
        assertEquals(0, history.findById("odd00000000b").orElseThrow().mods());
    }

    @Test
    void aFileThatIsNotAHistoryIsKeptForThePlayerNotOverwritten() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "this is not json at all", StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());
        assertEquals(0, history.size());
        assertEquals("this is not json at all", Files.readString(file()), "reading changed nothing");

        history.add(record("new00000000a", NOW));

        Path aside = file().resolveSibling(UploadHistory.FILE_NAME + ".unreadable");
        assertEquals("this is not json at all", Files.readString(aside), "moved aside before the first write");
        assertEquals(1, new UploadHistory(file()).size());
    }

    @Test
    void aFileFromANewerVersionIsNotOverwrittenEither() throws IOException {
        Files.createDirectories(file().getParent());
        String newer = "{\"schemaVersion\": 99, \"entries\": [], \"somethingNew\": true}";
        Files.writeString(file(), newer, StandardCharsets.UTF_8);

        new UploadHistory(file()).add(record("new00000000a", NOW));

        assertEquals(newer, Files.readString(file().resolveSibling(UploadHistory.FILE_NAME + ".unreadable")));
    }

    @Test
    void aFailedWriteDoesNotLoseTheEntryForTheSession() throws IOException {
        Path blocker = Files.writeString(directory.resolve("blocker"), "x");
        UploadHistory history = new UploadHistory(blocker.resolve(UploadHistory.FILE_NAME));

        history.add(record("mem00000000a", NOW));

        assertEquals("mem00000000a", history.last().orElseThrow().id(), "the link was handed out; the entry stays");
    }

    @Test
    void theFileHoldsNoSecretAndNoServerAddress() throws IOException {
        UploadHistory history = new UploadHistory(file());
        history.add(record("list0000000a", NOW));

        String text = Files.readString(file(), StandardCharsets.UTF_8).toLowerCase();

        assertTrue(text.contains("list0000000a") && text.contains("createdat"), "armed: it was written");
        assertFalse(text.contains("token"), "tokens live in the credentials file");
        assertFalse(text.contains("server"));
    }
}
