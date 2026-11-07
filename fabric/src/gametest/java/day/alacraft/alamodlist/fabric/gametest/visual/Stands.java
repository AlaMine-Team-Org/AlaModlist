package day.alacraft.alamodlist.fabric.gametest.visual;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The stands of the lane, in the order they run: the one seam between the lane and what the mod has to show.
 * {@link AlaModlistClientGameTest} calls {@link #run} and nothing else of the stands.
 *
 * <p>A client-only mod has no menus and no blocks, so its stands are {@link ClientStands}: the mixin probe, the
 * settings screen, the mod's key and its command, and the preview screen in its states.
 */
final class Stands {

    private Stands() {
    }

    /**
     * Runs every stand in the one world the lane built. Each stand that opens a screen closes it again and waits until
     * it is gone, so the next one starts from a clean frame. The real roads (key, command) go first: they need the real
     * collection, and the states over a model built by the stands come after them.
     */
    static void run(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ClientStands.mixinApplied(context);
        ClientStands.configScreen(context);
        ClientStands.openKey(context);
        ClientStands.commandAndAlias(context);
        ClientStands.previewHashing(context);
        ClientStands.previewLongList(context);
        ClientStands.previewUnavailable(context);
        ClientStands.previewBlocked(context);
        ClientStands.previewSendStates(context);
    }
}
