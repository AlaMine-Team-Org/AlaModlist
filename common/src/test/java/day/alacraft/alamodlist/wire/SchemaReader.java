package day.alacraft.alamodlist.wire;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.ObjectSpec;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a request body into the model, strictly and by the schema: a key the schema does not list is an error, not
 * something to skip. The tests use it to prove that a body the service accepted can be expressed in the model - and
 * that the writer then writes the same body back.
 */
final class SchemaReader {

    private SchemaReader() {
    }

    static Snapshot read(JsonObject body) {
        return (Snapshot) object(WireSchema.object(WireSchema.ROOT), body, "$");
    }

    private static Object object(ObjectSpec spec, JsonElement element, String where) {
        JsonObject json = element.getAsJsonObject();
        for (String key : json.keySet()) {
            if (spec.field(key) == null) {
                throw new AssertionError(where + "." + key + " is not in the request schema");
            }
        }
        try {
            RecordComponent[] components = spec.type().getRecordComponents();
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                String component = components[i].getName();
                Field field = spec.fields().stream().filter(f -> f.component().equals(component))
                        .findFirst().orElseThrow();
                JsonElement value = json.get(field.json());
                args[i] = value == null || value.isJsonNull() ? null : value(field, value, where + "." + field.json());
            }
            return spec.type().getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot build " + spec.type().getSimpleName(), e);
        }
    }

    private static Object value(Field field, JsonElement value, String where) {
        return switch (field.kind()) {
            case TEXT, MULTILINE, IDENTIFIER, FILE_NAME, HEX, ENUM -> value.getAsString();
            case BOOLEAN -> value.getAsBoolean();
            case INTEGER -> value.getAsInt();
            case LONG -> value.getAsLong();
            case OBJECT -> object(WireSchema.object(field.object()), value, where);
            case LIST -> {
                List<Object> items = new ArrayList<>();
                int index = 0;
                for (JsonElement item : value.getAsJsonArray()) {
                    items.add(value(field.item(), item, where + "[" + index++ + "]"));
                }
                yield items;
            }
        };
    }
}
