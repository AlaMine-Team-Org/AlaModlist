package day.alacraft.alamodlist.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.net.HttpTransport;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.testsupport.Canaries;
import day.alacraft.alamodlist.testsupport.LiveFixtures;
import day.alacraft.alamodlist.testsupport.SnapshotSeed;
import day.alacraft.alamodlist.testsupport.StubSite;
import day.alacraft.alamodlist.testsupport.StubSite.Reply;
import day.alacraft.alamodlist.wire.SnapshotJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The API client against a throwaway server that answers with the service's recorded answers, word for word
 * ({@code contract/live}). Never against the real service: the tests run offline and on every build.
 *
 * <p>What is asserted about the wire is asserted on what the server received, not on what the client says it sent.
 */
class ModlistApiTest {

    private static final String PATH = "/api/v1/modlists";
    private static final KnownValues KNOWN = Canaries.known();

    private StubSite site;
    private final List<ModlistApi> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        site = new StubSite();
    }

    @AfterEach
    void stop() {
        clients.forEach(ModlistApi::close);
        site.close();
    }

    private ModlistApi api() {
        ModlistApi api = ModlistApi.builder(site.baseUrl())
                .userAgent(HttpTransport.userAgent("0.1.0", "26.3", "fabric")).build();
        clients.add(api);

        return api;
    }

    /** A body that passes the gate: the harmless seeded snapshot, written by the one writer there is. */
    private static byte[] cleanBody() {
        return SnapshotJson.toBytes(SnapshotSeed.benign());
    }

    /** A body that leaks: the player's name planted in the title. */
    private static byte[] leakyBody() {
        return SnapshotJson.toBytes(SnapshotSeed.build(path -> path.equals("title") ? Canaries.NAME : null));
    }

    private static ApiError failure(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));

        return ApiException.of(thrown).error();
    }

    private static <T> T result(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private ApiError createFails(StubSite.Reply reply) {
        site.answer(reply);

        return failure(api().create(cleanBody(), KNOWN));
    }

    // ---- create: what is sent ----

    @Test
    void createSendsTheCheckedBytesGzippedAndReadsTheRecordedAnswer() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("create-201")));
        byte[] body = cleanBody();

        CreateResult created = result(api().create(body, KNOWN));

        StubSite.Request request = site.requests().get(0);
        assertEquals(1, site.requests().size());
        assertEquals("POST", request.method());
        assertEquals(PATH, request.uri().getPath());
        assertEquals("gzip", request.header("Content-Encoding"));
        assertEquals("application/json", request.header("Content-Type"));
        assertEquals("application/json", request.header("Accept"));
        assertEquals("AlaModlist/0.1.0 (Minecraft 26.3; fabric)", request.header("User-Agent"));
        assertNull(request.header("Authorization"), "anonymous unless the player has an account token");
        assertArrayEquals(body, request.bodyBytes(), "the server got exactly the bytes the gate looked at");
        assertTrue(request.rawBytes().length < request.bodyBytes().length, "and they went compressed");

        // The names below are those of the recorded answer; the escaped slashes in it were read as JSON.
        assertEquals("t4pmmVSebjX8", created.id());
        assertEquals("http://127.0.0.1:8061/ru/modlists/t4pmmVSebjX8", created.url());
        assertEquals("lcsrHBmvC53DuNIIesf1wjs9MvR8l3T8RwpOBQG6JGJT5stsl6YVAlkthwrbFbws", created.deleteToken());
        assertTrue(created.isDeletable());
        assertEquals(new CreateResult.Counts(1, 1, 1, 1, 0, 0, 1, java.util.OptionalInt.empty()), created.counts(),
                "matched is null in the answer to a create, and means 'not matched yet'");
        assertFalse(created.truncated());
        assertEquals(Instant.parse("2027-02-05T04:28:50Z"), created.expires().orElseThrow());
    }

    @Test
    void theGzipRecordingParsesToTheSameShape() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("create-201-gzip")));

        CreateResult created = result(api().create(cleanBody(), KNOWN));

        assertEquals("fsYRUuz9eGhW", created.id());
        assertEquals(new CreateResult.Counts(1, 1, 1, 1, 0, 0, 1, java.util.OptionalInt.empty()), created.counts());
    }

    @Test
    void theAccountTokenIsReadAtTheMomentOfTheCallAndSentAsBearer() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("create-201-account")));
        AtomicReference<String> token = new AtomicReference<>("first-account-token");
        ModlistApi api = ModlistApi.builder(site.baseUrl()).accountToken(token::get).build();
        clients.add(api);

        result(api.create(cleanBody(), KNOWN));
        token.set("second-account-token");
        result(api.create(cleanBody(), KNOWN));
        token.set("");
        result(api.create(cleanBody(), KNOWN));

        List<StubSite.Request> requests = site.requests();
        assertEquals("Bearer first-account-token", requests.get(0).header("Authorization"));
        assertEquals("Bearer second-account-token", requests.get(1).header("Authorization"),
                "a changed token works without a restart");
        assertNull(requests.get(2).header("Authorization"), "a cleared token means anonymous");
    }

    @Test
    void aTokenWithALineBreakDoesNotBreakTheRequest() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("create-201-account")));
        ModlistApi api = ModlistApi.builder(site.baseUrl()).accountToken(() -> "pasted-token\n").build();
        clients.add(api);

        result(api.create(cleanBody(), KNOWN));

        assertEquals("Bearer pasted-token", site.requests().get(0).header("Authorization"));
    }

    @Test
    void theCallerCannotChangeTheBodyBetweenTheLookAndTheWire() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("create-201")));
        byte[] body = cleanBody();
        byte[] original = body.clone();

        CompletableFuture<CreateResult> pending = api().create(body, KNOWN);
        Arrays.fill(body, (byte) 'x');
        result(pending);

        assertArrayEquals(original, site.requests().get(0).bodyBytes());
    }

    // ---- the privacy gate is part of sending ----

    @Test
    void aBodyTheGateObjectsToNeverReachesTheWire() {
        site.answer(Reply.of(LiveFixtures.load("create-201")));

        // Armed: the same call with the clean body does reach the wire, so the gate is the only difference.
        assertDoesNotThrowOnCreate(cleanBody());
        int before = site.requests().size();
        assertEquals(1, before);

        ApiError error = failure(api().create(leakyBody(), KNOWN));

        assertEquals(ApiErrorCode.GATE_BLOCKED, error.code());
        assertEquals(ApiErrorCode.Category.CLIENT, error.code().category());
        assertEquals(0, error.status(), "there was no HTTP response to have a status");
        assertEquals(before, site.requests().size(), "nothing was sent after the gate objected");
        assertFalse(error.message().contains(Canaries.NAME), "the message names the kind and the place, never the value");
        assertTrue(error.message().contains("title"), error.message());
    }

    private void assertDoesNotThrowOnCreate(byte[] body) {
        try {
            result(api().create(body, KNOWN));
        } catch (Exception e) {
            throw new AssertionError("the clean body should have been sent", e);
        }
    }

    @Test
    void aGateGivenNothingToLookForSendsNothing() {
        site.answer(Reply.of(LiveFixtures.load("create-201")));

        assertEquals(ApiErrorCode.GATE_BLOCKED,
                failure(api().create(cleanBody(), KnownValues.builder().build())).code());
        assertEquals(ApiErrorCode.GATE_BLOCKED, failure(api().create(cleanBody(), null)).code());
        assertTrue(site.requests().isEmpty());
    }

    @Test
    void aBodyThatIsNotAJsonDocumentIsNotSent() {
        site.answer(Reply.of(LiveFixtures.load("create-201")));

        for (byte[] junk : new byte[][] {"not json".getBytes(StandardCharsets.UTF_8), new byte[0], {(byte) 0xC3, 0x28}}) {
            assertEquals(ApiErrorCode.GATE_BLOCKED, failure(api().create(junk, KNOWN)).code());
        }
        assertEquals(ApiErrorCode.GATE_BLOCKED, failure(api().create(null, KNOWN)).code());
        assertTrue(site.requests().isEmpty(), "the gate fails closed");
    }

    // ---- create: what comes back ----

    @Test
    void everyRecordedErrorIsTypedAndKeepsWhatTheServiceSaid() {
        Map<String, Object[]> expected = Map.of(
                "error-invalid_field-unknown-key", new Object[] {400, ApiErrorCode.INVALID_FIELD, "invalid_field"},
                "error-invalid_field-path", new Object[] {400, ApiErrorCode.INVALID_FIELD, "invalid_field"},
                "error-invalid_body", new Object[] {400, ApiErrorCode.INVALID_BODY, "invalid_body"},
                "error-too_many_entries", new Object[] {400, ApiErrorCode.TOO_MANY_ENTRIES, "too_many_entries"},
                "error-too_large", new Object[] {413, ApiErrorCode.TOO_LARGE, "too_large"},
                "error-too_large-inflated", new Object[] {413, ApiErrorCode.TOO_LARGE, "too_large"},
                "error-unsupported_encoding", new Object[] {415, ApiErrorCode.UNSUPPORTED_ENCODING, "unsupported_encoding"},
                "error-malformed_body", new Object[] {400, ApiErrorCode.MALFORMED_BODY, "malformed_body"},
                "error-insufficient_scope", new Object[] {403, ApiErrorCode.INSUFFICIENT_SCOPE, "insufficient_scope"},
                "error-rate_limited", new Object[] {429, ApiErrorCode.RATE_LIMITED, "rate_limited"});

        for (Map.Entry<String, Object[]> row : expected.entrySet()) {
            ApiError error = createFails(Reply.of(LiveFixtures.load(row.getKey())));

            assertEquals(row.getValue()[0], error.status(), row.getKey());
            assertEquals(row.getValue()[1], error.code(), row.getKey());
            assertEquals(row.getValue()[2], error.rawCode(), row.getKey());
            assertFalse(error.message().isBlank(), row.getKey());
        }
    }

    @Test
    void anInvalidFieldNamesTheFieldInTheMessage() {
        ApiError error = createFails(Reply.of(LiveFixtures.load("error-invalid_field-unknown-key")));

        assertTrue(error.message().contains("mods[0].hashes.sha512"), error.message());
        assertEquals(ApiErrorCode.Category.CONTRACT_MISMATCH, error.code().category());
        assertFalse(error.isRetryable(), "a contract mismatch is not cured by sending again");
    }

    @Test
    void rateLimitingSaysHowLongToWaitAndIsNotRepeatedByItself() {
        ApiError error = createFails(Reply.of(LiveFixtures.load("error-rate_limited")));

        assertEquals(ApiErrorCode.Category.RATE_LIMIT, error.code().category());
        assertEquals(Duration.ofSeconds(3596), error.retryAfter().orElseThrow());
        assertEquals(3596L, error.retryAfterSeconds(0));
        assertTrue(error.isRetryable());
        assertFalse(error.isTransport());
        assertEquals(1, site.requests().size(), "a create is never sent again by itself");
    }

    @Test
    void everyErrorCodeOfEveryRecordedErrorIsKnown() throws Exception {
        Path directory = Path.of(ModlistApiTest.class.getResource("/contract/live").toURI());
        List<String> names;

        try (Stream<Path> files = Files.list(directory)) {
            names = files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("error-"))
                    .map(n -> n.substring(0, n.length() - ".http".length())).sorted().toList();
        }

        assertTrue(names.size() >= 10, "armed: the recordings were found, not an empty directory");
        for (String name : names) {
            JsonObject body = JsonParser.parseString(LiveFixtures.load(name).body()).getAsJsonObject();
            String code = body.get("error_code").getAsString();

            assertNotEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.of(code), name + " carries '" + code
                    + "', which the client does not know");
            assertEquals(code, ApiErrorCode.of(code).wireName());
        }
    }

    // Two codes have no recording: 410 needs a list taken down by a moderator, and 500 needs a broken database.
    // The bodies below are therefore built by hand in the service's general error format - they test the mapping,
    // not the service.
    @Test
    void theCodesWithoutARecordingMapByTheirName() {
        ApiError gone = createFails(Reply.json(410,
                "{\"success\":false,\"error\":\"This list was removed.\",\"error_code\":\"removed\"}"));
        assertEquals(ApiErrorCode.REMOVED, gone.code());
        assertEquals(ApiErrorCode.Category.GONE, gone.code().category());

        ApiError storage = createFails(Reply.json(500,
                "{\"success\":false,\"error\":\"Could not store the list.\",\"error_code\":\"storage_failed\"}"));
        assertEquals(ApiErrorCode.STORAGE_FAILED, storage.code());
        assertTrue(storage.isRetryable());
    }

    @Test
    void anHtmlErrorPageFromAProxyStillLandsOnTheRightCase() {
        ApiError tooLarge = createFails(Reply.html(413, "<html><title>413 Request Entity Too Large</title></html>"));
        assertEquals(ApiErrorCode.TOO_LARGE, tooLarge.code());
        assertEquals("", tooLarge.rawCode());
        assertFalse(tooLarge.message().isBlank(), "a fallback sentence is expected");

        ApiError bad = createFails(Reply.html(502, "<html>502 Bad Gateway</html>"));
        assertEquals(ApiErrorCode.SERVER_ERROR, bad.code());
        assertTrue(bad.isRetryable());
    }

    @Test
    void aSuccessThatIsNotJsonIsReportedAsSuch() {
        ApiError error = createFails(Reply.html(200, "<html>Sign in to continue</html>"));

        assertEquals(ApiErrorCode.MALFORMED_RESPONSE, error.code());
        assertEquals(200, error.status());
    }

    @Test
    void aRedirectSaysToCheckTheUrlAndIsNotFollowed() {
        ApiError error = createFails(Reply.html(301, "").header("Location", "http://127.0.0.1:1/moved"));

        assertEquals(301, error.status());
        assertTrue(error.message().contains("base URL"), error.message());
        assertEquals(1, site.requests().size());
    }

    @Test
    void anUnknownErrorCodeIsKeptRatherThanLost() {
        ApiError error = createFails(Reply.json(422,
                "{\"success\":false,\"error\":\"Something new.\",\"error_code\":\"brand_new_code\"}"));

        assertEquals(ApiErrorCode.UNKNOWN, error.code());
        assertEquals("brand_new_code", error.rawCode());
        assertEquals("Something new.", error.message());
    }

    @Test
    void theServicesSentenceIsCleanedBeforeItCanReachAScreen() {
        String signCode = "§" + "c";
        ApiError error = createFails(Reply.json(400, "{\"success\":false,\"error\":\"" + signCode
                + "Red alert\\u0007 " + "x".repeat(2000) + "\",\"error_code\":\"invalid_body\"}"));

        assertFalse(error.message().contains("§"), "no formatting code");
        assertFalse(error.message().contains("\u0007"), "no control character");
        assertTrue(error.message().length() <= 512, "and a ceiling on the length");
        assertTrue(error.message().startsWith("Red alert"), error.message());
    }

    @Test
    void aSuccessWithoutAUsableIdOrLinkIsNotASuccess() {
        JsonObject answer = JsonParser.parseString(LiveFixtures.load("create-201").body()).getAsJsonObject();

        for (String[] bad : new String[][] {
                {"url", "javascript:alert(1)"}, {"url", "file:///etc/hosts"}, {"url", "not a url"}, {"url", ""},
                {"id", "../admin"}, {"id", ""}, {"id", "a".repeat(40)}}) {
            JsonObject broken = answer.deepCopy();
            broken.addProperty(bad[0], bad[1]);
            site.answer(Reply.json(201, broken.toString()));

            assertEquals(ApiErrorCode.MALFORMED_RESPONSE, failure(api().create(cleanBody(), KNOWN)).code(),
                    bad[0] + "=" + bad[1]);
        }
    }

    @Test
    void aTimeoutMeansTheOutcomeIsUnknown() {
        site.answer(request -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.of(LiveFixtures.load("create-201"));
        });
        ModlistApi api = ModlistApi.builder(site.baseUrl()).requestTimeout(Duration.ofMillis(300)).build();
        clients.add(api);

        ApiError error = failure(api.create(cleanBody(), KNOWN));

        assertEquals(ApiErrorCode.TIMEOUT, error.code());
        assertTrue(error.code().outcomeUnknown(), "the request was sent; whether the list exists is not known");
        assertTrue(error.isTransport());
        assertEquals(0, error.status());
    }

    @Test
    void anUnreachableSiteIsNotAnApiError() throws IOException {
        int deadPort;

        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }

        ModlistApi api = ModlistApi.builder("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + deadPort).build();
        clients.add(api);

        ApiError error = failure(api.create(cleanBody(), KNOWN));

        assertEquals(ApiErrorCode.OFFLINE, error.code());
        assertEquals(ApiErrorCode.Category.NETWORK, error.code().category());
        assertFalse(error.code().outcomeUnknown(), "never connected: nothing was created");
    }

    // ---- limits ----

    @Test
    void limitsAreReadFromTheRecordedAnswerOnlyWhenAsked() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("limits-200")));
        ModlistApi api = api();

        assertTrue(site.requests().isEmpty(), "building the client asks the service nothing");

        Limits limits = result(api.limits());

        assertEquals(1, site.requests().size());
        assertEquals("GET", site.requests().get(0).method());
        assertEquals(PATH + "/limits", site.requests().get(0).uri().getPath());
        assertEquals(2000, limits.maxEntries());
        assertEquals(1_048_576L, limits.maxBytes());
        assertEquals(90, limits.storageDays());
    }

    // ---- delete ----

    @Test
    void deleteUsesTheListsOwnTokenNotTheAccountOne() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("delete-200")));
        ModlistApi api = ModlistApi.builder(site.baseUrl()).accountToken(() -> "account-token").build();
        clients.add(api);

        result(api.delete("t4pmmVSebjX8", "the-lists-own-token"));

        StubSite.Request request = site.requests().get(0);
        assertEquals("DELETE", request.method());
        assertEquals(PATH + "/t4pmmVSebjX8", request.uri().getPath());
        assertEquals("Bearer the-lists-own-token", request.header("Authorization"));
    }

    @Test
    void deleteWithTheWrongTokenIsTold() {
        site.answer(Reply.of(LiveFixtures.load("error-invalid_token")));

        ApiError error = failure(api().delete("t4pmmVSebjX8", "wrong"));

        assertEquals(ApiErrorCode.INVALID_TOKEN, error.code());
        assertEquals(ApiErrorCode.Category.DELETE_TOKEN, error.code().category());
        assertFalse(error.isRetryable(), "sending a rejected token again only burns the rate limit");
        assertFalse(error.message().contains("wrong"));
    }

    @Test
    void deletingAListThatIsGoneIsReported() {
        site.answer(Reply.of(LiveFixtures.load("error-not_found")));

        ApiError error = failure(api().delete("t4pmmVSebjX8", "token"));

        assertEquals(ApiErrorCode.NOT_FOUND, error.code());
        assertEquals(ApiErrorCode.Category.GONE, error.code().category());
    }

    @Test
    void deleteRefusesWhatCannotBeAnIdOrATokenBeforeAnythingIsSent() {
        ModlistApi api = api();

        for (String id : new String[] {"../../admin", "", " ", "a/b", "a b", "limits?x=1", "x".repeat(33)}) {
            assertEquals(ApiErrorCode.INVALID_ID, failure(api.delete(id, "token")).code(), "'" + id + "'");
        }
        assertEquals(ApiErrorCode.INVALID_ID, failure(api.delete(null, "token")).code());
        assertEquals(ApiErrorCode.INVALID_TOKEN, failure(api.delete("t4pmmVSebjX8", "")).code());
        assertEquals(ApiErrorCode.INVALID_TOKEN, failure(api.delete("t4pmmVSebjX8", null)).code());
        assertTrue(site.requests().isEmpty(), "a refused id must not reach the network");
    }

    // ---- the delete token is a secret ----

    @Test
    void theDeleteTokenIsNotInAnyTextTheResultCanProduce() throws Exception {
        site.answer(Reply.of(LiveFixtures.load("create-201")));
        CreateResult created = result(api().create(cleanBody(), KNOWN));
        String token = created.deleteToken();

        assertFalse(token.isEmpty(), "armed: the token really came back");
        assertFalse(created.toString().contains(token), "toString is what a log line or a chat line is made of");
        assertTrue(created.toString().contains(created.id()));
    }

    // ---- construction ----

    @Test
    void aBaseUrlThatCannotWorkIsRejectedImmediately() {
        assertThrows(IllegalArgumentException.class, () -> ModlistApi.builder(""));
        assertThrows(IllegalArgumentException.class, () -> ModlistApi.builder("alacraft.day"));
        assertThrows(IllegalArgumentException.class, () -> ModlistApi.builder("ftp://alacraft.day"));
        assertEquals("alacraft.day", ModlistApi.builder(ModlistApi.DEFAULT_BASE_URL).build().host());
    }
}
