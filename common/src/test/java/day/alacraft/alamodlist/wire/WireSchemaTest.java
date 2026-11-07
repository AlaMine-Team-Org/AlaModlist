package day.alacraft.alamodlist.wire;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.PluginEntry;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.testsupport.SnapshotSeed;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.Kind;
import day.alacraft.alamodlist.wire.WireSchema.ObjectSpec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schema against the records that hold it, against the types a request may contain, and against the service. */
class WireSchemaTest {

    // ---------------------------------------------------------------------------------------------
    // Records and schema are the same thing

    @Test
    void everyRecordHasExactlyTheComponentsOfItsSchemaObject() {
        assertFalse(WireSchema.objects().isEmpty());
        for (ObjectSpec spec : WireSchema.objects()) {
            List<String> components = new ArrayList<>();
            for (RecordComponent component : spec.type().getRecordComponents()) {
                components.add(component.getName());
            }
            List<String> fields = spec.fields().stream().map(Field::component).toList();
            assertEquals(fields, components, spec.name() + ": the record and the schema list different fields");
            for (RecordComponent component : spec.type().getRecordComponents()) {
                Field field = spec.fields().stream().filter(f -> f.component().equals(component.getName()))
                        .findFirst().orElseThrow();
                assertEquals(expectedType(spec, field), component.getType(),
                        spec.name() + "." + field.json() + ": the component type does not fit the field kind");
            }
        }
    }

