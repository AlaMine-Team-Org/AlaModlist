package day.alacraft.alamodlist.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import day.alacraft.alamodlist.AlaModlist;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A JSON config file built from the {@link Knob}-annotated static fields of one holder class.
 *
 * <p>What it guarantees, and why each point is here:
 * <ul>
 *   <li><b>The declaration is the registration.</b> The knobs are read from the holder by reflection
 *       once; nothing is listed twice.</li>
 *   <li><b>Deterministic file layout.</b> Sections in {@link Section} declaration order, keys sorted by
 *       name within a section - never {@code getDeclaredFields()} order, which the JLS does not
 *       specify, so the same values cannot be written in a different order on a different JVM.</li>
 *   <li><b>All or nothing.</b> Every value is parsed and validated before any field is touched; a
 *       value of the wrong type aborts the load and leaves the live values exactly as they were.</li>
 *   <li><b>A file from a newer mod is refused</b> ({@link LoadResult#SCHEMA_TOO_NEW}): this build cannot
 *       know what its keys mean, so the live values fall back to the compiled defaults and the file is
 *       left untouched. Guessing is how a config silently corrupts.</li>
 *   <li><b>A changed default reaches existing installs.</b> The file records, per knob, the default it
 *       was written against ({@code builtinDefaults}). A value still equal to that recorded default was
 *       never chosen by anybody, so when a new build ships a different default, it is adopted (and
 *       logged). A value the operator edited is kept forever. Without this, the file written by the
 *       first version shadows every later default change, and a "new balance" is never seen in game.</li>
 *   <li><b>Self-healing.</b> After a successful load the file is rewritten in canonical form (comments,
 *       new knobs, recorded defaults) - but only when its content actually differs, so a reload does
 *       not churn the file.</li>
 * </ul>
 *
 * <p>A key absent from the file takes its compiled default. A number outside its declared range makes
 * the default live, but the file keeps the value as written and the warning repeats on every load: a
 * fallback is never saved, or the operator's value would vanish after one warning. Unknown keys are
 * reported and disappear on the canonical rewrite.
 */
public final class KnobConfig {

    private static final String COMMENT_PREFIX = "_comment_";
    private static final String SCHEMA_VERSION_KEY = "schemaVersion";
    private static final String BUILTIN_DEFAULTS_KEY = "builtinDefaults";

    private static final String SCHEMA_VERSION_DOC =
            "Layout version of this file. Written by the mod - do not raise it by hand. An older file is"
                    + " migrated automatically on load; a file from a NEWER mod version is refused (the mod"
                    + " logs a warning and runs on its built-in defaults instead of guessing).";

    private static final String BUILTIN_DEFAULTS_DOC =
            "Written by the mod - do not edit. For each setting, the mod's own default at the moment this"
                    + " file was saved. A setting still holding exactly that value counts as untouched, so a"
                    + " mod update that changes the default applies it here too (the change is logged). A"
                    + " setting you edited to anything else is left alone. A value you deliberately set to"
                    + " the same number as the default cannot be told apart from one you never touched.";

    /** Pretty, and without HTML escaping - otherwise quotes and equals signs in the docs become \\u0027. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String name;
    private final int schemaVersion;
    private final List<Migration> migrations;
    private final List<SectionInfo> sections;
    private final List<KnobField> knobs;
    private volatile Path lastPath;

    private KnobConfig(String name, int schemaVersion, List<Migration> migrations,
                       List<SectionInfo> sections, List<KnobField> knobs) {
        this.name = name;
        this.schemaVersion = schemaVersion;
        this.migrations = migrations;
        this.sections = sections;
        this.knobs = knobs;
    }

    /**
     * Builds the config of {@code holder}. Must be called from a static initializer placed BELOW the
     * knob fields: each knob captures its compiled default by reading its field, and Java runs static
     * initializers in source order.
     *
     * @param schemaVersion current layout version, starting at 1; raise it only when the SHAPE or the
     *                      MEANING of a key changes - never for adding a knob
     * @param migrations    one step per version hop: {@code migrations.get(i)} rewrites a version
     *                      {@code i + 1} document to {@code i + 2}; exactly {@code schemaVersion - 1} steps
     * @throws IllegalStateException for any declaration the loader could not honour - a silently ignored
     *                               knob is exactly what this class exists to prevent
     */
    public static KnobConfig of(Class<?> holder, int schemaVersion, List<Migration> migrations) {
        if (schemaVersion < 1) {
            throw new IllegalStateException(holder.getName() + ": schemaVersion starts at 1, got " + schemaVersion);
        }
        if (migrations.size() != schemaVersion - 1) {
            throw new IllegalStateException(holder.getName() + ": schemaVersion " + schemaVersion + " needs exactly "
                    + (schemaVersion - 1) + " migration step(s), got " + migrations.size());
        }
        for (int i = 0; i < migrations.size(); i++) {
            if (migrations.get(i).fromVersion() != i + 1) {
                throw new IllegalStateException(holder.getName() + ": migration #" + i + " must start at version "
                        + (i + 1) + ", starts at " + migrations.get(i).fromVersion());
            }
        }

        List<SectionInfo> sections = new ArrayList<>();
        for (Section section : holder.getAnnotationsByType(Section.class)) {
            if (sections.stream().anyMatch(s -> s.id().equals(section.id()))) {
                throw new IllegalStateException(holder.getName() + ": section '" + section.id() + "' declared twice");
            }
            sections.add(new SectionInfo(section.id(), section.doc()));
        }
        if (sections.isEmpty()) {
            throw new IllegalStateException(holder.getName() + " declares no @Section");
        }

        List<KnobField> knobs = new ArrayList<>();
        for (Field field : holder.getDeclaredFields()) {
            Knob knob = field.getAnnotation(Knob.class);
            if (knob == null) {
                continue;
            }
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                throw new IllegalStateException("@Knob field " + holder.getSimpleName() + "." + field.getName()
                        + " must be public, static and not final");
            }
            if (sections.stream().noneMatch(s -> s.id().equals(knob.section()))) {
                throw new IllegalStateException("@Knob field " + holder.getSimpleName() + "." + field.getName()
                        + " names section '" + knob.section() + "', which the class does not declare");
            }
            knobs.add(new KnobField(field, knob));
        }
        if (knobs.isEmpty()) {
            throw new IllegalStateException(holder.getName() + " declares no @Knob field");
        }
        knobs.sort(Comparator.comparingInt((KnobField k) -> indexOf(sections, k.section)).thenComparing(k -> k.key));

        return new KnobConfig(holder.getSimpleName(), schemaVersion, List.copyOf(migrations),
                List.copyOf(sections), List.copyOf(knobs));
    }

    /** Outcome of a load, precise enough for a reload command to tell the operator what happened. */
    public enum LoadResult {
        /** The file existed and was applied. */
        LOADED,
        /** The file was absent; the current values were written to it. */
        DEFAULTS_WRITTEN,
        /** The file could not be read or parsed; the live values were left exactly as they were. */
        ERROR,
        /** The file is from a newer mod version; the live values were reset to the built-in defaults. */
        SCHEMA_TOO_NEW
    }

    /**
     * One rung of the schema ladder: rewrites, in place, a document stored at {@code fromVersion} into
     * the shape of {@code fromVersion + 1}.
     */
    public record Migration(int fromVersion, java.util.function.Consumer<JsonObject> apply) {
        public Migration {
            Objects.requireNonNull(apply, "apply");
        }
    }

    /** Loads {@code path}, or writes the current values there if it does not exist yet. Never throws. */
    public LoadResult load(Path path) {
        this.lastPath = path;
        try {
            if (!Files.exists(path)) {
                writeAtomically(path, canonicalJson());
                AlaModlist.LOGGER.info("[config] {}: wrote defaults to {}", name, path);
                return LoadResult.DEFAULTS_WRITTEN;
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("the file is not a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();

            int fileVersion = readSchemaVersion(root);
            if (fileVersion > schemaVersion) {
                AlaModlist.LOGGER.warn("[config] {}: {} declares schemaVersion {}, but this build understands {}."
                        + " NOTHING from the file was applied - the built-in defaults are live, and the file"
                        + " is left untouched. Upgrade the mod or replace the file.",
                        name, path, fileVersion, schemaVersion);
                resetToDefaults();
                return LoadResult.SCHEMA_TOO_NEW;
            }
            migrate(root, fileVersion, path);

            // Staging: parse and validate everything into pending commits. Anything thrown here aborts the
            // load before a single field has changed.
            Map<String, JsonObject> bodies = new LinkedHashMap<>();
            for (SectionInfo section : sections) {
                bodies.put(section.id(), sectionBody(root, section.id()));
            }
            JsonObject recorded = builtinDefaults(root);
            List<Runnable> commits = new ArrayList<>(knobs.size());
            List<String> adopted = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            for (KnobField knob : knobs) {
                JsonElement element = bodies.get(knob.section).get(knob.key);
                Object value;
                JsonElement held = null;
                if (element == null || element.isJsonNull()) {
                    value = knob.defaultValue;
                } else {
                    value = knob.parse(element);
                    if (!knob.inRange(value)) {
                        rejected.add(knob.key + "=" + element + " (allowed " + knob.rangeText() + ")");
                        value = knob.defaultValue;
                        held = element;
                    } else {
                        Object recordedDefault = knob.parseOrNull(recorded.get(knob.key));
                        if (recordedDefault != null && recordedDefault.equals(value) && !value.equals(knob.defaultValue)) {
                            adopted.add(knob.key + ": " + value + " -> " + knob.defaultValue);
                            value = knob.defaultValue;
                        }
                    }
                }
                Object staged = value;
                JsonElement stagedHeld = held;
                commits.add(() -> {
                    knob.set(staged);
                    knob.heldBack = stagedHeld;
                });
            }
            List<String> unknown = unknownKeys(bodies);

            for (Runnable commit : commits) {
                commit.run();
            }
            AlaModlist.LOGGER.info("[config] {}: loaded {}", name, path);
            if (!adopted.isEmpty()) {
                AlaModlist.LOGGER.info("[config] {}: {} setting(s) still held the old default and now follow the"
                        + " new one: {}", name, adopted.size(), String.join(", ", adopted));
            }
            if (!rejected.isEmpty()) {
                AlaModlist.LOGGER.warn("[config] {}: out of range - the default is live, and the file keeps"
                        + " the value until it is fixed (this warning repeats on every load): {}",
                        name, String.join("; ", rejected));
            }
            if (!unknown.isEmpty()) {
                AlaModlist.LOGGER.warn("[config] {}: unknown key(s) ignored and removed from the file: {}",
                        name, String.join(", ", unknown));
            }

            String canonical = canonicalJson();
            if (!normalize(raw).equals(normalize(canonical))) {
                try {
                    writeAtomically(path, canonical);
                    AlaModlist.LOGGER.info("[config] {}: rewrote {} in canonical form", name, path);
                } catch (IOException writeError) {
                    // The load itself succeeded; failing to tidy the file must not undo it.
                    AlaModlist.LOGGER.error("[config] {}: could not rewrite {}: {}", name, path, writeError.toString());
                }
            }
            return LoadResult.LOADED;
        } catch (Exception e) {
            AlaModlist.LOGGER.error("[config] {}: could not load {}: {} - the live values were left unchanged.",
                    name, path, e.toString());
            return LoadResult.ERROR;
        }
    }

    /** Loads the file this config was last loaded from. */
    public LoadResult reload() {
        Path path = lastPath;
        if (path == null) {
            throw new IllegalStateException("[config] " + name + ": reload() before the first load(path)");
        }
        return load(path);
    }

    /** Writes the current values to the file this config was last loaded from (a config screen's Save). */
    public void save() throws IOException {
        Path path = lastPath;
        if (path == null) {
            throw new IllegalStateException("[config] " + name + ": save() before the first load(path)");
        }
        writeAtomically(path, canonicalJson());
    }

    /** Restores every knob to the value compiled into this build. */
    public void resetToDefaults() {
        for (KnobField knob : knobs) {
            knob.set(knob.defaultValue);
            knob.heldBack = null;
        }
    }

    /** The knobs in file order, for a config screen or a status command. */
    public List<KnobView> knobs() {
        List<KnobView> views = new ArrayList<>(knobs.size());
        for (KnobField knob : knobs) {
            views.add(new KnobView(knob));
        }
        return views;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** The whole current state as the canonical document, exactly as {@link #load} would write it. */
    public String canonicalJson() {
        JsonObject root = new JsonObject();
        root.addProperty(COMMENT_PREFIX + SCHEMA_VERSION_KEY, SCHEMA_VERSION_DOC);
        root.addProperty(SCHEMA_VERSION_KEY, schemaVersion);
        for (SectionInfo section : sections) {
            JsonObject body = new JsonObject();
            for (KnobField knob : knobs) {
                if (knob.section.equals(section.id())) {
                    body.addProperty(COMMENT_PREFIX + knob.key, knob.doc);
                    JsonElement held = knob.heldBack;
                    body.add(knob.key, held != null ? held : knob.toJson(knob.get()));
                }
            }
            root.addProperty(COMMENT_PREFIX + section.id(), section.doc());
            root.add(section.id(), body);
        }
        // Always THIS build's defaults: the file is being written by this build, so "the default this
        // value was saved against" is this build's default by definition.
        JsonObject defaults = new JsonObject();
        for (KnobField knob : knobs) {
            defaults.add(knob.key, knob.toJson(knob.defaultValue));
        }
        root.addProperty(COMMENT_PREFIX + BUILTIN_DEFAULTS_KEY, BUILTIN_DEFAULTS_DOC);
        root.add(BUILTIN_DEFAULTS_KEY, defaults);
        return GSON.toJson(root) + "\n";
    }

    // ---------------------------------------------------------------------------------------------

    private int readSchemaVersion(JsonObject root) {
        JsonElement element = root.get(SCHEMA_VERSION_KEY);
        if (element == null || element.isJsonNull()) {
            // Hand-written or trimmed file: read it as the current layout, and the canonical rewrite adds
            // the key back.
            return schemaVersion;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            int version = element.getAsInt();
            if (version < 1) {
                throw new IllegalArgumentException("'" + SCHEMA_VERSION_KEY + "' must be at least 1, got " + version);
            }
            return version;
        }
        throw new IllegalArgumentException("'" + SCHEMA_VERSION_KEY + "' must be a number, got " + element);
    }

    private void migrate(JsonObject root, int fileVersion, Path path) {
        if (fileVersion >= schemaVersion) {
            return;
        }
        for (Migration step : migrations) {
            if (step.fromVersion() >= fileVersion) {
                step.apply().accept(root);
            }
        }
        AlaModlist.LOGGER.info("[config] {}: migrated {} from schemaVersion {} to {}", name, path, fileVersion, schemaVersion);
    }

    /** A missing section reads as empty; a section holding something other than an object aborts the load. */
    private static JsonObject sectionBody(JsonObject root, String id) {
        JsonElement element = root.get(id);
        if (element == null || element.isJsonNull()) {
            return new JsonObject();
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        throw new IllegalArgumentException("section '" + id + "' must be an object, got " + element);
    }

    /**
     * The recorded defaults, or an empty object when absent or damaged. Tolerant on purpose: this block is
     * the mod's own bookkeeping, and reading a damaged one as "every value was edited by hand" changes
     * nothing - every value is kept as written and the block is rebuilt by the canonical rewrite.
     */
    private static JsonObject builtinDefaults(JsonObject root) {
        JsonElement element = root.get(BUILTIN_DEFAULTS_KEY);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private List<String> unknownKeys(Map<String, JsonObject> bodies) {
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, JsonObject> section : bodies.entrySet()) {
            Set<String> declared = new java.util.HashSet<>();
            for (KnobField knob : knobs) {
                if (knob.section.equals(section.getKey())) {
                    declared.add(knob.key);
                }
            }
            for (String key : section.getValue().keySet()) {
                if (!key.startsWith(COMMENT_PREFIX) && !declared.contains(key)) {
                    unknown.add(section.getKey() + "." + key);
                }
            }
        }
        return unknown;
    }

    private static int indexOf(List<SectionInfo> sections, String id) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id().equals(id)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    /** Writes through a temporary file in the same directory, so a crash never leaves half a config. */
    static void writeAtomically(Path path, String content) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path directory = absolute.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = Files.createTempFile(directory, absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record SectionInfo(String id, String doc) {
    }

    /** One knob: its field, its declaration and its compiled default. */
    static final class KnobField {
        final Field field;
        final String key;
        final String section;
        final String doc;
        final double min;
        final double max;
        final Class<?> type;
        final Object defaultValue;
        /**
         * The value the file holds for this knob when the load rejected it (out of range), else null. The
         * default is live, but the file keeps what the operator wrote: a fallback written back to disk would
         * erase their value after one warning (027 did that to an address a player typed).
         */
        volatile JsonElement heldBack;

        KnobField(Field field, Knob knob) {
            this.field = field;
            this.key = field.getName();
            this.section = knob.section();
            this.doc = knob.doc();
            this.min = knob.min();
            this.max = knob.max();
            this.type = field.getType();
            if (type != boolean.class && type != int.class && type != long.class
                    && type != double.class && type != String.class) {
                throw new IllegalStateException("@Knob field " + field.getDeclaringClass().getSimpleName() + "." + key
                        + " has unsupported type " + type.getName() + " (boolean, int, long, double, String)");
            }
            this.defaultValue = get();
            if (defaultValue == null) {
                throw new IllegalStateException("@Knob field " + key + " has no default value");
            }
            if (!inRange(defaultValue)) {
                throw new IllegalStateException("@Knob field " + key + " has default " + defaultValue
                        + " outside its own range " + rangeText());
            }
        }

        Object get() {
            try {
                return field.get(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        void set(Object value) {
            try {
                field.set(null, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The value in {@code element} for this knob's type, or an exception naming the key. */
        Object parse(JsonElement element) {
            Object value = parseOrNull(element);
            if (value == null) {
                throw new IllegalArgumentException("'" + key + "' must be " + typeName() + ", got " + element);
            }
            return value;
        }

        /** Like {@link #parse}, but {@code null} instead of an exception for an absent or mistyped value. */
        Object parseOrNull(JsonElement element) {
            if (element == null || !element.isJsonPrimitive()) {
                return null;
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (type == boolean.class) {
                return primitive.isBoolean() ? primitive.getAsBoolean() : null;
            }
            if (type == String.class) {
                return primitive.isString() ? primitive.getAsString() : null;
            }
            if (!primitive.isNumber()) {
                return null;
            }
            if (type == double.class) {
                return primitive.getAsDouble();
            }
            // Integral types: "2.5" is a typo for an int, not a request to truncate it to 2.
            BigDecimal number = primitive.getAsBigDecimal();
            try {
                return type == int.class ? (Object) number.intValueExact() : (Object) number.longValueExact();
            } catch (ArithmeticException notIntegral) {
                return null;
            }
        }

        boolean inRange(Object value) {
            if (value instanceof Number number) {
                double d = number.doubleValue();
                return !Double.isNaN(d) && d >= min && d <= max;
            }
            return true;
        }

        String rangeText() {
            return (min == Double.NEGATIVE_INFINITY ? "-inf" : trim(min)) + ".." + (max == Double.POSITIVE_INFINITY ? "+inf" : trim(max));
        }

        JsonElement toJson(Object value) {
            if (value instanceof Boolean b) {
                return new JsonPrimitive(b);
            }
            if (value instanceof Number n) {
                return new JsonPrimitive(n);
            }
            return new JsonPrimitive(String.valueOf(value));
        }

        private String typeName() {
            return type == boolean.class ? "true or false" : type == String.class ? "a string"
                    : type == double.class ? "a number" : "a whole number";
        }

        private static String trim(double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
    }

    /** A read-and-validated-write view of one knob, for screens and commands. */
    public static final class KnobView {
        private final KnobField knob;

        private KnobView(KnobField knob) {
            this.knob = knob;
        }

        public String key() {
            return knob.key;
        }

        public String section() {
            return knob.section;
        }

        public String doc() {
            return knob.doc;
        }

        /** {@code boolean.class}, {@code int.class}, {@code long.class}, {@code double.class} or {@code String.class}. */
        public Class<?> type() {
            return knob.type;
        }

        public Object value() {
            return knob.get();
        }

        public Object defaultValue() {
            return knob.defaultValue;
        }

        public String rangeText() {
            return knob.rangeText();
        }

        /** Whether {@code value} is of this knob's type and within its range. */
        public boolean accepts(Object value) {
            return value != null && boxed(knob.type).isInstance(value) && knob.inRange(value);
        }

        /**
         * Sets the live value; call {@link KnobConfig#save()} to persist it. A value chosen here replaces an
         * out-of-range one the file was keeping: the operator has now picked a real one.
         */
        public void set(Object value) {
            if (!accepts(value)) {
                throw new IllegalArgumentException("'" + knob.key + "' does not accept " + value);
            }
            knob.set(value);
            knob.heldBack = null;
        }

        private static Class<?> boxed(Class<?> type) {
            if (type == boolean.class) {
                return Boolean.class;
            }
            if (type == int.class) {
                return Integer.class;
            }
            if (type == long.class) {
                return Long.class;
            }
            if (type == double.class) {
                return Double.class;
            }
            return type;
        }
    }
}
