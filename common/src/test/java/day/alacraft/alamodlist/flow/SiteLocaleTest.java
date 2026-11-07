package day.alacraft.alamodlist.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteLocaleTest {

    /**
     * @implements TC-PREVIEW-001-FUN11 - the language of the game reaches the language segment of the link
     * @covers R-CLI-03
     */
    @Test
    void theGamesLanguageCodesReachTheSitesSegments() {
        assertEquals("ru", SiteLocale.of("ru_ru"));
        assertEquals("uk", SiteLocale.of("uk_ua"), "Ukrainian is uk on the site, never ua");
        assertEquals("en", SiteLocale.of("en_us"));
        assertEquals("de", SiteLocale.of("de_at"), "a regional variant reaches the language");
        assertEquals("es", SiteLocale.of("es_mx"));
        assertEquals("ja", SiteLocale.of("ja_jp"));
        assertEquals("zh", SiteLocale.of("zh_tw"), "the site has Simplified only");
        assertEquals("fr", SiteLocale.of("FR-CA"), "case and a dash do not matter");
    }

    @Test
    void aLanguageTheSiteDoesNotServeGetsEnglish() {
        assertEquals("en", SiteLocale.of("pl_pl"));
        assertEquals("en", SiteLocale.of("pt_br"));
        assertEquals("en", SiteLocale.of(""));
        assertEquals("en", SiteLocale.of(null));
        assertEquals("en", SiteLocale.of("   "));
        assertEquals("en", SiteLocale.of("tlh_aa"));
    }

    @Test
    void theListIsTheEightLanguagesOfTheSite() {
        assertEquals(List.of("ru", "uk", "de", "fr", "es", "ja", "zh", "en"), SiteLocale.SERVED);
    }

    /**
     * The list must not run ahead of the site: a segment the site does not serve is a 404 behind the player's link.
     * When {@code ALAMODLIST_SITE_LOCALES} names the site's own {@code config/laravellocalization.php}, the two are
     * compared; without it the test says why it did nothing.
     */
    @Test
    void theListMatchesTheSitesOwnConfigWhenItIsGiven() throws Exception {
        String given = System.getenv("ALAMODLIST_SITE_LOCALES");
        org.junit.jupiter.api.Assumptions.assumeTrue(given != null && !given.isBlank(),
                "ALAMODLIST_SITE_LOCALES is not set: the site's locale config is not compared");
        Path config = Path.of(given);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(config),
                "ALAMODLIST_SITE_LOCALES does not name a file: " + config);

        String php = Files.readString(config);
        for (String locale : SiteLocale.SERVED) {
            // At the start of a line: a locale that is only commented out is not served.
            assertTrue(java.util.regex.Pattern.compile("(?m)^\\s*'" + locale + "'\\s*=>").matcher(php).find(),
                    "the site does not declare '" + locale + "'");
        }
    }
}
