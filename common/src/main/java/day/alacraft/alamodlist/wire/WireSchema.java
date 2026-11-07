package day.alacraft.alamodlist.wire;

import day.alacraft.alamodlist.model.ChannelEntry;
import day.alacraft.alamodlist.model.CommandReply;
import day.alacraft.alamodlist.model.DataPackEntry;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.model.Hashes;
import day.alacraft.alamodlist.model.LauncherInfo;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.NamespaceEntry;
import day.alacraft.alamodlist.model.PackEntry;
import day.alacraft.alamodlist.model.PluginEntry;
import day.alacraft.alamodlist.model.ServerInfo;
import day.alacraft.alamodlist.model.ServerModEntry;
import day.alacraft.alamodlist.model.ShaderEntry;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.privacy.TextClean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The request schema of the service API, v1: the one table every other piece reads.
 *
 * <p>It is the allow-list. {@link SnapshotJson} writes a key only if it is here, the model records clean their
 * fields by the rules here, the privacy gate treats a key that is not here as a leak, and the tests compare the
 * table with the service's published limits and with a verbatim request the service accepted. A field the
 * service does not know does not exist for the mod either: it would be refused (strict schema).
 *
 * <p>Names are the wire names (snake case). The record component of a field is the same name in camel case.
 */
public final class WireSchema {

    /** What a field holds, and therefore how its value is checked and how the privacy gate may treat it. */
    public enum Kind {
        /** Single-line free text: cleaned and cut to the limit. */
        TEXT,
        /** Multi-line free text (the note, a command reply): cleaned and cut, line breaks kept. */
        MULTILINE,
        /** An identifier: never cut or repaired, refused when it does not fit. */
        IDENTIFIER,
        /** A bare file name: an identifier without a directory separator. */
        FILE_NAME,
        /** Lower-case hexadecimal of exactly {@code max} digits. */
        HEX,
        /** One of {@link Field#allowed()}. */
        ENUM,
        BOOLEAN,
        INTEGER,
        LONG,
        /** A nested object; {@link Field#object()} names its spec. */
        OBJECT,
        /** A list; {@link Field#item()} describes an element, {@link Field#max()} is the item limit. */
        LIST;

        /** Free text can be repaired by replacing a part of it; nothing else can. */
        public boolean isFreeText() {
            return this == TEXT || this == MULTILINE;
        }
    }

    /**
     * One field of an object.
     *
     * @param json      the wire name
     * @param component the name of the record component that holds it
     * @param max       text: code points; hex: digits; list: items
     * @param limit     where the service publishes this limit in {@code GET /limits} (dotted path), or null
     * @param item      for a list, the element
     */
    public record Field(String json, String component, Kind kind, int max, boolean required, List<String> allowed,
                        long min, long maxValue, String object, Field item, String limit) {
    }

    /** One object of the request and the record class that holds it. */
    public record ObjectSpec(String name, Class<? extends Record> type, List<Field> fields) {

        /** The field with this wire name, or null. */
        public Field field(String json) {
            for (Field field : fields) {
                if (field.json().equals(json)) {
                    return field;
                }
            }
            return null;
        }
    }

    /** Name of the root object. */
    public static final String ROOT = "snapshot";

    // Allowed values of the enumerated fields: the service's schema, word for word.
    public static final List<String> LOADERS = List.of("fabric", "neoforge");
    public static final List<String> OS_FAMILIES = List.of("windows", "macos", "linux");
    public static final List<String> MOD_KINDS = List.of("mod", "library", "builtin");
    public static final List<String> SIDES = List.of("client", "server", "universal");
    public static final List<String> PACK_SOURCES = List.of("default", "server", "builtin");
    public static final List<String> PLUGIN_ORIGINS = List.of("reply", "pasted");
    public static final List<String> CONNECTIONS = List.of("multiplayer", "lan", "realms");
    public static final List<String> CONNECTION_TYPES = List.of("neoforge", "other");
    public static final List<String> CHANNEL_PHASES = List.of("configuration", "play");
    public static final List<String> CHANNEL_SOURCES = List.of("negotiated", "adhoc", "common", "sendable");
    public static final List<String> COMMANDS = List.of("plugins", "version", "about", "velocity_plugins", "neoforge_mods");
    public static final List<String> COMMAND_STATUSES = List.of("ok", "denied", "unknown_command", "timeout");

    private static final Map<String, ObjectSpec> BY_NAME = new LinkedHashMap<>();
    private static final Map<Class<?>, ObjectSpec> BY_TYPE = new LinkedHashMap<>();

