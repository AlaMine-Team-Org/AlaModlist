package day.alacraft.alamodlist.store;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The player's account token and the delete token of every list they made: the mod's secrets, kept in one
 * {@link SecretStore} and nowhere else.
 *
 * <p>The tokens are not settings (they would be exported with the modpack and shown on the settings screen) and
 * they are not history (a history entry is shown to the player and may be logged). Reading is always from the file,
 * never from a copy in a field, so a token the player replaces works at once.
 *
 * <p>Nothing here prints a token: {@link #toString()} names the file only, and the methods that take a token
 * never put it in an exception message.
 */
public final class Tokens {

    /** The name of the account token inside the store. */
    static final String ACCOUNT_TOKEN = "account-token";

    private static final String DELETE_PREFIX = "delete-token.";

    /** The shape of a public id, with room to spare; the id becomes part of a secret's name. */
    private static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final SecretStore store;

    public Tokens(SecretStore store) {
        this.store = store;
    }

    /** The account token, read from the file now; empty when there is none (the player is anonymous). */
    public String accountToken() {
        return store.get(ACCOUNT_TOKEN).orElse("");
    }

    /**
     * Remembers the account token; a blank one forgets it.
     *
     * @return {@code true} if it is on disk, {@code false} if it is held in memory for this session only
     */
    public boolean setAccountToken(String token) {
        if (token == null || token.isBlank()) {
            return store.remove(ACCOUNT_TOKEN);
        }

        return store.put(ACCOUNT_TOKEN, token.trim());
    }

    /**
     * Remembers the delete token of a list. The service returns it exactly once.
     *
     * @return {@code true} if it is on disk, {@code false} if it is held in memory for this session only (then the
     *         list can be deleted until the game is closed, and not after)
     * @throws IllegalArgumentException for an id that is not a list id or an empty token (the token is not quoted)
     */
    public boolean saveDeleteToken(String id, String token) {
        return store.put(DELETE_PREFIX + requireId(id), token);
    }

    /** The delete token of a list, read from the file now. */
    public Optional<String> deleteToken(String id) {
        return LIST_ID.matcher(id == null ? "" : id).matches() ? store.get(DELETE_PREFIX + id) : Optional.empty();
    }

    /** Forgets the delete token of a list, because the list is gone (or the token expired with it). */
    public boolean forgetDeleteToken(String id) {
        return store.remove(DELETE_PREFIX + requireId(id));
    }

    /** What is wrong with the file right now, in words fit for a log or a screen; never a token. */
    public Optional<String> problem() {
        return store.problem();
    }

    @Override
    public String toString() {
        return "Tokens[" + store + "]";
    }

    private static String requireId(String id) {
        if (id == null || !LIST_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a list id");
        }

        return id;
    }
}
