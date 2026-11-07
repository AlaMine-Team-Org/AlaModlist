package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.assemble.EnvironmentAssembler;
import day.alacraft.alamodlist.flow.PreviewModel.Buttons;
import day.alacraft.alamodlist.flow.PreviewModel.Phase;
import day.alacraft.alamodlist.flow.PreviewModel.Prepared;
import day.alacraft.alamodlist.flow.PreviewModel.Row;
import day.alacraft.alamodlist.flow.PreviewModel.Slot;
import day.alacraft.alamodlist.flow.PreviewModel.Status;
import day.alacraft.alamodlist.flow.PreviewModel.Tone;
import day.alacraft.alamodlist.api.CreateResult;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.service.SendService;
import day.alacraft.alamodlist.model.Hashes;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.privacy.PrivacyGate;
import day.alacraft.alamodlist.testsupport.Canaries;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of the preview screen that is a decision and not a drawing: what exists, what can be switched on, which
 * button is live in which phase, what request the selection makes.
 */
class PreviewModelTest {

    private static Snapshot snapshot(int mods) {
        List<ModEntry> entries = new ArrayList<>();
        for (int i = 0; i < mods; i++) {
            String kind = i % 10 == 9 ? "library" : "mod";
            entries.add(new ModEntry("mod" + i, "Mod number " + i, "1." + i, kind, null, "universal",
                    "mod-" + i + ".jar", 1000L + i, new Hashes("a".repeat(40), null), null, List.of()));
        }

        return new Snapshot("alamodlist/test", null, null, null,
                EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5"), entries,
                List.of(), List.of(), List.of(), null, null, List.of());
    }

    private static PreviewModel ready(int mods) {
        PreviewModel model = new PreviewModel(new Progress());
        model.collected(snapshot(mods));

        return model;
    }

    // ---- categories ----

    /**
     * @implements TC-PREVIEW-001-FUN01 - every category of the contract is listed; only the environment and the mods can be collected, the rest say why not
     * @covers R-GUI-03
     */
    @Test
    void everyCategoryOfTheContractIsListedAndOnlyTwoCanBeCollectedYet() {
        List<Category> available = new ArrayList<>();
        for (Category category : Category.values()) {
            if (category.available()) {
                available.add(category);
            } else {
                assertNotNull(category.reason().key(), category + " is off and must say why");
            }
        }

        assertEquals(8, Category.values().length);
        assertEquals(List.of(Category.ENVIRONMENT, Category.MODS), available);
        assertNull(Category.MODS.reason().key());
    }

    @Test
    void theReasonsNameTheSideTheCategoryWorksOn() {
        assertEquals("screen.alamodlist.preview.later_singleplayer", Category.DATA_PACKS.reason().key());
        assertEquals("screen.alamodlist.preview.later_multiplayer", Category.SERVER.reason().key());
        assertEquals("screen.alamodlist.preview.later", Category.PLUGINS.reason().key());
    }

    @Test
    void theContractDefaultsAreOnForEnvironmentAndModsAndNothingElse() {
        PreviewModel model = new PreviewModel(new Progress());

        for (Category category : Category.values()) {
            boolean expected = category == Category.ENVIRONMENT || category == Category.MODS;
            assertEquals(expected, model.isSelected(category), category.toString());
        }
    }

    @Test
    void theServerCategoryStaysOffByDefaultEvenOnceItExists() {
        // Decision 28: data the server sent is the player's opt-in. The flag is what a future enabling must not touch.
        assertFalse(Category.valueOf("SERVER").selectedByDefault());
    }

    /**
     * @implements TC-PREVIEW-001-FUN02 - a category the mod cannot collect cannot be switched on
     * @covers R-GUI-03
     */
    @Test
    void aCategoryThatCannotBeCollectedCannotBeSwitchedOn() {
        PreviewModel model = ready(3);

        assertFalse(model.select(Category.PLUGINS, true));
        assertFalse(model.isSelected(Category.PLUGINS));
        assertFalse(model.select(Category.SERVER, true));
    }

    @Test
    void switchingAnAvailableCategoryReportsWhetherAnythingChanged() {
        PreviewModel model = ready(3);

        assertTrue(model.select(Category.MODS, false));
        assertFalse(model.select(Category.MODS, false), "already off");
        assertTrue(model.select(Category.MODS, true));
    }

    // ---- buttons by phase ----

    @Test
    void whileCollectingSendIsOffAndTheSecondButtonCancels() {
        PreviewModel model = new PreviewModel(new Progress());

        Buttons buttons = model.buttons();

        assertEquals(Phase.COLLECTING, model.phase());
        assertFalse(buttons.send());
        assertEquals(Slot.CANCEL, buttons.slot());
        assertTrue(buttons.slotLive());
        assertNull(model.status(), "the progress bar speaks for this phase");
    }

    @Test
    void whenReadySendAndRefreshAreLive() {
        PreviewModel model = ready(5);

        Buttons buttons = model.buttons();

        assertTrue(buttons.send());
        assertEquals(Slot.REFRESH, buttons.slot());
        assertTrue(buttons.slotLive());
        assertTrue(buttons.editable());
        assertEquals("screen.alamodlist.preview.status.ready", model.status().key());
    }

