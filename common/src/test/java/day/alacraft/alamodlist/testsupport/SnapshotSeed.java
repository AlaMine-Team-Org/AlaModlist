package day.alacraft.alamodlist.testsupport;

import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.wire.WireSchema;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.ObjectSpec;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds a {@link Snapshot} with every field of the schema filled, straight from the schema, and reads one back.
 *
 * <p>Generic on purpose: a field added to the schema is filled (and so seeded with planted values) without anyone
 * editing a test, which is what keeps the privacy self-test from going blind to new fields.
 */
public final class SnapshotSeed {

    private SnapshotSeed() {
    }

    /**
     * A full snapshot. {@code text} gives the value of a string field by its normalized path (such as
     * {@code mods[].name}), or null for "use the harmless default".
     */
    public static Snapshot build(Function<String, String> text) {
        return (Snapshot) make(WireSchema.object(WireSchema.ROOT), "", text);
    }

    /** A full snapshot of harmless values. */
    public static Snapshot benign() {
        return build(path -> null);
    }

    /** The field at a normalized path, such as {@code server.command_replies[].text}. */
    public static Field fieldAt(String path) {
        ObjectSpec spec = WireSchema.object(WireSchema.ROOT);
        Field found = null;
        for (String part : path.split("\\.")) {
            String name = part.endsWith("[]") ? part.substring(0, part.length() - 2) : part;
            found = spec.field(name);
            if (found == null) {
                throw new IllegalArgumentException("no field at " + path);
            }
            Field next = part.endsWith("[]") ? found.item() : found;
            if (next.kind() == WireSchema.Kind.OBJECT) {
                spec = WireSchema.object(next.object());
            }
            found = next;
        }
        return found;
    }

    /** Every string of the snapshot, by normalized path. */
    public static Map<String, List<String>> strings(Snapshot snapshot) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        walk(WireSchema.object(WireSchema.ROOT), snapshot, "", out);
        return out;
    }

    // ---------------------------------------------------------------------------------------------

    private static Object make(ObjectSpec spec, String prefix, Function<String, String> text) {
        try {
            RecordComponent[] components = spec.type().getRecordComponents();
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                Field field = byComponent(spec, components[i].getName());
                args[i] = value(field, prefix + field.json(), text);
            }
            return spec.type().getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build " + spec.type().getSimpleName(), e);
        }
    }

    private static Object value(Field field, String path, Function<String, String> text) {
        return switch (field.kind()) {
            case TEXT, MULTILINE, IDENTIFIER, FILE_NAME -> {
                String planted = text.apply(path);
                yield planted != null ? planted : harmless(field);
            }
            case HEX -> "0123456789abcdef0123456789abcdef01234567".substring(0, field.max());
            case ENUM -> field.allowed().get(0);
            case BOOLEAN -> Boolean.TRUE;
            case INTEGER -> (int) Math.min(field.maxValue(), Math.max(field.min(), 7));
            case LONG -> Math.min(field.maxValue(), Math.max(field.min(), 123456L));
            case OBJECT -> make(WireSchema.object(field.object()), path + ".", text);
            case LIST -> {
                Field item = field.item();
                String itemPath = path + "[]";
                yield List.of(item.kind() == WireSchema.Kind.OBJECT
                        ? make(WireSchema.object(item.object()), itemPath + ".", text)
                        : value(item, itemPath, text));
            }
        };
    }

    private static String harmless(Field field) {
        return switch (field.kind()) {
            case FILE_NAME -> "sample.jar";
            case IDENTIFIER -> "sample-id";
            default -> "Sample text";
        };
    }

    private static void walk(ObjectSpec spec, Object record, String prefix, Map<String, List<String>> out) {
        try {
            for (Field field : spec.fields()) {
                Object value = record.getClass().getMethod(field.component()).invoke(record);
                collect(field, value, prefix + field.json(), out);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void collect(Field field, Object value, String path, Map<String, List<String>> out) {
        if (value == null) {
            return;
        }
        switch (field.kind()) {
            case TEXT, MULTILINE, IDENTIFIER, FILE_NAME ->
                    out.computeIfAbsent(path, key -> new ArrayList<>()).add((String) value);
            case OBJECT -> walk(WireSchema.object(field.object()), value, path + ".", out);
            case LIST -> {
                for (Object item : (List<?>) value) {
                    if (field.item().kind() == WireSchema.Kind.OBJECT) {
                        walk(WireSchema.object(field.item().object()), item, path + "[].", out);
                    } else {
                        collect(field.item(), item, path + "[]", out);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static Field byComponent(ObjectSpec spec, String component) {
        return spec.fields().stream().filter(f -> f.component().equals(component)).findFirst().orElseThrow();
    }
}
