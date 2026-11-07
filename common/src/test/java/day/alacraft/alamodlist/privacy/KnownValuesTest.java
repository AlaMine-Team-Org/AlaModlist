package day.alacraft.alamodlist.privacy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How each kind of known value is recognised - and, for each, what it must not be confused with. */
class KnownValuesTest {

    @Test
    void aShortNameMatchesAsAWholeWordOnly() {
        KnownValues known = KnownValues.builder().playerName("Al").build();

        assertTrue(hits(known, "made by Al."), "armed: the word itself");
        assertTrue(hits(known, "made by AL"), "case does not matter");
        assertFalse(hits(known, "Alpha Beta"), "inside a word");
        assertFalse(hits(known, "total"), "inside a word, at the end");
    }

    @Test
    void aLongNameMatchesAsASubstring() {
        KnownValues known = KnownValues.builder().playerName("Steve_77").build();

        assertTrue(hits(known, "mod-by-steve_77-v2"));
        assertTrue(hits(known, "STEVE_77"));
        assertFalse(hits(known, "Steve_7"));
    }

    @Test
    void aBlankValueIsSkippedNotAnError() {
        KnownValues known = KnownValues.builder().playerName(null).playerName("  ").serverAddress(null).homeDir(null)
                .session(null).accessToken("short").worldSeed(12345).build();

        assertTrue(known.isEmpty(), "nothing usable was given, and nothing was invented: " + known);
    }

    @Test
    void aUuidIsFoundInBothSpellingsAndInAnyCase() {
        UUID id = UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
        KnownValues known = KnownValues.builder().playerUuid(id).build();

        assertTrue(hits(known, "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9"));
        assertTrue(hits(known, "0A1B2C3D4E5F60718293A4B5C6D7E8F9"));
        assertTrue(hits(known, "head 0a1b2c3d only"), "the first group alone is a fragment");
        assertFalse(hits(known, "0a1b2c3dffff"), "the first group inside a longer word");
        assertTrue(known.needles().stream().anyMatch(Needle::fragment));
    }

    @Test
    void anAddressIsFoundWithoutItsPortAndSchemeAndByItsSecondLevelLabel() {
        KnownValues known = KnownValues.builder().serverAddress("tcp://Play.Example-Realm.net:25565").build();

        assertTrue(hits(known, "play.example-realm.net"));
        assertTrue(hits(known, "at PLAY.EXAMPLE-REALM.NET today"));
        assertTrue(hits(known, "example-realm"), "the label before the top-level domain");
        assertFalse(hits(known, "example"), "a part of a label");
    }

    @Test
    void anIpv4AddressIsFoundWholeAndByItsFirstThreeOctetsButNotInsideANumber() {
        KnownValues known = KnownValues.builder().serverAddress("203.0.113.42:25565").build();

        assertTrue(hits(known, "203.0.113.42"));
        assertTrue(hits(known, "net 203.0.113."), "three octets is still a network");
        assertFalse(hits(known, "1203.0.113.42"), "inside a longer number");
        assertFalse(hits(known, "26.2.0.67"), "a version is not an address");
    }

    @Test
    void aPathIsFoundInEverySpelling() {
        String home = "C:\\" + "Us" + "ers\\zqpath";
        KnownValues known = KnownValues.builder().homeDir(home).build();

        assertTrue(hits(known, "C:\\" + "Us" + "ers\\zqpath\\AppData"));
        assertTrue(hits(known, "c:/" + "us" + "ers/ZQPATH/mods"));
        assertTrue(hits(known, "file:///C:/" + "Us" + "ers/zqpath/x.jar"), "as a file address");
        assertTrue(hits(known, "zqpath"), "the last segment alone is a fragment");
        assertFalse(hits(known, "C:\\" + "Us" + "ers\\other\\mods"));
    }

    @Test
    void aSecretIsMatchedExactlyAndByItsPieces() {
        String token = "aaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbb.cccccccccccccccc";
        KnownValues known = KnownValues.builder().accessToken(token).build();

        assertTrue(hits(known, token));
        assertTrue(hits(known, "x bbbbbbbbbbbbbbbbbbbb y"), "a piece of it");
        assertFalse(hits(known, "BBBBBBBBBBBBBBBBBBBB"), "a secret is case-sensitive");
        assertFalse(hits(known, "aaaaaaaa"), "a short run is not the secret");
    }

    @Test
    void aSeedIsFoundAsAWholeNumberAndByItsFirstDigitsButNotInsideANumber() {
        KnownValues known = KnownValues.builder().worldSeed(-7026191857309640518L).build();

        assertTrue(hits(known, "seed 7026191857309640518"));
        assertTrue(hits(known, "70261918"), "the first eight digits");
        assertTrue(hits(known, "7026191857309640518"));
        assertFalse(hits(known, "17026191857309640518"), "inside a longer number");
        assertFalse(hits(known, "5170261918"), "digits that only contain the head");
    }

    @Test
    void aScopedNeedleAppliesToItsOwnPathOnly() {
        KnownValues known = KnownValues.builder().otherPlayers(List.of("Neighbour")).build();
        Needle needle = known.needles().get(0);

        assertTrue(needle.appliesTo("server.command_replies[].text"));
        assertFalse(needle.appliesTo("title"));
    }

    @Test
    void gameAndConfigDirectoriesAreKnownToo() {
        KnownValues known = KnownValues.builder().gameDir(Path.of("games", "zqgame")).configDir(Path.of("games", "zqgame", "config")).build();

        assertTrue(hits(known, "games/zqgame"));
        assertTrue(hits(known, "games\\zqgame\\config"));
    }

    @Test
    void theJvmValuesAreCollected() {
        KnownValues known = KnownValues.builder().fromJvm().build();
        String home = System.getProperty("user.home");

        assertEquals(home != null && home.strip().length() >= 4, known.needles().stream()
                .anyMatch(n -> n.label().equals(KnownValues.HOME_DIR)));
    }

    private static boolean hits(KnownValues known, String text) {
        return known.needles().stream().anyMatch(n -> !n.find(text).isEmpty());
    }
}
