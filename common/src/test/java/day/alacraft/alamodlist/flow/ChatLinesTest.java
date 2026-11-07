package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.api.CreateResult;
import day.alacraft.alamodlist.flow.ChatMessage.Action;
import day.alacraft.alamodlist.flow.ChatMessage.Kind;
import day.alacraft.alamodlist.service.SendService.Sent;
import day.alacraft.alamodlist.store.UploadRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLinesTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final CreateResult.Counts COUNTS = new CreateResult.Counts(214, 3, 2, 0, 0, 0, 0, OptionalInt.empty());

    private static Sent sent(boolean deletable, boolean tokenOnDisk, boolean truncated, Optional<Instant> expires) {
        return new Sent("t4pmmVSebjX8", "https://alacraft.day/uk/modlists/t4pmmVSebjX8", COUNTS, truncated, expires,
                deletable, tokenOnDisk);
    }

    private static List<String> keys(List<ChatMessage> messages) {
        return messages.stream().flatMap(m -> m.parts().stream()).map(ChatMessage.Part::key)
                .filter(java.util.Objects::nonNull).toList();
    }

    @Test
    void theSentMessagesCarryTheCountTheLinkAndTheDaysItIsKept() {
        List<ChatMessage> lines = ChatLines.sent(sent(true, true, false, Optional.of(NOW.plus(Duration.ofDays(90)))), NOW);

        assertEquals(List.of("214", "5"), lines.get(0).parts().get(0).args(), "mods, then libraries and built-in");
        assertEquals(List.of("90"), lines.get(2).parts().get(0).args());
        assertEquals("message.alamodlist.sent.expires", lines.get(2).parts().get(0).key());
        assertEquals(3, lines.size());
    }

    @Test
    void theLinkIsShownOnceAndEveryButtonActsOnTheSameAddress() {
        ChatMessage links = ChatLines.sent(sent(true, true, false, Optional.empty()), NOW).get(1);

        long shown = links.parts().stream().filter(p -> p.kind() == Kind.LINK).count();
        assertEquals(1, shown);
        assertEquals("https://alacraft.day/uk/modlists/t4pmmVSebjX8", links.parts().get(0).value());
        assertEquals(Action.OPEN_URL, links.parts().get(2).action());
        assertEquals(Action.COPY, links.parts().get(4).action());
        assertEquals("https://alacraft.day/uk/modlists/t4pmmVSebjX8", links.parts().get(4).value());
    }

    @Test
    void aListThatCannotBeDeletedHasNoDeleteButtonAndSaysSo() {
        List<ChatMessage> lines = ChatLines.sent(sent(false, false, false, Optional.empty()), NOW);

        assertTrue(lines.get(1).parts().stream().noneMatch(p -> p.action() == Action.RUN_COMMAND));
        assertTrue(keys(lines).contains("message.alamodlist.sent.not_deletable"));
    }

    @Test
    void aTokenThatIsOnlyInMemoryIsAWarningInTheErrorColour() {
        List<ChatMessage> lines = ChatLines.sent(sent(true, false, true, Optional.empty()), NOW);

        assertTrue(keys(lines).contains("message.alamodlist.sent.truncated"));
        ChatMessage warning = lines.get(lines.size() - 1);
        assertEquals("message.alamodlist.sent.token_memory", warning.parts().get(0).key());
        assertEquals(ChatMessage.Tone.ERROR, warning.tone());
    }

    @Test
    void theListHasADateAndAnIdWhenThereIsNoTitle() {
        UploadRecord titled = new UploadRecord("aaaaaaaaaaaa", "https://x.example/en/modlists/aaaaaaaaaaaa", NOW, "My pack",
                5, 0, 0, 0, 0);
        UploadRecord untitled = new UploadRecord("bbbbbbbbbbbb", "", NOW, "", 7, 0, 0, 0, 0);

        List<ChatMessage> lines = ChatLines.list(List.of(titled, untitled), id -> id.startsWith("a"));

        assertEquals(List.of("2"), lines.get(0).parts().get(0).args());
        assertEquals("My pack", lines.get(1).parts().get(0).value());
        assertEquals(List.of("5", "2026-11-07"), lines.get(1).parts().get(2).args());
        assertEquals("bbbbbbbbbbbb", lines.get(2).parts().get(0).value());
        assertEquals(1, lines.get(1).parts().stream().filter(p -> p.kind() == Kind.BUTTON).count());
        assertEquals(0, lines.get(2).parts().stream().filter(p -> p.kind() == Kind.BUTTON).count());
        assertEquals(0, lines.get(2).parts().stream().filter(p -> p.kind() == Kind.LINK).count(), "no address, no link");
    }

    @Test
    void everyCodeHasASentenceForBothActionsAndOnlyTheTimeoutOfASendReadsAsUnknown() {
        Set<String> sendKeys = new HashSet<>();

        for (ApiErrorCode code : ApiErrorCode.values()) {
            String send = ErrorText.key(code, ErrorText.Action.SEND);
            String delete = ErrorText.key(code, ErrorText.Action.DELETE);

            assertTrue(send.startsWith("message.alamodlist.error."), code.toString());
            assertTrue(delete.startsWith("message.alamodlist.error."), code.toString());
            if (code != ApiErrorCode.TIMEOUT) {
                assertEquals(send, delete, code.toString());
            }
            sendKeys.add(send);
        }

        assertEquals("message.alamodlist.error.timeout_unknown", ErrorText.key(ApiErrorCode.TIMEOUT, ErrorText.Action.SEND));
        assertEquals("message.alamodlist.error.timeout", ErrorText.key(ApiErrorCode.TIMEOUT, ErrorText.Action.DELETE));
        assertTrue(sendKeys.size() >= 12, "the sentences really differ: " + sendKeys);
    }

    @Test
    void aRateLimitRoundsTheWaitUpToWholeMinutes() {
        ApiError error = new ApiError(429, ApiErrorCode.RATE_LIMITED, "rate_limited", "x", Optional.of(Duration.ofSeconds(61)));
        ApiError none = new ApiError(429, ApiErrorCode.RATE_LIMITED, "rate_limited", "x", Optional.empty());

        assertEquals(List.of("2"), ErrorText.args(error));
        assertEquals(List.of("1"), ErrorText.args(none));
        assertFalse(ErrorText.args(ApiError.client(ApiErrorCode.OFFLINE, "x")).iterator().hasNext());
    }

    @Test
    void theStringsOfAMessageIncludeKeysValuesAndArguments() {
        ChatMessage message = ChatLines.deleteAsk(new UploadRecord("aaaaaaaaaaaa", "", NOW, "My pack", 1, 0, 0, 0, 0));

        List<String> strings = message.strings();
        assertTrue(strings.contains("My pack"));
        assertTrue(strings.contains("/alamodlist delete aaaaaaaaaaaa confirm"));
        assertTrue(strings.contains("message.alamodlist.button.cancel.hover"));
    }
}
