package day.alacraft.alamodlist.hash;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.store.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hashes remembered between snapshots, so the second snapshot reads no jar at all.
 *
 * <p>An entry is valid while the file keeps its size and modification time. The key is not the path but the first
 * 16 hexadecimal digits of the SHA-256 of the real path: the cache file lives in a directory that launchers
 * export with a modpack, and a full path would carry the name of the user account of the machine into it. Only
 * the hashes of public files are stored, so the file is an ordinary one, not a private one.
 *
 * <p>Only the entries used by the current snapshot are written back, so the file never grows past the mods that
 * are installed. A file that cannot be read is ignored and replaced; one that cannot be written leaves the cache
 * in memory for the session, with one warning in the log.
 */
public final class HashCache {

    private static final int VERSION = 1;
    private static final int KEY_DIGITS = 16;

    /** What is remembered about a file. */
    public record Entry(String sha1, Long murmur2) {
    }

    private record Stored(long size, long mtime, String sha1, Long murmur2) {
    }

    private final Path file;
    private final Map<String, Stored> entries = new ConcurrentHashMap<>();
    private final Set<String> used = ConcurrentHashMap.newKeySet();
    private volatile boolean warned;

    private HashCache(Path file) {
        this.file = file;
    }

    /** A cache kept in the given file; what the file holds is loaded now. */
    public static HashCache open(Path file) {
        HashCache cache = new HashCache(file);
        cache.load();
        return cache;
    }

    /** A cache that lives only as long as this object. */
    public static HashCache inMemory() {
        return new HashCache(null);
    }

    /**
     * The remembered hashes of a file, if its size and modification time are the ones they were taken at.
     *
     * @param needMurmur2 an entry without the CurseForge fingerprint does not answer a request for it: the file
     *                    is hashed again, once, and the entry is replaced
     */
    public Optional<Entry> lookup(Path jar, long size, long mtimeMillis, boolean needMurmur2) {
        String key = keyOf(jar);
        Stored stored = entries.get(key);
        if (stored == null || stored.size() != size || stored.mtime() != mtimeMillis) {
            return Optional.empty();
        }
        if (needMurmur2 && stored.murmur2() == null) {
            return Optional.empty();
        }
        used.add(key);
        return Optional.of(new Entry(stored.sha1(), stored.murmur2()));
    }

    /** Remembers the hashes of a file. */
    public void put(Path jar, long size, long mtimeMillis, Entry entry) {
        String key = keyOf(jar);
        entries.put(key, new Stored(size, mtimeMillis, entry.sha1(), entry.murmur2()));
        used.add(key);
    }

    /** Writes the entries this snapshot used. Never throws: a cache that cannot be saved is only a slower start. */
    public void save() {
        if (file == null) {
            return;
        }
        JsonObject list = new JsonObject();
        Map<String, Stored> sorted = new TreeMap<>();
        for (String key : used) {
            Stored stored = entries.get(key);
            if (stored != null) {
                sorted.put(key, stored);
            }
        }
        for (Map.Entry<String, Stored> item : sorted.entrySet()) {
            Stored stored = item.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("s", stored.size());
            json.addProperty("m", stored.mtime());
            json.addProperty("sha1", stored.sha1());
            if (stored.murmur2() != null) {
                json.addProperty("m2", stored.murmur2());
            }
            list.add(item.getKey(), json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.add("entries", list);
        try {
            AtomicFiles.write(file, root.toString());
        } catch (IOException | RuntimeException e) {
            if (!warned) {
                warned = true;
                AlaModlist.LOGGER.warn("Could not save the hash cache; it stays in memory for this session ({}).",
                        e.getClass().getSimpleName());
            }
        }
    }

    /** The cache key of a file: a digest of its real path, never the path. */
    static String keyOf(Path jar) {
        String path;
        try {
            path = jar.toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            path = jar.toAbsolutePath().normalize().toString();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, KEY_DIGITS);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime has to provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject root = parsed.getAsJsonObject();
            if (root.get("v").getAsInt() != VERSION) {
                return;
            }
            for (Map.Entry<String, JsonElement> item : root.getAsJsonObject("entries").entrySet()) {
                Stored stored = parseEntry(item.getValue());
                if (stored != null) {
                    entries.put(item.getKey(), stored);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable, or not what we wrote: start empty. The next save replaces it.
            entries.clear();
        }
    }

    private static Stored parseEntry(JsonElement element) {
        try {
            JsonObject json = element.getAsJsonObject();
            String sha1 = json.get("sha1").getAsString();
            if (sha1.length() != 40 || !sha1.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return null;
            }
            Long murmur2 = json.has("m2") ? Long.valueOf(json.get("m2").getAsLong()) : null;
            return new Stored(json.get("s").getAsLong(), json.get("m").getAsLong(), sha1, murmur2);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
