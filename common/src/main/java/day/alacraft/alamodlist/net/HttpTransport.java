package day.alacraft.alamodlist.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLException;

/**
 * The bytes-in, bytes-out half of the connection to the service: one HTTP client, its threads, and the rules that
 * keep a misbehaving server from hurting the game. It knows nothing about the service's contract - the mapping
 * of answers to results lives one package up - so this package has no dependency on the mod's models.
 *
 * <p>Build one per process and keep it: it owns an {@link HttpClient} and a pool of virtual threads. Every method
 * returns immediately with a {@link CompletableFuture}; nothing here may be called on a thread the game needs back,
 * which is why even the gzip pass is meant to run on {@link #executor()}.
 *
 * <p>Rules worth stating, each paid for by a failure of a sibling mod:
 * <ul>
 *   <li><b>No redirects.</b> The JDK would turn a redirected POST into a GET and the body would vanish silently;
 *       a 3xx is surfaced as an answer instead, so the caller can say "check the base URL".</li>
 *   <li><b>A ceiling on the response.</b> The base URL is configurable, so "the server" can be a typo, a captive
 *       portal or a proxy handing back something else entirely; an unbounded read would let any of them take down
 *       the game's JVM.</li>
 *   <li><b>Failures that are not answers</b> (no route, no time, no certificate) are told apart from HTTP errors,
 *       because the advice for each is different.</li>
 * </ul>
 */
public final class HttpTransport implements AutoCloseable {

    /** Long enough for a slow DNS answer, short enough that a dead host is not a hang. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Generous on purpose: a list is posted from a home connection while the service is under load. */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** How long a shutdown waits for a request in flight before cutting it off. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /**
     * The largest response held in memory. Every answer of this API is small - a result, a limits object, an
     * error - so one megabyte is orders of magnitude above the real ones and far below anything that hurts.
     */
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    /**
     * An answer: the status, the body as text (cut at the ceiling) and, when the service said how long to wait,
     * that pause.
     */
    public record Reply(int status, String body, Optional<Duration> retryAfter) {
    }

    private final HttpClient http;
    private final ExecutorService executor;
    private final String baseUrl;
    private final String userAgent;
    private final Duration requestTimeout;

    private HttpTransport(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.userAgent = builder.userAgent;
        this.requestTimeout = builder.requestTimeout;

        // Virtual threads: one per request, no pool to size, and daemon by nature - a forgotten close() cannot
        // keep the JVM from exiting.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .executor(executor)
                // Redirects are NOT followed: see the class comment.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** @throws IllegalArgumentException when {@code baseUrl} is not a usable address ({@link ApiEndpoint}) */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /**
     * The User-Agent the service asks automated clients to send, e.g.
     * {@code AlaModlist/1.0.0 (Minecraft 26.3; fabric)}.
     *
     * <p>It is how our traffic is told apart from a scraper's when someone is looking at why requests fail.
     * Anything that cannot travel in a header is replaced rather than rejected: a version string is not worth
     * failing a request over.
     */
    public static String userAgent(String version, String minecraftVersion, String loader) {
        return "AlaModlist/" + headerSafe(version, "dev")
                + " (Minecraft " + headerSafe(minecraftVersion, "unknown")
                + "; " + headerSafe(loader, "unknown") + ")";
    }

    /**
     * The host this client talks to, for messages that have to name it: someone running their own instance
     * changed {@code apiBaseUrl} precisely because they do not use ours, and naming ours sends them to check a
     * machine that is not theirs.
     *
     * <p>Never null and never empty: {@link Builder} rejects a base URL without an http/https scheme and a host.
     */
    public String host() {
        return URI.create(baseUrl).getHost();
    }

    /** The threads this client runs on; heavy work (compression) is handed here, not run on the caller. */
    public Executor executor() {
        return executor;
    }

    /** A request to {@code path} (starting with a slash) under the base URL, with the standard headers. */
    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
    }

