package dev.incusspawn.vm;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VmManagerTest {

    @TempDir Path tempHome;
    private String originalHome;

    @BeforeEach
    void isolateEnvironment() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreEnvironment() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void detectCpusReturnsAtLeastOne() {
        assertTrue(VmManager.detectCpus() >= 1);
    }

    @Test
    void detectMemoryReturnsAtLeast2048() {
        assertTrue(VmManager.detectMemoryMiB() >= 2048);
    }

    @Test
    void diskSizeDefaultIs60G() {
        if (System.getenv("ISX_VM_DISK") == null) {
            assertEquals("60G", VmManager.diskSize());
        }
    }

    @Test
    void isRunningReturnsFalseWhenNoPidFile() {
        assertFalse(VmManager.isRunning());
    }

    @Test
    void detectBackendReturnsValueOnCurrentOs() {
        if (Environment.isLinux()) {
            try {
                var backend = VmManager.detectBackend();
                assertEquals(VmManager.Backend.QEMU, backend);
            } catch (VmException e) {
                assertTrue(e.getMessage().contains("qemu-system"));
            }
        } else if (Environment.isMacOS()) {
            try {
                var backend = VmManager.detectBackend();
                assertEquals(VmManager.Backend.VFKIT, backend);
            } catch (VmException e) {
                assertTrue(e.getMessage().contains("vfkit"));
            }
        }
    }

    @Test
    void gatewayIpDefaultIs10_166_11_1() {
        if (System.getenv("ISX_GATEWAY") == null) {
            assertEquals("10.166.11.1", VmManager.gatewayIp());
        }
    }

    @Test
    void mitmPortDefaultIs18443() {
        if (System.getenv("ISX_MITM_PORT") == null) {
            assertEquals("18443", VmManager.mitmPort());
        }
    }

    @Test
    void checkArtifactsThrowsWhenNoKernel() {
        var ex = assertThrows(VmException.class, VmManager::checkArtifacts);
        assertTrue(ex.getMessage().contains("vmlinuz"));
    }

    @Test
    void statusReportsNotRunningWhenVmIsStopped() {
        assertEquals("VM not running", VmManager.status());
    }

    @Test
    void parseDiskSizeGigabytes() {
        assertEquals(60L * 1024 * 1024 * 1024, VmManager.parseDiskSize("60G"));
    }

    @Test
    void parseDiskSizeMegabytes() {
        assertEquals(512L * 1024 * 1024, VmManager.parseDiskSize("512M"));
    }

    @Test
    void parseDiskSizeTerabytes() {
        assertEquals(1024L * 1024 * 1024 * 1024, VmManager.parseDiskSize("1T"));
    }

    @Test
    void parseDiskSizeCaseInsensitive() {
        assertEquals(60L * 1024 * 1024 * 1024, VmManager.parseDiskSize("60g"));
    }

    @Test
    void ensureDiskThrowsWhenNoCompressedImage() {
        var ex = assertThrows(VmException.class, VmManager::ensureDisk);
        assertTrue(ex.getMessage().contains("disk.img.gz"));
    }
}
