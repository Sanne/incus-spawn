package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class DriftRestartRecordTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void isolateHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private static final BuildInfo CLI = BuildInfo.instance();

    private Path installedProxy(String content) throws IOException {
        var binary = tempDir.resolve("isx-proxy");
        Files.writeString(binary, content);
        return binary;
    }

    /** What restartAlreadyTried() compares against, minus the `which isx` lookup. */
    private static boolean recordedFor(Path binary) throws IOException {
        var recorded = DriftRestartRecord.Stamp.parse(Files.readString(Environment.proxyDriftRestartFile()));
        return recorded != null && recorded.equals(DriftRestartRecord.Stamp.of(CLI, binary.toString()));
    }

    @Test
    void nothingIsRecordedAtFirst() {
        assertNull(DriftRestartRecord.restartAlreadyTried());
    }

    @Test
    void aRecordedRestartMatchesTheSameCliAndBinary() throws IOException {
        var binary = installedProxy("v1");
        DriftRestartRecord.write(binary.toString());
        assertTrue(recordedFor(binary));
    }

    @Test
    void reinstallingTheProxyIsANewBinary() throws IOException {
        var binary = installedProxy("v1");
        DriftRestartRecord.write(binary.toString());

        installedProxy("v2 is a longer build");
        assertFalse(recordedFor(binary), "a new size is a new binary");

        DriftRestartRecord.write(binary.toString());
        Files.setLastModifiedTime(binary, FileTime.fromMillis(Files.getLastModifiedTime(binary).toMillis() + 60_000));
        assertFalse(recordedFor(binary), "a new mtime is a new binary");
    }

    @Test
    void aRecordFromAnotherCliBuildIsIgnoredWithoutResolvingTheBinary() throws IOException {
        var binary = installedProxy("v1");
        var other = new DriftRestartRecord.Stamp(CLI.version(), "another-cli-sha", binary.toString(),
                Files.getLastModifiedTime(binary).toMillis(), Files.size(binary));
        Files.createDirectories(Environment.proxyDriftRestartFile().getParent());
        Files.writeString(Environment.proxyDriftRestartFile(), other.serialize());
        assertNull(DriftRestartRecord.restartAlreadyTried());
    }

    @Test
    void aMissingBinaryIsNeitherStampedNorRecorded() {
        assertNull(DriftRestartRecord.Stamp.of(CLI, null));
        DriftRestartRecord.write(tempDir.resolve("absent").toString());
        assertFalse(Files.exists(Environment.proxyDriftRestartFile()));
    }

    @Test
    void aCorruptRecordMatchesNothing() throws IOException {
        Files.createDirectories(Environment.proxyDriftRestartFile().getParent());
        Files.writeString(Environment.proxyDriftRestartFile(), "cliVersion=0.0.2\nsize=not-a-number\n");
        assertNull(DriftRestartRecord.restartAlreadyTried());
    }

    @Test
    void stampsSurviveARoundTrip() {
        var stamp = new DriftRestartRecord.Stamp("0.0.2", "cli7654321", "/path with spaces/isx-proxy", 123L, 456L);
        assertEquals(stamp, DriftRestartRecord.Stamp.parse(stamp.serialize()));
    }
}
