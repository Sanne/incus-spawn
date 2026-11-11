package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OwnDirectoryTest {

    @TempDir
    Path tmp;

    /**
     * Under a umask of 002, as Ubuntu's {@code pam_umask} gives a user with a private group, the
     * directory isx makes is still its owner's alone to write, so the proxy accepts it whatever
     * the group's gid (#2136). A JVM cannot change its own umask, so a child JVM is started under
     * one.
     */
    @Test
    void aDirectoryMadeUnderAGroupWritableUmaskIsNotGroupWritable() throws Exception {
        var dir = tmp.resolve("config/incus-spawn");
        var control = tmp.resolve("control");
        var java = ProcessHandle.current().info().command().orElseThrow();
        var process = new ProcessBuilder("sh", "-c", "umask 002 && exec \"$0\" -cp \"$1\" \"$2\" \"$3\" \"$4\"",
                java, System.getProperty("java.class.path"), Create.class.getName(), dir.toString(), control.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child JVM did not finish");
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
        // What Files.createDirectories made beside it shows the child ran under umask 002
        assertTrue(Files.getPosixFilePermissions(control).contains(PosixFilePermission.GROUP_WRITE),
                "the child did not run under umask 002: " + PosixFilePermissions.toString(Files.getPosixFilePermissions(control)));

        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParent())),
                "a parent made on the way");
        // No private group accepted, as for a user whose gid is not their uid
        OwnDirectory.require(dir, OwnDirectory.uidOf(dir), null, "isx");
    }

    /** An existing directory is checked, never repaired: a group that could write may have. */
    @Test
    void anExistingGroupWritableDirectoryIsLeftToTheCheck() throws IOException {
        var dir = Files.createDirectory(tmp.resolve("incus-spawn"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxr-x"));

        assertSame(dir, OwnDirectory.create(dir));

        assertEquals("rwxrwxr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        var refused = assertThrows(OwnDirectory.NotOwn.class,
                () -> OwnDirectory.require(dir, OwnDirectory.uidOf(dir), null, "isx"));
        assertTrue(refused.getMessage().contains("chmod go-w"), refused.getMessage());
    }

    /** Something other than a directory in its place fails at once, as Files.createDirectories does. */
    @Test
    void aFileInItsPlaceFails() throws IOException {
        var file = Files.writeString(tmp.resolve("incus-spawn"), "");
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> OwnDirectory.create(file));
    }

    /** The child JVM's entry: makes the first directory named as isx does, the second as Java does. */
    public static final class Create {
        public static void main(String[] args) throws IOException {
            OwnDirectory.create(Path.of(args[0]));
            Files.createDirectories(Path.of(args[1]));
        }
    }
}
