package day.alacraft.alamodlist.client;

/**
 * DEMO - proof that the shared mixin config is applied on the loader that is running.
 *
 * <p>{@code mixin.client.MinecraftClientMixin} flips the flag on the first client tick; the client game-test
 * lane asserts it. A mixin config that a loader was never told about does not fail - it silently does not
 * apply, with the mod otherwise working. This probe turns that silence into a red test.
 *
 * <p>Not in the mixin package on purpose: every class of a mixin config's package is treated as a mixin and
 * must not be referenced from ordinary code.
 */
public final class ClientMixinProbe {

    private static volatile boolean ticked;

    private ClientMixinProbe() {
    }

    public static void markTicked() {
        ticked = true;
    }

    public static boolean ticked() {
        return ticked;
    }
}
