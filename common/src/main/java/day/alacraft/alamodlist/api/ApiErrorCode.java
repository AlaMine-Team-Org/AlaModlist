package day.alacraft.alamodlist.api;

/**
 * Every distinct way a call can fail, as one closed set.
 *
 * <p>The first group mirrors the {@code error_code} field of the service's error bodies; the rest are produced on
 * this side, for failures that never reach the service or come back unreadable. They share one enum on purpose:
 * the caller's question is always "what do I tell the player", and that has one answer per constant whichever side
 * produced it. The answer is the {@link Category}, and the mod's own translations are keyed off it - the wording
 * of the service's {@code error} sentence can change and is never shown as it is.
 *
 * <p>Unrecognised wire codes become {@link #UNKNOWN} rather than an exception: the service may add a code before
 * the mod knows about it, and a request must not fail differently because of a string not seen before.
 * {@link ApiError#rawCode()} keeps what was actually said.
 */
public enum ApiErrorCode {

    // ---- Reported by the service, in the error_code field ----

    /** The body was not a JSON object (also an empty body, an array or a string). */
    INVALID_BODY("invalid_body", Category.CONTRACT_MISMATCH),

    /** A field is not part of the contract, or has the wrong shape; the response names it in {@code field}. */
    INVALID_FIELD("invalid_field", Category.CONTRACT_MISMATCH),

    /** A list is longer than the service accepts (it refuses, it does not cut). */
    TOO_MANY_ENTRIES("too_many_entries", Category.LIMIT),

    /** The body is larger than the service accepts, also after decompression. */
    TOO_LARGE("too_large", Category.LIMIT),

    /** The account token is real but not allowed to create lists. */
    INSUFFICIENT_SCOPE("insufficient_scope", Category.TOKEN),

    /** Too many requests; {@link ApiError#retryAfter()} says how long to wait. */
    RATE_LIMITED("rate_limited", Category.RATE_LIMIT),

    /** A moderator took the list down. Distinct from {@link #NOT_FOUND}: it existed. */
    REMOVED("removed", Category.GONE),

    /** No such list, or it expired. Retention runs from the last view. */
    NOT_FOUND("not_found", Category.GONE),

    /** The delete token is wrong or missing: the list stays. */
    INVALID_TOKEN("invalid_token", Category.DELETE_TOKEN),

    /** The service could not store the list. */
    STORAGE_FAILED("storage_failed", Category.SERVER),

    /** The service does not understand the {@code Content-Encoding} sent. */
    UNSUPPORTED_ENCODING("unsupported_encoding", Category.CONTRACT_MISMATCH),

    /** The compressed body could not be decompressed. */
    MALFORMED_BODY("malformed_body", Category.CONTRACT_MISMATCH),

    // ---- Derived here, from a response that carried no usable code ----

    /** The service answered 5xx. Its problem, not the player's. */
    SERVER_ERROR("server_error", Category.SERVER),

    /** An answer arrived that was not the JSON this API promises (a proxy page, a captive portal). */
    MALFORMED_RESPONSE("malformed_response", Category.SERVER),

    /** An error not recognised. {@link ApiError#rawCode()} keeps what the service actually said. */
    UNKNOWN("unknown", Category.SERVER),

    // ---- Produced here, without a usable answer from the service ----

    /** The service could not be reached at all: refused, unroutable, an unresolvable host. */
    OFFLINE("network_offline", Category.NETWORK),

    /** Reached, but out of time. For a create this means the list may or may not exist. */
    TIMEOUT("network_timeout", Category.NETWORK),

    /** The TLS handshake failed: a certificate or protocol problem, not a missing network. */
    TLS("network_tls", Category.NETWORK),

    /** The privacy gate refused the body: nothing was sent. */
    GATE_BLOCKED("gate_blocked", Category.CLIENT),

    /** The player cancelled; nothing was sent or the answer is ignored. */
    CANCELLED("cancelled", Category.CLIENT),

    /** An id that cannot be one of ours (it would have been put into a URL): nothing was sent. */
    INVALID_ID("invalid_id", Category.CLIENT),

    /** A bug on this side. If a player ever sees this, it was written wrong. */
    INTERNAL("client_internal", Category.CLIENT);

    /** What the player is told, in kinds. Each kind has its own translated sentence in the mod. */
    public enum Category {
        /** The mod and the service disagree about the format: "update the mod". Never retried automatically. */
        CONTRACT_MISMATCH,
        /** The list is too big for the service: "drop a category". */
        LIMIT,
        /** The account token is not accepted: "issue a new token in your profile". */
        TOKEN,
        /** "Wait N minutes"; the snapshot stays in memory. */
        RATE_LIMIT,
        /** The list is already gone: for a delete, that is the wanted state. */
        GONE,
        /** The delete token is not accepted: the record stays, with an explanation. */
        DELETE_TOKEN,
        /** "The site is unavailable right now". */
        SERVER,
        /** The network: reachability, timeout, certificate. */
        NETWORK,
        /** A refusal or a failure on this side; nothing reached the service. */
        CLIENT
    }

    private final String wireName;
    private final Category category;

    ApiErrorCode(String wireName, Category category) {
        this.wireName = wireName;
        this.category = category;
    }

    /** The string form: what the service sends, or a matching name for the client-side codes. */
    public String wireName() {
        return wireName;
    }

    public Category category() {
        return category;
    }

    /** The code for a wire string, or {@link #UNKNOWN} for anything unrecognised. */
    public static ApiErrorCode of(String wireName) {
        if (wireName != null && !wireName.isBlank()) {
            for (ApiErrorCode code : values()) {
                if (code.wireName.equals(wireName)) {
                    return code;
                }
            }
        }
        return UNKNOWN;
    }

    /** True when the request never got an answer, so nothing was created or deleted for certain. */
    public boolean isTransport() {
        return this == OFFLINE || this == TIMEOUT || this == TLS;
    }

    /**
     * True when the same request could plausibly succeed later.
     *
     * <p>Deliberately narrow, and advisory: nothing in the mod repeats a create by itself, because a repeat can
     * make a second public page and orphan the first delete token. "Send again" is the player's button.
     */
    public boolean isRetryable() {
        return this == RATE_LIMITED || this == SERVER_ERROR || this == STORAGE_FAILED || this == OFFLINE
                || this == TIMEOUT;
    }

    /**
     * True when a create ended without knowing whether the list exists on the service: only a timeout, where the
     * request was sent and no answer came. The player must not be told "it failed".
     */
    public boolean outcomeUnknown() {
        return this == TIMEOUT;
    }
}
