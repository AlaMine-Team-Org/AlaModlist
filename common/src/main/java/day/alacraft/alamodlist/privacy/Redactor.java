package day.alacraft.alamodlist.privacy;

import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.wire.WireSchema;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.ObjectSpec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Hide and continue": replaces the known values found in free text with {@link #PLACEHOLDER}.
 *
 * <p>It repairs only free text (titles, names, versions, replies). An identifier is never touched - changing it
 * changes what it names - so a known value inside one is a reason not to send, and stays a finding. The caller
 * runs the gate again on the repaired request; if the placeholder itself matches a value (a player called
 * {@code hidden}), the gate says so again and the request is blocked after a bounded number of passes.
 */
public final class Redactor {

    /** What replaces a hidden value. */
    public static final String PLACEHOLDER = "[hidden]";

    private Redactor() {
    }

    /** A copy of the snapshot with every known value in its free-text fields replaced. */
    public static Snapshot redact(Snapshot snapshot, KnownValues known) {
        return (Snapshot) record(WireSchema.object(WireSchema.ROOT), snapshot, "", known);
    }

    private static Object record(ObjectSpec spec, Object value, String normalized, KnownValues known) {
        try {
            RecordComponent[] components = spec.type().getRecordComponents();
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                types[i] = component.getType();
                Field field = byComponent(spec, component.getName());
                Method accessor = component.getAccessor();
                Object original = accessor.invoke(value);
                String path = normalized.isEmpty() ? field.json() : normalized + "." + field.json();
                args[i] = element(field, original, path, known);
            }
            Constructor<? extends Record> constructor = spec.type().getDeclaredConstructor(types);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot rebuild " + spec.type().getSimpleName(), e);
        }
    }

    private static Object element(Field field, Object value, String path, KnownValues known) {
        if (value == null) {
            return null;
        }
        if (field.kind().isFreeText()) {
            return replace((String) value, path, known);
        }
        if (field.kind() == WireSchema.Kind.OBJECT) {
            return record(WireSchema.object(field.object()), value, path, known);
        }
        if (field.kind() == WireSchema.Kind.LIST) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(element(field.item(), item, path + "[]", known));
            }
            return out;
        }
        return value;
    }

    private static String replace(String text, String normalized, KnownValues known) {
        List<int[]> spans = new ArrayList<>();
        for (Needle needle : known.needles()) {
            if (needle.appliesTo(normalized)) {
                spans.addAll(needle.find(text));
            }
        }
        if (spans.isEmpty()) {
            return text;
        }
        spans.sort(Comparator.<int[]>comparingInt(span -> span[0]).thenComparingInt(span -> span[1]));
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        int[] open = null;
        for (int[] span : spans) {
            if (open != null && span[0] <= open[1]) {
                open[1] = Math.max(open[1], span[1]);
                continue;
            }
            if (open != null) {
                out.append(text, at, open[0]).append(PLACEHOLDER);
                at = open[1];
            }
            open = new int[] {span[0], span[1]};
        }
        out.append(text, at, open[0]).append(PLACEHOLDER);
        out.append(text, open[1], text.length());
        return out.toString();
    }

    private static Field byComponent(ObjectSpec spec, String component) {
        for (Field field : spec.fields()) {
            if (field.component().equals(component)) {
                return field;
            }
        }
        throw new IllegalStateException(spec.type().getSimpleName() + "." + component + " is not in the request schema");
    }
}
