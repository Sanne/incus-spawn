package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vfkit link in isx's app bundle is replaced when it dangles or names another vfkit (#2231). */
class VfkitAppBundleTest {

    @TempDir
    Path dir;

    @Test
    void replacesDanglingLink() throws IOException {
        var bundle = dir.resolve("incus-spawn-vm.app");
        var link = bundle.resolve("Contents/MacOS/vfkit");
        Files.createDirectories(link.getParent());
        Files.writeString(bundle.resolve("Contents/Info.plist"), "<plist/>");
        Files.createSymbolicLink(link, dir.resolve("gone/vfkit"));

        var vfkit = dir.resolve("new-vfkit");
        Files.writeString(vfkit, "");

        assertEquals(link.toString(), VmManager.ensureVfkitAppBundle(bundle, vfkit.toString()));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(vfkit, Files.readSymbolicLink(link));
    }

    @Test
    void keepsLinkToTheSameVfkit() throws IOException {
        var bundle = dir.resolve("incus-spawn-vm.app");
        var vfkit = dir.resolve("vfkit");
        Files.writeString(vfkit, "");
        var link = Path.of(VmManager.ensureVfkitAppBundle(bundle, vfkit.toString()));
        assertEquals(link.toString(), VmManager.ensureVfkitAppBundle(bundle, vfkit.toString()));
        assertEquals(vfkit, Files.readSymbolicLink(link));
    }
}
