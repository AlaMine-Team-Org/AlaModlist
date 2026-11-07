package day.alacraft.alamodlist.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.AlaModlist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A small file of named string secrets (an account token, delete tokens): the one place a secret is kept.
 *
 * <p><b>A secret is not a setting.</b> A {@code @Knob} lands in the operator's config file, which launchers
 * export with the modpack, and in the settings screen. A secret must do neither, so it lives in its own
 * file, written by {@link AtomicFiles#writePrivate}, in a directory the caller chooses <em>outside
 * {@code config/}</em> ({@link #FILE_NAME} inside the mod's own data directory). See ADR-036.
 *
 * <p>Behaviour that matters:
 * <ul>
 *   <li>Values are read from the file on every {@link #get}; they are not copied into a field of a
 *       long-lived object.</li>
 *   <li>Nothing here logs, prints or throws a value. {@link #toString()}, {@link #problem()} and every
 *       exception message name the file and an exception class at most.</li>
 *   <li>A file that cannot be read (damaged, empty after a torn write, written by a newer version) is not
 *       a crash: the store works from memory and says why in {@link #problem()}. Nothing is rewritten
 *       until the next explicit {@link #put}, and then the unreadable file is first moved aside to
 *       {@code credentials.json.unreadable} - an automatic repair would erase what the player may still
 *       be able to recover by hand.</li>
 *   <li>A file that cannot be written leaves the value <em>in memory only</em> for this session, and
 *       {@link #put} reports {@code false}. The fallback is never written anywhere else.</li>
 * </ul>
 *
 * <p>Not thread-safe across stores: two {@code SecretStore} objects on one file would race; keep one per
 * file. Methods of one store are synchronised.
 */
public final class SecretStore {

    /** The file name inside the data directory the caller supplies. */
    public static final String FILE_NAME = "credentials.json";

    /** The layout version of the file; a file with a higher one is left alone. */
    static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    /**
     * Set only while the disk could not be written: then it is the whole truth for this session (so a
     * removal that could not be saved stays removed). Cleared by the next successful write.
     */
    private Map<String, String> inMemory;

    private String problem;

    public SecretStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    /** The secret stored under {@code name}, read from the file now. */
    public synchronized Optional<String> get(String name) {
        return Optional.ofNullable(snapshot().secrets().get(name));
    }

    /**
     * Stores {@code value} under {@code name}.
     *
     * @return {@code true} if it is on disk, {@code false} if it is held in memory only (see
     *         {@link #problem()})
     * @throws IllegalArgumentException for an empty name or value; the message never contains the value
     */
    public synchronized boolean put(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A secret needs a name");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("A secret needs a value: " + name);
        }
        Snapshot before = snapshot();
        Map<String, String> next = new TreeMap<>(before.secrets());
        next.put(name, value);
        persist(next, before.unreadable());
        return inMemory == null;
    }

    /** Forgets {@code name}. The result has the meaning of {@link #put}'s. */
    public synchronized boolean remove(String name) {
        Snapshot before = snapshot();
        if (before.secrets().containsKey(name)) {
            Map<String, String> next = new TreeMap<>(before.secrets());
            next.remove(name);
            persist(next, before.unreadable());
        }
        return inMemory == null;
    }

    /**
     * What is wrong with the file right now, in words fit for a log or a screen; empty when nothing is. Never
     * contains a secret.
     */
    public synchronized Optional<String> problem() {
        snapshot();
        return Optional.ofNullable(problem);
    }

    @Override
    public String toString() {
        return "SecretStore[" + file.getFileName() + "]";
    }

    // --- internals

    private record Snapshot(Map<String, String> secrets, boolean unreadable) {
    }

    /** The current truth: the in-memory copy while the disk is failing, else the file (absent = empty). */
    private Snapshot snapshot() {
        if (inMemory != null) {
            return new Snapshot(inMemory, false);
        }
        if (!Files.exists(file)) {
            clearProblem();
            return new Snapshot(new TreeMap<>(), false);
        }
        try {
            Map<String, String> secrets = parse(Files.readString(file, StandardCharsets.UTF_8));
            clearProblem();
            return new Snapshot(secrets, false);
        } catch (IOException | RuntimeException e) {
            // Only the class is reported: a parser's message can quote the text it choked on.
            String reason = e instanceof Unreadable ? e.getMessage() : e.getClass().getSimpleName();
            setProblem("Could not read " + FILE_NAME + " (" + reason + "); saved secrets are unavailable until it "
                    + "is fixed or replaced.");
            return new Snapshot(new TreeMap<>(), true);
        }
    }

    private static Map<String, String> parse(String text) {
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) {
            throw new Unreadable("not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement version = object.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            throw new Unreadable("no version");
        }
        if (version.getAsInt() > FORMAT_VERSION) {
            throw new Unreadable("written by a newer version");
        }
        JsonElement secrets = object.get("secrets");
        if (secrets == null || !secrets.isJsonObject()) {
            throw new Unreadable("no secrets object");
        }
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : secrets.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new Unreadable("a value is not a string");
            }
            result.put(entry.getKey(), value.getAsString());
        }
        return result;
    }

    private void persist(Map<String, String> next, boolean replacingUnreadable) {
        Path directory = file.getParent();
        boolean directoryIsNew = directory != null && !Files.exists(directory);
        try {
            if (replacingUnreadable) {
                setAside();
            }
            AtomicFiles.writePrivate(file, toJson(next));
            if (directoryIsNew) {
                // We made this directory for the secrets: nobody else's data lives in it yet.
                PrivateFiles.restrictToOwner(directory);
            }
            inMemory = null;
            clearProblem();
        } catch (IOException | RuntimeException e) {
            inMemory = next;
            setProblem("Could not save " + FILE_NAME + " (" + e.getClass().getSimpleName()
                    + "); secrets are kept in memory for this session only.");
        }
    }

    private static String toJson(Map<String, String> secrets) {
        JsonObject object = new JsonObject();
        object.addProperty("version", FORMAT_VERSION);
        JsonObject entries = new JsonObject();
        secrets.forEach(entries::addProperty);
        object.add("secrets", entries);
        return GSON.toJson(object) + "\n";
    }

    /** Keeps an unreadable file for the player instead of overwriting it; best effort. */
    private void setAside() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AlaModlist.LOGGER.debug("Could not move {} aside ({}).", file.getFileName(), e.getClass().getSimpleName());
        }
    }

    private void setProblem(String text) {
        if (!text.equals(problem)) {
            AlaModlist.LOGGER.warn(text);
        }
        problem = text;
    }

    private void clearProblem() {
        problem = null;
    }

    /** A file that parsed but is not ours; the message is a fixed phrase, never file content. */
    private static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String reason) {
            super(reason, null, false, false);
        }
    }
}