    static {
        define(ROOT, Snapshot.class,
                ident("source", 64, false, "fieldLengths.source"),
                ident("locale", 16, false, null),
                text("title", 80, false, "fieldLengths.title"),
                multiline("note", 500, false, "fieldLengths.note"),
                object("environment", "environment", true),
                objects("mods", "mod", 2000, "maxEntries"),
                objects("resource_packs", "resource_pack", 2000, "maxEntries"),
                objects("shader_packs", "shader_pack", 2000, "maxEntries"),
                objects("data_packs", "data_pack", 2000, "maxEntries"),
                object("launcher", "launcher", false),
                object("server", "server", false),
                objects("plugins", "plugin", 500, "maxPlugins"));

        define("environment", EnvironmentInfo.class,
                text("mc_version", 32, true, "fieldLengths.mcVersion"),
                integer("protocol", 0, Integer.MAX_VALUE),
                enumeration("loader", true, LOADERS),
                text("loader_version", 64, false, "fieldLengths.loaderVersion"),
                integer("java_major", 8, Integer.MAX_VALUE),
                enumeration("os_family", false, OS_FAMILIES));

        define("mod", ModEntry.class,
                ident("id", 128, true, "fieldLengths.id"),
                text("name", 160, false, "fieldLengths.name"),
                text("version", 64, false, "fieldLengths.version"),
                enumeration("kind", false, MOD_KINDS),
                ident("parent_id", 128, false, "fieldLengths.id"),
                enumeration("side", false, SIDES),
                fileName("file_name", 160, false, "fieldLengths.fileName"),
                longInt("file_size", 0, Long.MAX_VALUE),
                object("hashes", "hashes", false),
                text("license", 128, false, "fieldLengths.license"),
                list("authors", 16, "fieldLengths.authors", text("authors", 80, false, "fieldLengths.author")));

        define("hashes", Hashes.class,
                hex("sha1", 40, true),
                longInt("murmur2", 0, 4294967295L));

        define("resource_pack", PackEntry.class,
                ident("id", 128, true, "fieldLengths.id"),
                text("title", 160, false, "fieldLengths.packTitle"),
                bool("enabled"),
                integer("order", 0, Integer.MAX_VALUE),
                ident("pack_format", 16, false, "fieldLengths.packFormat"),
                enumeration("source", false, PACK_SOURCES),
                hex("sha1", 40, false));

        define("shader_pack", ShaderEntry.class,
                text("name", 160, true, "fieldLengths.shaderName"),
                bool("active"));

        define("data_pack", DataPackEntry.class,
                ident("id", 128, true, "fieldLengths.id"),
                bool("enabled"));

        define("launcher", LauncherInfo.class,
                text("brand", 64, true, "fieldLengths.launcherBrand"),
                text("version", 64, false, "fieldLengths.launcherVersion"),
                text("instance_name", 80, false, "fieldLengths.launcherInstance"));

        define("plugin", PluginEntry.class,
                text("name", 128, true, "fieldLengths.pluginName"),
                text("version", 64, false, "fieldLengths.pluginVersion"),
                bool("enabled"),
                enumeration("origin", true, PLUGIN_ORIGINS));

        // `command_roots` (reserved by the contract, not filled in 0.1) is deliberately absent: a field the mod
        // cannot fill is a field the gate would have to defend for nothing.
        define("server", ServerInfo.class,
                enumeration("connection", false, CONNECTIONS),
                text("brand", 64, false, "server.fieldLengths.brand"),
                text("ping_version", 64, false, "server.fieldLengths.pingVersion"),
                integer("ping_protocol", Integer.MIN_VALUE, Integer.MAX_VALUE),
                enumeration("connection_type", false, CONNECTION_TYPES),
                objects("channels", "channel", 500, "server.maxChannels"),
                objects("namespaces", "namespace", 200, "server.maxNamespaces"),
                list("feature_flags", 64, "server.maxFeatureFlags",
                        ident("feature_flags", 128, false, "server.fieldLengths.featureFlag")),
                bool("has_server_resource_pack"),
                integer("server_links_count", 0, Integer.MAX_VALUE),
                objects("mods", "server_mod", 1000, "server.maxMods"),
                objects("command_replies", "command_reply", 4, "server.maxCommandReplies"));

        define("channel", ChannelEntry.class,
                ident("id", 128, true, "server.fieldLengths.channelId"),
                text("version", 32, false, "server.fieldLengths.channelVersion"),
                enumeration("phase", true, CHANNEL_PHASES),
                enumeration("source", true, CHANNEL_SOURCES));

        define("namespace", NamespaceEntry.class,
                ident("namespace", 64, true, "server.fieldLengths.namespace"),
                integer("registry_entries", 0, Integer.MAX_VALUE),
                integer("tag_count", 0, Integer.MAX_VALUE),
                integer("dimensions", 0, Integer.MAX_VALUE),
                bool("client_has_mod"));

        define("server_mod", ServerModEntry.class,
                fileName("file_name", 160, true, "server.fieldLengths.modFileName"),
                ident("id", 128, true, "server.fieldLengths.modId"),
                text("version", 64, false, "server.fieldLengths.modVersion"),
                integer("mods_in_file", 1, Integer.MAX_VALUE));

        define("command_reply", CommandReply.class,
                enumeration("command", true, COMMANDS),
                multiline("text", 8192, false, "server.fieldLengths.commandText"),
                enumeration("status", true, COMMAND_STATUSES));
    }

