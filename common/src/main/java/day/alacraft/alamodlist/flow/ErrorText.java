package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;

import java.util.List;

/**
 * Which sentence the player reads for a failure, and the numbers in it. One place for chat and screen alike, so they
 * cannot say different things about the same failure.
 *
 * <p>The sentence is chosen by the error <em>code</em>, never by what the service wrote: its wording can change and
 * is whatever {@code apiBaseUrl} points at. The service's text stays in the log.
 */
public final class ErrorText {

    /** The sentence for a site address that cannot be used (the setting is not an http(s) address). */
    public static final String BAD_ADDRESS = "message.alamodlist.error.bad_address";

    private ErrorText() {
    }

    /** What the player was doing when it failed: the same code means different things for a create and a delete. */
    public enum Action {
        SEND, DELETE
    }

    /** The translation key for a failure. Every code has one; an unknown one reads as "could not". */
    public static String key(ApiErrorCode code, Action action) {
        return switch (code) {
            case INVALID_BODY, INVALID_FIELD, UNSUPPORTED_ENCODING, MALFORMED_BODY ->
                    "message.alamodlist.error.contract";
            case TOO_MANY_ENTRIES, TOO_LARGE -> "message.alamodlist.error.limit";
            case INSUFFICIENT_SCOPE -> "message.alamodlist.error.token";
            case RATE_LIMITED -> "message.alamodlist.error.rate_limit";
            case REMOVED, NOT_FOUND -> "message.alamodlist.error.gone";
            case INVALID_TOKEN -> "message.alamodlist.error.delete_token";
            case STORAGE_FAILED, SERVER_ERROR, MALFORMED_RESPONSE, UNKNOWN -> "message.alamodlist.error.server";
            case OFFLINE -> "message.alamodlist.error.offline";
            // A create that timed out was sent: whether the list exists is unknown, and "failed" would be a lie.
            case TIMEOUT -> action == Action.SEND ? "message.alamodlist.error.timeout_unknown"
                    : "message.alamodlist.error.timeout";
            case TLS -> "message.alamodlist.error.tls";
            case GATE_BLOCKED -> "message.alamodlist.error.gate";
            case CANCELLED -> "message.alamodlist.error.cancelled";
            case INVALID_ID -> "message.alamodlist.error.invalid_id";
            case INTERNAL -> "message.alamodlist.error.internal";
        };
    }

    /** The values the sentence takes: the wait, in minutes, for a rate limit; none otherwise. */
    public static List<String> args(ApiError error) {
        if (error.code() == ApiErrorCode.RATE_LIMITED) {
            long seconds = error.retryAfterSeconds(60);

            return List.of(Long.toString(Math.max(1, (seconds + 59) / 60)));
        }

        return List.of();
    }
}
