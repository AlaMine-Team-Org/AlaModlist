package day.alacraft.alamodlist.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.api.ApiException;
import day.alacraft.alamodlist.api.CreateResult;
import day.alacraft.alamodlist.api.Limits;
import day.alacraft.alamodlist.api.ModlistApi;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.service.SendService.Deletion;
import day.alacraft.alamodlist.service.SendService.Sent;
import day.alacraft.alamodlist.store.Tokens;
import day.alacraft.alamodlist.store.SecretStore;
import day.alacraft.alamodlist.store.UploadHistory;
import day.alacraft.alamodlist.store.UploadRecord;
import day.alacraft.alamodlist.testsupport.Canaries;
import day.alacraft.alamodlist.testsupport.LiveFixtures;
import day.alacraft.alamodlist.testsupport.SnapshotSeed;
import day.alacraft.alamodlist.testsupport.StubSite;
import day.alacraft.alamodlist.testsupport.StubSite.Reply;
import day.alacraft.alamodlist.wire.SnapshotJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of things around a create and a delete, against a throwaway server answering with recorded answers: the
 * token and the history entry are written before the player is told, a delete forgets the record only after the
 * service answered, and no text this layer produces holds a delete token.
 */
class SendServiceTest {

    private static final KnownValues KNOWN = Canaries.known();
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final String LIST_ID = "t4pmmVSebjX8";
    private static final String DELETE_TOKEN = "lcsrHBmvC53DuNIIesf1wjs9MvR8l3T8RwpOBQG6JGJT5stsl6YVAlkthwrbFbws";

    @TempDir
    Path directory;

    private StubSite site;
    private ModlistApi api;
    private Tokens tokens;
    private UploadHistory history;
    private SendService service;

    @BeforeEach
    void start() {
        site = new StubSite();
        api = ModlistApi.builder(site.baseUrl()).build();
        tokens = new Tokens(new SecretStore(directory.resolve("data").resolve(SecretStore.FILE_NAME)));
        history = new UploadHistory(directory.resolve("data").resolve(UploadHistory.FILE_NAME));
        service = new SendService(api, tokens, history, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        api.close();
        site.close();
    }

    /** Limits for the first request, the recorded create answer for the POST. */
    private void serveRecorded() {
        site.answer(request -> request.method().equals("GET") && request.uri().getPath().endsWith("/limits")
                ? Reply.of(LiveFixtures.load("limits-200"))
                : Reply.of(LiveFixtures.load("create-201")));
    }

    private static byte[] cleanBody() {
        return SnapshotJson.toBytes(SnapshotSeed.benign());
    }

    private static byte[] leakyBody() {
        return SnapshotJson.toBytes(SnapshotSeed.build(path -> path.equals("title") ? Canaries.NAME : null));
    }

    private static <T> T result(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static ApiError failure(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));

        return ApiException.of(thrown).error();
    }

    private Sent sendClean() throws Exception {
        return result(service.send(cleanBody(), KNOWN, 3, "My list"));
    }

    // ---- send ----

    @Test
    void sendAsksTheLimitsThenCreatesThenWritesTheTokenAndTheHistoryBeforeAnswering() throws Exception {
        serveRecorded();

        Sent sent = sendClean();

        List<StubSite.Request> requests = site.requests();
        assertEquals(2, requests.size());
        assertEquals("GET", requests.get(0).method());
        assertTrue(requests.get(0).uri().getPath().endsWith("/limits"));
        assertEquals("POST", requests.get(1).method());

        assertEquals(LIST_ID, sent.id());
        assertEquals("http://127.0.0.1:8061/ru/modlists/" + LIST_ID, sent.url());
        assertTrue(sent.deletable());
        assertTrue(sent.tokenOnDisk());

        // By the time the future completed, both records exist - nothing is left for "later".
        assertEquals(Optional.of(DELETE_TOKEN), tokens.deleteToken(LIST_ID));
        UploadRecord recorded = history.findById(LIST_ID).orElseThrow();
        assertEquals(sent.url(), recorded.url());
        assertEquals("My list", recorded.title());
        assertEquals(NOW, recorded.createdAt());
        assertEquals(1, recorded.mods());
        assertEquals(1, recorded.resourcePacks());
        assertEquals(1, recorded.plugins());
    }

