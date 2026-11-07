package day.alacraft.alamodlist.client.screen;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.config.KnobConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A config screen built from any {@link KnobConfig}: one row per knob, Done saves, Cancel discards.
 *
 * <p>Edits are kept in {@link #pending} and applied only on Done, so Cancel (or Escape) really leaves the
 * config as it was, and a window resize (which re-runs {@link #init}) keeps what was typed.
 *
 * <p>Labels and tooltips come from lang keys {@code <prefix><key>} and {@code <prefix><key>.tooltip}
 * - player-facing text is translated, the English docs in the annotations are for the file.
 */
public class KnobConfigScreen extends Screen {

    /** Text colours carry an alpha byte: in 26.2 text drawn with alpha 0 (0xRRGGBB) is not drawn at all. */
    private static final int WHITE = 0xFFFFFFFF;
    private static final int VALID = 0xFFE0E0E0;
    private static final int INVALID = 0xFFFF5555;

    private static final int ROW_WIDTH = 310;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 24;

    private final Screen parent;
    private final KnobConfig config;
    private final String langPrefix;
    private final Map<String, Object> pending = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();

    public KnobConfigScreen(Screen parent, KnobConfig config, Component title, String langPrefix) {
        super(title);
        this.parent = parent;
        this.config = config;
        this.langPrefix = langPrefix;
    }

    @Override
    protected void init() {
        labels.clear();
        int left = width / 2 - ROW_WIDTH / 2;
        int y = 40;
        for (KnobConfig.KnobView knob : config.knobs()) {
            Component label = Component.translatable(langPrefix + knob.key());
            Tooltip tooltip = Tooltip.create(Component.translatable(langPrefix + knob.key() + ".tooltip"));
            if (knob.type() == boolean.class) {
                boolean current = (Boolean) pending.getOrDefault(knob.key(), knob.value());
                CycleButton<Boolean> button = CycleButton.onOffBuilder(current)
                        .create(left, y, ROW_WIDTH, ROW_HEIGHT, label, (widget, value) -> pending.put(knob.key(), value));
                button.setTooltip(tooltip);
                addRenderableWidget(button);
            } else {
                labels.add(new Label(label, left, y + 6));
                EditBox box = new EditBox(font, left + ROW_WIDTH / 2, y, ROW_WIDTH / 2, ROW_HEIGHT, label);
                box.setValue(String.valueOf(pending.getOrDefault(knob.key(), knob.value())));
                box.setTooltip(tooltip);
                box.setResponder(text -> {
                    Object parsed = parse(knob, text);
                    if (parsed != null && knob.accepts(parsed)) {
                        pending.put(knob.key(), parsed);
                        box.setTextColor(VALID);
                    } else {
                        pending.remove(knob.key());
                        box.setTextColor(INVALID);
                    }
                });
                addRenderableWidget(box);
            }
            y += ROW_SPACING;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds(width / 2 - 155, height - 28, 150, ROW_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(width / 2 + 5, height - 28, 150, ROW_HEIGHT).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 15, WHITE);
        for (Label label : labels) {
            graphics.text(font, label.text(), label.x(), label.y(), WHITE);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private void saveAndClose() {
        if (!pending.isEmpty()) {
            for (KnobConfig.KnobView knob : config.knobs()) {
                if (pending.containsKey(knob.key())) {
                    knob.set(pending.get(knob.key()));
                }
            }
            try {
                config.save();
            } catch (IOException e) {
                AlaModlist.LOGGER.error("Could not save the config: {}", e.toString());
            }
        }
        onClose();
    }

    /** The typed value for a text field, or null when the text is not one. */
    private static Object parse(KnobConfig.KnobView knob, String text) {
        try {
            if (knob.type() == int.class) {
                return Integer.parseInt(text.trim());
            }
            if (knob.type() == long.class) {
                return Long.parseLong(text.trim());
            }
            if (knob.type() == double.class) {
                return Double.parseDouble(text.trim());
            }
            return text;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Label(Component text, int x, int y) {
    }
}
