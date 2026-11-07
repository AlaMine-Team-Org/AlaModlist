package day.alacraft.alamodlist.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one answer both the settings and the client build on: what is a usable API base URL.
 *
 * <p>The last test keeps the two from disagreeing: a value this accepts must build a client, and a value it
 * rejects must not - otherwise a setting would hand the builder something the builder throws on, from inside the
 * mod's entry point.
 */
class ApiEndpointTest {

    @Test
    void acceptsTheAddressesPeopleActuallyConfigure() {
        assertEquals("https://alacraft.day", ApiEndpoint.normalise("https://alacraft.day").orElseThrow());
        assertEquals("http://127.0.0.1:8061", ApiEndpoint.normalise("http://127.0.0.1:8061").orElseThrow());
        assertEquals("https://site.example.org/base",
                ApiEndpoint.normalise("  https://site.example.org/base  ").orElseThrow());
    }

    @Test
    @DisplayName("trailing slashes go, because every path is appended with a leading one")
    void stripsTrailingSlashes() {
        assertEquals("https://alacraft.day", ApiEndpoint.normalise("https://alacraft.day//").orElseThrow());
    }

    @Test
    @DisplayName("a fragment would swallow every path appended to this value")
    void dropsAFragment() {
        assertEquals("https://alacraft.day", ApiEndpoint.normalise("https://alacraft.day#notes").orElseThrow());
        assertEquals("https://alacraft.day", ApiEndpoint.normalise("https://alacraft.day/#notes").orElseThrow(),
                "and the slash it was hiding still goes");
    }

    @Test
    @DisplayName("a query string does the same, one degree less completely")
    void dropsAQueryString() {
        assertEquals("https://alacraft.day", ApiEndpoint.normalise("https://alacraft.day?token=abc").orElseThrow());
    }

    @Test
    @DisplayName("credentials in the address are dropped, because they are never sent anyway")
    void dropsCredentials() {
        assertEquals("https://site.example.org", ApiEndpoint.normalise("https://user:pass@site.example.org").orElseThrow());
        assertEquals("https://site.example.org", ApiEndpoint.normalise("https://sometoken@site.example.org").orElseThrow(),
                "including the bare-token shape");
    }

    @Test
    void keepsAPortAndRefusesAnImpossibleOne() {
        assertEquals("http://127.0.0.1:8123", ApiEndpoint.normalise("http://127.0.0.1:8123").orElseThrow());
        assertEquals("http://[::1]:8123", ApiEndpoint.normalise("http://[::1]:8123").orElseThrow(), "IPv6");

        assertTrue(ApiEndpoint.normalise("https://alacraft.day:99999").isEmpty());
        assertTrue(ApiEndpoint.normalise("https://alacraft.day:0").isEmpty());
    }

    @Test
    void rejectsWhatCannotBeUsed() {
        assertTrue(ApiEndpoint.normalise(null).isEmpty());
        assertTrue(ApiEndpoint.normalise("   ").isEmpty(), "blank");
        assertTrue(ApiEndpoint.normalise("alacraft.day").isEmpty(), "no scheme");
        assertTrue(ApiEndpoint.normalise("ftp://alacraft.day").isEmpty(), "not http(s)");
        assertTrue(ApiEndpoint.normalise("file:///etc/hosts").isEmpty(), "not a web address");
        assertTrue(ApiEndpoint.normalise("https:///api").isEmpty(), "no host");
        assertTrue(ApiEndpoint.normalise("http://").isEmpty(), "nothing but a scheme");
        assertTrue(ApiEndpoint.normalise("not a url at all").isEmpty(), "not a URL");
        assertTrue(ApiEndpoint.normalise("///").isEmpty(), "slashes only");
    }

    @Test
    @DisplayName("the client refuses exactly what this rejects")
    void theClientAgrees() {
        try (HttpTransport accepted = HttpTransport.builder(
                ApiEndpoint.normalise("https://alacraft.day/").orElseThrow()).build()) {
            assertEquals("alacraft.day", accepted.host());
        }

        for (String rejected : new String[] {"", "alacraft.day", "ftp://alacraft.day"}) {
            assertTrue(ApiEndpoint.normalise(rejected).isEmpty(), rejected);
            assertThrows(IllegalArgumentException.class, () -> HttpTransport.builder(rejected), rejected);
        }
    }
}