    @Test
    void everyRecordOfTheModelPackageIsInTheSchema() throws Exception {
        URL directory = Snapshot.class.getResource(".");
        assertTrue(directory != null && "file".equals(directory.getProtocol()), "armed: the package is on disk");
        Set<String> onDisk = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".class"))
                    .forEach(name -> onDisk.add(name.substring(0, name.length() - ".class".length())));
        }
        Set<String> inSchema = new LinkedHashSet<>();
        WireSchema.objects().forEach(spec -> inSchema.add(spec.type().getSimpleName()));
        assertFalse(onDisk.isEmpty());
        assertEquals(inSchema, onDisk, "a class of the model package that the schema does not list, or the reverse");
    }

    // ---------------------------------------------------------------------------------------------
    // Layer one: no type that can carry a path or an identity

    /**
     * @implements TC-PRIVACY-001-STR01 - no record of the wire model can hold a path, an identity or a game type
     * @covers R-PRV-01
     */
    @Test
    void noRecordOfTheModelCanHoldAPathOrAnIdentity() {
        for (ObjectSpec spec : WireSchema.objects()) {
            assertEquals(List.of(), violations(spec.type()), spec.name());
        }
    }

    /** The check must be able to fail: each of these holds something the model must never hold. */
    @Test
    void theTypeRuleCatchesAPlantedViolator() {
        record WithPath(Path file) {
        }
        record WithFile(File file) {
        }
        record WithUuid(UUID id) {
        }
        record WithUri(URI address) {
        }
        record WithObject(Object anything) {
        }
        record WithPathList(List<Path> files) {
        }
        record WithPrimitive(int number) {
        }

        assertFalse(violations(WithPath.class).isEmpty());
        assertFalse(violations(WithFile.class).isEmpty());
        assertFalse(violations(WithUuid.class).isEmpty());
        assertFalse(violations(WithUri.class).isEmpty());
        assertFalse(violations(WithObject.class).isEmpty());
        assertFalse(violations(WithPathList.class).isEmpty());
        assertFalse(violations(WithPrimitive.class).isEmpty(), "a primitive cannot be absent");
    }

    // ---------------------------------------------------------------------------------------------
    // Against the service

    /**
     * @implements TC-PRIVACY-001-STR03 - the schema and the limits the service publishes name the same fields with the same numbers
     * @covers R-PRV-01
     */
    @Test
    void theSchemaAgreesWithTheLimitsTheServicePublishes() throws IOException {
        JsonObject limits = fixture("contract/limits.json");

        assertEquals(List.of(), limitMismatches(limits));
        assertEquals(List.of(), unreferencedLimits(limits));
    }

    /** The comparison must be able to fail: change one published number and it says so. */
    @Test
    void theLimitsComparisonSeesAChangedNumber() throws IOException {
        JsonObject limits = fixture("contract/limits.json");
        limits.getAsJsonObject("fieldLengths").addProperty("name", 161);
        limits.addProperty("maxPlugins", 501);
        limits.getAsJsonObject("server").getAsJsonObject("fieldLengths").addProperty("brand", 65);

        List<String> mismatches = limitMismatches(limits);

        assertEquals(3, mismatches.size(), mismatches.toString());

        JsonObject extra = fixture("contract/limits.json");
        extra.getAsJsonObject("fieldLengths").addProperty("somethingNew", 10);
        assertEquals(List.of("fieldLengths.somethingNew"), unreferencedLimits(extra),
                "a limit the schema never uses means a field the mod does not know");
    }

    // ---------------------------------------------------------------------------------------------
    // Construction enforces the schema

    /**
     * @implements TC-PRIVACY-001-STR04 - a record cannot be built with a value the schema refuses
     * @covers R-PRV-01
     */
    @Test
    void aRecordCannotHoldWhatTheServiceWouldRefuse() {
        Snapshot ok = SnapshotSeed.benign();
        assertEquals("sample.jar", ok.mods().get(0).fileName());

        assertThrows(IllegalArgumentException.class, () -> mod("id", "a/b.jar", null), "a path as a file name");
        assertThrows(IllegalArgumentException.class, () -> mod("", "a.jar", null), "an empty id");
        assertThrows(IllegalArgumentException.class, () -> mod(null, "a.jar", null), "a missing id");
        assertThrows(IllegalArgumentException.class, () -> mod("a\nb", "a.jar", null), "a line break in an id");
        assertThrows(IllegalArgumentException.class, () -> mod("x".repeat(129), "a.jar", null), "an id past its limit");
        assertThrows(IllegalArgumentException.class, () -> mod("id", "a.jar", "zz"), "a hash that is not hex");
        assertThrows(IllegalArgumentException.class, () -> mod("id", "a.jar", "ab".repeat(19)), "a short hash");
        assertThrows(IllegalArgumentException.class, () -> new PluginEntry("x", null, null, "elsewhere"), "an origin");
        assertThrows(IllegalArgumentException.class, () -> new PluginEntry(null, null, null, "reply"), "a nameless plugin");
    }

    @Test
    void aHashIsLowerCasedAndFreeTextIsCleanedOnConstruction() {
        ModEntry entry = new ModEntry("id", "A" + (char) 0xA7 + "a  Name", null, null, null, null, null, null,
                new day.alacraft.alamodlist.model.Hashes("AB".repeat(20), null), null, List.of("  ", "Ann"));

        assertEquals("A Name", entry.name());
        assertEquals("ab".repeat(20), entry.hashes().sha1());
        assertEquals(List.of("Ann"), entry.authors(), "a list item that is empty after cleaning is dropped");
    }

    @Test
    void listsAreBoundedAndCopied() {
        assertThrows(IllegalArgumentException.class, () -> new ModEntry("id", null, null, null, null, null, null, null,
                null, null, java.util.Collections.nCopies(17, "a")), "17 authors");
        List<String> authors = new ArrayList<>(List.of("a"));
        ModEntry entry = new ModEntry("id", null, null, null, null, null, null, null, null, null, authors);
        authors.add("b");
        assertEquals(List.of("a"), entry.authors(), "the record keeps its own copy");
        assertThrows(UnsupportedOperationException.class, () -> entry.authors().add("c"));
        assertEquals(List.of(), new ModEntry("id", null, null, null, null, null, null, null, null, null, null).authors());
    }

    // ---------------------------------------------------------------------------------------------
    // helpers

    private static ModEntry mod(String id, String fileName, String sha1) {
        return new ModEntry(id, null, null, null, null, null, fileName, null,
                sha1 == null ? null : new day.alacraft.alamodlist.model.Hashes(sha1, null), null, null);
    }

    private static Class<?> expectedType(ObjectSpec spec, Field field) {
        return switch (field.kind()) {
            case TEXT, MULTILINE, IDENTIFIER, FILE_NAME, HEX, ENUM -> String.class;
            case BOOLEAN -> Boolean.class;
            case INTEGER -> Integer.class;
            case LONG -> Long.class;
            case OBJECT -> WireSchema.object(field.object()).type();
            case LIST -> List.class;
        };
    }

    /** What stops a record from being part of the wire model: any type that is not plainly data about the game. */
    static List<String> violations(Class<?> type) {
        List<String> out = new ArrayList<>();
        if (!type.isRecord()) {
            out.add(type.getName() + " is not a record");
            return out;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            check(type.getSimpleName() + "." + component.getName(), component.getGenericType(), out);
        }
        return out;
    }

    private static void check(String where, Type type, List<String> out) {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() != List.class) {
                out.add(where + ": only List may be generic");
                return;
            }
            check(where + "[]", parameterized.getActualTypeArguments()[0], out);
            return;
        }
        if (type == String.class || type == Boolean.class || type == Integer.class || type == Long.class) {
            return;
        }
        if (type instanceof Class<?> c && c.isRecord() && c.getPackageName().equals(Snapshot.class.getPackageName())) {
            return;
        }
        out.add(where + ": " + type.getTypeName() + " may not be part of the wire model");
    }

    private static JsonObject fixture(String name) throws IOException {
        try (InputStream in = WireSchemaTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue(in != null, "missing fixture " + name);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    /** Every schema limit against the number the service publishes for it. */
    private static List<String> limitMismatches(JsonObject limits) {
        List<String> out = new ArrayList<>();
        int[] checked = {0};
        for (ObjectSpec spec : WireSchema.objects()) {
            for (Field field : spec.fields()) {
                compare(spec.name(), field, limits, out, checked);
            }
        }
        assertTrue(checked[0] > 40, "armed: the comparison looked at " + checked[0] + " limits");
        return out;
    }

    private static void compare(String where, Field field, JsonObject limits, List<String> out, int[] checked) {
        if (field.limit() != null) {
            JsonElement published = resolve(limits, field.limit());
            checked[0]++;
            if (published == null) {
                out.add(where + "." + field.json() + ": the service publishes no " + field.limit());
            } else if (published.getAsInt() != field.max()) {
                out.add(where + "." + field.json() + ": " + field.limit() + " is " + published.getAsInt()
                        + ", the schema says " + field.max());
            }
        }
        if (field.item() != null && field.item().kind() != Kind.OBJECT) {
            compare(where + "." + field.json(), field.item(), limits, out, checked);
        }
    }

    /** Published numbers the schema never reads. The few below are not request fields; each has its reason. */
    private static List<String> unreferencedLimits(JsonObject limits) {
        Set<String> referenced = new LinkedHashSet<>();
        for (ObjectSpec spec : WireSchema.objects()) {
            for (Field field : spec.fields()) {
                if (field.limit() != null) {
                    referenced.add(field.limit());
                }
                if (field.item() != null && field.item().limit() != null) {
                    referenced.add(field.item().limit());
                }
            }
        }
        // Not about the request body: how long a list is kept, how big a body may be, how long an id is; and the
        // reserved command_roots field, which the mod does not fill in 0.1 (the schema omits it on purpose).
        Set<String> notRequestFields = Set.of("storageTime", "maxBytes", "idLength", "server.maxCommandRoots",
                "server.fieldLengths.commandRoot");
        List<String> out = new ArrayList<>();
        for (String path : numbers(limits, "")) {
            if (!referenced.contains(path) && !notRequestFields.contains(path)) {
                out.add(path);
            }
        }
        return out;
    }

    private static List<String> numbers(JsonObject object, String prefix) {
        List<String> out = new ArrayList<>();
        for (String key : object.keySet()) {
            JsonElement value = object.get(key);
            if (value.isJsonObject()) {
                out.addAll(numbers(value.getAsJsonObject(), prefix + key + "."));
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.add(prefix + key);
            }
        }
        return out;
    }

    private static JsonElement resolve(JsonObject root, String dotted) {
        JsonElement current = root;
        for (String part : dotted.split("\\.")) {
            if (!current.isJsonObject() || !current.getAsJsonObject().has(part)) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }
}
