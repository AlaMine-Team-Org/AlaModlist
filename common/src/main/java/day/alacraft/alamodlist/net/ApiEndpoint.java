package day.alacraft.alamodlist.net;

import day.alacraft.alamodlist.AlaModlist;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * What counts as a usable API base URL, decided in one place.
 *
 * <p>Two callers want opposite things when the answer is no: a settings file repairs itself and carries on (a
 * typo must not stop the game from starting), while the client refuses to be built (a client pointed at nothing
 * is not a client). Both ask here, so they cannot disagree.
 *
 * <p><b>The answer is rebuilt, not echoed.</b> Every path is appended to this value by concatenation, so anything
 * after the path in a URL swallows what comes next. A base URL ending in {@code #notes} would send every request
 * to the same address, because the appended path lands inside the fragment and is never transmitted. A query
 * string does the same, one degree less completely. Neither means anything for an API base, so both are dropped
 * rather than obeyed, as is a {@code user:pass@} part: an HTTP client does not send it, and keeping it in the
 * value would only print it into the log.
 */
public final class ApiEndpoint {

    private ApiEndpoint() {
    }

    /**
     * The URL as a client should use it, or empty when it cannot be used at all.
     *
     * <p>Trailing slashes go because every path is appended with a leading one, and {@code .../api/v1//modlists}
     * is a 404 on one server and a redirect on the next - and a redirected POST loses its body silently.
     *
     * <p>Only the scheme, the host and the port are checked. Whether the host resolves or answers is a network
     * question, and asking it here would mean a DNS lookup while the game is still loading.
     */
    public static Optional<String> normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        URI parsed;

        try {
            parsed = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);

        if (!scheme.equals("http") && !scheme.equals("https") || parsed.getHost() == null) {
            return Optional.empty();
        }

        int port = parsed.getPort();

        // java.net.URI parses the digits without judging them, so :99999 would arrive here intact and fail much
        // later, as an IllegalArgumentException from the HTTP client on the first request.
        if (port != -1 && (port < 1 || port > 65535)) {
            return Optional.empty();
        }

        if (parsed.getRawUserInfo() != null) {
            AlaModlist.LOGGER.warn("The credentials in apiBaseUrl are ignored - an HTTP client does not send "
                    + "them. Keep the account token in its own file instead. They have been dropped from the address so "
                    + "they cannot reach a log file.");
        }

        StringBuilder rebuilt = new StringBuilder()
                .append(parsed.getScheme())
                .append("://")
                .append(parsed.getHost());

        if (port != -1) {
            rebuilt.append(':').append(port);
        }

        if (parsed.getRawPath() != null) {
            rebuilt.append(parsed.getRawPath());
        }

        while (rebuilt.length() > 0 && rebuilt.charAt(rebuilt.length() - 1) == '/') {
            rebuilt.setLength(rebuilt.length() - 1);
        }

        return Optional.of(rebuilt.toString());
    }
}
