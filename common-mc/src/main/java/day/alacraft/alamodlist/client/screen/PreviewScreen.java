package day.alacraft.alamodlist.client.screen;

import day.alacraft.alamodlist.client.PreviewSession;
import day.alacraft.alamodlist.flow.ByteSize;
import day.alacraft.alamodlist.flow.Category;
import day.alacraft.alamodlist.flow.GateReport;
import day.alacraft.alamodlist.flow.PreviewModel;
import day.alacraft.alamodlist.flow.TextFit;
import day.alacraft.alamodlist.hash.Progress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The preview: what will be sent, before anything is. A list of categories on the left with a switch for each, the
 * lines of the chosen category on the right, the title above, and Send, Refresh and Close below.
 *
 * <p>This class draws and forwards clicks. Every decision - which category exists, which button is live, what the
 * request is - is {@link PreviewModel}'s, and the work behind the buttons is {@link PreviewSession}'s; both are tested
 * without the game.
 *
 * <p>Closing the screen, by any road, stops the collection ({@link #removed()}).
 *
 * <p>Drawing uses the calls both lines of the game share and no sprite or pipeline type: lists and widgets are
 * vanilla, the progress bar is two filled rectangles. Colours carry an alpha byte (rule 12: text with alpha 0 is not
 * drawn on 26.2).
 */
public final class PreviewScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int HINT = 0xFFC0C0C0;
    private static final int PROBLEM = 0xFFFF5555;
    private static final int TAG = 0xFFFFD24A;
    private static final int BAR_BACK = 0xFF2A2A2A;
    private static final int BAR_FILL = 0xFF55FF55;
    private static final int SELECTED_ROW = 0x40FFFFFF;

    private static final int MARGIN = 12;
    private static final int HEADER_HEIGHT = 62;
    private static final int STATUS_HEIGHT = 34;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_PADDING = 12;
    private static final int MIN_BUTTON_WIDTH = 70;
    private static final int RAIL_ROW_HEIGHT = 42;
    private static final int LINE_HEIGHT = 14;
    private static final int LABEL_WIDTH = 96;

    private final PreviewSession session;
    private final PreviewModel model;

    private HeaderAndFooterLayout layout;
    private EditBox titleBox;
    private Button sendButton;
    private Button slotButton;
    private Button closeButton;
    private CategoryList rail;
    private LineList lines;
    private Category shown;
    private boolean showGate;
    private PreviewModel.Phase lastPhase;
    private String builtFor = "";
    private int statusTop;

    public PreviewScreen(PreviewSession session) {
        this(session, Category.MODS);
    }

    /** @param first the category whose lines are shown when the screen opens */
    public PreviewScreen(PreviewSession session, Category first) {
        super(Component.translatable("screen.alamodlist.preview.title"));
        this.session = session;
        this.model = session.model();
        this.shown = first;
        this.lastPhase = model.phase();
        this.showGate = model.phase() == PreviewModel.Phase.BLOCKED;
    }

    /** What the screen draws, for the visual stands that wait for a state. */
    public PreviewModel model() {
        return model;
    }

    // ---- widgets ----

    @Override
    protected void init() {
        Component send = Component.translatable("screen.alamodlist.preview.send");
        Component refresh = Component.translatable("screen.alamodlist.preview.refresh");
        Component cancel = Component.translatable("screen.alamodlist.preview.cancel");

        sendButton = Button.builder(send, button -> session.send()).build();
        slotButton = Button.builder(refresh, button -> {
            if (model.buttons().slot() == PreviewModel.Slot.CANCEL) {
                session.cancelCollecting();
            } else {
                session.refresh();
            }
        }).build();
        Component close = Component.translatable("screen.alamodlist.preview.close");
        closeButton = Button.builder(close, button -> onClose()).build();

        int footer = placeButtons(List.of(sendButton, slotButton, closeButton), List.of(send, refresh, cancel, close));
        layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, footer);
        layout.arrangeElements();

        titleBox = new EditBox(font, MARGIN, 36, width - 2 * MARGIN, 20,
                Component.translatable("screen.alamodlist.preview.title_hint"));
        titleBox.setMaxLength(PreviewModel.TITLE_MAX);
        titleBox.setHint(Component.translatable("screen.alamodlist.preview.title_hint"));
        titleBox.setValue(model.title());
        titleBox.setResponder(model::setTitle);
        addRenderableWidget(titleBox);

        int top = layout.getHeaderHeight();
        int contentHeight = layout.getContentHeight();
        int railWidth = Math.max(110, Math.min(170, width * 2 / 5));

        rail = new CategoryList(minecraft, railWidth, contentHeight, top);
        for (Category category : Category.values()) {
            rail.add(new CategoryRow(category));
        }
        addRenderableWidget(rail);

        lines = new LineList(minecraft, width - railWidth, contentHeight, top);
        lines.updateSizeAndPosition(width - railWidth, contentHeight, railWidth, top);
        addRenderableWidget(lines);

        addRenderableWidget(sendButton);
        addRenderableWidget(slotButton);
        addRenderableWidget(closeButton);

        builtFor = "";
        refreshLines();
    }

    /**
     * Places the footer buttons, each as wide as its own label needs, in one row when the row fits and stacked when it
     * does not (a translation that is half again as long must not cut a label). Returns the height of the footer.
     *
     * @param widest the labels the buttons can show, for measuring: Send, Refresh, Cancel (the second button is either), Close
     */
    private int placeButtons(List<Button> buttons, List<Component> widest) {
        int[] widths = {
                buttonWidth(widest.get(0)),
                Math.max(buttonWidth(widest.get(1)), buttonWidth(widest.get(2))),
                buttonWidth(widest.get(3))
        };
        int row = BUTTON_GAP * (buttons.size() - 1);
        for (int w : widths) {
            row += w;
        }

        int available = width - 2 * MARGIN;
        if (row <= available) {
            int footer = STATUS_HEIGHT + BUTTON_HEIGHT + 14;
            int y = height - BUTTON_HEIGHT - 8;
            int x = (width - row) / 2;

            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).setPosition(x, y);
                buttons.get(i).setWidth(widths[i]);
                x += widths[i] + BUTTON_GAP;
            }
            statusTop = height - footer + 4;

            return footer;
        }

        int stackWidth = Math.min(available, 220);
        int x = (width - stackWidth) / 2;
        int footer = STATUS_HEIGHT + buttons.size() * (BUTTON_HEIGHT + 4) + 6;
        int y = height - buttons.size() * (BUTTON_HEIGHT + 4) - 2;

        for (Button button : buttons) {
            button.setPosition(x, y);
            button.setWidth(stackWidth);
            y += BUTTON_HEIGHT + 4;
        }
        statusTop = height - footer + 4;

        return footer;
    }

    private int buttonWidth(Component label) {
        return Math.max(MIN_BUTTON_WIDTH, font.width(label) + BUTTON_PADDING * 2);
    }

    // ---- life ----

    @Override
    public void tick() {
        session.tick();

        PreviewModel.Buttons buttons = model.buttons();
        sendButton.active = buttons.send();
        slotButton.active = buttons.slotLive();
        slotButton.setMessage(Component.translatable(buttons.slot() == PreviewModel.Slot.CANCEL
                ? "screen.alamodlist.preview.cancel" : "screen.alamodlist.preview.refresh"));
        titleBox.setEditable(buttons.editable());

        if (model.phase() != lastPhase) {
            // A new block shows what the gate found; every other change shows the category again.
            showGate = model.phase() == PreviewModel.Phase.BLOCKED;
            lastPhase = model.phase();
        }
        refreshLines();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(null);
    }

    @Override
    public void removed() {
        session.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(font, title, width / 2, 8, WHITE);
        drawSummary(graphics);
        drawStatus(graphics);
    }

    private void drawSummary(GuiGraphicsExtractor graphics) {
        List<PreviewModel.SummaryPart> parts = model.summary();
        Component summary;

        if (parts.isEmpty()) {
            summary = Component.translatable("screen.alamodlist.preview.collecting");
        } else {
            MutableComponent joined = Component.empty();
            for (int i = 0; i < parts.size(); i++) {
                PreviewModel.SummaryPart part = parts.get(i);

                joined.append(i == 0 ? Component.empty() : Component.literal(", "));
                String key = part.category().summaryKey();

                joined.append(part.count() == null ? Component.translatable(key)
                        : part.extra() == null ? Component.translatable(key, part.count())
                        : Component.translatable(key, part.count(), part.extra()));
            }
            summary = joined;
        }
        graphics.centeredText(font, summary, width / 2, 22, HINT);
    }

    private void drawStatus(GuiGraphicsExtractor graphics) {
        int left = MARGIN;
        int right = width - MARGIN;

        if (model.phase() == PreviewModel.Phase.COLLECTING) {
            Progress progress = model.progress();
            graphics.text(font, progressText(progress), left, statusTop, HINT);

            int barTop = statusTop + font.lineHeight + 4;
            int filled = (int) Math.round((right - left) * PreviewModel.fraction(progress));

            graphics.fill(left, barTop, right, barTop + 8, BAR_BACK);
            graphics.fill(left, barTop, left + filled, barTop + 8, BAR_FILL);

            return;
        }

        PreviewModel.Status status = model.status();
        if (status != null) {
            Component text = Component.translatable(status.key(), status.args().toArray());
            int color = status.tone() == PreviewModel.Tone.ERROR ? PROBLEM : HINT;
            int y = statusTop;
            int shownLines = 0;

            for (FormattedCharSequence line : font.split(text, right - left)) {
                if (shownLines++ == 3) {
                    break;
                }
                graphics.text(font, line, left, y, ARGB.opaque(color));
                y += font.lineHeight + 1;
            }
        }
    }

    private Component progressText(Progress progress) {
        if (progress.stage() == Progress.Stage.HASHING && progress.filesTotal() > 0) {
            return Component.translatable("screen.alamodlist.preview.progress.hashing", progress.filesDone(),
                    progress.filesTotal(), ByteSize.human(progress.bytesDone()), ByteSize.human(progress.bytesTotal()));
        }

        return Component.translatable("screen.alamodlist.preview.progress.listing");
    }

    // ---- the lines on the right ----

    /** Rebuilds the right-hand list when what it should show has changed. */
    private void refreshLines() {
        String key = shown + "|" + model.phase() + "|" + showGate;
        if (key.equals(builtFor)) {
            return;
        }
        builtFor = key;

        int textWidth = lines.getRowWidth() - 8;
        List<LineEntry> entries = new ArrayList<>();

        if (showGate && model.phase() == PreviewModel.Phase.BLOCKED && model.gate() != null) {
            gateLines(entries, textWidth);
        } else if (!shown.available()) {
            wrapped(entries, Component.translatable(shown.nameKey()), WHITE, textWidth);
            wrapped(entries, Component.translatable(shown.reason().key()), MUTED, textWidth);
        } else if (model.phase() == PreviewModel.Phase.COLLECTING) {
            wrapped(entries, Component.translatable("screen.alamodlist.preview.collecting"), MUTED, textWidth);
        } else {
            for (PreviewModel.Row row : model.rows(shown)) {
                entries.add(LineEntry.row(row));
            }
        }

        lines.replaceEntries(entries);
        lines.setScrollAmount(0);
    }

    private void gateLines(List<LineEntry> entries, int textWidth) {
        GateReport report = model.gate();

        wrapped(entries, Component.translatable("screen.alamodlist.gate.title"), PROBLEM, textWidth);
        wrapped(entries, Component.translatable("screen.alamodlist.gate.explain"), HINT, textWidth);
        for (GateReport.Line line : report.lines()) {
            wrapped(entries, Component.literal("- ").append(Component.translatable(line.labelKey()))
                    .append(Component.literal(" (" + line.path() + ")")), WHITE, textWidth);
        }
        if (report.more() > 0) {
            wrapped(entries, Component.translatable("screen.alamodlist.gate.more", report.more()), MUTED, textWidth);
        }
    }

    private void wrapped(List<LineEntry> entries, Component text, int color, int textWidth) {
        for (FormattedCharSequence part : font.split(text, textWidth)) {
            entries.add(LineEntry.plain(part, text, color));
        }
    }

    private void show(Category category) {
        shown = category;
        showGate = false;
        refreshLines();
    }

    // ---- the category list ----

    private final class CategoryList extends ContainerObjectSelectionList<CategoryRow> {

        CategoryList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, RAIL_ROW_HEIGHT);
        }

        void add(CategoryRow row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return width - 20;
        }
    }

    /** One category: a switch, a button that shows its lines, and a muted second line (the reason, or the count). */
    private final class CategoryRow extends ContainerObjectSelectionList.Entry<CategoryRow> {

        private final Category category;
        private final Checkbox box;
        private final Button open;

        CategoryRow(Category category) {
            this.category = category;
            this.box = Checkbox.builder(Component.empty(), font)
                    .selected(model.isSelected(category))
                    .tooltip(Tooltip.create(Component.translatable(tooltipKey(category))))
                    .onValueChange((checkbox, value) -> model.select(category, value))
                    .build();
            this.open = Button.builder(label(), button -> show(category)).build();
        }

        private Component label() {
            MutableComponent name = Component.translatable(category.nameKey());
            Integer count = model.count(category);

            return count == null || !category.available() ? name : name.append(" (" + count + ")");
        }

        private Component note() {
            if (!category.available()) {
                return Component.translatable(category.reason().key());
            }

            return category.mandatory() ? Component.translatable("screen.alamodlist.preview.always") : null;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(box, open);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(box, open);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
                                   float partialTick) {
            int x = getContentX();
            int y = getContentY();
            int boxSize = Checkbox.getBoxSize(font);

            if (category == shown && !showGate) {
                graphics.fill(x - 2, y - 1, x + getContentWidth() + 2, y + getContentHeight() - 1, SELECTED_ROW);
            }

            // A switch is live only for a category that can be collected, may be left out, and while nothing is out.
            box.active = category.available() && !category.mandatory() && model.buttons().editable();
            box.setPosition(x, y + (BUTTON_HEIGHT - boxSize) / 2);
            box.extractRenderState(graphics, mouseX, mouseY, partialTick);

            open.setMessage(label());
            open.setPosition(x + boxSize + 6, y);
            open.setWidth(getContentWidth() - boxSize - 6);
            open.extractRenderState(graphics, mouseX, mouseY, partialTick);

            Component note = note();
            if (note != null) {
                int noteX = x + boxSize + 8;
                int noteWidth = getContentWidth() - boxSize - 8;
                String full = note.getString();
                String text = TextFit.ellipsize(full, noteWidth, font::width);

                graphics.text(font, text, noteX, y + BUTTON_HEIGHT + 4, MUTED);
                // The whole sentence on hover over the row (the switch has its own hint).
                if (hovered && !box.isMouseOver(mouseX, mouseY) && TextFit.isCut(full, noteWidth, font::width)) {
                    graphics.setTooltipForNextFrame(font, note, mouseX, mouseY);
                }
            }
        }
    }

    private static String tooltipKey(Category category) {
        if (!category.available()) {
            return category.reason().key();
        }

        return category.mandatory() ? "screen.alamodlist.preview.always" : "screen.alamodlist.preview.include";
    }

    // ---- the lines list ----

    private final class LineList extends ObjectSelectionList<LineEntry> {

        LineList(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, LINE_HEIGHT);
        }

        /** The lines are read, not picked: a click on one must not outline it as if it did something. */
        @Override
        protected boolean entriesCanBeSelected() {
            return false;
        }

        @Override
        public int getRowWidth() {
            return width - 20;
        }
    }

    /** One line of the right-hand list: a name and its version, a label and its value, or a line of plain text. */
    private static final class LineEntry extends ObjectSelectionList.Entry<LineEntry> {

        private final Component narration;
        private final String label;
        private final String text;
        private final String detail;
        private final String tag;
        private final FormattedCharSequence wrapped;
        private final int color;

        private LineEntry(Component narration, String label, String text, String detail, String tag,
                          FormattedCharSequence wrapped, int color) {
            this.narration = narration;
            this.label = label;
            this.text = text;
            this.detail = detail;
            this.tag = tag;
            this.wrapped = wrapped;
            this.color = color;
        }

        static LineEntry row(PreviewModel.Row row) {
            String label = row.labelKey() == null ? null : Component.translatable(row.labelKey()).getString();
            String tag = row.kindKey() == null ? null : Component.translatable(row.kindKey()).getString();

            return new LineEntry(Component.literal(row.text()), label, row.text(), row.detail(), tag, null, WHITE);
        }

        static LineEntry plain(FormattedCharSequence part, Component whole, int color) {
            return new LineEntry(whole, null, null, null, null, part, color);
        }

        @Override
        public Component getNarration() {
            return narration;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered,
                                   float partialTick) {
            Minecraft minecraft = Minecraft.getInstance();
            var font = minecraft.font;
            int x = getContentX() + 2;
            int y = getContentYMiddle() - font.lineHeight / 2;
            int right = getContentRight() - 2;

            if (wrapped != null) {
                graphics.text(font, wrapped, x, y, ARGB.opaque(color));
                return;
            }

            if (label != null) {
                int valueWidth = right - x - LABEL_WIDTH;
                graphics.text(font, TextFit.ellipsize(label, LABEL_WIDTH - 4, font::width), x, y, MUTED);
                graphics.text(font, TextFit.ellipsize(text, valueWidth, font::width), x + LABEL_WIDTH, y, WHITE);
                if (hovered && (TextFit.isCut(label, LABEL_WIDTH - 4, font::width)
                        || TextFit.isCut(text, valueWidth, font::width))) {
                    graphics.setTooltipForNextFrame(font, Component.literal(label + ": " + text), mouseX, mouseY);
                }
                return;
            }

            int detailWidth = detail.isEmpty() ? 0 : font.width(detail) + 8;
            int tagWidth = tag == null ? 0 : font.width(tag) + 8;
            int nameWidth = Math.max(20, right - x - detailWidth - tagWidth);
            String name = TextFit.ellipsize(text, nameWidth, font::width);

            graphics.text(font, name, x, y, WHITE);
            if (tag != null) {
                graphics.text(font, tag, x + font.width(name) + 8, y, TAG);
            }
            if (!detail.isEmpty()) {
                graphics.text(font, detail, right - font.width(detail), y, MUTED);
            }
            if (hovered && TextFit.isCut(text, nameWidth, font::width)) {
                graphics.setTooltipForNextFrame(font, Component.literal(tag == null ? text + " " + detail
                        : text + " " + detail + " (" + tag + ")"), mouseX, mouseY);
            }
        }
    }
}