    @Test
    void whenTheLimitsCannotBeReadThePublishedOnesApplyAndTheSendGoesOn() throws Exception {
        site.answer(request -> request.method().equals("GET")
                ? Reply.html(503, "<html>down</html>")
                : Reply.of(LiveFixtures.load("create-201")));

        Sent sent = sendClean();

        assertEquals(LIST_ID, sent.id());
        assertEquals(2, site.requests().size());
    }

    @Test
    void aBodyOverTheServicesLimitIsRefusedBeforeItIsSent() {
        serveRecorded();

        ApiError tooMany = failure(service.send(cleanBody(), KNOWN, Limits.DEFAULTS.maxEntries() + 1, "x"));
        assertEquals(ApiErrorCode.TOO_MANY_ENTRIES, tooMany.code());

        byte[] huge = new byte[(int) Limits.DEFAULTS.maxBytes() + 1];
        ApiError tooLarge = failure(service.send(huge, KNOWN, 1, "x"));
        assertEquals(ApiErrorCode.TOO_LARGE, tooLarge.code());

        assertTrue(site.requests().stream().noneMatch(r -> r.method().equals("POST")), "no create was sent");
        assertEquals(0, history.size());
    }

    @Test
    void aBodyTheGateObjectsToIsNotSentAndNothingIsWritten() {
        serveRecorded();

        ApiError error = failure(service.send(leakyBody(), KNOWN, 3, "x"));

        assertEquals(ApiErrorCode.GATE_BLOCKED, error.code());
        assertTrue(site.requests().stream().noneMatch(r -> r.method().equals("POST")));
        assertEquals(0, history.size());
        assertEquals(Optional.empty(), tokens.deleteToken(LIST_ID));
    }

    @Test
    void aFailedCreateWritesNothing() {
        site.answer(request -> request.method().equals("GET")
                ? Reply.of(LiveFixtures.load("limits-200"))
                : Reply.of(LiveFixtures.load("error-rate_limited")));

        ApiError error = failure(service.send(cleanBody(), KNOWN, 3, "x"));

        assertEquals(ApiErrorCode.RATE_LIMITED, error.code());
        assertEquals(0, history.size());
        assertEquals(Optional.empty(), tokens.deleteToken(LIST_ID));
        assertEquals(1, site.requests().stream().filter(r -> r.method().equals("POST")).count(),
                "and the create was not sent again by itself");
    }

    @Test
    void aDiskThatCannotBeWrittenDoesNotTurnASuccessIntoAFailure() throws Exception {
        serveRecorded();
        Path blocker = Files.writeString(directory.resolve("blocker"), "x");
        tokens = new Tokens(new SecretStore(blocker.resolve(SecretStore.FILE_NAME)));
        history = new UploadHistory(blocker.resolve(UploadHistory.FILE_NAME));
        service = new SendService(api, tokens, history, Clock.fixed(NOW, ZoneOffset.UTC));

        Sent sent = sendClean();

        assertEquals(LIST_ID, sent.id(), "the list exists and the link is what the player asked for");
        assertTrue(sent.deletable());
        assertFalse(sent.tokenOnDisk(), "but the player can be told the token will not survive a restart");
        assertEquals(Optional.of(DELETE_TOKEN), tokens.deleteToken(LIST_ID), "it is usable this session");
    }

    // ---- delete ----

    @Test
    void deleteSendsTheStoredTokenAndForgetsTheRecordOnlyAfterTheAnswer() throws Exception {
        serveRecorded();
        sendClean();
        site.answer(Reply.of(LiveFixtures.load("delete-200")));

        assertEquals(Deletion.DELETED, result(service.delete(LIST_ID)));

        StubSite.Request request = site.requests().get(site.requests().size() - 1);
        assertEquals("DELETE", request.method());
        assertEquals("Bearer " + DELETE_TOKEN, request.header("Authorization"));
        assertTrue(history.findById(LIST_ID).isEmpty());
        assertEquals(Optional.empty(), tokens.deleteToken(LIST_ID));
    }

    @Test
    void aRefusedDeleteKeepsTheRecordAndTheToken() throws Exception {
        serveRecorded();
        sendClean();
        site.answer(Reply.of(LiveFixtures.load("error-invalid_token")));

        ApiError error = failure(service.delete(LIST_ID));

        assertEquals(ApiErrorCode.INVALID_TOKEN, error.code());
        assertTrue(history.findById(LIST_ID).isPresent(), "the list is still public, so it is still listed");
        assertEquals(Optional.of(DELETE_TOKEN), tokens.deleteToken(LIST_ID));
    }

