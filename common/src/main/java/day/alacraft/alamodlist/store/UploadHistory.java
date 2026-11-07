package day.alacraft.alamodlist.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.AlaModlist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The lists this player has made, kept on disk so closing the game does not lose them.
 *
 * <p>Kept in a field this would die with the process, and a game client is closed every session; the list shared in
 * the morning could never be found again in the evening. Persisting it is the feature, and the rest of this class
 * exists to make the persistence trustworthy:
 * <ul>
 *   <li><b>No secrets.</b> The delete token is in {@link Tokens}, not here: an entry is shown to the player
 *       and may be logged.</li>
 *   <li><b>Written atomically</b> ({@link AtomicFiles#writePrivate}): a game killed mid-write leaves the previous
 *       file or the new one, never half of each.</li>
 *   <li><b>Bounded in count and in age.</b> The newest {@value #DEFAULT_MAX_ENTRIES} entries, none older than
 *       {@link #DEFAULT_MAX_AGE} (the service forgets a list a while after its last view, so an older entry points
 *       at nothing). {@link #expire} returns what it dropped so the caller can forget the tokens of those lists.</li>
 *   <li><b>A damaged file is not overwritten.</b> If the file cannot be read as a history at all, it is moved aside
 *       ({@code history.json.unreadable}) before the next write; one damaged entry costs only itself.</li>
 *   <li><b>A failed write is not a failed send.</b> The link is already in the player's hands; the entry is kept in
 *       memory and a warning names the file and the kind of error, never a path component the player did not
 *       choose.</li>
 * </ul>
 *
 * <p>Every method is synchronised: lists are made off the game thread, so two sends can finish at once.
 */
public final class UploadHistory {

    /** The file name inside the data directory the caller supplies. */
    public static final String FILE_NAME = "history.json";

    /** How many lists to remember. */
    public static final int DEFAULT_MAX_ENTRIES = 50;

    /** How long an entry is worth keeping: the service's retention counted from the last view. */
    public static final Duration DEFAULT_MAX_AGE = Duration.ofDays(90);

    /** Bumped only when a field changes meaning. */
    private static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Comparator<UploadRecord> NEWEST_FIRST =
            Comparator.comparing(UploadRecord::createdAt).reversed();

    private final Path file;
    private final int maxEntries;
    private final Duration maxAge;

    /** Newest first, so {@link #last()} and {@link #recent(int)} are a prefix. */
    private final List<UploadRecord> entries = new ArrayList<>();

    private boolean unreadable;

    public UploadHistory(Path file) {
        this(file, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_AGE);
    }

    public UploadHistory(Path file, int maxEntries, Duration maxAge) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.maxEntries = Math.max(1, maxEntries);
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        load();
    }

    /** Records a list (replacing an earlier entry with the same id) and writes the file. */
    public synchronized void add(UploadRecord record) {
        Objects.requireNonNull(record, "record");

        entries.removeIf(existing -> existing.id().equals(record.id()));
        entries.add(record);
        // Sorting keeps the order in memory the same as the order the file reloads in. The sort is stable.
        entries.sort(NEWEST_FIRST);
        trim();
        save();
    }

    /**
     * Drops the entries older than the maximum age, as of {@code now}.
     *
     * @return what was dropped, so the caller can forget their tokens; empty when nothing was
     */
    public synchronized List<UploadRecord> expire(Instant now) {
        Instant oldest = now.minus(maxAge);
        List<UploadRecord> dropped = entries.stream().filter(record -> record.createdAt().isBefore(oldest)).toList();

        if (!dropped.isEmpty()) {
            entries.removeAll(dropped);
            save();
        }

        return dropped;
    }

    /** The most recent lists, newest first, at most {@code count} of them. */
    public synchronized List<UploadRecord> recent(int count) {
        if (count <= 0) {
            return List.of();
        }

        return List.copyOf(entries.subList(0, Math.min(count, entries.size())));
    }

    /** Looks a list up by the id printed in chat. */
    public synchronized Optional<UploadRecord> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        return entries.stream().filter(record -> record.id().equals(id)).findFirst();
    }

    /** The most recent list. */
    public synchronized Optional<UploadRecord> last() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /**
     * Forgets a list, after the service confirmed it is gone.
     *
     * @return whether there was anything to forget
     */
    public synchronized boolean remove(String id) {
        if (id == null || id.isBlank() || !entries.removeIf(record -> record.id().equals(id))) {
            return false;
        }

        save();

        return true;
    }

    public synchronized int size() {
        return entries.size();
    }

    // ---- file ----

    private void load() {
        if (!Files.exists(file)) {
            return;
        }

        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));

            if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")
                    || !root.getAsJsonObject().get("entries").isJsonArray()) {
                throw new IllegalStateException("not a history");
            }

            if (root.getAsJsonObject().has("schemaVersion")
                    && root.getAsJsonObject().get("schemaVersion").getAsInt() > SCHEMA_VERSION) {
                throw new IllegalStateException("written by a newer version");
            }

            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("entries")) {
                // One malformed entry must not cost the player the other forty-nine.
                readRecord(element).ifPresent(entries::add);
            }

            entries.sort(NEWEST_FIRST);
            trim();
        } catch (IOException | RuntimeException e) {
            // The kind only: a parser's message can quote the text it choked on.
            AlaModlist.LOGGER.warn("Could not read {} ({}). Starting with an empty history; the file is kept.",
                    FILE_NAME, e.getClass().getSimpleName());
            entries.clear();
            unreadable = true;
        }
    }

    private void save() {
        try {
            if (unreadable) {
                setAside();
                unreadable = false;
            }

            AtomicFiles.writePrivate(file, GSON.toJson(document()) + "\n");
        } catch (IOException | RuntimeException e) {
            AlaModlist.LOGGER.warn("Could not write {} ({}). The list history of this session will not survive a "
                    + "restart.", FILE_NAME, e.getClass().getSimpleName());
        }
    }

    private JsonObject document() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);

        JsonArray array = new JsonArray();
        for (UploadRecord record : entries) {
            JsonObject json = new JsonObject();
            json.addProperty("id", record.id());
            json.addProperty("url", record.url());
            // ISO-8601 rather than epoch millis: the file is meant to be readable by the person it belongs to.
            json.addProperty("createdAt", record.createdAt().toString());
            json.addProperty("title", record.title());
            json.addProperty("mods", record.mods());
            json.addProperty("resourcePacks", record.resourcePacks());
            json.addProperty("shaderPacks", record.shaderPacks());
            json.addProperty("dataPacks", record.dataPacks());
            json.addProperty("plugins", record.plugins());
            array.add(json);
        }
        root.add("entries", array);

        return root;
    }

    private static Optional<UploadRecord> readRecord(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }

        JsonObject json = element.getAsJsonObject();
        String id = string(json, "id");

        if (id.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new UploadRecord(
                id,
                string(json, "url"),
                instant(json, "createdAt"),
                string(json, "title"),
                integer(json, "mods"),
                integer(json, "resourcePacks"),
                integer(json, "shaderPacks"),
                integer(json, "dataPacks"),
                integer(json, "plugins")));
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static int integer(JsonObject json, String key) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? Math.max(0, json.get(key).getAsInt()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instant(JsonObject json, String key) {
        try {
            return Instant.parse(string(json, key));
        } catch (DateTimeParseException e) {
            // An unreadable timestamp only affects order and expiry, so the entry sorts to the bottom (and is the
            // first to expire) instead of being thrown away over a date.
            return Instant.EPOCH;
        }
    }

    private void trim() {
        while (entries.size() > maxEntries) {
            entries.remove(entries.size() - 1);
        }
    }

    /** Keeps an unreadable file for the player instead of overwriting it; best effort. */
    private void setAside() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AlaModlist.LOGGER.debug("Could not move {} aside ({}).", FILE_NAME, e.getClass().getSimpleName());
        }
    }
}
