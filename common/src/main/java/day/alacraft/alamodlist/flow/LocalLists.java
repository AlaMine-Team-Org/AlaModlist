package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.store.Tokens;
import day.alacraft.alamodlist.store.UploadHistory;
import day.alacraft.alamodlist.store.UploadRecord;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The commands that need no network: list the lists, and ask before deleting one. Kept apart from
 * {@link ModlistActions} so that {@code /alamodlist list} does not build an HTTP client.
 */
public final class LocalLists {

    /** How many lists {@code /alamodlist list} shows. The history keeps 50. */
    public static final int SHOWN = 10;

    private final Tokens tokens;
    private final UploadHistory history;
    private final Clock clock;

    public LocalLists(Tokens tokens, UploadHistory history, Clock clock) {
        this.tokens = tokens;
        this.history = history;
        this.clock = clock;
    }

    /** The player's most recent lists. Lists the service no longer keeps are forgotten first, with their tokens. */
    public List<ChatMessage> list() {
        for (UploadRecord dropped : history.expire(clock.instant())) {
            tokens.forgetDeleteToken(dropped.id());
        }

        return ChatLines.list(history.recent(SHOWN), id -> tokens.deleteToken(id).isPresent());
    }

    /** The question before a delete. A list whose token is not stored cannot be deleted, and the answer says so. */
    public ChatMessage askDelete(String id) {
        Optional<UploadRecord> record = history.findById(id);

        if (record.isEmpty()) {
            return ChatLines.deleteUnknown();
        }

        return tokens.deleteToken(id).isPresent() ? ChatLines.deleteAsk(record.get())
                : ChatLines.deleteNoToken(record.get());
    }

    /** The player changed their mind. */
    public ChatMessage cancelDelete() {
        return ChatLines.deleteCancelled();
    }
}
