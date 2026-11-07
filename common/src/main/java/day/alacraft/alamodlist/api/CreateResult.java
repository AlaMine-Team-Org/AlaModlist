package day.alacraft.alamodlist.api;

import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * A stored list: the link to hand the player, and the token that deletes it.
 *
 * <p>There is no {@code success} field, though the wire has one: it is always {@code true} here - anything else
 * completes the future with an {@link ApiException} - and a field that can only hold one value is a field every
 * caller has to read and none can learn from.
 *
 * <p><b>The delete token is a secret.</b> The service returns it exactly once and keeps only its hash, so a token
 * not saved now is a list that can never be taken back; but it must also never reach a chat line, a log or a
 * screen. This record therefore keeps it out of {@link #toString()}; the one place that reads it is the code that
 * stores it.
 *
 * @param id          the public id, for example {@code t4pmmVSebjX8}
 * @param url         the page of the list; the line that goes into chat (the service builds it in the player's
 *                    language; only {@code http} and {@code https} addresses are accepted here)
 * @param deleteToken the one-time delete token, or empty when the service sent none
 * @param counts      how many entries of each kind were stored
 * @param truncated   true when a text was cut to its field limit
 * @param expires     when the list expires unless it is viewed, if the service said
 */
public record CreateResult(String id, String url, String deleteToken, Counts counts, boolean truncated,
                           Optional<Instant> expires) {

    /** The shape of a public id, with room to spare; an id is put into a URL, so nothing else may pass. */
    static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /**
     * Entries stored per kind. {@code matched} is absent ({@code null} on the wire) in the answer to a create: the
     * matching with Modrinth has not run yet.
     */
    public record Counts(int mods, int libraries, int builtin, int resourcePacks, int shaderPacks, int dataPacks,
                         int plugins, OptionalInt matched) {

        static final Counts NONE = new Counts(0, 0, 0, 0, 0, 0, 0, OptionalInt.empty());

        static Counts from(JsonObject json) {
            if (json == null) {
                return NONE;
            }

            return new Counts(
                    Json.intValue(json, "mods", 0),
                    Json.intValue(json, "libraries", 0),
                    Json.intValue(json, "builtin", 0),
                    Json.intValue(json, "resource_packs", 0),
                    Json.intValue(json, "shader_packs", 0),
                    Json.intValue(json, "data_packs", 0),
                    Json.intValue(json, "plugins", 0),
                    Json.optionalInt(json, "matched"));
        }
    }

    public CreateResult {
        id = id == null ? "" : id;
        url = url == null ? "" : url;
        deleteToken = deleteToken == null ? "" : deleteToken;
        counts = counts == null ? Counts.NONE : counts;
        expires = expires == null ? Optional.empty() : expires;
    }

    /** True when the list can still be deleted by the player - the token came back and was kept. */
    public boolean isDeletable() {
        return !deleteToken.isBlank();
    }

    @Override
    public String toString() {
        return "CreateResult[id=" + id + ", url=" + url + ", deleteToken=" + (deleteToken.isEmpty() ? "none" : "hidden")
                + ", counts=" + counts + ", truncated=" + truncated + ", expires=" + expires + "]";
    }

    /**
     * Reads the answer to a create. Names are those of the recorded live answer, not remembered ones.
     *
     * @throws ApiException {@link ApiErrorCode#MALFORMED_RESPONSE} when the id or the link is not usable: a
     *                      "success" that could not be shown to the player is no success
     */
    static CreateResult from(JsonObject json) {
        String id = Json.string(json, "id");
        String url = Json.string(json, "url");

        if (!LIST_ID.matcher(id).matches() || !isWebAddress(url)) {
            throw new ApiException(ApiError.client(ApiErrorCode.MALFORMED_RESPONSE,
                    "The service answered with a list that has no usable id or link."));
        }

        return new CreateResult(
                id,
                url,
                Json.string(json, "delete_token"),
                Counts.from(Json.object(json, "counts")),
                Json.boolValue(json, "truncated", false),
                instant(Json.string(json, "expires")));
    }

    private static boolean isWebAddress(String url) {
        try {
            URI parsed = URI.create(url);
            String scheme = parsed.getScheme();

            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && parsed.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Optional<Instant> instant(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
