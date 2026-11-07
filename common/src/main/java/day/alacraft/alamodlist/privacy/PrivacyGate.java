package day.alacraft.alamodlist.privacy;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import day.alacraft.alamodlist.wire.WireSchema;
import day.alacraft.alamodlist.wire.WireSchema.Field;
import day.alacraft.alamodlist.wire.WireSchema.ObjectSpec;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer two of the privacy gate: a look at the final bytes of a request, right before they are sent.
 *
 * <p>Layer one is the construction of the request itself (the model has no field for a path or an identity, the
 * writer writes only the schema, free text is cleaned). This layer assumes layer one failed, and does not trust
 * it: it takes the very bytes that would leave the machine, reads them again as JSON and searches every string
 * and every number for the values {@link KnownValues} holds. Keys outside the schema are findings as well - a
 * leaky writer would put a path under a key of its own.
 *
 * <p>It fails closed: bytes that are not valid UTF-8 or strict JSON, nesting too deep, or any exception inside
 * the gate give a {@link Verdict} with an error and no permission to send. A key written twice is a finding,
 * because a reader that keeps the last value would never see the first.
 */
public final class PrivacyGate {

    private static final int MAX_DEPTH = 16;

    /** The root as a field description, so the walk needs no special case for the first object. */
    private static final Field ROOT = new Field("$", "$", WireSchema.Kind.OBJECT, 0, true, List.of(), 0, 0,
            WireSchema.ROOT, null, null);

    private PrivacyGate() {
    }

    /**
     * Searches {@code body} - the exact bytes about to be sent, before compression - for the known values.
     *
     * @return the findings; never throws
     */
    public static Verdict check(byte[] body, KnownValues known) {
        try {
            if (body == null || known == null) {
                throw new IllegalArgumentException("nothing to check");
            }
            String text = decodeStrict(body);
            Scan scan = new Scan(known);
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                scan.value(reader, ROOT, "", "", 0);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException("data after the document");
                }
            }
            return new Verdict(List.copyOf(scan.findings), null);
        } catch (Exception | StackOverflowError e) {
            // The kind of failure only: a message could quote the text that was being read.
            return new Verdict(List.of(), e.getClass().getSimpleName());
        }
    }

    private static String decodeStrict(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
    }

    private static final class Scan {

        private final KnownValues known;
        private final Set<Finding> findings = new LinkedHashSet<>();

        Scan(KnownValues known) {
            this.known = known;
        }

        void value(JsonReader reader, Field field, String path, String normalized, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("nesting too deep");
            }
            switch (reader.peek()) {
                case BEGIN_OBJECT -> object(reader, field, path, normalized, depth);
                case BEGIN_ARRAY -> {
                    Field item = field != null && field.kind() == WireSchema.Kind.LIST ? field.item() : null;
                    reader.beginArray();
                    for (int i = 0; reader.hasNext(); i++) {
                        value(reader, item, path + "[" + i + "]", normalized + "[]", depth + 1);
                    }
                    reader.endArray();
                }
                case STRING, NUMBER -> text(reader.nextString(), field, path, normalized);
                case BOOLEAN -> reader.nextBoolean();
                case NULL -> reader.nextNull();
                default -> throw new IOException("unexpected token");
            }
        }

        private void object(JsonReader reader, Field field, String path, String normalized, int depth)
                throws IOException {
            ObjectSpec spec = field != null && field.kind() == WireSchema.Kind.OBJECT
                    ? WireSchema.object(field.object()) : null;
            Set<String> seen = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                String childPath = path.isEmpty() ? name : path + "." + name;
                String childNormalized = normalized.isEmpty() ? name : normalized + "." + name;
                Field child = spec == null ? null : spec.field(name);
                if (!seen.add(name)) {
                    findings.add(new Finding("duplicate_key", childPath, Finding.Kind.DUPLICATE_KEY, false));
                }
                if (spec != null && child == null) {
                    findings.add(new Finding("unknown_key", childPath, Finding.Kind.UNKNOWN_KEY, false));
                }
                value(reader, child, childPath, childNormalized, depth + 1);
            }
            reader.endObject();
        }

        private void text(String value, Field field, String path, String normalized) {
            boolean redactable = field != null && field.kind().isFreeText();
            for (Needle needle : known.needles()) {
                if (needle.appliesTo(normalized) && !needle.find(value).isEmpty()) {
                    findings.add(new Finding(needle.label(), path,
                            needle.fragment() ? Finding.Kind.FRAGMENT : Finding.Kind.VALUE, redactable));
                }
            }
        }
    }
}
