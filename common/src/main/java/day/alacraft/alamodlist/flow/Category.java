package day.alacraft.alamodlist.flow;

/**
 * The categories of a snapshot the contract knows, in the order the preview lists them.
 *
 * <p>Every category is listed, available or not: one that the mod cannot collect yet is shown switched off with the
 * reason, because a button that does nothing is worse than a missing one and a missing category leaves the player
 * wondering whether the mod looked at it. Flipping {@code implemented} is the whole change that turns one on once its
 * source is written and checked.
 *
 * <p>All translation keys are written out in full: a key built by concatenation is invisible to the catalogue check.
 */
public enum Category {

    /** The request is not valid without it (the wire schema requires {@code environment}), so it cannot be switched off. */
    ENVIRONMENT("environment", true, true, Reason.NONE,
            "screen.alamodlist.preview.category.environment", "screen.alamodlist.preview.summary.environment"),
    MODS("mods", true, true, Reason.NONE,
            "screen.alamodlist.preview.category.mods", "screen.alamodlist.preview.summary.mods"),
    RESOURCE_PACKS("resource_packs", false, true, Reason.LATER,
            "screen.alamodlist.preview.category.resource_packs", "screen.alamodlist.preview.summary.resource_packs"),
    SHADER_PACKS("shader_packs", false, true, Reason.LATER,
            "screen.alamodlist.preview.category.shader_packs", "screen.alamodlist.preview.summary.shader_packs"),
    DATA_PACKS("data_packs", false, true, Reason.LATER_SINGLEPLAYER,
            "screen.alamodlist.preview.category.data_packs", "screen.alamodlist.preview.summary.data_packs"),
    LAUNCHER("launcher", false, true, Reason.LATER,
            "screen.alamodlist.preview.category.launcher", "screen.alamodlist.preview.summary.launcher"),
    /** Switched off by default even when it exists: it is data the server sent, and the player opts in (decision 28). */
    SERVER("server", false, false, Reason.LATER_MULTIPLAYER,
            "screen.alamodlist.preview.category.server", "screen.alamodlist.preview.summary.server"),
    PLUGINS("plugins", false, true, Reason.LATER,
            "screen.alamodlist.preview.category.plugins", "screen.alamodlist.preview.summary.plugins");

    /** Why a category is switched off. */
    public enum Reason {
        NONE(null),
        LATER("screen.alamodlist.preview.later"),
        LATER_SINGLEPLAYER("screen.alamodlist.preview.later_singleplayer"),
        LATER_MULTIPLAYER("screen.alamodlist.preview.later_multiplayer");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** The translation key of the explanation, or {@code null} for {@link #NONE}. */
        public String key() {
            return key;
        }
    }

    private final String wireName;
    private final boolean implemented;
    private final boolean selectedByDefault;
    private final Reason reason;
    private final String nameKey;
    private final String summaryKey;

    Category(String wireName, boolean implemented, boolean selectedByDefault, Reason reason, String nameKey,
             String summaryKey) {
        this.wireName = wireName;
        this.implemented = implemented;
        this.selectedByDefault = selectedByDefault;
        this.reason = reason;
        this.nameKey = nameKey;
        this.summaryKey = summaryKey;
    }

    /** Whether the request cannot be made without it: the player sees it on and cannot switch it off. */
    public boolean mandatory() {
        return this == ENVIRONMENT;
    }

    /** The name in the request body. */
    public String wireName() {
        return wireName;
    }

    /** Whether the mod can collect it in this version. */
    public boolean available() {
        return implemented;
    }

    /** What the contract says is on when the preview opens; an unavailable category is never selected. */
    public boolean selectedByDefault() {
        return implemented && selectedByDefault;
    }

    /** Why it is off; {@link Reason#NONE} when it is available. */
    public Reason reason() {
        return implemented ? Reason.NONE : reason;
    }

    public String nameKey() {
        return nameKey;
    }

    /** The key of the one-line summary of this category ({@code Mods: %s}); the environment has no count. */
    public String summaryKey() {
        return summaryKey;
    }
}
