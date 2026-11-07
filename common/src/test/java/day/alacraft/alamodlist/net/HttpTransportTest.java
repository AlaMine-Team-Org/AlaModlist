package day.alacraft.alamodlist.net;

import day.alacraft.alamodlist.testsupport.StubSite;
import day.alacraft.alamodlist.testsupport.StubSite.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport against a throwaway server: the rules that keep a misbehaving server from hurting the game - no
 * redirects, a ceiling on the response, failures that are not answers told apart from those that are.
 */
class HttpTransportTest {

    private StubSite site;
    private HttpTransport transport;

    @BeforeEach
    void start() {
        site = new StubSite();
        transport = HttpTransport.builder(site.baseUrl()).build();
    }

    @AfterEach
    void stop() {
        transport.close();
        site.close();
    }

    private HttpTransport.Reply get(String path) throws Exception {
        return transport.send(transport.request(path).GET().build()).get(10, TimeUnit.SECONDS);
    }

    private static TransportException failure(java.util.concurrent.CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));

        return assertInstanceOf(TransportException.class, thrown.getCause(),
                "a transport fails with a TransportException and nothing else");
    }

    /** A redirect is an answer, not something to follow: the JDK would turn a POST into a GET and drop the body. */
    @Test
    void aRedirectIsReportedNotFollowed() throws Exception {
        site.answer(request -> request.uri().getPath().equals("/moved")
                ? Reply.html(301, "").header("Location", "/elsewhere")
                : Reply.json(200, "{\"followed\":true}"));

        HttpTransport.Reply reply = transport.send(transport.request("/moved")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()).get(10, TimeUnit.SECONDS);

        assertEquals(301, reply.status());
        assertEquals(1, site.requests().size(), "the Location must not be visited");
        assertEquals("/moved", site.requests().get(0).uri().getPath());
        assertEquals("POST", site.requests().get(0).method());
    }

    /**
     * The base URL is configurable, so "the server" can be a captive portal that answers with megabytes. The
     * answer is not held: the request fails (as a failed connection, which is what a server like that is).
     */
    @Test
    void aHugeAnswerIsRefusedNotHeldInMemory() {
        site.answer(Reply.json(200, "a".repeat(3 * 1024 * 1024)));

        TransportException error = failure(transport.send(transport.request("/anything").GET().build()));

        assertEquals(TransportException.Kind.OFFLINE, error.kind());

        site.answer(Reply.json(200, "a".repeat((1 << 20) - 10)));
        try {
            assertEquals(200, get("/anything").status(), "armed: an answer under the ceiling is read");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void anyStatusIsAnAnswer() throws Exception {
        site.answer(Reply.html(503, "<html>down</html>"));

        HttpTransport.Reply reply = get("/anything");

        assertEquals(503, reply.status());
        assertEquals("<html>down</html>", reply.body());
        assertTrue(reply.retryAfter().isEmpty());
    }

    @Test
    void retryAfterIsReadInSecondsAndOnlyInSeconds() throws Exception {
        for (Object[] row : new Object[][] {
                {"42", Optional.of(Duration.ofSeconds(42))},
                {" 7 ", Optional.of(Duration.ofSeconds(7))},
                {"0", Optional.of(Duration.ZERO)},
                {"-5", Optional.empty()},
                {"soon", Optional.empty()},
                {"Wed, 21 Oct 2026 07:28:00 GMT", Optional.empty()}}) {
            site.answer(Reply.json(429, "{}").header("Retry-After", (String) row[0]));

            assertEquals(row[1], get("/x").retryAfter(), "Retry-After: " + row[0]);
        }
    }

    @Test
    void aSlowServerIsATimeoutAndNotAServerFailure() {
        site.answer(request -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.json(200, "{}");
        });

        try (HttpTransport quick = HttpTransport.builder(site.baseUrl()).requestTimeout(Duration.ofMillis(300)).build()) {
            TransportException error = failure(quick.send(quick.request("/slow").GET().build()));

            assertEquals(TransportException.Kind.TIMEOUT, error.kind());
        }
    }

    @Test
    void anUnreachableSiteIsOffline() throws IOException {
        int deadPort;

        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }

        try (HttpTransport dead = HttpTransport.builder(
                "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + deadPort).build()) {
            TransportException error = failure(dead.send(dead.request("/x").GET().build()));

            assertEquals(TransportException.Kind.OFFLINE, error.kind());
            assertTrue(error.getCause() != null, "the cause is kept for the log");
        }
    }

    @Test
    void gzipCompressesAndTheOtherSideCanUndoIt() throws IOException {
        byte[] raw = "{\"name\":\"a mod list\"}".repeat(2000).getBytes(StandardCharsets.UTF_8);

        byte[] packed = HttpTransport.gzip(raw);

        assertTrue(packed.length * 5 < raw.length, "a repetitive body shrinks far more than fivefold");
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            assertArrayEquals(raw, in.readAllBytes());
        }
    }

    @Test
    void userAgentFollowsTheDocumentedShape() {
        assertEquals("AlaModlist/1.0.0 (Minecraft 26.3; fabric)", HttpTransport.userAgent("1.0.0", "26.3", "fabric"));
        assertEquals("AlaModlist/dev (Minecraft unknown; neoforge)", HttpTransport.userAgent("", null, "neoforge"),
                "blanks become the documented placeholders");
        assertEquals("AlaModlist/1.0. (Minecraft 26.3; fabric)", HttpTransport.userAgent("1.0\n", "26.3", "fabric"),
                "a header cannot carry a line break; replacing beats failing the request");
    }

    @Test
    void theClientNamesTheHostItActuallyTalksTo() {
        for (String[] row : new String[][] {
                {"https://alacraft.day", "alacraft.day"},
                {"https://site.example.org/base", "site.example.org"},
                {"http://127.0.0.1:8123", "127.0.0.1"}}) {
            try (HttpTransport named = HttpTransport.builder(row[0]).build()) {
                assertEquals(row[1], named.host());
            }
        }
    }

    @Test
    void aBaseUrlThatCannotWorkIsRejectedImmediately() {
        assertThrows(IllegalArgumentException.class, () -> HttpTransport.builder(""));
        assertThrows(IllegalArgumentException.class, () -> HttpTransport.builder("alacraft.day"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpTransport.builder("https://alacraft.day").requestTimeout(Duration.ZERO));
    }
}
