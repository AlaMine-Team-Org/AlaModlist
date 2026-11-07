package day.alacraft.alamodlist.archfixture;

import net.minecraft.client.Minecraft;

/** Breaks CLIENT_TYPES_STAY_IN_CLIENT_PACKAGES on purpose: a client type outside a client package. Never run. */
public final class ClientTypeViolator {

    private Minecraft minecraft;

    public Minecraft minecraft() {
        return minecraft;
    }
}
