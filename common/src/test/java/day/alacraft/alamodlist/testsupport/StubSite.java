package day.alacraft.alamodlist.testsupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * A throwaway HTTP server on the loopback address, standing in for the service.
 *
 * <p>Never the real service: these tests run offline, in CI, on every build. It records every request (so a test can
 * say "nothing reached the wire") and answers with whatever the test sets, usually a recorded answer
 * ({@link LiveFixtures}).
 */
public final class StubSite implements AutoCloseable {

    /** What the server received. {@code body} is already decompressed; {@code rawBytes} are the bytes on the wire. */
    public record Request(String method, URI uri, Map<String, String> headers, byte[] rawBytes, byte[] bodyBytes) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String body() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }

        public JsonObject json() {
            return JsonParser.parseString(body()).getAsJsonObject();
        }
    }

    /** What the server answers with. */
    public record Reply(int status, Map<String, String> headers, String body) {

        public static Reply of(LiveFixtures.Recorded recorded) {
            return new Reply(recorded.status(), new LinkedHashMap<>(recorded.headers()), recorded.body());
        }

        public static Reply json(int status, String body) {
            return new Reply(status, new LinkedHashMap<>(Map.of("Content-Type", "application/json")), body);
        }

        public static Reply html(int status, String body) {
            return new Reply(status, new LinkedHashMap<>(Map.of("Content-Type", "text/html")), body);
        }

        public Reply header(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(headers);
            copy.put(name, value);

            return new Reply(status, copy, body);
        }
    }

    private final HttpServer server;
    private final ExecutorService threads;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Request, Reply> responder = request -> Reply.json(200, "{\"success\":true}");

    public StubSite() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the stub site", e);
        }

        // Daemon threads: one test parks a handler on purpose to force a timeout, and a live non-daemon thread would
        // keep the test JVM from exiting.
        threads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-site");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(threads);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The site root, for example {@code http://127.0.0.1:41234}. */
    public String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void answer(Function<Request, Reply> responder) {
        this.responder = responder;
    }

    public void answer(Reply reply) {
        this.responder = request -> reply;
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        boolean gzipped = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"));
        byte[] decoded = gzipped ? gunzip(raw) : raw;

        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));

        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(), headers, raw, decoded);
        requests.add(request);

        Reply reply = responder.apply(request);
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);

        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);

        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static byte[] gunzip(byte[] raw) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return in.readAllBytes();
        }
    }
}
