package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.proxy.HostRouteSecret;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx init} makes the config directory its owner's alone, and repairs none (#2136). */
@ExtendWith(IsolatedHome.class)
class InitConfigDirectoryTest {

    @Test
    void aMissingDirectoryIsMadeWithoutGroupWriteAndQuietly() throws Exception {
        var dir = Environment.configDir();
        var output = RecordingOutput.during(() -> InitCommand.ownConfigDirectory(dir));

        assertTrue(Files.isDirectory(dir));
        var permissions = Files.getPosixFilePermissions(dir);
        assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE), permissions.toString());
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE), permissions.toString());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.WARN));
    }

    /** An older isx made it group-writable under umask 002: init warns, and leaves it to the person. */
    @Test
    void anExistingGroupWritableDirectoryIsWarnedAboutAndLeftAlone() throws Exception {
        var dir = Files.createDirectories(Environment.configDir());
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));

        var warnings = RecordingOutput.during(() -> InitCommand.ownConfigDirectory(dir)).events(RecordingOutput.Kind.WARN);

        assertEquals("rwxrwxrwx", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().text().contains("chmod go-w"), warnings.toString());
        assertTrue(warnings.getFirst().text().contains(dir.toRealPath().toString()), warnings.toString());
    }

    /**
     * Judged by the uid isx runs as, not the directory's own owner: one an earlier
     * {@code sudo isx init} left root's is refused by every build, so init says so.
     */
    @Test
    void aDirectoryOwnedBySomeoneElseIsWarnedAbout() throws Exception {
        var dir = Files.createDirectories(Environment.configDir()).toRealPath();
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        var someoneElse = OwnDirectory.uidOf(dir) + 1;

        var warnings = RecordingOutput.during(() -> InitCommand.ownConfigDirectory(dir, someoneElse)).events(RecordingOutput.Kind.WARN);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().text().contains("belongs to uid"), warnings.toString());
        assertTrue(warnings.getFirst().text().contains("chown it"), warnings.toString());
    }

    /** A parent isx cannot write to, as an earlier sudo may leave {@code ~/.config}: a warning, not a crash. */
    @Test
    void aParentItCannotWriteToIsWarnedAbout() throws Exception {
        var parent = Files.createDirectories(Environment.configDir().getParent());
        // Root writes through a read-only mode
        Assumptions.assumeFalse(OwnDirectory.uidOf(parent) == 0, "runs as root");
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var warnings = RecordingOutput.during(() -> InitCommand.ownConfigDirectory(Environment.configDir()))
                    .events(RecordingOutput.Kind.WARN);

            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().text().contains("isx cannot write to " + parent.toRealPath() + ", which belongs to uid"),
                    warnings.toString());
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * A refusing parent reached through a link, as macOS's {@code /var} is one to
     * {@code /private/var}: the warning names the real directory, as the others do, never the
     * link or the directory above the link.
     */
    @Test
    void aParentReachedThroughALinkIsNamedByItsRealPath() throws Exception {
        var real = Files.createDirectories(Environment.home().resolve("real-config"));
        Assumptions.assumeFalse(OwnDirectory.uidOf(real) == 0, "runs as root");
        var link = Files.createSymbolicLink(Environment.home().resolve("linked-config"), real);
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var warnings = RecordingOutput.during(() -> InitCommand.ownConfigDirectory(link.resolve("incus-spawn")))
                    .events(RecordingOutput.Kind.WARN);

            assertEquals(1, warnings.size(), warnings.toString());
            var text = warnings.getFirst().text();
            assertTrue(text.contains("isx cannot write to " + real.toRealPath() + ", which belongs to uid"), text);
        } finally {
            Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * One isx cannot write to, as an earlier {@code sudo isx init} leaves it (root's, 0755), through
     * the entry init runs: the warning names the directory and its owner, never the probe file
     * that could not be made in it, and a uid cached by an earlier call does not hide it.
     */
    @Test
    void aDirectoryItCannotWriteToIsWarnedAboutByName() throws Exception {
        var dir = Files.createDirectories(Environment.configDir()).toRealPath();
        Assumptions.assumeFalse(OwnDirectory.uidOf(dir) == 0, "runs as root");
        // A first call, as a build or the proxy in this process would make, caches the uid
        HostRouteSecret.ownUid(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var warnings = RecordingOutput.during(() -> InitCommand.ownConfigDirectory(Environment.configDir()))
                    .events(RecordingOutput.Kind.WARN);

            assertEquals(1, warnings.size(), warnings.toString());
            var text = warnings.getFirst().text();
            assertTrue(text.contains("isx cannot write to " + dir + ", which belongs to uid " + OwnDirectory.uidOf(dir)), text);
            assertFalse(text.contains(".uid-"), text);
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
