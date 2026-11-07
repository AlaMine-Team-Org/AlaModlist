package day.alacraft.alamodlist.net;

/**
 * A failure that is not the service's answer but the absence of one.
 *
 * <p>Kept apart from HTTP errors because the advice differs completely: "no route to the site" is the player's
 * network, "429" is the service's, and telling someone to check their firewall because they were rate limited
 * wastes their evening.
 */
public final class TransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What went wrong, in the kinds the caller can act on. */
    public enum Kind {
        /** Refused, unroutable, unresolvable, or a connect that timed out. */
        OFFLINE,
        /** Connected, but no answer in time. */
        TIMEOUT,
        /** The TLS handshake failed. */
        TLS,
        /** Anything else: a bug on this side. */
        INTERNAL
    }

    private final Kind kind;

    public TransportException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
