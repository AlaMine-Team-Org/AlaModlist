package day.alacraft.alamodlist.privacy;

import day.alacraft.alamodlist.privacy.Needle.Boundary;
import day.alacraft.alamodlist.privacy.Needle.Fold;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything the mod knows about the player and the machine that must not appear in a request: the second layer
 * of the privacy gate searches the final bytes for exactly these.
 *
 * <p>The values are read again at the moment of sending (the player may have switched server while looking at the
 * preview), by whoever has access to the game; this class only turns them into {@link Needle}s. Short values match
 * as whole words, long ones as substrings - see {@link #NAME_SUBSTRING_MIN}.
 *
 * <p>{@link #toString()} never prints a value.
 */
public final class KnownValues {

    /** Labels name a value to the player in the gate's message; they are stable identifiers, not display text. */
    public static final String PLAYER_NAME = "player_name";
    public static final String PLAYER_UUID = "player_uuid";
    public static final String SESSION = "session";
    public static final String ACCESS_TOKEN = "access_token";
    public static final String SERVER_ADDRESS = "server_address";
    public static final String SERVER_NAME = "server_name";
    public static final String HOME_DIR = "home_dir";
    public static final String OS_USER = "os_user";
    public static final String COMPUTER_NAME = "computer_name";
    public static final String GAME_DIR = "game_dir";
    public static final String CONFIG_DIR = "config_dir";
    public static final String WORLD_SEED = "world_seed";
    public static final String WORLD_NAME = "world_name";
    public static final String OTHER_PLAYERS = "other_players";

    /** From this length a name matches as a substring; shorter, only as a whole word. */
    public static final int NAME_SUBSTRING_MIN = 6;

    /** A secret shorter than this is not treated as one: it would match ordinary text. */
    public static final int SECRET_MIN = 16;

    /** A seed with fewer digits would match ordinary numbers. */
    public static final int SEED_MIN_DIGITS = 8;

    /** The only field other players' names are looked for in: a reply to a command can list them. */
    public static final String OTHER_PLAYERS_SCOPE = "server.command_replies[].text";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final int HOST_MIN = 4;
    private static final int FRAGMENT_NAME_MIN = 3;
    private static final int HOST_LABEL_MIN = 5;
    private static final int PATH_MIN = 4;

    private final List<Needle> needles;

    private KnownValues(List<Needle> needles) {
        this.needles = List.copyOf(needles);
    }

    public List<Needle> needles() {
        return needles;
    }

    public boolean isEmpty() {
        return needles.isEmpty();
    }

    @Override
    public String toString() {
        return "KnownValues[" + needles.size() + " needles]";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects values; a blank or too-short value is skipped, never an error. */
    public static final class Builder {

        private final List<Needle> needles = new ArrayList<>();

        private Builder() {
        }

        /** The player's name in the game. */
        public Builder playerName(String name) {
            name(PLAYER_NAME, name, false, null);
            return this;
        }

        /** The player's UUID: in both spellings, any case; its first group of eight digits is a fragment. */
        public Builder playerUuid(UUID uuid) {
            if (uuid == null) {
                return this;
            }
            String dashed = uuid.toString().toLowerCase(Locale.ROOT);
            add(PLAYER_UUID, dashed, Fold.CASE, Boundary.NONE, Boundary.NONE, false, null);
            add(PLAYER_UUID, dashed.replace("-", ""), Fold.CASE, Boundary.NONE, Boundary.NONE, false, null);
            add(PLAYER_UUID, dashed.substring(0, 8), Fold.CASE, Boundary.WORD, Boundary.WORD, true, null);
            return this;
        }

        /** The session identifier of the account. Matched exactly; its pieces between {@code :} and {@code .} too. */
        public Builder session(String sessionId) {
            secret(SESSION, sessionId);
            return this;
        }

        /** The access token of the account. */
        public Builder accessToken(String token) {
            secret(ACCESS_TOKEN, token);
            return this;
        }

        /**
         * The address the player typed or the connection resolved to: a host name or an address, with or without a
         * scheme and a port. A name also gives its second-level label as a fragment, an IPv4 address its first three
         * octets.
         */
        public Builder serverAddress(String address) {
            if (address == null) {
                return this;
            }
            String host = host(address);
            if (host.length() < HOST_MIN) {
                return this;
            }
            if (IPV4.matcher(host).matches()) {
                add(SERVER_ADDRESS, host, Fold.CASE, Boundary.DIGIT, Boundary.DIGIT, false, null);
                add(SERVER_ADDRESS, host.substring(0, host.lastIndexOf('.') + 1), Fold.CASE,
                        Boundary.DIGIT, Boundary.NONE, true, null);
                return this;
            }
            add(SERVER_ADDRESS, host, Fold.CASE, Boundary.NONE, Boundary.NONE, false, null);
            String[] labels = host.split("\\.");
            if (labels.length >= 2 && labels[labels.length - 2].length() >= HOST_LABEL_MIN) {
                add(SERVER_ADDRESS, labels[labels.length - 2], Fold.CASE, Boundary.WORD, Boundary.WORD, true, null);
            }
            return this;
        }

        /** The name the player gave the server in the server list. */
        public Builder serverName(String name) {
            name(SERVER_NAME, name, false, null);
            return this;
        }

        /** The home directory of the operating system user; its last segment is a fragment. */
        public Builder homeDir(String path) {
            path(HOME_DIR, path, true);
            return this;
        }

        /** The name of the operating system user. */
        public Builder osUser(String user) {
            name(OS_USER, user, false, null);
            return this;
        }

        /** The name of the computer. */
        public Builder computerName(String name) {
            name(COMPUTER_NAME, name, false, null);
            return this;
        }

        /** The game directory. */
        public Builder gameDir(Path dir) {
            path(GAME_DIR, dir == null ? null : dir.toString(), false);
            return this;
        }

        /** The config directory. */
        public Builder configDir(Path dir) {
            path(CONFIG_DIR, dir == null ? null : dir.toString(), false);
            return this;
        }

        /** The seed of the world the player is in; found as a whole number, and by its first eight digits. */
        public Builder worldSeed(long seed) {
            String digits = Long.toString(seed).replace("-", "");
            if (digits.length() < SEED_MIN_DIGITS) {
                return this;
            }
            add(WORLD_SEED, digits, Fold.NONE, Boundary.DIGIT, Boundary.DIGIT, false, null);
            if (digits.length() > SEED_MIN_DIGITS) {
                add(WORLD_SEED, digits.substring(0, SEED_MIN_DIGITS), Fold.NONE, Boundary.DIGIT, Boundary.NONE,
                        true, null);
            }
            return this;
        }

        /** The name of the single-player world. */
        public Builder worldName(String name) {
            name(WORLD_NAME, name, false, null);
            return this;
        }

        /** The names of the other players on the server; looked for in command replies only. */
        public Builder otherPlayers(Collection<String> names) {
            if (names != null) {
                for (String name : names) {
                    name(OTHER_PLAYERS, name, false, OTHER_PLAYERS_SCOPE);
                }
            }
            return this;
        }

        /** What the Java virtual machine knows: the home directory, the user name, the computer name. */
        public Builder fromJvm() {
            homeDir(System.getProperty("user.home"));
            osUser(System.getProperty("user.name"));
            osUser(System.getenv("USERNAME"));
            osUser(System.getenv("USER"));
            computerName(System.getenv("COMPUTERNAME"));
            computerName(System.getenv("HOSTNAME"));
            return this;
        }

        public KnownValues build() {
            return new KnownValues(needles);
        }

        // -----------------------------------------------------------------------------------------

        private void name(String label, String value, boolean fragment, String scope) {
            if (value == null || value.isBlank()) {
                return;
            }
            String trimmed = value.strip();
            if (trimmed.codePointCount(0, trimmed.length()) >= NAME_SUBSTRING_MIN) {
                add(label, trimmed, Fold.CASE, Boundary.NONE, Boundary.NONE, fragment, scope);
            } else {
                add(label, trimmed, Fold.CASE, Boundary.WORD, Boundary.WORD, fragment, scope);
            }
        }

        private void secret(String label, String value) {
            if (value == null || value.length() < SECRET_MIN) {
                return;
            }
            add(label, value, Fold.NONE, Boundary.NONE, Boundary.NONE, false, null);
            for (String piece : value.split("[:.]")) {
                if (piece.length() >= SECRET_MIN && !piece.equals(value)) {
                    add(label, piece, Fold.NONE, Boundary.NONE, Boundary.NONE, true, null);
                }
            }
        }

        private void path(String label, String value, boolean withLastSegment) {
            if (value == null) {
                return;
            }
            String normal = value.strip().replace('\\', '/');
            while (normal.endsWith("/")) {
                normal = normal.substring(0, normal.length() - 1);
            }
            if (normal.length() < PATH_MIN) {
                return;
            }
            add(label, normal, Fold.PATH, Boundary.NONE, Boundary.NONE, false, null);
            if (withLastSegment) {
                String last = normal.substring(normal.lastIndexOf('/') + 1);
                if (last.length() >= FRAGMENT_NAME_MIN) {
                    name(label, last, true, null);
                }
            }
        }

        private void add(String label, String text, Fold fold, Boundary left, Boundary right, boolean fragment,
                         String scope) {
            Needle needle = new Needle(label, text, fold, left, right, fragment, scope);
            if (!needles.contains(needle)) {
                needles.add(needle);
            }
        }

        /** The host of an address: no scheme, no credentials, no port, no trailing dot, lower case. */
        private static String host(String address) {
            String host = address.strip();
            int scheme = host.indexOf("://");
            if (scheme >= 0) {
                host = host.substring(scheme + 3);
            }
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            int at = host.lastIndexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.indexOf(':') == colon) {
                host = host.substring(0, colon);
            }
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return Objects.requireNonNull(host).toLowerCase(Locale.ROOT);
        }
    }
}
