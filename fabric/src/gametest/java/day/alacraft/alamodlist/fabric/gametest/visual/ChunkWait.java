package day.alacraft.alamodlist.fabric.gametest.visual;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The one place the lane waits for the client to draw the chunks around the player. How the client
 * game-test API reaches the connection for it is not the same on every Minecraft line; a stand calls this,
 * never the API directly, so the lines differ in one method.
 */
final class ChunkWait {

    private ChunkWait() {
    }

    /** Blocks until the chunks in view are rendered. */
    static void forRender(TestSingleplayerContext singleplayer) {
        singleplayer.getConnection().waitForChunksRender();
    }
}
