package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.api.ModlistApi;
import day.alacraft.alamodlist.assemble.EnvironmentAssembler;
import day.alacraft.alamodlist.flow.ChatMessage.Action;
import day.alacraft.alamodlist.flow.ChatMessage.Kind;
import day.alacraft.alamodlist.flow.ChatMessage.Part;
import day.alacraft.alamodlist.flow.ModlistActions.Outcome;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.Hashes;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.service.SendService;
import day.alacraft.alamodlist.store.SecretStore;
import day.alacraft.alamodlist.store.Tokens;
import day.alacraft.alamodlist.store.UploadHistory;
import day.alacraft.alamodlist.testsupport.Canaries;
import day.alacraft.alamodlist.testsupport.LiveFixtures;
import day.alacraft.alamodlist.testsupport.StubSite;
import day.alacraft.alamodlist.testsupport.StubSite.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Send, list and delete as the player meets them, against a throwaway server answering with recorded answers: what
 * ends up in chat, what the privacy gate does with the final bytes, and - the point of this class - that no message
 * of the mod ever holds a delete token.
 */
class ModlistActionsTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final String LIST_ID = "t4pmmVSebjX8";
    private static final String DELETE_TOKEN = "lcsrHBmvC53DuNIIesf1wjs9MvR8l3T8RwpOBQG6JGJT5stsl6YVAlkthwrbFbws";

    @TempDir
    Path directory;

    private StubSite site;
    private ModlistApi api;
    private Tokens tokens;
    private UploadHistory history;
    private ModlistActions actions;
    private LocalLists local;
    private final List<ChatMessage> everythingSaid = new ArrayList<>();

    @BeforeEach
    void start() {
        site = new StubSite();
        api = ModlistApi.builder(site.baseUrl()).build();
        open();
    }

    private void open() {
        tokens = new Tokens(new SecretStore(directory.resolve("data").resolve(SecretStore.FILE_NAME)));
        history = new UploadHistory(directory.resolve("data").resolve(UploadHistory.FILE_NAME));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        actions = new ModlistActions(new SendService(api, tokens, history, clock), history, clock);
        local = new LocalLists(tokens, history, clock);
    }

    @AfterEach
    void stop() {
        api.close();
        site.close();
    }

    private void serveRecorded() {
        site.answer(request -> request.method().equals("GET") && request.uri().getPath().endsWith("/limits")
                ? Reply.of(LiveFixtures.load("limits-200"))
                : Reply.of(LiveFixtures.load("create-201")));
    }

    private PreviewModel.Prepared prepared(String title) {
        List<ModEntry> mods = List.of(new ModEntry("sodium", "Sodium", "0.6.1", "mod", null, "client",
                "sodium.jar", 1000L, new Hashes("b".repeat(40), null), null, List.of()));
        PreviewModel model = new PreviewModel(new Progress());
        model.collected(new Snapshot("alamodlist/test", null, null, null,
                EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5"), mods, List.of(), List.of(), List.of(),
                null, null, List.of()));
        model.setTitle(title);

        return model.prepare("ru");
    }

    private Outcome send(String title) throws Exception {
        Outcome outcome = actions.send(prepared(title), Canaries.known()).get(10, TimeUnit.SECONDS);
        everythingSaid.addAll(outcome.chat());

        return outcome;
    }

    private List<ChatMessage> said(CompletableFuture<List<ChatMessage>> future) throws Exception {
        List<ChatMessage> messages = future.get(10, TimeUnit.SECONDS);
        everythingSaid.addAll(messages);

        return messages;
    }

    private static List<Part> buttons(List<ChatMessage> messages) {
        return messages.stream().flatMap(message -> message.parts().stream())
                .filter(part -> part.kind() == Kind.BUTTON).toList();
    }

    // ---- send ----

    /**
     * @implements TC-PREVIEW-001-FUN10 - a sent list gives the link with open, copy and delete
     * @covers R-CLI-03
     */
    @Test
    void aSentListGivesTheLinkWithOpenCopyAndDelete() throws Exception {
        serveRecorded();

        Outcome outcome = send("My pack");

        assertEquals(ModlistActions.Kind.SENT, outcome.kind());
        List<Part> buttons = buttons(outcome.chat());
        assertEquals(List.of(Action.OPEN_URL, Action.COPY, Action.RUN_COMMAND),
                buttons.stream().map(Part::action).toList());
        assertEquals("http://127.0.0.1:8061/ru/modlists/" + LIST_ID, buttons.get(0).value());
        assertEquals(buttons.get(0).value(), buttons.get(1).value());
        assertEquals("/alamodlist delete " + LIST_ID, buttons.get(2).value(), "only the public id travels in the command");
        assertTrue(outcome.chat().get(1).parts().stream().anyMatch(p -> p.kind() == Kind.LINK
                && p.value().endsWith("/ru/modlists/" + LIST_ID)));
        assertEquals(1, history.size());
    }

    @Test
    void theRequestThatLeftCarriesTheLinkLanguageAndTheTitle() throws Exception {
        serveRecorded();

        send("My pack");

        var post = site.requests().stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
        assertEquals("ru", post.json().get("locale").getAsString());
        assertEquals("My pack", post.json().get("title").getAsString());
    }

    /**
     * @implements TC-PREVIEW-001-FUN06 - the privacy check judges the final bytes and stops a list with the player's name in it
     * @covers R-PRV-02 R-PRV-05
     */
    @Test
    void aTitleHoldingThePlayersNameIsStoppedBeforeAnythingIsSent() throws Exception {
        serveRecorded();

        Outcome outcome = send("List of " + Canaries.NAME);

        assertEquals(ModlistActions.Kind.BLOCKED, outcome.kind());
        assertEquals("screen.alamodlist.gate.found.player_name", outcome.gate().lines().get(0).labelKey());
        assertEquals("title", outcome.gate().lines().get(0).path());
        assertTrue(site.requests().isEmpty(), "not even the limits were asked for");
        assertEquals(0, history.size());
        assertNull(outcome.error());
    }

    @Test
    void aFailedSendSaysWhyInTheWordsOfItsCodeAndKeepsNothing() throws Exception {
        site.answer(request -> request.method().equals("GET")
                ? Reply.of(LiveFixtures.load("limits-200"))
                : Reply.of(LiveFixtures.load("error-rate_limited")));

        Outcome outcome = send("x");

        assertEquals(ModlistActions.Kind.FAILED, outcome.kind());
        assertEquals(ApiErrorCode.RATE_LIMITED, outcome.error().code());
        Part sentence = outcome.chat().get(0).parts().get(0);
        assertEquals("message.alamodlist.error.rate_limit", sentence.key());
        assertFalse(sentence.args().isEmpty(), "the wait is in the sentence");
        assertEquals(ChatMessage.Tone.ERROR, outcome.chat().get(0).tone());
        assertEquals(0, history.size());
    }

    @Test
    void aSentenceNeverCarriesTheTextTheServiceWrote() throws Exception {
        site.answer(Reply.json(500, "{\"success\":false,\"error\":\"SELECT * FROM secrets -- zqcanary\","
                + "\"error_code\":\"storage_failed\"}"));

        Outcome outcome = send("x");

        for (String text : outcome.chat().get(0).strings()) {
            assertFalse(text.contains("zqcanary"), text);
        }
        assertEquals("message.alamodlist.error.server", outcome.chat().get(0).parts().get(0).key());
    }

    // ---- list and delete ----

    @Test
    void theListShowsTheNewestListsWithADeleteButtonOnlyWhereTheTokenIsStored() throws Exception {
        serveRecorded();
        send("My pack");

        List<ChatMessage> shown = local.list();

        assertEquals("message.alamodlist.list.header", shown.get(0).parts().get(0).key());
        assertEquals(1, buttons(shown).size());
        assertEquals("/alamodlist delete " + LIST_ID, buttons(shown).get(0).value());

        tokens.forgetDeleteToken(LIST_ID);
        assertTrue(buttons(local.list()).isEmpty(), "no token, no button that could not work");
    }

    @Test
    void anEmptyHistoryIsSaidAsSuch() {
        assertEquals("message.alamodlist.list.empty", local.list().get(0).parts().get(0).key());
    }

    /**
     * @implements TC-PREVIEW-001-FUN12 - a delete asks first, and the question carries only the public id
     * @covers R-CLI-03
     */
    @Test
    void deleteAsksFirstAndTheAnswerCarriesOnlyThePublicId() throws Exception {
        serveRecorded();
        send("My pack");

        ChatMessage ask = local.askDelete(LIST_ID);

        List<Part> buttons = buttons(List.of(ask));
        assertEquals(List.of("/alamodlist delete " + LIST_ID + " confirm", "/alamodlist delete " + LIST_ID + " cancel"),
                buttons.stream().map(Part::value).toList());
        assertEquals(1, history.size(), "asking deletes nothing");
        assertEquals("message.alamodlist.delete.cancelled", local.cancelDelete().parts().get(0).key());
    }

    @Test
    void anIdThatIsNotInTheHistoryIsUnknownAndNothingIsAsked() {
        assertEquals("message.alamodlist.delete.unknown", local.askDelete("nonexistent1").parts().get(0).key());
    }

    @Test
    void aListWhoseTokenIsNotStoredCannotBeDeletedFromTheGameAndSaysSo() throws Exception {
        serveRecorded();
        send("My pack");
        tokens.forgetDeleteToken(LIST_ID);

        ChatMessage ask = local.askDelete(LIST_ID);

        assertEquals("message.alamodlist.delete.no_token", ask.parts().get(0).key());
        assertTrue(buttons(List.of(ask)).isEmpty());
    }

    @Test
    void confirmingDeletesThroughTheServiceAndForgetsTheRecord() throws Exception {
        serveRecorded();
        send("My pack");
        site.answer(Reply.of(LiveFixtures.load("delete-200")));

        List<ChatMessage> done = said(actions.confirmDelete(LIST_ID));

        assertEquals("message.alamodlist.delete.done", done.get(0).parts().get(0).key());
        assertEquals("DELETE", site.requests().get(site.requests().size() - 1).method());
        assertEquals(0, history.size());
    }

    @Test
    void aListTheServiceNoLongerHasCountsAsDeleted() throws Exception {
        serveRecorded();
        send("My pack");
        site.answer(Reply.of(LiveFixtures.load("error-not_found")));

        assertEquals("message.alamodlist.delete.gone", said(actions.confirmDelete(LIST_ID)).get(0).parts().get(0).key());
        assertEquals(0, history.size());
    }

    @Test
    void aRefusedDeleteKeepsTheRecordAndSaysWhy() throws Exception {
        serveRecorded();
        send("My pack");
        site.answer(Reply.of(LiveFixtures.load("error-invalid_token")));

        assertEquals("message.alamodlist.error.delete_token", said(actions.confirmDelete(LIST_ID)).get(0).parts().get(0).key());
        assertEquals(1, history.size());
    }

    @Test
    void confirmingAnUnknownIdSendsNothing() throws Exception {
        assertEquals("message.alamodlist.delete.unknown", said(actions.confirmDelete("nonexistent1")).get(0).parts().get(0).key());
        assertTrue(site.requests().isEmpty());
    }

    // ---- the delete token is a secret ----

    /**
     * Every message of the mod from a whole session - a send, the list, the question, the delete, each of the failures
     * - searched for the token, whole or in part, and for the header it travels in. The token is really in play (it is
     * in the store, and was in the answer of the create), so a clean result means something.
     *
     * @implements TC-PREVIEW-001-FUN13 - no chat message of a whole session holds the delete token
     * @covers R-PRV-06
     */
    @Test
    void noChatMessageOfAWholeSessionHoldsTheDeleteToken() throws Exception {
        serveRecorded();
        send("My pack");
        everythingSaid.addAll(local.list());
        everythingSaid.add(local.askDelete(LIST_ID));
        everythingSaid.add(local.cancelDelete());
        everythingSaid.add(local.askDelete("nonexistent1"));
        for (String fixture : new String[] {"error-invalid_token", "error-rate_limited", "error-not_found",
                "delete-200"}) {
            site.answer(Reply.of(LiveFixtures.load(fixture)));
            said(actions.confirmDelete(LIST_ID));
            if (!history.findById(LIST_ID).isPresent()) {
                serveRecorded();
                send("My pack");
            }
        }
        site.answer(Reply.html(502, "<html>bad gateway</html>"));
        said(actions.confirmDelete(LIST_ID));
        // A disk that cannot be written: the token lives in memory only, and the sentence about it is a message too.
        Path blocker = Files.writeString(directory.resolve("blocker"), "x");
        tokens = new Tokens(new SecretStore(blocker.resolve(SecretStore.FILE_NAME)));
        history = new UploadHistory(blocker.resolve(UploadHistory.FILE_NAME));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        actions = new ModlistActions(new SendService(api, tokens, history, clock), history, clock);
        local = new LocalLists(tokens, history, clock);
        serveRecorded();
        send("My pack");
        everythingSaid.addAll(local.list());
        everythingSaid.add(local.askDelete(LIST_ID));

        // Armed: a token is in the store of the last session, and the strings were really collected.
        assertEquals(DELETE_TOKEN, tokens.deleteToken(LIST_ID).orElseThrow());
        assertTrue(everythingSaid.size() >= 14, "collected " + everythingSaid.size() + " messages");
        assertTrue(everythingSaid.stream().anyMatch(m -> buttons(List.of(m)).stream()
                .anyMatch(b -> b.action() == Action.RUN_COMMAND)), "and some of them carry commands");
        for (ChatMessage message : everythingSaid) {
            for (String text : message.strings()) {
                assertFalse(text.contains(DELETE_TOKEN), "a delete token is in a chat message: " + message.tone());
                assertFalse(text.contains(DELETE_TOKEN.substring(0, 12)), "or a piece of it");
                assertFalse(text.toLowerCase().contains("bearer"), "or the header it travels in");
            }
        }
        assertNotNull(everythingSaid.get(0));
    }

    @Test
    void whenTheTokenCouldNotBeSavedThePlayerIsToldInASentenceAndNotShownTheToken() throws Exception {
        Path blocker = Files.writeString(directory.resolve("blocker"), "x");
        tokens = new Tokens(new SecretStore(blocker.resolve(SecretStore.FILE_NAME)));
        history = new UploadHistory(blocker.resolve(UploadHistory.FILE_NAME));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        actions = new ModlistActions(new SendService(api, tokens, history, clock), history, clock);
        serveRecorded();

        Outcome outcome = send("My pack");

        assertTrue(outcome.chat().stream().anyMatch(m -> m.parts().stream()
                .anyMatch(p -> "message.alamodlist.sent.token_memory".equals(p.key()))));
    }
}
