package day.alacraft.alamodlist.source;

/** Where a mod says it runs. A claim of the mod, not a fact. */
public enum Side {
    CLIENT("client"),
    SERVER("server"),
    UNIVERSAL("universal");

    private final String wire;

    Side(String wire) {
        this.wire = wire;
    }

    /** The value of the {@code side} field. */
    public String wire() {
        return wire;
    }

    /**
     * What a mod declares in its metadata, from the loader's own word for it ({@code CLIENT}, {@code SERVER} or
     * {@code UNIVERSAL}); null when the loader has no such declaration or the word is not one of these.
     */
    public static Side declared(String environment) {
        if (environment == null) {
            return null;
        }
        return switch (environment) {
            case "CLIENT" -> CLIENT;
            case "SERVER" -> SERVER;
            case "UNIVERSAL" -> UNIVERSAL;
            default -> null;
        };
    }
}
