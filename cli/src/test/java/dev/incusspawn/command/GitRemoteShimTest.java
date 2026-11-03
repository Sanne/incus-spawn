package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * isx init writes {@code git-remote-isx} beside {@code isx}. Where isx came from a package, that
 * directory is root's, and init warned about it on every run (#1978): it is skipped quietly now.
 */
class GitRemoteShimTest {

    @TempDir
    Path dir;

    @Test
    void aDirectoryTheUserCannotWriteIsSkippedQuietly() throws IOException {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(dir), "running as root, every directory is writable");
            var recorded = RecordingOutput.during(() -> new InitCommand().installGitRemoteShim(dir));
            assertEquals(List.of(), recorded.events());
            assertFalse(Files.exists(dir.resolve("git-remote-isx")));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aWritableDirectoryGetsTheHelper() {
        var recorded = RecordingOutput.during(() -> new InitCommand().installGitRemoteShim(dir));
        var shim = dir.resolve("git-remote-isx");
        assertTrue(Files.isExecutable(shim));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.STEP, "Installed git remote helper: " + shim)),
                recorded.events());
    }

    @Test
    void anExistingHelperIsLeftAlone() throws IOException {
        var shim = Files.writeString(dir.resolve("git-remote-isx"), "the user's own\n");
        var recorded = RecordingOutput.during(() -> new InitCommand().installGitRemoteShim(dir));
        assertEquals(List.of(), recorded.events());
        assertEquals("the user's own\n", Files.readString(shim));
    }

    /** A dangling link is left too, never written through to wherever it points. */
    @Test
    void aDanglingLinkIsNotWrittenThrough() throws IOException {
        var target = dir.resolve("elsewhere");
        Files.createSymbolicLink(dir.resolve("git-remote-isx"), target);
        RecordingOutput.during(() -> new InitCommand().installGitRemoteShim(dir));
        assertFalse(Files.exists(target));
    }
}
