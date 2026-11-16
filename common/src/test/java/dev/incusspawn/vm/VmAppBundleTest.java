package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher's link in isx's app bundle is replaced when it dangles or names another launcher
 * (#2231), and a bundle an older isx made for vfkit is brought up to date (#2031).
 */
class VmAppBundleTest {

    @TempDir
    Path dir;

    @Test
    void replacesDanglingLink() throws IOException {
        var bundle = dir.resolve("incus-spawn-vm.app");
        var link = bundle.resolve("Contents/MacOS/isx-vm");
        Files.createDirectories(link.getParent());
        Files.writeString(bundle.resolve("Contents/Info.plist"), VmManager.APP_BUNDLE_PLIST);
        Files.createSymbolicLink(link, dir.resolve("gone/isx-vm"));

        var launcher = dir.resolve("new-isx-vm");
        Files.writeString(launcher, "");

        assertEquals(link.toString(), VmManager.ensureAppBundle(bundle, launcher));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(launcher.toRealPath(), Files.readSymbolicLink(link));
    }

    @Test
    void keepsLinkToTheSameLauncher() throws IOException {
        var bundle = dir.resolve("incus-spawn-vm.app");
        var launcher = dir.resolve("isx-vm");
        Files.writeString(launcher, "");
        var link = Path.of(VmManager.ensureAppBundle(bundle, launcher));
        var plist = bundle.resolve("Contents/Info.plist");
        var written = Files.getLastModifiedTime(plist);
        assertEquals(link.toString(), VmManager.ensureAppBundle(bundle, launcher));
        assertEquals(launcher.toRealPath(), Files.readSymbolicLink(link));
        assertEquals(written, Files.getLastModifiedTime(plist));
    }

    /** The bundle names its executable, so one left naming vfkit would start nothing (#2031). */
    @Test
    void aBundleMadeForVfkitIsMadeForTheLauncher() throws IOException {
        var bundle = dir.resolve("incus-spawn-vm.app");
        var oldLink = bundle.resolve("Contents/MacOS/vfkit");
        Files.createDirectories(oldLink.getParent());
        Files.writeString(bundle.resolve("Contents/Info.plist"), "<string>vfkit</string>");
        Files.createSymbolicLink(oldLink, dir.resolve("vfkit"));
        var launcher = dir.resolve("isx-vm");
        Files.writeString(launcher, "");

        VmManager.ensureAppBundle(bundle, launcher);

        assertFalse(Files.exists(oldLink, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(VmManager.APP_BUNDLE_PLIST, Files.readString(bundle.resolve("Contents/Info.plist")));
        assertTrue(VmManager.APP_BUNDLE_PLIST.contains("<string>isx-vm</string>"));
    }
}