    /**
     * Sends the request. The future completes with the answer whatever its status (a 404 or a 301 is an answer),
     * and fails with a {@link TransportException} - and nothing else - when there is no answer.
     */
    public CompletableFuture<Reply> send(HttpRequest request) {
        return http.sendAsync(request, boundedString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        TransportException classified = transportError(cause);
                        if (cause != null) {
                            classified.initCause(cause);
                        }
                        throw classified;
                    }

                    return new Reply(response.statusCode(), response.body(), retryAfter(response));
                });
    }

    /** The bytes gzip-compressed. Everything is in memory, so an IOException here would mean a bug. */
    public static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 8));

            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(raw);
            }

            return out.toByteArray();
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.INTERNAL,
                    "Could not compress the request: " + describe(e));
        }
    }

    /**
     * Stop accepting work and let go of the threads.
     *
     * <p>Bounded rather than "wait for everything": this runs while the game is shutting down, and a hung request
     * must not be the reason the process refuses to exit.
     */
    @Override
    public void close() {
        http.shutdown();

        try {
            if (!http.awaitTermination(SHUTDOWN_GRACE)) {
                http.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            http.shutdownNow();
        }

        executor.shutdownNow();
    }

    // ---- plumbing ----

    private static HttpResponse.BodyHandler<String> boundedString() {
        return info -> HttpResponse.BodySubscribers.limiting(
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), MAX_RESPONSE_BYTES);
    }

    /**
     * How long the service wants us to wait.
     *
     * <p>Only the delta-seconds form is read. The HTTP-date form is legal but neither Laravel's throttler nor
     * anything in front of it sends one, and guessing wrong about a clock skew would be worse than saying "later".
     */
    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value);

                        return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * A failure that is not the service's answer but the absence of one.
     */
    private static TransportException transportError(Throwable cause) {
        return switch (cause) {
            // Subclass of HttpTimeoutException, so it has to come first. Running
            // out of time while still trying to connect is a reachability
            // problem, not a slow server.
            case HttpConnectTimeoutException e ->
                    new TransportException(TransportException.Kind.OFFLINE, "Timed out connecting: " + describe(e));
            case HttpTimeoutException e ->
                    new TransportException(TransportException.Kind.TIMEOUT, "The request timed out: " + describe(e));
            case UnknownHostException e ->
                    new TransportException(TransportException.Kind.OFFLINE, "Host could not be resolved: " + describe(e));
            case ConnectException e ->
                    new TransportException(TransportException.Kind.OFFLINE, "Connection refused: " + describe(e));
            case SSLException e ->
                    new TransportException(TransportException.Kind.TLS, "The secure connection failed: " + describe(e));
            case IOException e ->
                    new TransportException(TransportException.Kind.OFFLINE, "The connection failed: " + describe(e));
            case null -> new TransportException(TransportException.Kind.INTERNAL, "The request failed for an unknown reason.");
            default -> new TransportException(TransportException.Kind.INTERNAL, describe(cause));
        };
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();

        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    /**
     * A header value with anything unsendable replaced.
     *
     * <p>{@link HttpRequest.Builder#header} throws on characters outside printable ASCII, which would turn an odd
     * mod version or a token pasted with a line break into a failed request with a baffling message.
     */
    public static String headerSafe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        StringBuilder safe = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            safe.append(c >= 0x20 && c <= 0x7e ? c : '.');
        }

        return safe.toString();
    }

    /** Configures a client. Everything but the base URL has a working default. */
    public static final class Builder {

        private final String baseUrl;
        private String userAgent = HttpTransport.userAgent("dev", "unknown", "unknown");
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder(String baseUrl) {
            // Fail here rather than at the first request: a typo in a settings file should be reported when the
            // file is read. What "usable" means is decided by ApiEndpoint, which the settings consult too.
            this.baseUrl = ApiEndpoint.normalise(baseUrl).orElseThrow(() -> new IllegalArgumentException(
                    "The API base URL must be http:// or https:// with a host."));
        }

        /** See {@link HttpTransport#userAgent(String, String, String)} for the expected shape. */
        public Builder userAgent(String userAgent) {
            this.userAgent = headerSafe(userAgent, this.userAgent);

            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = require(connectTimeout, "connectTimeout");

            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = require(requestTimeout, "requestTimeout");

            return this;
        }

        public HttpTransport build() {
            return new HttpTransport(this);
        }

        private static Duration require(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be a positive duration.");
            }

            return value;
        }
    }
}
