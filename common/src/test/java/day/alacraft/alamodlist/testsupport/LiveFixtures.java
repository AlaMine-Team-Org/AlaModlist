package day.alacraft.alamodlist.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers of the service as they were recorded, word for word ({@code contract/live/*.http}): the status line, the
 * headers and the body, exactly as the recording printed them. The tests answer with these, so a field name or a
 * status in a test comes from the service and not from memory.
 *
 * <p>The recording was made against a local instance of the service. Headers that only add noise ({@code Date},
 * {@code Host}, {@code Connection}, {@code Cache-Control}) were left out of the recording; nothing else was touched.
 * Values that differ from request to request (an id, a delete token, a time) are those of one recorded request.
 * A recording is a snapshot: when the service changes, record again and let the tests that read it say what moved.
 */
public final class LiveFixtures {

    /**
     * One recorded answer.
     *
     * @param status  the HTTP status
     * @param headers the headers in the recorded order; names are as recorded
     * @param body    the body, without the line break the file ends with
     */
    public record Recorded(int status, Map<String, String> headers, String body) {

        /** The header with this name, ignoring case, or null. */
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
    }

    private LiveFixtures() {
    }

    /** The recorded answer {@code name} ({@code contract/live/<name>.http}). */
    public static Recorded load(String name) {
        String path = "/contract/live/" + name + ".http";

        try (InputStream in = LiveFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("no recorded answer at " + path);
            }

            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int split = text.indexOf("\n\n");

            if (split < 0) {
                throw new IllegalStateException(path + " has no blank line between headers and body");
            }

            String[] head = text.substring(0, split).split("\n");
            String[] statusLine = head[0].split(" ", 3);

            if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/")) {
                throw new IllegalStateException(path + " does not start with a status line");
            }

            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < head.length; i++) {
                int colon = head[i].indexOf(':');
                if (colon <= 0) {
                    throw new IllegalStateException(path + " has a malformed header line");
                }
                headers.put(head[i].substring(0, colon).trim(), head[i].substring(colon + 1).trim());
            }

            String body = text.substring(split + 2);

            return new Recorded(Integer.parseInt(statusLine[1]), headers,
                    body.endsWith("\n") ? body.substring(0, body.length() - 1) : body);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
