package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.flow.ChatMessage.Action;
import day.alacraft.alamodlist.flow.ChatMessage.Part;
import day.alacraft.alamodlist.service.SendService;
import day.alacraft.alamodlist.store.UploadRecord;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Every chat message the mod writes after the preview, built from plain results.
 *
 * <p><b>None of these takes a delete token, and none can.</b> The inputs are {@link SendService.Sent} and
 * {@link UploadRecord}, which hold none; the delete button runs a command that carries only the public id of the
 * list, and the token is looked up from the store when the player confirms. Chat is read by chat loggers, screenshots
 * and other mods, so a token that never reaches this layer is a token that cannot leave through it.
 *
 * <p>The sentences are translation keys written out in full, so the catalogue check can see every one.
 */
public final class ChatLines {

    /** The command every button runs; the id is public. */
    private static final String COMMAND = "/" + AlaModlist.MOD_ID;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private ChatLines() {
    }

    /** The messages after a list was made. */
    public static List<ChatMessage> sent(SendService.Sent sent, Instant now) {
        List<ChatMessage> lines = new ArrayList<>();

        lines.add(ChatMessage.info(Part.text("message.alamodlist.sent",
                Integer.toString(sent.counts().mods()), Integer.toString(sent.counts().libraries() + sent.counts().builtin()))));

        List<Part> links = new ArrayList<>();
        links.add(Part.link(sent.url()));
        links.add(Part.space());
        links.add(Part.button("message.alamodlist.button.open", "message.alamodlist.button.open.hover",
                Action.OPEN_URL, sent.url()));
        links.add(Part.space());
        links.add(Part.button("message.alamodlist.button.copy", "message.alamodlist.button.copy.hover",
                Action.COPY, sent.url()));
        if (sent.deletable()) {
            links.add(Part.space());
            links.add(deleteButton(sent.id()));
        }
        lines.add(new ChatMessage(ChatMessage.Tone.INFO, links));

        sent.expires().ifPresent(expires -> lines.add(ChatMessage.info(Part.text("message.alamodlist.sent.expires",
                Long.toString(Math.max(1, ChronoUnit.DAYS.between(now, expires)))))));
        if (sent.truncated()) {
            lines.add(ChatMessage.info(Part.text("message.alamodlist.sent.truncated")));
        }
        if (!sent.deletable()) {
            lines.add(ChatMessage.info(Part.text("message.alamodlist.sent.not_deletable")));
        } else if (!sent.tokenOnDisk()) {
            lines.add(ChatMessage.error(Part.text("message.alamodlist.sent.token_memory")));
        }

        return lines;
    }

    /**
     * The player's lists, newest first.
     *
     * @param deletable whether the delete token of a list is stored (the button is offered only then)
     */
    public static List<ChatMessage> list(List<UploadRecord> records, Predicate<String> deletable) {
        List<ChatMessage> lines = new ArrayList<>();

        if (records.isEmpty()) {
            lines.add(ChatMessage.info(Part.text("message.alamodlist.list.empty")));
            return lines;
        }

        lines.add(ChatMessage.info(Part.text("message.alamodlist.list.header", Integer.toString(records.size()))));
        for (UploadRecord record : records) {
            List<Part> parts = new ArrayList<>();
            parts.add(Part.note(name(record)));
            parts.add(Part.space());
            parts.add(Part.text("message.alamodlist.list.item", Integer.toString(record.mods()),
                    DATE.format(record.createdAt())));
            if (!record.url().isEmpty()) {
                parts.add(Part.space());
                parts.add(Part.link(record.url()));
            }
            if (deletable.test(record.id())) {
                parts.add(Part.space());
                parts.add(deleteButton(record.id()));
            }
            lines.add(new ChatMessage(ChatMessage.Tone.INFO, parts));
        }

        return lines;
    }

    /** Asks before deleting: nothing is deleted by the first click. */
    public static ChatMessage deleteAsk(UploadRecord record) {
        return ChatMessage.info(
                Part.text("message.alamodlist.delete.ask", name(record)),
                Part.space(),
                Part.button("message.alamodlist.button.confirm", "message.alamodlist.button.confirm.hover",
                        Action.RUN_COMMAND, COMMAND + " delete " + record.id() + " confirm"),
                Part.space(),
                Part.button("message.alamodlist.button.cancel", "message.alamodlist.button.cancel.hover",
                        Action.RUN_COMMAND, COMMAND + " delete " + record.id() + " cancel"));
    }

    /** The list is known but its delete token is not stored: it cannot be deleted from the game. */
    public static ChatMessage deleteNoToken(UploadRecord record) {
        return ChatMessage.error(Part.text("message.alamodlist.delete.no_token", name(record)));
    }

    /** The id is not one the mod made (or its record has expired). */
    public static ChatMessage deleteUnknown() {
        return ChatMessage.error(Part.text("message.alamodlist.delete.unknown"));
    }

    public static ChatMessage deleted(UploadRecord record) {
        return ChatMessage.info(Part.text("message.alamodlist.delete.done", name(record)));
    }

    /** The service says it is not there any more: expired or taken down - what the player wanted. */
    public static ChatMessage alreadyGone(UploadRecord record) {
        return ChatMessage.info(Part.text("message.alamodlist.delete.gone", name(record)));
    }

    public static ChatMessage deleteCancelled() {
        return ChatMessage.info(Part.text("message.alamodlist.delete.cancelled"));
    }

    /** A failure, in the words for its code; the service's own text is never shown. */
    public static ChatMessage error(ApiError error, ErrorText.Action action) {
        List<String> args = ErrorText.args(error);

        return ChatMessage.error(new Part(ChatMessage.Kind.TEXT, ErrorText.key(error.code(), action), null, null, null,
                args));
    }

    /** What the list is called to the player: its title, or its id when it has none. */
    private static String name(UploadRecord record) {
        return record.title().isEmpty() ? record.id() : record.title();
    }

    private static Part deleteButton(String id) {
        return Part.button("message.alamodlist.button.delete", "message.alamodlist.button.delete.hover",
                Action.RUN_COMMAND, COMMAND + " delete " + id);
    }
}
