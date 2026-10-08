package dev.incusspawn.vm;

import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VmManager#stop()} says whether the guest shut down or was killed (#1155). The script
 * that keeps a VM's data disk across an uninstall warns from it; before, a stop that ended in
 * SIGKILL looked the same as a clean one, which is every stop on a Mac until #881 is fixed.
 *
 * <p>A copy of {@code sleep} named like qemu stands in for the hypervisor. Linux only: on macOS a
 * copied system binary is killed on exec.
 */
@EnabledOnOs(OS.LINUX)
class VmStopTest {

    @TempDir Path tempHome;
    private String originalHome;
    private Process vm;
    private HttpServer rest;

    @BeforeEach
    void runningVm() throws IOException {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var qemu = tempHome.resolve("qemu-system-x86_64");
        Files.copy(Path.of("/bin/sleep").toRealPath(), qemu);
        qemu.toFile().setExecutable(true);
        vm = new ProcessBuilder(qemu.toString(), "60").start();
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmPidFile(), Long.toString(vm.pid()));
        assertTrue(VmManager.isRunning(), "the stand-in must pass for a VM");
    }

    @AfterEach
    void restore() {
        if (rest != null) rest.stop(0);
        vm.destroyForcibly();
        System.setProperty("user.home", originalHome);
    }

    @Test
    void aGuestThatDoesNotShutDownIsReportedAsSignalled() {
        // No REST API to ask: what a guest ignoring vfkit's stop request comes to (#881)
        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertFalse(vm.isAlive());
    }

    @Test
    void aGuestThatShutsDownWhenAskedIsReportedAsShutDown() throws IOException {
        rest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rest.createContext("/vm/state", exchange -> {
            vm.destroy();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        rest.start();
        Files.writeString(Environment.vmRestUriFile(), "http://127.0.0.1:" + rest.getAddress().getPort());

        assertEquals(VmManager.StopResult.SHUT_DOWN, VmManager.stop());
        assertFalse(vm.isAlive());
    }

    @Test
    void aVmThatIsNotRunningSaysSo() throws IOException {
        Files.delete(Environment.vmPidFile());
        assertEquals(VmManager.StopResult.NOT_RUNNING, VmManager.stop());
    }
}
