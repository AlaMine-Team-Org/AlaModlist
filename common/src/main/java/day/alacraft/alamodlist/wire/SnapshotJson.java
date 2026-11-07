package day.alacraft.alamodlist.wire;

import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.ObjectSpec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a {@link Snapshot} as the JSON body of the request - the only writer there is.
 *
 * <p>It walks {@link WireSchema}, not the record: a key is written because the schema lists it, and a record
 * component the schema does not list is a mistake that stops the write instead of leaking a field. Values are
 * checked once more on the way out (the records already cleaned them), so a value that reaches the wire is
 * allowed by the schema whichever way it got into the record.
 *
 * <p>Absent values are left out: {@code null}, and an empty list (the service reads a missing list as empty).
 * The output is compact UTF-8 without escaping non-ASCII letters, in the order of the schema, so the same state
 * always gives the same bytes.
 */
public final class SnapshotJson {

    private SnapshotJson() {
    }

    /** The body, as UTF-8 bytes: these exact bytes are scanned by the privacy gate and these are what is sent. */
    public static byte[] toBytes(Snapshot snapshot) {
        return toJson(snapshot).getBytes(StandardCharsets.UTF_8);
    }

    public static String toJson(Snapshot snapshot) {
        StringBuilder out = new StringBuilder(4096);
        writeObject(out, WireSchema.object(WireSchema.ROOT), snapshot);
        return out.toString();
    }

    private static void writeObject(StringBuilder out, ObjectSpec spec, Object record) {
        if (!spec.type().isInstance(record)) {
            throw new IllegalStateException("expected " + spec.type().getSimpleName() + " for '" + spec.name() + "'");
        }
        checkNoUnlistedComponent(spec);
        out.append('{');
        boolean first = true;
        for (Field field : spec.fields()) {
            Object value = read(record, field);
            if (absent(value)) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, field.json());
            out.append(':');
            writeValue(out, spec.name() + "." + field.json(), field, value);
        }
        out.append('}');
    }

    private static void writeValue(StringBuilder out, String where, Field field, Object value) {
        switch (field.kind()) {
            case TEXT, MULTILINE, IDENTIFIER, FILE_NAME, HEX, ENUM -> {
                // Second look: the record cleaned it, the schema must agree it is already in its final form.
                Object again = recheck(where, field, value);
                if (!value.equals(again)) {
                    throw new IllegalStateException(where + " is not in the form the schema allows");
                }
                string(out, (String) value);
            }
            case BOOLEAN, INTEGER, LONG -> out.append(value);
            case OBJECT -> writeObject(out, WireSchema.object(field.object()), value);
            case LIST -> {
                out.append('[');
                boolean first = true;
                for (Object item : (List<?>) value) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeValue(out, where + "[]", field.item(), item);
                }
                out.append(']');
            }
        }
    }

    private static Object recheck(String where, Field field, Object value) {
        // Reuse the one cleaning routine through the schema owner-less entry: a private copy would drift.
        return WireSchema.cleanField(where, field, value);
    }

    private static boolean absent(Object value) {
        return value == null || (value instanceof List<?> list && list.isEmpty());
    }

    private static Object read(Object record, Field field) {
        try {
            Method accessor = record.getClass().getMethod(field.component());
            return accessor.invoke(record);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(record.getClass().getSimpleName() + " has no component '"
                    + field.component() + "' for the schema field '" + field.json() + "'", e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + record.getClass().getSimpleName() + "."
                    + field.component(), e);
        }
    }

    /** A record component the schema does not list would be silently dropped; refuse the write instead. */
    private static void checkNoUnlistedComponent(ObjectSpec spec) {
        for (var component : spec.type().getRecordComponents()) {
            boolean listed = spec.fields().stream().anyMatch(f -> f.component().equals(component.getName()));
            if (!listed) {
                throw new IllegalStateException(spec.type().getSimpleName() + "." + component.getName()
                        + " is not in the request schema");
            }
        }
    }

    /** A JSON string literal. Non-ASCII text is written as it is; only what JSON requires is escaped. */
    static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
