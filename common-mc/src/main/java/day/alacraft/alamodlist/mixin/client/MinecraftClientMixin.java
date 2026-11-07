package day.alacraft.alamodlist.mixin.client;

import day.alacraft.alamodlist.client.ClientMixinProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DEMO - the smallest useful client mixin: it proves the shared mixin config reaches the loader that is
 * running. Listed under {@code "client"} in the mixin config, so a dedicated server never loads it.
 *
 * <p>Mixin policy of this template, in short (docs/ARCHITECTURE.md has the long form):
 * <ul>
 *   <li>Use the loader's API or a vanilla hook first; a mixin is the last resort, and its javadoc says
 *       why no API would do. Here no API is needed at all - the class exists only as the probe.</li>
 *   <li>Targets are vanilla classes, and the mixin lives here in common-mc, once - both loaders ship
 *       Fabric Mixin. A mixin into a loader's own class belongs in that loader's module.</li>
 *   <li>Method names we add are prefixed with the mod id ({@code alamodlist$...}); bodies are one line
 *       that calls ordinary code.</li>
 *   <li>A mixin the mod cannot work without goes into the required config; a nice-to-have goes into a
 *       second config with {@code "required": false}, so a conflict with another mod disables the
 *       feature instead of crashing the game.</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void alamodlist$markTicked(CallbackInfo callback) {
        ClientMixinProbe.markTicked();
    }
}
