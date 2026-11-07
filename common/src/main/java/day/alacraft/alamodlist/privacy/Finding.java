package day.alacraft.alamodlist.privacy;

/**
 * One thing the privacy gate found in a request body.
 *
 * <p>It names the field and what kind of value was found, and deliberately never the value or a piece of the text
 * around it: the message built from a finding is shown to the player and may be logged.
 *
 * @param label      what was found ({@link KnownValues#PLAYER_NAME} and the like), or {@code unknown_key} /
 *                   {@code duplicate_key} for a structure the schema does not allow
 * @param path       where, as in {@code mods[12].name}
 * @param kind       a whole value, a part of one, or a structural fault
 * @param redactable true when the field is free text, so replacing the match repairs it; an identifier cannot be
 *                   repaired, because changing it changes what it names
 */
public record Finding(String label, String path, Kind kind, boolean redactable) {

    /** What sort of match it is. */
    public enum Kind {
        /** A known value, whole. */
        VALUE,
        /** A part of a known value: a partial leak is still a leak. */
        FRAGMENT,
        /** A key the schema does not list. */
        UNKNOWN_KEY,
        /** A key written twice in one object (the first value would hide from a reader that keeps the last). */
        DUPLICATE_KEY
    }
}
