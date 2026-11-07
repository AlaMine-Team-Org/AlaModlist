package day.alacraft.alamodlist.store;

import day.alacraft.alamodlist.AlaModlist;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Keeps a file or directory usable only by the account that runs the game.
 *
 * <p>POSIX is the easy half: {@code rw-------} for a file, {@code rwx------} for a directory. On Windows
 * the file inherits the ACL of its directory, and on a normal profile that names several principals
 * (administrators, the system account, sometimes other groups). {@link AclFileAttributeView#setAcl}
 * replaces the whole discretionary list, inherited entries included, so one ALLOW entry for the current
 * user is all it takes. The current user is looked up by the OS user name; when that fails the file's
 * owner is used (for an elevated process that can be the Administrators group - then the file is
 * restricted to that group, which is still narrower than the inherited list).
 *
 * <p>Honest limits, none of which this class can fix: it protects against other accounts, not against
 * the same account (any program the player runs can read the file); the system account and backup tools
 * lose access on Windows; a filesystem with neither view (FAT, some network shares) stays as it was.
 * Best effort by design: a mod that refuses to start because it could not tighten a file would be worse
 * than a file another local account can read, so a refusal is reported by the return value, never thrown.
 */
public final class PrivateFiles {

    private static final String FILE_PERMISSIONS = "rw-------";
    private static final String DIRECTORY_PERMISSIONS = "rwx------";

    private PrivateFiles() {
    }

    /**
     * Restricts an existing file or directory to its owner, as far as the filesystem allows.
     *
     * @return {@code true} if the restriction is in place, {@code false} if the filesystem could not (or
     *         would not) take it
     */
    public static boolean restrictToOwner(Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path,
                        PosixFilePermissions.fromString(directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS));
                return true;
            }

            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return false;
            }
            AclEntry.Builder entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(currentUser(path))
                    // Full control: the owner must be able to replace and delete the file on every save.
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            view.setAcl(List.of(entry.build()));
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            AlaModlist.LOGGER.debug("Could not restrict {} to its owner ({}).", path.getFileName(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** The account the game runs as; the file's owner if the OS user name cannot be resolved. */
    static UserPrincipal currentUser(Path path) throws IOException {
        String name = System.getProperty("user.name");
        if (name != null && !name.isBlank()) {
            try {
                return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
            } catch (IOException | UnsupportedOperationException e) {
                // Falls through to the owner.
            }
        }
        return Files.getOwner(path);
    }
}
