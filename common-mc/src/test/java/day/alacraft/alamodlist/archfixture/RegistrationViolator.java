package day.alacraft.alamodlist.archfixture;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/** Breaks SHARED_CODE_DOES_NOT_REGISTER on purpose: registration from shared code. Never run. */
public final class RegistrationViolator {

    public static Block register(Identifier id, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, id, block);
    }
}
