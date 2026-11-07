package day.alacraft.alamodlist.api;

import com.google.gson.JsonObject;
import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.net.ApiEndpoint;
import day.alacraft.alamodlist.net.HttpTransport;
import day.alacraft.alamodlist.net.TransportException;
import day.alacraft.alamodlist.privacy.Finding;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.privacy.PrivacyGate;
import day.alacraft.alamodlist.privacy.Verdict;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The mod's connection to the Ala Modlist API: create a list, read the limits, delete a list.
 *
 * <p>Build one per process and keep it. Every method returns immediately with a {@link CompletableFuture} that
 * fails with an {@link ApiException} and nothing else; none may be waited on from a thread the game needs back.
 *
 * <p><b>The privacy gate is part of sending.</b> {@link #create} takes the bytes of the request, runs
 * {@link PrivacyGate} on them and gzips <em>the same array</em>: there is no way to send a body the gate has not
 * looked at, and none to change it between the look and the wire. A body the gate objects to - or cannot judge, or
 * is given no values to look for - is not sent; the future fails with {@link ApiErrorCode#GATE_BLOCKED}.
 *
 * <p><b>Nothing is repeated by itself.</b> A create that is sent again can make a second public page and orphan
 * the delete token of the first, so a failure is reported and the player decides ("send again" is a button, with
 * the same snapshot held in memory). The same goes for a timeout: the request was sent, and whether the list
 * exists is unknown ({@link ApiErrorCode#outcomeUnknown()}).
 *
 * <p><b>Bearer means two different things.</b> On create it is the player's account token (optional: it attaches
 * the list to their profile and moves them off the shared per-address limit). On delete it is the one-time token
 * that came back with that very list. They are never interchangeable, so the account token is read through a
 * {@link Supplier} at the moment of use - a changed token works without a restart - and the delete token is a
 * parameter.
 */
public final class ModlistApi implements AutoCloseable {

    /** The service the mod talks to unless told otherwise. */
    public static final String DEFAULT_BASE_URL = "https://alacraft.day";

    private static final String PATH = "/api/v1/modlists";

    private final HttpTransport transport;
    private final Supplier<String> accountToken;
    private final String baseUrl;
    private final AtomicBoolean clearTextWarned = new AtomicBoolean();

    private ModlistApi(Builder builder) {
        this.transport = builder.transport.build();
        this.accountToken = builder.accountToken;
        this.baseUrl = builder.baseUrl;
    }

    /** @throws IllegalArgumentException when {@code baseUrl} is not a usable address */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /** The host this client talks to, for messages that have to name it. */
    public String host() {
        return transport.host();
    }

    /**
     * Creates a list from the exact bytes of the request body (see {@code SnapshotJson.toBytes}).
     *
     * <p>Order: copy the bytes, look at them with the gate, compress that copy, send. All of it off the calling
     * thread.
     *
     * @param body  the request body; not modified, and copied before anything is done with it
     * @param known the values the gate looks for; empty means the caller forgot them, and nothing is sent
     */
    public CompletableFuture<CreateResult> create(byte[] body, KnownValues known) {
        byte[] bytes = body == null ? new byte[0] : body.clone();
        String token = HttpTransport.headerSafe(accountToken.get().strip(), "");

        return CompletableFuture
                .supplyAsync(() -> prepare(bytes, known), transport.executor())
                .thenCompose(gzipped -> {
                    HttpRequest.Builder post = transport.request(PATH)
                            .header("Content-Type", "application/json")
                            .header("Content-Encoding", "gzip")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped));

                    if (!token.isEmpty()) {
                        warnIfTokenTravelsInClear();
                        post.header("Authorization", "Bearer " + token);
                    }

                    return send(post.build(), CreateResult::from);
                });
    }

    /**
     * What the service accepts, fetched now: there is no cache and no start-up call, so the service is asked only
     * when the player has done something that needs the answer.
     */
    public CompletableFuture<Limits> limits() {
        return send(transport.request(PATH + "/limits").GET().build(), Limits::from);
    }

    /**
     * Deletes a list with the token that came back when it was created.
     *
     * <p>{@link ApiErrorCode#NOT_FOUND} means it is already gone - for a delete, the wanted state, and the caller
     * decides whether to treat it so. An {@code id} that cannot be one of ours, or a blank token, fails the future
     * before anything is sent.
     */
    public CompletableFuture<Void> delete(String id, String deleteToken) {
        if (id == null || !CreateResult.LIST_ID.matcher(id).matches()) {
            return CompletableFuture.failedFuture(new ApiException(
                    ApiError.client(ApiErrorCode.INVALID_ID, "Not a list id.")));
        }

        String token = HttpTransport.headerSafe(deleteToken == null ? "" : deleteToken.strip(), "");

        if (token.isEmpty()) {
            return CompletableFuture.failedFuture(new ApiException(
                    ApiError.client(ApiErrorCode.INVALID_TOKEN, "There is no delete token for this list.")));
        }

        return send(transport.request(PATH + "/" + id)
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build(), json -> null);
    }

    @Override
    public void close() {
        transport.close();
    }

    // ---- the gate and the compression: one array, looked at and then sent ----

    private static byte[] prepare(byte[] bytes, KnownValues known) {
        if (known == null || known.isEmpty()) {
            throw new ApiException(ApiError.client(ApiErrorCode.GATE_BLOCKED,
                    "The privacy gate was given no values to look for, so the request was not sent."));
        }

        Verdict verdict = PrivacyGate.check(bytes, known);

        if (!verdict.clean()) {
            throw new ApiException(ApiError.client(ApiErrorCode.GATE_BLOCKED, describe(verdict)));
        }

        return HttpTransport.gzip(bytes);
    }

    /** Words for the log and the preview: what kind and where, never the value (a finding holds none). */
    private static String describe(Verdict verdict) {
        if (verdict.errored()) {
            return "The privacy gate could not check the request (" + verdict.error() + "), so it was not sent.";
        }

        Finding first = verdict.findings().get(0);

        return "The privacy gate stopped the request: " + verdict.findings().size() + " finding(s), the first is "
                + first.label() + " at " + first.path() + ".";
    }

    // ---- answers ----

    private <T> CompletableFuture<T> send(HttpRequest request, Function<JsonObject, T> mapper) {
        return transport.send(request).handle((reply, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);

                throw cause instanceof ApiException api
                        ? api
                        : new ApiException(transportError(cause), cause);
            }

            return mapper.apply(body(reply));
        });
    }

    /** The JSON of a successful answer, or an {@link ApiException} describing why there is none. */
    private static JsonObject body(HttpTransport.Reply reply) {
        int status = reply.status();
        JsonObject json = Json.parseObject(reply.body());

        if (status >= 200 && status < 300) {
            if (json == null) {
                // A 200 that is not JSON is a proxy or a captive portal, not the API. Saying so beats a
                // NullPointerException three frames later.
                throw new ApiException(new ApiError(
                        status,
                        ApiErrorCode.MALFORMED_RESPONSE,
                        "",
                        "The server answered HTTP " + status + " with something that is not JSON.",
                        Optional.empty()));
            }

            // The contract puts failures on non-2xx statuses, so this should be unreachable - but a body that
            // says it failed is believed over a status that says it did not.
            if (json.has("success") && !Json.boolValue(json, "success", true)) {
                throw new ApiException(error(reply, json));
            }

            return json;
        }

        throw new ApiException(error(reply, json));
    }

    /** An error answer, whether or not it was the JSON we expect. */
    private static ApiError error(HttpTransport.Reply reply, JsonObject json) {
        int status = reply.status();
        String rawCode = json == null ? "" : Json.string(json, "error_code");
        String message = json == null ? "" : Json.string(json, "error");

        // An error can arrive from in front of the application - a proxy refusing an oversized body, a gateway
        // timeout - and those answer with HTML. The status is then all we have, and it is enough to reach the
        // right case.
        ApiErrorCode code = rawCode.isEmpty() ? codeForStatus(status) : ApiErrorCode.of(rawCode);

        return new ApiError(
                status,
                code,
                rawCode,
                message.isEmpty() ? fallbackMessage(status) : message,
                reply.retryAfter());
    }

    private static ApiErrorCode codeForStatus(int status) {
        return switch (status) {
            case 401 -> ApiErrorCode.INVALID_TOKEN;
            case 403 -> ApiErrorCode.INSUFFICIENT_SCOPE;
            case 404 -> ApiErrorCode.NOT_FOUND;
            case 410 -> ApiErrorCode.REMOVED;
            case 413 -> ApiErrorCode.TOO_LARGE;
            case 415 -> ApiErrorCode.UNSUPPORTED_ENCODING;
            case 429 -> ApiErrorCode.RATE_LIMITED;
            default -> status >= 500 ? ApiErrorCode.SERVER_ERROR : ApiErrorCode.UNKNOWN;
        };
    }

    private static String fallbackMessage(int status) {
        if (status >= 300 && status < 400) {
            return "The server redirected the request instead of answering it (HTTP " + status
                    + "). Check the API base URL - an http:// address for a site that serves https:// does this.";
        }

        return "The server answered HTTP " + status + " without a readable error message.";
    }

    private static ApiError transportError(Throwable cause) {
        if (cause instanceof TransportException transport) {
            ApiErrorCode code = switch (transport.kind()) {
                case OFFLINE -> ApiErrorCode.OFFLINE;
                case TIMEOUT -> ApiErrorCode.TIMEOUT;
                case TLS -> ApiErrorCode.TLS;
                case INTERNAL -> ApiErrorCode.INTERNAL;
            };

            return ApiError.client(code, transport.getMessage());
        }

        return ApiError.client(ApiErrorCode.INTERNAL, cause == null ? "Unknown failure." : cause.toString());
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    /**
     * Says so once when an account token is about to travel over plain HTTP.
     *
     * <p>{@code http://} stays allowed - development against a local site is normal. What is not normal is
     * keeping that address with a real token in it. Loopback is exempt because nothing leaves the machine there.
     */
    private void warnIfTokenTravelsInClear() {
        URI parsed = URI.create(baseUrl);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.equals("::1");

        if (scheme.equals("http") && !loopback && clearTextWarned.compareAndSet(false, true)) {
            AlaModlist.LOGGER.warn("apiBaseUrl uses http://, so the account token will be sent unencrypted to {}. "
                    + "Use https:// unless this is a local instance.", host);
        }
    }

    /** Configures a client. Everything but the base URL has a working default. */
    public static final class Builder {

        private final String baseUrl;
        private final HttpTransport.Builder transport;
        private Supplier<String> accountToken = () -> "";

        private Builder(String baseUrl) {
            this.transport = HttpTransport.builder(baseUrl);
            this.baseUrl = ApiEndpoint.normalise(baseUrl).orElseThrow();
        }

        /** See {@link HttpTransport#userAgent(String, String, String)} for the expected shape. */
        public Builder userAgent(String userAgent) {
            transport.userAgent(userAgent);

            return this;
        }

        /**
         * Where the account token comes from, read at each create (empty or null: anonymous). A supplier, not a
         * value, so that a token the player changes works at once and no long-lived object holds a copy.
         */
        public Builder accountToken(Supplier<String> accountToken) {
            this.accountToken = accountToken == null ? () -> "" : () -> {
                String value = accountToken.get();

                return value == null ? "" : value;
            };

            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            transport.connectTimeout(connectTimeout);

            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            transport.requestTimeout(requestTimeout);

            return this;
        }

        public ModlistApi build() {
            return new ModlistApi(this);
        }
    }
}
