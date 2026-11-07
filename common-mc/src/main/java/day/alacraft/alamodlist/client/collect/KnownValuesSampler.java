package day.alacraft.alamodlist.client.collect;

import day.alacraft.alamodlist.client.ClientPlatform;
import day.alacraft.alamodlist.privacy.KnownValues;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Reads, on the game thread, the values the privacy gate must not find in a request: who the player is, the
 * session, the server the player is on, and the directories and names of this machine.
 *
 * <p>The values are read at the moment of the player's action. {@link KnownValues} is immutable and prints no
 * value, so it can cross to the worker thread and be held for the length of the job.
 */
public final class KnownValuesSampler {

    private KnownValuesSampler() {
    }

    public static KnownValues sample(Minecraft minecraft, ClientPlatform platform) {
        KnownValues.Builder known = KnownValues.builder()
                .fromJvm()
                .gameDir(platform.gameDirectory())
                .configDir(platform.configDirectory());

        User user = minecraft.getUser();
        if (user != null) {
            known.playerName(user.getName())
                    .playerUuid(user.getProfileId())
                    .session(user.getSessionId())
                    .accessToken(user.getAccessToken());
        }

        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            known.serverAddress(server.ip).serverName(server.name);
        }
        return known.build();
    }
}
