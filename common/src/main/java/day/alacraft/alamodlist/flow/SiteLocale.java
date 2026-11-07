package day.alacraft.alamodlist.flow;

import java.util.List;
import java.util.Locale;

/**
 * Maps a Minecraft language code onto the locale segment the site uses in its URLs.
 *
 * <p>The link is the product: a Russian player handed {@code /en/modlists/...} lands on an English page about their
 * own list. The game translates every other text of the mod by itself, so this is the one question it cannot answer.
 *
 * <p>The list is the languages the site serves ({@code config/laravellocalization.php} of the site); a code the site
 * does not serve gets English, which is the site's own fallback. The site invents no segment for a language it has
 * not got ({@code /pl/} is a 404), so this list must not grow ahead of the site.
 */
public final class SiteLocale {

    /** The languages the site serves. Ukrainian is {@code uk} there, never {@code ua}. */
    public static final List<String> SERVED = List.of("ru", "uk", "de", "fr", "es", "ja", "zh", "en");

    private static final String FALLBACK = "en";

    private SiteLocale() {
    }

    /**
     * The site locale for a Minecraft language code such as {@code ru_ru}.
     *
     * <p>Matched on the language half alone, so the regional variants clients report ({@code de_at}, {@code es_mx},
     * {@code en_gb}) reach the right page. {@code zh_tw} lands on the Simplified Chinese page: the site has no
     * Traditional variant, and a Simplified page beats an English one for a player who reads Chinese.
     */
    public static String of(String minecraftLanguage) {
        if (minecraftLanguage == null || minecraftLanguage.isBlank()) {
            return FALLBACK;
        }

        String code = minecraftLanguage.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        int underscore = code.indexOf('_');
        String language = underscore >= 0 ? code.substring(0, underscore) : code;

        return SERVED.contains(language) ? language : FALLBACK;
    }
}
