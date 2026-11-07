package day.alacraft.alamodlist.store;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is checked where: the POSIX half runs on Linux and macOS, the Windows ACL half only on Windows (a
 * Linux CI runner never executes it, and a Windows machine never executes the POSIX half: each half is
 * proven only where it runs, which is a limit of the lane, not a property of the code).
 */
class PrivateFilesTest {

    @TempDir
    Path directory;

    static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Asserts that only the current user can reach {@code path}: {@code rw-------} / {@code rwx------} on
     * POSIX, a single ALLOW entry for the current user in the ACL on Windows.
     */
    static void assertOwnerOnly(Path path) throws IOException {
        if (isPosix()) {
            String expected = Files.isDirectory(path) ? "rwx------" : "rw-------";
            assertEquals(expected, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)),
                    path.getFileName() + " is reachable by someone else");
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "this filesystem has no ACL view");
        List<AclEntry> acl = view.getAcl();
        assertEquals(1, acl.size(), "the ACL of " + path.getFileName() + " must hold one entry, has: " + acl);
        assertEquals(AclEntryType.ALLOW, acl.get(0).type());
        String principal = acl.get(0).principal().getName().toLowerCase(Locale.ROOT);
        String user = System.getProperty("user.name").toLowerCase(Locale.ROOT);
        assertTrue(principal.equals(user) || principal.endsWith("\\" + user),
                "the one entry must name the current user, names " + principal);
    }

    @Test
    @DisplayName("a file is restricted to its owner and the owner can still use it")
    void restrictsAFile() throws IOException {
        Path file = Files.writeString(directory.resolve("secret.json"), "{}");

        assertTrue(PrivateFiles.restrictToOwner(file));

        assertOwnerOnly(file);
        Files.writeString(file, "{\"still\":\"writable\"}");
        assertEquals("{\"still\":\"writable\"}", Files.readString(file));
        Files.delete(file);
    }

    @Test
    @DisplayName("a directory is restricted too, and a file made in it can be deleted by the owner")
    void restrictsADirectory() throws IOException {
        Path folder = Files.createDirectory(directory.resolve("data"));

        assertTrue(PrivateFiles.restrictToOwner(folder));

        assertOwnerOnly(folder);
        Path inside = Files.writeString(folder.resolve("inside.txt"), "x");
        Files.delete(inside);
    }

    @Test
    @DisplayName("on Windows a directory ACL is inherited by what is created inside")
    void directoryAclIsInheritable() throws IOException {
        Assumptions.assumeFalse(isPosix(), "ACLs are the Windows half");
        Path folder = Files.createDirectory(directory.resolve("data"));
        PrivateFiles.restrictToOwner(folder);

        AclFileAttributeView view = Files.getFileAttributeView(folder, AclFileAttributeView.class);
        AclEntry entry = view.getAcl().get(0);

        assertTrue(entry.flags().contains(AclEntryFlag.FILE_INHERIT));
        assertTrue(entry.flags().contains(AclEntryFlag.DIRECTORY_INHERIT));
    }

    @Test
    @DisplayName("restricting a path that does not exist reports false and does not throw")
    void missingPathIsNotAnError() {
        assertFalse(PrivateFiles.restrictToOwner(directory.resolve("missing.json")));
    }
}