    private WireSchema() {
    }

    // ---------------------------------------------------------------------------------------------
    // Lookups

    /** The spec of an object by name; throws for a name that is not in the schema. */
    public static ObjectSpec object(String name) {
        ObjectSpec spec = BY_NAME.get(name);
        if (spec == null) {
            throw new IllegalStateException("no object '" + name + "' in the request schema");
        }
        return spec;
    }

    /** The spec of the record class; throws for a class that is not in the schema. */
    public static ObjectSpec of(Class<?> type) {
        ObjectSpec spec = BY_TYPE.get(type);
        if (spec == null) {
            throw new IllegalStateException(type.getName() + " is not in the request schema");
        }
        return spec;
    }

    /** Every object of the schema, root first. */
    public static List<ObjectSpec> objects() {
        return List.copyOf(BY_NAME.values());
    }

    /**
     * The normalized paths of every free-text field, such as {@code mods[].name} or {@code mods[].authors[]}.
     * The privacy self-test seeds each of them with a planted value; a new field without a planted value fails it.
     */
    public static List<String> freeTextPaths() {
        return paths(kind -> kind.isFreeText());
    }

    /** The normalized paths of every identifier-like field (ids, file names, hashes, enumerations). */
    public static List<String> identifierPaths() {
        return paths(kind -> kind == Kind.IDENTIFIER || kind == Kind.FILE_NAME || kind == Kind.HEX
                || kind == Kind.ENUM);
    }

    private static List<String> paths(java.util.function.Predicate<Kind> wanted) {
        List<String> out = new ArrayList<>();
        collect(object(ROOT), "", wanted, out);
        return List.copyOf(out);
    }

