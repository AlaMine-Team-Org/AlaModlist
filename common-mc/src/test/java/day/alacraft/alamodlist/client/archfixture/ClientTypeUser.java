package day.alacraft.alamodlist.client.archfixture;

import net.minecraft.client.Minecraft;

/** The allowed counterpart of ClientTypeViolator: the same client type, inside a client package. Never run. */
public final class ClientTypeUser {

    private Minecraft minecraft;

    public Minecraft minecraft() {
        return minecraft;
    }
}
