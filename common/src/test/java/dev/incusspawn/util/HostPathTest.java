package dev.incusspawn.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Commands are found on the {@code PATH} in-process, never through {@code which}: Arch's base
 * system does not ship it, and with it missing {@code isx init} reported no supported package
 * manager on a host with pacman (#1972).
 */
class HostPathTest {

    @TempDir
    Path dir;

    private Path file(String dirName, String name, String mode) throws IOException {
        var parent = Files.createDirectories(dir.resolve(dirName));
        var file = Files.writeString(parent.resolve(name), "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        return file;
    }

    private String path(String... dirs) {
        var parts = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) parts[i] = dir.resolve(dirs[i]).toString();
        return String.join(File.pathSeparator, parts);
    }

    @Test
    void theFirstExecutableOnThePathWins() throws IOException {
        file("a", "tool", "rw-r--r--");
        var second = file("b", "tool", "rwxr-xr-x");
        file("c", "tool", "rwxr-xr-x");
        assertEquals(second.toString(), HostPath.find("tool", path("a", "b", "c")));
    }

    @Test
    void aMissingCommandOrADirectoryIsNotFound() throws IOException {
        Files.createDirectories(dir.resolve("a").resolve("tool"));
        assertNull(HostPath.find("tool", path("a")));
        assertNull(HostPath.find("absent", path("a")));
    }

    @Test
    void blankEntriesAreSkipped() throws IOException {
        var tool = file("a", "tool", "rwxr-xr-x");
        assertEquals(tool.toString(), HostPath.find("tool", File.pathSeparator + path("a")));
    }

    /** Callers write the path into a service unit, ssh config and sudoers, which want it absolute. */
    @Test
    void aRelativeEntryGivesAnAbsolutePath() throws IOException {
        var tool = file("a", "tool", "rwxr-xr-x");
        var relative = Path.of("").toAbsolutePath().relativize(tool.getParent()).toString();
        assertEquals(tool.toAbsolutePath().toString(), HostPath.find("tool", relative));
    }

    @Test
    void anUnsetPathSearchesTheUsualDirectories() {
        assertEquals(HostPath.find("sh", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"),
                HostPath.find("sh", null));
        assertNotNull(HostPath.find("sh", null));
    }
}
