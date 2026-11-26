package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What isx downloads and how it starts the Mac's compositor; the start itself needs a Mac. */
class HostCompositorTest {

    @Test
    void thePinNamesOneReleasesArm64ZipAndItsSum() {
        assertEquals("https://github.com/J-x-Z/cocoa-way/releases/download/v" + HostCompositor.VERSION
                + "/Cocoa-Way-v" + HostCompositor.VERSION + "-arm64-macos.zip", HostCompositor.URL);
        assertTrue(HostCompositor.SHA256.matches("[0-9a-f]{64}"), HostCompositor.SHA256);
        // a new pin unpacks beside the old one, never over it
        assertTrue(HostCompositor.installDir().endsWith("gui/cocoa-way-" + HostCompositor.VERSION));
    }

    @Test
    void itIsStartedRootlessWithIsxsOwnRuntimeDirectory() {
        assertEquals(Map.of("COCOA_WAY_PRESENTATION", "rootless", "COCOA_WAY_DISPLAY_RUNTIME_DIR", "/s/gui"),
                HostCompositor.startEnvironment(Path.of("/s/gui")));
        assertEquals(Path.of("/i/Cocoa-Way.app/Contents/MacOS/cocoa-way"), HostCompositor.binary(Path.of("/i")));
        assertEquals(List.of("/usr/bin/ditto", "-x", "-k", "/c/x.zip", "/i"),
                HostCompositor.unpackCommand(Path.of("/c/x.zip"), Path.of("/i")));
    }

    @Test
    void aDisplayIsLookedForInTheOneDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("wayland-0.lock"), "");
        assertNull(GuiBridge.displayIn(dir.toString()));
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(dir.resolve("wayland-0")));
            assertEquals(new GuiBridge.HostDisplay(dir.toString(), "wayland-0"), GuiBridge.displayIn(dir.toString()));
        }
    }
}