    private static void collect(ObjectSpec spec, String prefix, java.util.function.Predicate<Kind> wanted,
                                List<String> out) {
        for (Field field : spec.fields()) {
            String path = prefix + field.json();
            switch (field.kind()) {
                case OBJECT -> collect(object(field.object()), path + ".", wanted, out);
                case LIST -> {
                    Field item = field.item();
                    if (item.kind() == Kind.OBJECT) {
                        collect(object(item.object()), path + "[].", wanted, out);
                    } else if (wanted.test(item.kind())) {
                        out.add(path + "[]");
                    }
                }
                default -> {
                    if (wanted.test(field.kind())) {
                        out.add(path);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cleaning: the one place a record constructor asks "is this value allowed, and what does it become"

    /**
     * The value of a field as it may be stored: free text cleaned and cut, an identifier checked as it is, a
     * number checked against its range, a list checked for size and element by element.
     *
     * <p>Called from the compact constructor of every record, so a record cannot be built with a value the
     * schema does not allow. Returns {@code null} for an absent optional value and an empty list for an absent
     * list; throws {@link IllegalArgumentException} (never quoting the value) otherwise.
     */
    @SuppressWarnings("unchecked")
    public static <T> T clean(Class<? extends Record> owner, String json, T value) {
        ObjectSpec spec = of(owner);
        Field field = spec.field(json);
        if (field == null) {
            throw new IllegalStateException(spec.name() + " has no field '" + json + "'");
        }
        return (T) cleanValue(spec.name() + "." + json, field, value);
    }

    /** The same routine for a field already in hand: the writer's second look at a value. */
    static Object cleanField(String where, Field field, Object value) {
        return cleanValue(where, field, value);
    }

    private static Object cleanValue(String where, Field field, Object value) {
        if (value == null) {
            if (field.required()) {
                throw new IllegalArgumentException(where + " is required");
            }
            return field.kind() == Kind.LIST ? List.of() : null;
        }
        return switch (field.kind()) {
            case TEXT -> freeText(where, field, TextClean.line(field.max(), string(where, value)));
            case MULTILINE -> freeText(where, field, TextClean.multiline(field.max(), string(where, value)));
            case IDENTIFIER -> TextClean.requireIdentifier(where, field.max(), string(where, value));
            case FILE_NAME -> TextClean.requireFileName(where, field.max(), string(where, value));
            case HEX -> hexValue(where, field, string(where, value));
            case ENUM -> {
                String text = string(where, value);
                if (!field.allowed().contains(text)) {
                    throw new IllegalArgumentException(where + " is not one of " + field.allowed());
                }
                yield text;
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException(where + " is not a boolean");
                }
                yield value;
            }
            case INTEGER -> {
                if (!(value instanceof Integer number)) {
                    throw new IllegalArgumentException(where + " is not an integer");
                }
                range(where, field, number);
                yield value;
            }
            case LONG -> {
                if (!(value instanceof Long number)) {
                    throw new IllegalArgumentException(where + " is not a long");
                }
                range(where, field, number);
                yield value;
            }
            case OBJECT -> {
                Class<?> type = object(field.object()).type();
                if (!type.isInstance(value)) {
                    throw new IllegalArgumentException(where + " is not a " + type.getSimpleName());
                }
                yield value;
            }
            case LIST -> listValue(where, field, value);
        };
    }

    private static String string(String where, Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(where + " is not a string");
        }
        return text;
    }

    private static String freeText(String where, Field field, String cleaned) {
        if (cleaned.isEmpty()) {
            if (field.required()) {
                throw new IllegalArgumentException(where + " is empty after cleaning");
            }
            return null;
        }
        return cleaned;
    }

    private static String hexValue(String where, Field field, String text) {
        if (text.length() != field.max()) {
            throw new IllegalArgumentException(where + " must be " + field.max() + " hexadecimal digits");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!digit) {
                throw new IllegalArgumentException(where + " must be " + field.max() + " hexadecimal digits");
            }
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static void range(String where, Field field, long number) {
        if (number < field.min() || number > field.maxValue()) {
            throw new IllegalArgumentException(where + " is outside " + field.min() + ".." + field.maxValue());
        }
    }

    private static List<Object> listValue(String where, Field field, Object value) {
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException(where + " is not a list");
        }
        if (items.size() > field.max()) {
            throw new IllegalArgumentException(where + " has more than " + field.max() + " items");
        }
        List<Object> out = new ArrayList<>(items.size());
        for (Object item : items) {
            Object cleaned = cleanValue(where + "[]", field.item(), Objects.requireNonNull(item, where + " has a null item"));
            if (cleaned != null) {
                out.add(cleaned);
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------------------------------
    // Building the table

    private static void define(String name, Class<? extends Record> type, Field... fields) {
        ObjectSpec spec = new ObjectSpec(name, type, List.of(fields));
        if (BY_NAME.put(name, spec) != null || BY_TYPE.put(type, spec) != null) {
            throw new IllegalStateException("object '" + name + "' is defined twice");
        }
    }

    private static String camel(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean up = false;
        for (char c : json.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                out.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return out.toString();
    }

    private static Field field(String json, Kind kind, int max, boolean required, List<String> allowed, long min,
                               long maxValue, String object, Field item, String limit) {
        return new Field(json, camel(json), kind, max, required, allowed, min, maxValue, object, item, limit);
    }

    private static Field text(String json, int max, boolean required, String limit) {
        return field(json, Kind.TEXT, max, required, List.of(), 0, 0, null, null, limit);
    }

    private static Field multiline(String json, int max, boolean required, String limit) {
        return field(json, Kind.MULTILINE, max, required, List.of(), 0, 0, null, null, limit);
    }

    private static Field ident(String json, int max, boolean required, String limit) {
        return field(json, Kind.IDENTIFIER, max, required, List.of(), 0, 0, null, null, limit);
    }

    private static Field fileName(String json, int max, boolean required, String limit) {
        return field(json, Kind.FILE_NAME, max, required, List.of(), 0, 0, null, null, limit);
    }

    private static Field hex(String json, int digits, boolean required) {
        return field(json, Kind.HEX, digits, required, List.of(), 0, 0, null, null, null);
    }

    private static Field enumeration(String json, boolean required, List<String> allowed) {
        return field(json, Kind.ENUM, 0, required, allowed, 0, 0, null, null, null);
    }

    private static Field bool(String json) {
        return field(json, Kind.BOOLEAN, 0, false, List.of(), 0, 0, null, null, null);
    }

    private static Field integer(String json, long min, long maxValue) {
        return field(json, Kind.INTEGER, 0, false, List.of(), min, maxValue, null, null, null);
    }

    private static Field longInt(String json, long min, long maxValue) {
        return field(json, Kind.LONG, 0, false, List.of(), min, maxValue, null, null, null);
    }

    private static Field object(String json, String object, boolean required) {
        return field(json, Kind.OBJECT, 0, required, List.of(), 0, 0, object, null, null);
    }

    /** A list of objects. */
    private static Field objects(String json, String object, int maxItems, String limit) {
        Field item = field(json, Kind.OBJECT, 0, true, List.of(), 0, 0, object, null, null);
        return field(json, Kind.LIST, maxItems, false, List.of(), 0, 0, null, item, limit);
    }

    /** A list of scalars. */
    private static Field list(String json, int maxItems, String limit, Field item) {
        return field(json, Kind.LIST, maxItems, false, List.of(), 0, 0, null, item, limit);
    }
}