    /**
     * @implements TC-PREVIEW-001-FUN04 - while the request is out nothing can be sent again or changed
     * @covers R-PRV-05
     */
    @Test
    void sendingLocksEverythingExceptClosing() {
        PreviewModel model = ready(5);
        model.sending();

        Buttons buttons = model.buttons();

        assertFalse(buttons.send());
        assertFalse(buttons.slotLive());
        assertFalse(buttons.editable());
        assertFalse(model.select(Category.MODS, false), "the selection cannot change under a request that is out");
        assertTrue(model.isSelected(Category.MODS));
    }

    @Test
    void aFailedOrCancelledCollectOffersRefreshAndNotSend() {
        PreviewModel model = new PreviewModel(new Progress());
        model.collectFailed(true);

        assertFalse(model.buttons().send());
        assertEquals(Slot.REFRESH, model.buttons().slot());
        assertEquals(new Status(Tone.INFO, "screen.alamodlist.preview.status.cancelled", List.of()), model.status());

        model.collectFailed(false);
        assertEquals(Tone.ERROR, model.status().tone());
        assertEquals("screen.alamodlist.preview.status.collect_failed", model.status().key());
    }

    @Test
    void afterAGateBlockOrAFailedSendTheSnapshotIsStillThereToSendAgain() {
        PreviewModel model = ready(5);

        model.blocked(new GateReport(List.of(), 0));
        assertTrue(model.buttons().send(), "the player can change the title and try again");
        assertEquals("screen.alamodlist.preview.status.blocked", model.status().key());

        model.sendFailed(ApiError.client(ApiErrorCode.OFFLINE, "x"));
        assertTrue(model.buttons().send());
        assertEquals("message.alamodlist.error.offline", model.status().key());
    }

    @Test
    void aRateLimitSaysHowLongToWaitInMinutes() {
        PreviewModel model = ready(5);
        model.sendFailed(new ApiError(429, ApiErrorCode.RATE_LIMITED, "rate_limited", "x",
                java.util.Optional.of(java.time.Duration.ofSeconds(150))));

        assertEquals(List.of("3"), model.status().args());
    }

    @Test
    void refreshKeepsTheSelectionAndTheTitleButDropsTheOldSnapshot() {
        PreviewModel model = ready(5);
        model.select(Category.MODS, false);
        model.setTitle("My pack");

        model.restart(new Progress());

        assertEquals(Phase.COLLECTING, model.phase());
        assertFalse(model.isSelected(Category.MODS));
        assertEquals("My pack", model.title());
        assertTrue(model.rows(Category.MODS).isEmpty());
        assertThrows(IllegalStateException.class, () -> model.prepare("en"));
    }

    /**
     * @implements TC-PREVIEW-001-FUN03 - the environment cannot be switched off and is always in the request
     * @covers R-GUI-03 R-PRV-05
     */
    @Test
    void theEnvironmentIsAlwaysInTheRequestBecauseTheRequestIsNotValidWithoutIt() {
        PreviewModel model = ready(5);

        assertTrue(Category.ENVIRONMENT.mandatory());
        assertFalse(model.select(Category.ENVIRONMENT, false), "it cannot be switched off");
        assertTrue(model.isSelected(Category.ENVIRONMENT));

        model.select(Category.MODS, false);
        assertTrue(model.buttons().send(), "the environment alone is a list");
        assertTrue(new String(model.prepare("en").body(), StandardCharsets.UTF_8).contains("\"environment\""));
    }

    // ---- numbers and rows ----

    @Test
    void theSummaryCountsOnlyWhatIsSelected() {
        PreviewModel model = ready(214);

        assertEquals(List.of(new PreviewModel.SummaryPart(Category.ENVIRONMENT, null, null),
                new PreviewModel.SummaryPart(Category.MODS, 193, 21)), model.summary());

        model.select(Category.MODS, false);
        assertEquals(List.of(new PreviewModel.SummaryPart(Category.ENVIRONMENT, null, null)), model.summary());
        assertTrue(new PreviewModel(new Progress()).summary().isEmpty(), "nothing to count before the snapshot");
    }

    /** The screen and the chat after sending say the same numbers: mods proper, then libraries and built-in together. */
    @Test
    void theScreenCountsModsTheWayTheSiteDoesSoTheChatAgrees() {
        PreviewModel model = ready(54);

        assertEquals(49, model.modCount());
        assertEquals(5, model.libraryCount());
        assertEquals(49, model.count(Category.MODS));
        assertEquals(54, model.rows(Category.MODS).size(), "the list still shows every entry, tagged by kind");

        CreateResult.Counts site = new CreateResult.Counts(model.modCount(), 4, 1, 0, 0, 0, 0, OptionalInt.empty());
        List<String> args = ChatLines.sent(new SendService.Sent("t4pmmVSebjX8", "https://alacraft.day/en/modlists/t4pmmVSebjX8",
                site, false, Optional.empty(), true, true), Instant.parse("2026-11-07T12:00:00Z")).get(0).parts().get(0).args();
        assertEquals(List.of("49", "5"), args);
        assertEquals(List.of(new PreviewModel.SummaryPart(Category.ENVIRONMENT, null, null),
                new PreviewModel.SummaryPart(Category.MODS, 49, 5)), model.summary());
    }