    @Test
    void aTransportFailureOnDeleteKeepsTheRecordToo() throws Exception {
        serveRecorded();
        sendClean();
        site.answer(Reply.html(502, "<html>bad gateway</html>"));

        assertEquals(ApiErrorCode.SERVER_ERROR, failure(service.delete(LIST_ID)).code());
        assertTrue(history.findById(LIST_ID).isPresent());
        assertEquals(Optional.of(DELETE_TOKEN), tokens.deleteToken(LIST_ID));
    }

    @Test
    void deletingAListTheServiceNoLongerHasIsTheWantedStateAndForgetsIt() throws Exception {
        serveRecorded();
        sendClean();
        site.answer(Reply.of(LiveFixtures.load("error-not_found")));

        assertEquals(Deletion.ALREADY_GONE, result(service.delete(LIST_ID)));

        assertTrue(history.findById(LIST_ID).isEmpty());
        assertEquals(Optional.empty(), tokens.deleteToken(LIST_ID));
    }

    @Test
    void withoutAStoredTokenNothingIsSent() {
        site.answer(Reply.of(LiveFixtures.load("delete-200")));

        ApiError error = failure(service.delete(LIST_ID));

        assertEquals(ApiErrorCode.INVALID_TOKEN, error.code());
        assertTrue(site.requests().isEmpty());
    }

    // ---- housekeeping ----

    @Test
    void expiringAListForgetsItsTokenToo() throws Exception {
        serveRecorded();
        sendClean();
        SendService later = new SendService(api, tokens, history,
                Clock.fixed(NOW.plus(Duration.ofDays(91)), ZoneOffset.UTC));

        later.expire();

        assertTrue(history.findById(LIST_ID).isEmpty());
        assertEquals(Optional.empty(), tokens.deleteToken(LIST_ID));
    }

    // ---- the delete token is a secret ----

    /**
     * A token that reaches a chat line or a log is a token anyone who sees the line can use to delete the list. The
     * tests above cannot see a chat line, so they look at everything this layer can print or store: if it can be
     * shown, it is checked here.
     */
    @Test
    void noTextThisLayerProducesHoldsTheDeleteToken() throws Exception {
        serveRecorded();
        Sent sent = sendClean();
        List<String> surfaces = new ArrayList<>();
        surfaces.add(sent.toString());
        surfaces.add(history.findById(LIST_ID).orElseThrow().toString());
        surfaces.add(history.recent(10).toString());
        surfaces.add(tokens.toString());
        surfaces.add(Files.readString(directory.resolve("data").resolve(UploadHistory.FILE_NAME), StandardCharsets.UTF_8));

        // The failures of a delete: what they say is what a player may be shown.
        for (String fixture : new String[] {"error-invalid_token", "error-rate_limited"}) {
            site.answer(Reply.of(LiveFixtures.load(fixture)));
            ApiException thrown = ApiException.of(assertThrows(ExecutionException.class,
                    () -> service.delete(LIST_ID).get(10, TimeUnit.SECONDS)));
            surfaces.add(thrown.getMessage());
            surfaces.add(thrown.error().toString());
        }

        // Armed: the token really is in play - on disk where it belongs, and in the result it came back in.
        assertTrue(Files.readString(directory.resolve("data").resolve(SecretStore.FILE_NAME)).contains(DELETE_TOKEN));
        assertTrue(surfaces.size() >= 9, "the surfaces were really collected");
        for (String surface : surfaces) {
            assertFalse(surface.contains(DELETE_TOKEN), "a delete token is in: " + surface.substring(0, Math.min(60, surface.length())));
            assertFalse(surface.contains(DELETE_TOKEN.substring(0, 16)), "or a piece of it");
        }
    }

    @Test
    void theJsonOfTheHistoryFileIsReadableAndHoldsNoToken() throws Exception {
        serveRecorded();
        sendClean();

        JsonObject file = JsonParser.parseString(Files.readString(
                directory.resolve("data").resolve(UploadHistory.FILE_NAME))).getAsJsonObject();

        assertEquals(1, file.getAsJsonArray("entries").size());
        assertFalse(file.toString().toLowerCase().contains("token"));
    }

    @Test
    void createResultNeverPrintsItsToken() {
        CreateResult withToken = new CreateResult("id", "https://x", "super-secret", null, false, Optional.empty());

        assertFalse(withToken.toString().contains("super-secret"));
        assertTrue(withToken.toString().contains("hidden"));
    }
}
