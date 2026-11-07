package day.alacraft.alamodlist.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokensTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("alamodlist").resolve(SecretStore.FILE_NAME);
    }

    private Tokens tokens() {
        return new Tokens(new SecretStore(file()));
    }

    @Test
    void theAccountTokenIsAnonymousUntilSetThenReadFromTheFileEveryTime() throws IOException {
        Tokens tokens = tokens();
        assertEquals("", tokens.accountToken(), "no file: anonymous");

        assertTrue(tokens.setAccountToken("  account-token-1  "));
        assertEquals("account-token-1", tokens.accountToken(), "trimmed");

        // Another store object on the same file, as if the player edited it by hand: the change is seen at once.
        new SecretStore(file()).put(Tokens.ACCOUNT_TOKEN, "account-token-2");
        assertEquals("account-token-2", tokens.accountToken(), "read from disk, not from a copy in a field");

        assertTrue(tokens.setAccountToken("   "), "a blank token forgets it");
        assertEquals("", tokens.accountToken());
        assertTrue(Files.exists(file()));
    }

    @Test
    void deleteTokensAreKeptPerListAndForgotten() {
        Tokens tokens = tokens();

        assertTrue(tokens.saveDeleteToken("t4pmmVSebjX8", "token-one"));
        assertTrue(tokens.saveDeleteToken("fsYRUuz9eGhW", "token-two"));

        assertEquals(Optional.of("token-one"), tokens.deleteToken("t4pmmVSebjX8"));
        assertEquals(Optional.of("token-two"), tokens.deleteToken("fsYRUuz9eGhW"));
        assertEquals(Optional.empty(), tokens.deleteToken("neverMadeIt1"));

        tokens.forgetDeleteToken("t4pmmVSebjX8");
        assertEquals(Optional.empty(), tokens.deleteToken("t4pmmVSebjX8"));
        assertEquals(Optional.of("token-two"), tokens.deleteToken("fsYRUuz9eGhW"), "the other one stays");
    }

    @Test
    void theAccountTokenAndADeleteTokenCannotBeMixedUp() {
        Tokens tokens = tokens();
        tokens.setAccountToken("the-account-token");

        // A list whose id is the name of the account entry still gets its own key.
        tokens.saveDeleteToken("account-token", "a-delete-token");

        assertEquals("the-account-token", tokens.accountToken());
        assertEquals(Optional.of("a-delete-token"), tokens.deleteToken("account-token"));
    }

    @Test
    void anIdThatIsNotAListIdIsRefusedWithoutQuotingTheToken() {
        Tokens tokens = tokens();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tokens.saveDeleteToken("../escape", "very-secret-token"));
        assertFalse(e.getMessage().contains("very-secret-token"));
        assertThrows(IllegalArgumentException.class, () -> tokens.saveDeleteToken("", "x"));
        assertThrows(IllegalArgumentException.class, () -> tokens.saveDeleteToken(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> tokens.saveDeleteToken("okId", ""));
        assertEquals(Optional.empty(), tokens.deleteToken("../escape"));
        assertEquals(Optional.empty(), tokens.deleteToken(null));
    }

    @Test
    void secretsLiveOnlyInTheCredentialsFileAndNothingPrintsThem() throws IOException {
        Tokens tokens = tokens();
        tokens.setAccountToken("account-secret-value");
        tokens.saveDeleteToken("t4pmmVSebjX8", "delete-secret-value");

        String onDisk = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("account-secret-value") && onDisk.contains("delete-secret-value"),
                "armed: the secrets are really in the one file");

        assertFalse(tokens.toString().contains("secret-value"));
        assertFalse(tokens.problem().orElse("").contains("secret-value"));
        try (var files = Files.walk(directory)) {
            assertEquals(1, files.filter(Files::isRegularFile).count(), "one file, and no scratch file left beside it");
        }
    }

    @Test
    void whenTheFileCannotBeWrittenTheTokenLivesInMemoryAndTheCallSaysSo() throws IOException {
        // The "directory" of the store is a regular file, so nothing can be created inside it.
        Path blocker = Files.writeString(directory.resolve("blocker"), "x");
        Tokens tokens = new Tokens(new SecretStore(blocker.resolve(SecretStore.FILE_NAME)));

        assertFalse(tokens.saveDeleteToken("t4pmmVSebjX8", "memory-only-token"), "false: not on disk");

        assertEquals(Optional.of("memory-only-token"), tokens.deleteToken("t4pmmVSebjX8"),
                "still usable until the game is closed");
        assertTrue(tokens.problem().isPresent());
        assertFalse(tokens.problem().get().contains("memory-only-token"));
    }
}
