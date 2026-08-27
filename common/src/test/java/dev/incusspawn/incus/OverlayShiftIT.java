package dev.incusspawn.incus;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates that disk devices with shift=true fail when the source
 * path is on an overlayfs mount. This is a kernel limitation: overlayfs
 * does not support idmapped mounts.
 *
 * In tpl-incus-spawn (nested Incus inside a VM), the outer isx may
 * mount host-resources with mode=overlay. The resulting overlay mount
 * points cannot then be used as shift=true disk sources in inner
 * containers, causing "Required idmapping abilities not available".
 *
 * Run with:
 *   mvn verify -DskipITs=false -Dit.test=OverlayShiftIT
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OverlayShiftIT {

    private static final String CONTAINER = "isx-overlay-it-"
            + Integer.toHexString(ThreadLocalRandom.current().nextInt() >>> 1);

    private static IncusClient client;
    private static Path tmpDir;
    private static Path merged;

    @BeforeAll
    static void setUp() throws Exception {
        client = new IncusClient();
        var error = client.checkConnectivity();
        Assumptions.assumeTrue(error == null,
                "Incus not reachable — skipping: " + error);

        // Create an overlay mount on a temp directory.
        // This simulates what the outer isx does when it applies
        // host-resources with mode=overlay.
        tmpDir = Files.createTempDirectory("isx-overlay-shift-test-");
        var lower = tmpDir.resolve("lower");
        var upper = tmpDir.resolve("upper");
        var work = tmpDir.resolve("work");
        merged = tmpDir.resolve("merged");
        Files.createDirectories(lower);
        Files.createDirectories(upper);
        Files.createDirectories(work);
        Files.createDirectories(merged);
        Files.writeString(lower.resolve("test.txt"), "hello");

        int rc = new ProcessBuilder(
                "sudo", "mount", "-t", "overlay", "overlay",
                "-o", "lowerdir=" + lower + ",upperdir=" + upper + ",workdir=" + work,
                merged.toString())
                .inheritIO().start().waitFor();
        Assumptions.assumeTrue(rc == 0,
                "Cannot mount overlayfs (need root or mount privileges) — skipping");

        client.launch("images:alpine/edge", CONTAINER, false);
        client.waitForReady(CONTAINER);
    }

    @AfterAll
    static void tearDown() {
        if (client != null && client.checkConnectivity() == null) {
            try { client.delete(CONTAINER, true); } catch (Exception ignored) {}
        }
        if (merged != null) {
            try {
                new ProcessBuilder("sudo", "umount", merged.toString())
                        .inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
        if (tmpDir != null) {
            try {
                new ProcessBuilder("sudo", "rm", "-rf", tmpDir.toString())
                        .inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(1)
    void shiftWorksOnRegularFilesystem() {
        // Sanity check: shift=true works on a regular filesystem (tmpfs/ext4).
        var source = tmpDir.resolve("lower").toString();
        client.deviceAdd(CONTAINER, "test-regular-shift", "disk",
                "source=" + source,
                "path=/mnt/regular",
                "readonly=true",
                "shift=true");
        client.deviceRemove(CONTAINER, "test-regular-shift");
    }

    @Test
    @Order(2)
    void shiftFailsOnOverlayFs() {
        // Adding a disk device with shift=true using an overlay mount
        // as the source fails because overlayfs does not support
        // idmapped mounts.
        var ex = assertThrows(IncusException.class, () ->
                client.deviceAdd(CONTAINER, "test-overlay-shift", "disk",
                        "source=" + merged,
                        "path=/mnt/overlay",
                        "readonly=true",
                        "shift=true"));
        assertTrue(ex.getMessage().contains("idmapping"),
                "Error should mention idmapping, got: " + ex.getMessage());
    }

    @Test
    @Order(3)
    void noShiftWorksOnOverlayFs() {
        // Without shift=true, the same overlay source works fine.
        client.deviceAdd(CONTAINER, "test-overlay-noshift", "disk",
                "source=" + merged,
                "path=/mnt/overlay-noshift",
                "readonly=true");
        client.deviceRemove(CONTAINER, "test-overlay-noshift");
    }
}
