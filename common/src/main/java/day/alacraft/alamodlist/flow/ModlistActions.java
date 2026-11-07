package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiException;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.privacy.PrivacyGate;
import day.alacraft.alamodlist.privacy.Verdict;
import day.alacraft.alamodlist.service.SendService;
import day.alacraft.alamodlist.store.UploadHistory;
import day.alacraft.alamodlist.store.UploadRecord;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What the player's choices do, as plain results: send a prepared request, list the lists, delete one. The game layer
 * runs it off the game thread and shows what comes back; nothing here touches a screen, a chat or a game type.
 *
 * <p>The answers are {@link ChatMessage}s and {@link GateReport}s, which cannot hold a delete token (see
 * {@link ChatLines}); the token stays inside {@link SendService} and the token store.
 */
public final class ModlistActions {

    /** How a send ended. */
    public enum Kind {
        /** The list exists. */
        SENT,
        /** The privacy gate refused the request; nothing left the machine. */
        BLOCKED,
        /** The request did not succeed; the snapshot is unchanged. */
        FAILED
    }

    /**
     * @param kind  how it ended
     * @param chat  what to say in chat (for {@link Kind#SENT} the link and its buttons; for a failure, the sentence)
     * @param error the failure, for {@link Kind#FAILED}
     * @param gate  what the gate found, for {@link Kind#BLOCKED}
     */
    public record Outcome(Kind kind, List<ChatMessage> chat, ApiError error, GateReport gate) {
    }

    private final SendService service;
    private final UploadHistory history;
    private final Clock clock;

    public ModlistActions(SendService service, UploadHistory history, Clock clock) {
        this.service = service;
        this.history = history;
        this.clock = clock;
    }

    /**
     * Sends a prepared request. The privacy gate looks at the exact bytes first; a request it objects to is not sent
     * and the answer says what and where. The API client runs the gate once more on the way out.
     *
     * <p>Blocks for the gate (milliseconds), so call it off the game thread.
     */
    public CompletableFuture<Outcome> send(PreviewModel.Prepared prepared, KnownValues known) {
        Verdict verdict = PrivacyGate.check(prepared.body(), known);

        if (!verdict.clean()) {
            return CompletableFuture.completedFuture(
                    new Outcome(Kind.BLOCKED, List.of(), null, GateReport.of(verdict)));
        }

        return service.send(prepared.body(), known, prepared.largestList(), prepared.title())
                .handle((sent, failure) -> {
                    if (failure == null) {
                        return new Outcome(Kind.SENT, ChatLines.sent(sent, clock.instant()), null, null);
                    }

                    ApiError error = ApiException.of(failure).error();

                    return new Outcome(Kind.FAILED, List.of(ChatLines.error(error, ErrorText.Action.SEND)), error,
                            null);
                });
    }

    /**
     * Deletes a list after the player confirmed. The token is looked up here, from the store, and goes to the
     * service; the answer holds none.
     */
    public CompletableFuture<List<ChatMessage>> confirmDelete(String id) {
        Optional<UploadRecord> record = history.findById(id);

        if (record.isEmpty()) {
            return CompletableFuture.completedFuture(List.of(ChatLines.deleteUnknown()));
        }

        return service.delete(id).handle((deletion, failure) -> {
            if (failure != null) {
                return List.of(ChatLines.error(ApiException.of(failure).error(), ErrorText.Action.DELETE));
            }

            return List.of(deletion == SendService.Deletion.DELETED
                    ? ChatLines.deleted(record.get()) : ChatLines.alreadyGone(record.get()));
        });
    }
}