    /**
     * @implements TC-PREVIEW-001-FUN07 - six hundred mods give six hundred lines in their order
     * @covers R-GUI-02
     */
    @Test
    void sixHundredModsGiveSixHundredRowsInTheirOrderWithTheKindOfEachComponent() {
        PreviewModel model = ready(600);

        List<Row> rows = model.rows(Category.MODS);

        assertEquals(600, rows.size());
        assertEquals("Mod number 0", rows.get(0).text());
        assertEquals("1.0", rows.get(0).detail());
        assertNull(rows.get(0).kindKey());
        assertEquals("screen.alamodlist.preview.kind.library", rows.get(9).kindKey());
        assertEquals("Mod number 599", rows.get(599).text());
    }

    @Test
    void theEnvironmentRowsReadFromTheCollectedEnvironment() {
        List<Row> rows = ready(1).rows(Category.ENVIRONMENT);

        assertEquals(4, rows.size());
        assertEquals("26.3 (777)", rows.get(0).text());
        assertEquals("fabric 0.19.5", rows.get(1).text());
        assertTrue(rows.stream().allMatch(row -> row.labelKey() != null));
    }

    @Test
    void anUnavailableCategoryHasNoRowsBecauseTheScreenShowsItsReason() {
        assertTrue(ready(5).rows(Category.PLUGINS).isEmpty());
    }

    // ---- the request ----

    @Test
    void theRequestCarriesTheSelectedCategoriesTheTitleAndTheLinkLanguage() {
        PreviewModel model = ready(4);
        model.setTitle("  My   pack ");

        Prepared prepared = model.prepare("uk");
        String body = new String(prepared.body(), StandardCharsets.UTF_8);

        assertEquals("uk", prepared.snapshot().locale());
        assertEquals("My pack", prepared.title(), "cleaned the way the service cleans it");
        assertEquals(4, prepared.snapshot().mods().size());
        assertEquals(4, prepared.largestList());
        assertTrue(body.contains("\"locale\":\"uk\""));
        assertTrue(body.contains("\"title\":\"My pack\""));
        assertTrue(body.contains("\"environment\""));
    }

    /**
     * @implements TC-PREVIEW-001-FUN05 - a category switched off is not in the body, not even as an empty list
     * @covers R-PRV-05
     */
    @Test
    void aCategorySwitchedOffIsNotInTheBody() {
        PreviewModel model = ready(4);
        model.select(Category.MODS, false);

        Prepared prepared = model.prepare("en");
        String body = new String(prepared.body(), StandardCharsets.UTF_8);

        assertTrue(prepared.snapshot().mods().isEmpty());
        assertFalse(body.contains("\"mods\""), "not even an empty list");
        assertTrue(body.contains("\"environment\""));
        assertEquals(0, prepared.largestList());

        model.select(Category.MODS, true);
        assertTrue(new String(model.prepare("en").body(), StandardCharsets.UTF_8).contains("\"mods\""));
    }

    @Test
    void aTitleThatIsEmptyAfterCleaningIsLeftOut() {
        PreviewModel model = ready(1);
        model.setTitle("   ");

        assertNull(model.prepare("en").snapshot().title());
        assertFalse(new String(model.prepare("en").body(), StandardCharsets.UTF_8).contains("\"title\""));
    }

    @Test
    void aTitleLongerThanTheLimitIsCutToIt() {
        PreviewModel model = ready(1);
        model.setTitle("x".repeat(200));

        assertEquals(PreviewModel.TITLE_MAX, model.prepare("en").title().length());
    }

    @Test
    void aPlayerNameTypedAsATitleIsSomethingTheGateStopsOnTheFinalBytes() {
        PreviewModel model = ready(2);
        model.setTitle("Pack of " + Canaries.NAME);

        Prepared prepared = model.prepare("en");

        assertFalse(PrivacyGate.check(prepared.body(), Canaries.known()).clean());
        model.setTitle("Pack of mine");
        assertTrue(PrivacyGate.check(model.prepare("en").body(), Canaries.known()).clean());
    }

    @Test
    void filesThatGoOutWithoutAHashAreMentionedToThePlayer() {
        PreviewModel model = new PreviewModel(new Progress());
        model.collected(snapshot(5), 3);

        assertEquals(new Status(Tone.INFO, "screen.alamodlist.preview.status.unhashed", List.of("3")), model.status());
        assertTrue(model.buttons().send(), "and they can still be sent");
    }

    @Test
    void aRequestThatWasNotMadeAtAllHasItsOwnSentenceAndLeavesSendLive() {
        PreviewModel model = ready(5);
        model.rejected(ErrorText.BAD_ADDRESS);

        assertEquals(Phase.SEND_FAILED, model.phase());
        assertEquals(new Status(Tone.ERROR, ErrorText.BAD_ADDRESS, List.of()), model.status());
        assertTrue(model.buttons().send(), "after the address is fixed it can be sent again");
    }
}
