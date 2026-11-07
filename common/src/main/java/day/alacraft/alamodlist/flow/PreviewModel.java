package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.Snapshot;
import day.alacraft.alamodlist.privacy.TextClean;
import day.alacraft.alamodlist.wire.SnapshotJson;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the preview screen decides, without a screen: which categories exist and which can be switched on, what
 * is selected, which buttons are live in which phase, which sentence is shown, and what request the selection makes.
 * The screen only draws this and forwards clicks, so the part worth testing is testable without the game.
 *
 * <p>It lives on the game thread: the screen creates it, reads it every frame and is the only one to change it. The
 * background job is handed the {@link Progress} counters and a result, never this object.
 */
public final class PreviewModel {

    /** The longest title the request takes. */
    public static final int TITLE_MAX = 80;

    /** Where the player is in the flow. */
    public enum Phase {
        /** The snapshot is being built; the progress counters move. */
        COLLECTING,
        /** The snapshot is there and nothing has been sent. */
        READY,
        /** The request is out. */
        SENDING,
        /** The list exists. */
        SENT,
        /** Building the snapshot failed or was cancelled; nothing was sent. */
        COLLECT_FAILED,
        /** The privacy gate stopped the request; nothing was sent. */
        BLOCKED,
        /** The service did not take it; the snapshot is still here for another try. */
        SEND_FAILED
    }

    /** What the second footer button does now. */
    public enum Slot {
        REFRESH, CANCEL
    }

    /** Whether a line is a hint or a problem, for its colour. */
    public enum Tone {
        INFO, ERROR
    }

    /**
     * @param send     the Send button is live
     * @param slot     what the second button is: Refresh, or Cancel while the snapshot is being built
     * @param slotLive the second button is live
     * @param editable the title and the category switches can be changed
     */
    public record Buttons(boolean send, Slot slot, boolean slotLive, boolean editable) {
    }

    /** A sentence for the status line: the translation key and its values. */
    public record Status(Tone tone, String key, List<String> args) {
    }

    /**
     * One line of the list on the right.
     *
     * @param labelKey a translation key for a label, or {@code null} for a row that is only a name
     * @param text     the value or the name (already cleaned)
     * @param detail   a version or other short detail; may be empty
     * @param kindKey  a translation key that says what kind of component it is, or {@code null} for an ordinary one
     */
    public record Row(String labelKey, String text, String detail, String kindKey) {
    }

    /**
     * A category in the one-line summary; {@code count} is {@code null} for a category without a number.
     *
     * @param extra a second number the sentence carries (for the mods: libraries and built-in entries), or {@code null}
     */
    public record SummaryPart(Category category, Integer count, Integer extra) {
    }

    /** The request the selection makes. {@code body} is exactly what would be sent. */
    public record Prepared(Snapshot snapshot, byte[] body, int largestList, String title) {
    }

    private final Set<Category> selected = EnumSet.noneOf(Category.class);
    private Phase phase = Phase.COLLECTING;
    private Progress progress;
    private Snapshot collected;
    private String title = "";
    private boolean cancelled;
    private GateReport gate;
    private ApiError sendError;
    private String rejection;
    private int unhashed;

    public PreviewModel(Progress progress) {
        this.progress = progress;
        for (Category category : Category.values()) {
            if (category.selectedByDefault()) {
                selected.add(category);
            }
        }
    }

    // ---- state changes ----

    /** The snapshot is built. */
    public void collected(Snapshot snapshot) {
        collected(snapshot, 0);
    }

    /**
     * The snapshot is built.
     *
     * @param unhashed how many files go out without a hash (unreadable, or left when the time for reading ran out);
     *                 the player is told, because those entries cannot be matched on the site
     */
    public void collected(Snapshot snapshot, int unhashed) {
        this.collected = snapshot;
        this.unhashed = unhashed;
        this.phase = Phase.READY;
        this.cancelled = false;
    }

    /** Building the snapshot ended without one. */
    public void collectFailed(boolean cancelled) {
        this.collected = null;
        this.cancelled = cancelled;
        this.phase = Phase.COLLECT_FAILED;
    }

    /** Starts over (Refresh): the selection and the title are kept, the old snapshot and problems are not. */
    public void restart(Progress next) {
        this.progress = next;
        this.collected = null;
        this.cancelled = false;
        this.gate = null;
        this.sendError = null;
        this.rejection = null;
        this.unhashed = 0;
        this.phase = Phase.COLLECTING;
    }

    public void sending() {
        this.gate = null;
        this.sendError = null;
        this.rejection = null;
        this.phase = Phase.SENDING;
    }

