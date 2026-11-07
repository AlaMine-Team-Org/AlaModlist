package day.alacraft.alamodlist.client;

import com.mojang.blaze3d.platform.InputConstants;
import day.alacraft.alamodlist.AlaModlistMod;
import net.minecraft.client.KeyMapping;

import java.util.List;

/**
 * Every key mapping of the mod, declared once; each loader registers {@link #ALL} its own way
 * (Fabric {@code KeyMappingHelper}, NeoForge {@code RegisterKeyMappingsEvent}) and both poll the keys
 * from {@link ClientHooks#onClientTick}.
 */
public final class ModKeyMappings {

    /**
     * The mod's own category in the Controls screen; its label is the lang key
     * {@code key.category.alamodlist.main}.
     *
     * <p>Registered through vanilla's {@code Category.register}, which NeoForge (only NeoForge) marks
     * deprecated in favour of its event. The event is not reachable from shared code, and registering
     * the category a second time through it lists the category twice - so vanilla's call, once, here.
     */
    @SuppressWarnings("deprecation")
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(AlaModlistMod.id("main"));

    /**
     * Opens the snapshot preview. F8 by default (F7 belongs to Ala Screen, our screenshot mod); the player can
     * rebind it in Controls.
     *
     * <p>The three-argument constructor takes the key as an int and names no input type: the type's name
     * differs between Minecraft lines, the constructor does not. The default is an {@code InputConstants.KEY_*}
     * constant, never a literal: the numbering changed with the input library (F8 is 297 on 26.2 and 65 on 26.3).
     */
    public static final KeyMapping OPEN = new KeyMapping("key.alamodlist.open", InputConstants.KEY_F8, CATEGORY);

    /** Every mapping, in the order they appear in Controls. */
    public static final List<KeyMapping> ALL = List.of(OPEN);

    private ModKeyMappings() {
    }
}
