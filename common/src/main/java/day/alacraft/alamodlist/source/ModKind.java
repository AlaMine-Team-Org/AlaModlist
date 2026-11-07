package day.alacraft.alamodlist.source;

/** What an installed component is, as the service wants to know it. */
public enum ModKind {
    /** A mod the player installed (or a part of one). */
    MOD("mod"),
    /** A library jar, or a mod that is only there for other mods. */
    LIBRARY("library"),
    /** The game, the loader or the Java runtime: no file of its own worth hashing. */
    BUILTIN("builtin");

    private final String wire;

    ModKind(String wire) {
        this.wire = wire;
    }

    /** The value of the {@code kind} field. */
    public String wire() {
        return wire;
    }
}
