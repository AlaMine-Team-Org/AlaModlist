package day.alacraft.alamodlist.fabric.gametest.visual;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The L3 lane: a real Fabric client builds one world, and the stands take their frames in order through
 * {@link ShotRecorder}. Run with {@code ./gradlew :fabric:runClientGameTest} (not part of {@code build});
 * the frames and {@code shots-manifest.json} land in {@code fabric/build/run/clientGameTest/screenshots/},
 * and {@code python tools/shot_manifest_check.py} checks afterwards that every promised frame was taken.
 *
 * <p><b>The lane, not the stands.</b> This class is the only {@code fabric-client-gametest} entrypoint.
 * Every entrypoint builds its own world, and a client process does not survive more than two of them
 * (036 measured the third dying with "Timeout loading world"), so a new stand is a static holder called
 * from here - never a new entrypoint.
 *
 * <p>The lane calls {@link Stands#run} and nothing else of the stands. What runs is whatever {@code Stands} lists
 * for the mod's profile; each stand names the case it implements ({@code @implements}) and the rules of
 * docs/testing/RULES.md it covers ({@code @covers}).
 */
public class AlaModlistClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        // Consistent settings: the same seed and generator on every machine.
        try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(true).create()) {
            configure(context, singleplayer);
            ShotRecorder.begin(context);
            ChunkWait.forRender(singleplayer);

            Stands.run(context, singleplayer);

            // Leave the world with no container open: closing the world while the server still believes a
            // container is open hangs the client (036, MOD-362).
            awaitNoScreen(context);
            ShotRecorder.finish();
        }
    }

    /** A still world and a quiet client: frames differ only where a stand changes something. */
    private static void configure(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        TestServerContext server = singleplayer.getServer();
        server.runOnServer(mc -> {
            GameRules rules = mc.overworld().getGameRules();
            rules.set(GameRules.ADVANCE_TIME, false, mc);
            rules.set(GameRules.ADVANCE_WEATHER, false, mc);
            rules.set(GameRules.SPAWN_MOBS, false, mc);
            rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, mc);
            rules.set(GameRules.SEND_COMMAND_FEEDBACK, false, mc);
        });
        server.runCommand("time set 6000");
        server.runCommand("weather clear");
        context.runOnClient(mc -> {
            mc.options.inGameNotification().set(false);
            mc.options.chatVisibility().set(ChatVisiblity.HIDDEN);
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.guiScale().set(2);
            mc.getWindow().setWindowed(1280, 720);
            mc.getWindow().setGuiScale(mc.getWindow().calculateScale(2, mc.isEnforceUnicode()));
            mc.resizeGui();
            mc.gui.toastManager().clear();
        });
        context.waitTicks(3);
    }

    /** Closes an open container on both sides, the way Escape does, and waits until it is gone. */
    private static void awaitNoScreen(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
            mc.setScreenAndShow(null);
        });
        context.waitFor(mc -> mc.gui.screen() == null
                && mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu);
    }
}