    public void sent() {
        this.phase = Phase.SENT;
    }

    public void blocked(GateReport report) {
        this.gate = report;
        this.phase = Phase.BLOCKED;
    }

    public void sendFailed(ApiError error) {
        this.sendError = error;
        this.rejection = null;
        this.phase = Phase.SEND_FAILED;
    }

    /** The request was not made at all, for a reason on this side that has its own sentence (a bad site address). */
    public void rejected(String statusKey) {
        this.sendError = null;
        this.rejection = statusKey;
        this.phase = Phase.SEND_FAILED;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    /**
     * Switches a category on or off. A category that cannot be collected is never selected, one the request cannot
     * do without ({@link Category#mandatory()}) is never switched off, and nothing changes while the request is out.
     *
     * @return whether the selection changed
     */
    public boolean select(Category category, boolean on) {
        if (!buttons().editable() || !category.available() || (category.mandatory() && !on)) {
            return false;
        }

        return on ? selected.add(category) : selected.remove(category);
    }

    // ---- what the screen draws ----

    public Phase phase() {
        return phase;
    }

    public Progress progress() {
        return progress;
    }

    public String title() {
        return title;
    }

    public GateReport gate() {
        return gate;
    }

    public boolean isSelected(Category category) {
        return selected.contains(category);
    }

    /** The number shown beside a category, or {@code null} when it has none or the snapshot is not there yet. */
    public Integer count(Category category) {
        if (collected == null) {
            return null;
        }

        return switch (category) {
            case MODS -> modCount();
            case RESOURCE_PACKS -> collected.resourcePacks().size();
            case SHADER_PACKS -> collected.shaderPacks().size();
            case DATA_PACKS -> collected.dataPacks().size();
            case PLUGINS -> collected.plugins().size();
            default -> null;
        };
    }

    /**
     * How many entries are mods proper: everything that is not a library or a built-in component. This is the number
     * the site counts as {@code counts.mods} and the chat repeats, so the screen and the chat say the same thing.
     */
    public int modCount() {
        return collected == null ? 0 : (int) collected.mods().stream().filter(mod -> !isExtra(mod)).count();
    }

    /** How many entries are libraries or built-in components ({@code counts.libraries + counts.builtin} on the site). */
    public int libraryCount() {
        return collected == null ? 0 : collected.mods().size() - modCount();
    }

    private static boolean isExtra(ModEntry mod) {
        return "library".equals(mod.kind()) || "builtin".equals(mod.kind());
    }

    /** The selected categories, in the order of the list, with their numbers; empty before the snapshot is built. */
    public List<SummaryPart> summary() {
        List<SummaryPart> parts = new ArrayList<>();

        if (collected != null) {
            for (Category category : Category.values()) {
                if (selected.contains(category) && hasData(category)) {
                    parts.add(new SummaryPart(category, count(category), category == Category.MODS ? libraryCount() : null));
                }
            }
        }

        return parts;
    }

    /** The lines of a category's list. A category that cannot be collected has none: the screen shows its reason. */
    public List<Row> rows(Category category) {
        List<Row> rows = new ArrayList<>();

        if (collected == null || !category.available()) {
            return rows;
        }

        switch (category) {
            case ENVIRONMENT -> {
                EnvironmentInfo env = collected.environment();

                if (env != null) {
                    rows.add(new Row("screen.alamodlist.preview.env.minecraft",
                            join(env.mcVersion(), env.protocol() == null ? "" : "(" + env.protocol() + ")"), "", null));
                    rows.add(new Row("screen.alamodlist.preview.env.loader",
                            join(env.loader(), env.loaderVersion()), "", null));
                    rows.add(new Row("screen.alamodlist.preview.env.java",
                            env.javaMajor() == null ? "" : Integer.toString(env.javaMajor()), "", null));
                    rows.add(new Row("screen.alamodlist.preview.env.system", nullToEmpty(env.osFamily()), "", null));
                }
            }
            case MODS -> {
                for (ModEntry mod : collected.mods()) {
                    String name = mod.name() != null && !mod.name().isEmpty() ? mod.name() : mod.id();
                    String kindKey = "library".equals(mod.kind()) ? "screen.alamodlist.preview.kind.library"
                            : "builtin".equals(mod.kind()) ? "screen.alamodlist.preview.kind.builtin" : null;

                    rows.add(new Row(null, nullToEmpty(name), nullToEmpty(mod.version()), kindKey));
                }
            }
            default -> {
            }
        }

        return rows;
    }

    public Buttons buttons() {
        boolean editable = phase != Phase.SENDING && phase != Phase.SENT;
        boolean canSend = (phase == Phase.READY || phase == Phase.BLOCKED || phase == Phase.SEND_FAILED)
                && hasContent();

        return switch (phase) {
            case COLLECTING -> new Buttons(false, Slot.CANCEL, true, editable);
            case SENDING, SENT -> new Buttons(false, Slot.REFRESH, false, editable);
            default -> new Buttons(canSend, Slot.REFRESH, true, editable);
        };
    }

    /** The sentence for the status line, or {@code null} when there is nothing to say. */
    public Status status() {
        return switch (phase) {
            case COLLECTING, SENT -> null;
            case READY -> unhashed > 0
                    ? new Status(Tone.INFO, "screen.alamodlist.preview.status.unhashed", List.of(Integer.toString(unhashed)))
                    : new Status(Tone.INFO, "screen.alamodlist.preview.status.ready", List.of());
            case SENDING -> new Status(Tone.INFO, "screen.alamodlist.preview.status.sending", List.of());
            case COLLECT_FAILED -> cancelled
                    ? new Status(Tone.INFO, "screen.alamodlist.preview.status.cancelled", List.of())
                    : new Status(Tone.ERROR, "screen.alamodlist.preview.status.collect_failed", List.of());
            case BLOCKED -> new Status(Tone.ERROR, "screen.alamodlist.preview.status.blocked", List.of());
            case SEND_FAILED -> rejection != null
                    ? new Status(Tone.ERROR, rejection, List.of())
                    : new Status(Tone.ERROR, ErrorText.key(sendError.code(), ErrorText.Action.SEND),
                            ErrorText.args(sendError));
        };
    }

    /** The share of the hashing stage done, 0 to 1; an unknown total counts as 0. */
    public static double fraction(Progress progress) {
        if (progress.bytesTotal() > 0) {
            return clamp(progress.bytesDone() / (double) progress.bytesTotal());
        }
        if (progress.filesTotal() > 0) {
            return clamp(progress.filesDone() / (double) progress.filesTotal());
        }

        return 0;
    }

    // ---- the request ----

    /**
     * The request the selection makes: the collected snapshot, cut down to the selected categories, with the title and
     * the language of the link.
     *
     * @throws IllegalStateException when the snapshot is not there yet
     */
    public Prepared prepare(String siteLocale) {
        if (collected == null) {
            throw new IllegalStateException("There is no snapshot to send yet.");
        }

        String cleaned = TextClean.line(TITLE_MAX, title);
        Snapshot c = collected;
        Snapshot request = new Snapshot(c.source(), siteLocale, cleaned.isEmpty() ? null : cleaned, null,
                pick(Category.ENVIRONMENT, c.environment()),
                pickAll(Category.MODS, c.mods()),
                pickAll(Category.RESOURCE_PACKS, c.resourcePacks()),
                pickAll(Category.SHADER_PACKS, c.shaderPacks()),
                pickAll(Category.DATA_PACKS, c.dataPacks()),
                pick(Category.LAUNCHER, c.launcher()),
                pick(Category.SERVER, c.server()),
                pickAll(Category.PLUGINS, c.plugins()));

        int largest = Math.max(Math.max(request.mods().size(), request.resourcePacks().size()),
                Math.max(Math.max(request.shaderPacks().size(), request.dataPacks().size()),
                        request.plugins().size()));

        return new Prepared(request, SnapshotJson.toBytes(request), largest, cleaned);
    }

    // ---- internals ----

    private boolean hasContent() {
        if (collected == null) {
            return false;
        }
        for (Category category : selected) {
            if (hasData(category)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasData(Category category) {
        return switch (category) {
            case ENVIRONMENT -> collected.environment() != null;
            case MODS -> !collected.mods().isEmpty();
            case RESOURCE_PACKS -> !collected.resourcePacks().isEmpty();
            case SHADER_PACKS -> !collected.shaderPacks().isEmpty();
            case DATA_PACKS -> !collected.dataPacks().isEmpty();
            case LAUNCHER -> collected.launcher() != null;
            case SERVER -> collected.server() != null;
            case PLUGINS -> !collected.plugins().isEmpty();
        };
    }

    private <T> T pick(Category category, T value) {
        return selected.contains(category) ? value : null;
    }

    private <T> List<T> pickAll(Category category, List<T> value) {
        return selected.contains(category) ? value : List.of();
    }

    private static String join(String first, String second) {
        String a = nullToEmpty(first);
        String b = nullToEmpty(second);

        return a.isEmpty() ? b : b.isEmpty() ? a : a + " " + b;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
