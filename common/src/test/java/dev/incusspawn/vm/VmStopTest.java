package dev.incusspawn.vm;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VmManager#stop()} says whether the guest shut down or was killed (#1155). The script
 * that keeps a VM's data disk across an uninstall warns from it; before, a stop that ended in
 * SIGKILL looked the same as a clean one. That was every stop on a Mac until the guest's agent
 * could be asked to shut it down (#881), and still is for an appliance from before that.
 *
 * <p>A process named like the launcher, {@code isx-vm}, stands in for it: a copy of the shell on Linux, where a
 * process is known by the file it runs (not of {@code sleep}, which may be one multi-call binary),
 * and a link to {@code sleep} on macOS, where a copied system binary is killed on exec and a
 * process is known by the path it was started as.
 */
class VmStopTest {

    /**
     * Not a {@code @TempDir}: the fake agent listens on {@link Environment#vmAgentSocket()} under
     * this home, and macOS allows a Unix socket path 104 bytes, fewer than its own temp
     * directory plus JUnit's leaves for that.
     */
    private Path tempHome;
    private String originalHome;
    private Process vm;
    private ServerSocketChannel control;
    private final AtomicReference<String> controlAsked = new AtomicReference<>();
    private ServerSocketChannel agent;
    private final AtomicReference<String> asked = new AtomicReference<>();

    @BeforeEach
    void runningVm() throws IOException {
        startStandIn("isx-vm");
    }

    private void startStandIn(String program) throws IOException {
        startStandIn(program, java.util.List.of());
    }

    /** {@code extraArgs}: arguments the stand-in carries after its own, on Linux alone (sleep would read them). */
    private void startStandIn(String program, java.util.List<String> extraArgs) throws IOException {
        tempHome = Files.createTempDirectory(Path.of("/tmp"), "isxvm");
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var qemu = tempHome.resolve(program);
        if (OS.MAC.isCurrentOs()) {
            Files.createSymbolicLink(qemu, Path.of("/bin/sleep"));
        } else {
            // A shell, not sleep: Ubuntu 26.04's sleep is uutils coreutils, one multi-call binary
            // that exits at once when copied under another name.
            Files.copy(Path.of("/bin/sh").toRealPath(), qemu);
            qemu.toFile().setExecutable(true);
        }
        vm = (OS.MAC.isCurrentOs()
                ? new ProcessBuilder(qemu.toString(), "60")
                : new ProcessBuilder(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(qemu.toString(), "-c", "read x", program), extraArgs.stream()).toList())).start();
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmPidFile(), Long.toString(vm.pid()));
        assertTrue(VmManager.isRunning(), "the stand-in must pass for a VM");
    }

    @AfterEach
    void restore() {
        if (control != null) try { control.close(); } catch (IOException ignored) {}
        if (agent != null) try { agent.close(); } catch (IOException ignored) {}
        stopStandIn();
    }

    private void stopStandIn() {
        vm.destroyForcibly();
        System.setProperty("user.home", originalHome);
        try (var files = Files.walk(tempHome)) {  // does not follow the stand-in's link
            files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
        } catch (IOException ignored) {}
    }

    /**
     * A restart looks for the launcher before it stops the VM (#2552): a JBang install moving from
     * vfkit has no isx-vm, and the stop used to come first, so the VM ended up down.
     */
    @Test
    void aRestartWithNoLauncherInstalledLeavesTheVmRunning() {
        try {
            VmManager.findLauncher();
            org.junit.jupiter.api.Assumptions.abort("an isx-vm is installed on this host");
        } catch (VmException expected) {
            // what a host without isx-vm says, which this test is about
        }
        var restarted = new java.util.concurrent.atomic.AtomicBoolean(true);
        var out = dev.incusspawn.util.RecordingOutput.during(() -> restarted.set(VmManager.restart()));
        assertFalse(restarted.get());
        assertTrue(vm.isAlive(), "the VM must not be stopped for a launcher that is not there");
        var errors = out.events(dev.incusspawn.util.RecordingOutput.Kind.ERROR);
        assertTrue(errors.stream().anyMatch(e -> e.msg().contains("isx-vm, the program that runs the VM")), errors.toString());
    }

    @Test
    void aGuestThatDoesNotShutDownIsReportedAsSignalled() {
        // No agent and no control socket to ask: the launcher gets SIGTERM (#881)
        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertFalse(vm.isAlive());
    }

    /** A guest agent that answers one {@code shutdown} with {@code reply}, then runs {@code then}. */
    private void agentAnswering(String reply, Runnable then) throws IOException {
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(Environment.vmAgentSocket().toString()));
        agent = server;
        Thread.ofVirtual().start(() -> {
            try (var conn = server.accept()) {
                var verb = new BufferedReader(Channels.newReader(conn, StandardCharsets.US_ASCII)).readLine();
                asked.set(verb);
                conn.write(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.US_ASCII)));
            } catch (IOException ignored) {
                return;
            }
            then.run();
        });
    }

    @Test
    void aGuestAskedThroughItsAgentShutsDownByItself() throws IOException {
        // Before #881 this guest was signalled at once. Its agent takes a
        // moment to bring it down, as rcK does, and stop() waits for that.
        agentAnswering("shutting down", () -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            vm.destroy();
        });

        assertEquals(VmManager.StopResult.SHUT_DOWN, VmManager.stop());
        assertEquals("shutdown", asked.get());
        assertFalse(vm.isAlive());
    }

    @Test
    void anApplianceWithoutTheVerbIsStoppedAsBefore() throws IOException {
        agentAnswering("error: unknown verb", () -> {});

        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertEquals("shutdown", asked.get());
        assertFalse(vm.isAlive());
    }

    @Test
    void aGuestThatAgreesButNeverGoesDownIsSignalledAfterTheWait() throws IOException {
        var wait = VmManager.guestShutdownWait;
        VmManager.guestShutdownWait = Duration.ofMillis(300);
        try {
            agentAnswering("shutting down", () -> {});
            assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
            assertFalse(vm.isAlive());
        } finally {
            VmManager.guestShutdownWait = wait;
        }
    }

    @Test
    void aVmWhoseDisksAreDeletedNextIsNotAskedToShutDown() throws IOException {
        agentAnswering("shutting down", () -> {});

        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stopToDelete());
        assertNull(asked.get(), "a clean shutdown of disks about to be deleted is time wasted");
    }

    /**
     * A guest that does not go down by itself is stopped by the launcher, asked on its control
     * socket (#2031). The launcher cuts the disks off as a power cut would, so it is reported as
     * signalled, as SIGTERM would be.
     */
    @Test
    void aGuestTheLauncherStopsIsAskedOnItsControlSocketAndReportedAsSignalled() throws IOException {
        launcherAnswering(vm.pid());
        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertEquals("stop", controlAsked.get());
        assertFalse(vm.isAlive());
    }

    /**
     * A launcher answering on the socket that is not the VM's pid is another machine: the stop
     * of this VM signals it and never asks that one to stop.
     */
    @Test
    void aStopNeverAsksALauncherThatIsAnotherProcess() throws IOException {
        launcherAnswering(vm.pid() + 100_000);

        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertNotEquals("stop", controlAsked.get());
        assertFalse(vm.isAlive());
    }

    /** A fake launcher on the control socket: {@code state} names {@code pid}, {@code stop} ends the VM. */
    private void launcherAnswering(long pid) throws IOException {
        launcherAnswering(pid, "running");
    }

    private void launcherAnswering(long pid, String state) throws IOException {
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(Environment.vmControlSocket().toString()));
        control = server;
        Thread.ofVirtual().start(() -> {
            while (true) {
                try (var conn = server.accept()) {
                    var verb = new BufferedReader(Channels.newReader(conn, StandardCharsets.US_ASCII)).readLine();
                    controlAsked.set(verb);
                    var reply = "stop".equals(verb) ? "stopping\n" : state + " " + pid + "\n";
                    conn.write(ByteBuffer.wrap(reply.getBytes(StandardCharsets.US_ASCII)));
                    if ("stop".equals(verb)) vm.destroy();
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    /**
     * An isx from before the launcher, still running after the upgrade, takes an {@code isx-vm}
     * pid for a stale one and deletes the pid file (#2031). The launcher still answers on its
     * control socket, with its pid, so the VM is found again and no second one is started on its
     * disks.
     */
    @Test
    void aLauncherWhosePidFileAnOlderIsxRemovedIsFoundAgain() throws IOException {
        Files.delete(Environment.vmPidFile());
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(Environment.vmControlSocket().toString()));
        control = server;
        Thread.ofVirtual().start(() -> {
            try (var conn = server.accept()) {
                controlAsked.set(new BufferedReader(Channels.newReader(conn, StandardCharsets.US_ASCII)).readLine());
                conn.write(ByteBuffer.wrap(("running " + vm.pid() + "\n").getBytes(StandardCharsets.US_ASCII)));
            } catch (IOException ignored) {}
        });

        assertTrue(VmManager.isRunning());
        assertEquals("state", controlAsked.get());
        assertEquals(Long.toString(vm.pid()), Files.readString(Environment.vmPidFile()));
    }

    @Test
    void noControlSocketMeansNotRunningWithoutAPidFile() throws IOException {
        Files.delete(Environment.vmPidFile());
        assertFalse(VmManager.isRunning());
    }

    /**
     * A control socket a launcher left behind when it exited, with no pid file: nothing answers
     * and no launcher carries it, so it is removed, and the next command does not list this
     * user's processes again.
     */
    @Test
    void aStaleControlSocketWithoutAPidFileIsRemoved() throws IOException {
        Files.delete(Environment.vmPidFile());
        // A socket a launcher bound and left behind when it exited: it exists, and nothing listens
        try (var left = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            left.bind(UnixDomainSocketAddress.of(Environment.vmControlSocket().toString()));
        }

        assertFalse(VmManager.isRunning());
        assertFalse(Files.exists(Environment.vmControlSocket()));
    }

    /**
     * A VM still under vfkit whose tunnel stalls has no relay count to compare, and the generic
     * diagnosis knows Linux's remedies alone: the advice is the restart that moves it (#2031),
     * once the forwarder restart, which recovery tries first, did not help.
     */
    @Test
    void anUnreachableVmUnderVfkitIsToldToRestart() throws IOException {
        stopStandIn();
        startStandIn("vfkit");

        // Unknown, so recovery tries the forwarder restart first, which stops no instance.
        assertTrue(VmManager.detectLeakLayer().isEmpty());
        var advice = VmManager.unreachableAdvice().orElseThrow();
        assertTrue(advice.contains("isx vm restart"), advice);
        assertFalse(advice.contains("isx init") || advice.contains("systemctl"), advice);
    }

    /**
     * A launcher whose control socket exists but does not answer (its loop busy, or a stall) is
     * still found among this user's processes by its control socket argument: its pid file is not
     * taken for stale, its sockets are not removed, and no second VM is started on its disks.
     * Linux only: the macOS stand-in is sleep, which cannot carry the argument.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(OS.MAC)
    void aLauncherWhoseControlSocketDoesNotAnswerIsFoundByItsArguments() throws IOException {
        stopStandIn();
        var home = Files.createTempDirectory(Path.of("/tmp"), "isxvm");
        var control = home.resolve(".local/state/incus-spawn/vm.control.sock");
        Files.delete(home);
        startStandInAt(home, control);
        Files.writeString(control, "");  // exists, and nothing listens
        Files.delete(Environment.vmPidFile());

        assertTrue(VmManager.isRunning());
        assertEquals(Long.toString(vm.pid()), Files.readString(Environment.vmPidFile()));
        assertTrue(Files.exists(control), "a live launcher's socket is not cleaned up");
    }

    /**
     * What Sanne met upgrading from 0.5.2: a vfkit VM still running on this user's disks, which
     * vm.pid no longer names. A start must find it by the disk in its arguments and adopt it,
     * never put a second VM on the same images. Linux only, as above.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(OS.MAC)
    void aVfkitVmThePidFileLostIsFoundByItsDiskBeforeAnyLaunch() throws IOException {
        stopStandIn();
        var home = Files.createTempDirectory(Path.of("/tmp"), "isxvm");
        tempHome = home;
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(Environment.vmStateDir());
        var vfkit = home.resolve("vfkit");
        Files.copy(Path.of("/bin/sh").toRealPath(), vfkit);
        vfkit.toFile().setExecutable(true);
        vm = new ProcessBuilder(vfkit.toString(), "-c", "read x", "vfkit",
                "--device", "virtio-blk,path=" + Environment.vmDiskImage()).start();
        // An unrelated vfkit, another user's or another state directory's, is not the VM.
        var other = new ProcessBuilder(vfkit.toString(), "-c", "read x", "vfkit",
                "--device", "virtio-blk,path=/elsewhere/disk.img").start();
        try {
            assertFalse(VmManager.isRunning(), "no pid file: isRunning() alone cannot see it");

            assertTrue(VmManager.runningOrAdopted(), "a restart must stop and move it, not take it for stopped");
            assertEquals(Long.toString(vm.pid()), Files.readString(Environment.vmPidFile()));
            assertTrue(VmManager.isRunning());
            assertTrue(VmManager.runsUnderLegacyHypervisor());
        } finally {
            other.destroyForcibly();
        }
    }

    /**
     * A pid file naming another process (here this JVM) while vfkit still runs on the disks: the
     * VM is found before its sockets are cleaned up as stale, so a restart can still ask the
     * guest to shut down rather than kill it. Linux only, as above.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(OS.MAC)
    void aStalePidFileDoesNotRemoveTheSocketsOfAVfkitVmOnTheseDisks() throws IOException {
        stopStandIn();
        var home = Files.createTempDirectory(Path.of("/tmp"), "isxvm");
        tempHome = home;
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(Environment.vmStateDir());
        var vfkit = home.resolve("vfkit");
        Files.copy(Path.of("/bin/sh").toRealPath(), vfkit);
        vfkit.toFile().setExecutable(true);
        vm = new ProcessBuilder(vfkit.toString(), "-c", "read x", "vfkit",
                "--device", "virtio-blk,path=" + Environment.vmDiskImage()).start();
        Files.writeString(Environment.vmPidFile(), Long.toString(ProcessHandle.current().pid()));
        Files.writeString(Environment.vmAgentSocket(), "");

        assertTrue(VmManager.isRunning());
        assertEquals(Long.toString(vm.pid()), Files.readString(Environment.vmPidFile()));
        assertTrue(Files.exists(Environment.vmAgentSocket()), "the live VM's agent socket is kept");
    }

    /** `isx vm stop` of a vfkit VM whose pid file was lost stops it, rather than saying "not running". */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(OS.MAC)
    void aStopReachesAVfkitVmThePidFileLost() throws IOException {
        stopStandIn();
        var home = Files.createTempDirectory(Path.of("/tmp"), "isxvm");
        tempHome = home;
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(Environment.vmStateDir());
        var vfkit = home.resolve("vfkit");
        Files.copy(Path.of("/bin/sh").toRealPath(), vfkit);
        vfkit.toFile().setExecutable(true);
        vm = new ProcessBuilder(vfkit.toString(), "-c", "read x", "vfkit",
                "--device", "virtio-blk,path=" + Environment.vmDiskImage()).start();

        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stopToDelete());
        assertFalse(vm.isAlive());
    }

    @Test
    void noHypervisorOnTheseDisksIsAdoptedWhenNoneRuns() throws IOException {
        Files.delete(Environment.vmPidFile());
        assertTrue(VmManager.adoptHypervisorOnDisks().isEmpty());
        assertFalse(Files.exists(Environment.vmPidFile()));
        assertFalse(VmManager.runningOrAdopted(), "nothing runs: a restart starts the VM");
    }

    private void startStandInAt(Path home, Path control) throws IOException {
        Files.createDirectories(home);
        tempHome = home;
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var launcher = home.resolve("isx-vm");
        Files.copy(Path.of("/bin/sh").toRealPath(), launcher);
        launcher.toFile().setExecutable(true);
        vm = new ProcessBuilder(launcher.toString(), "-c", "read x", "isx-vm", "--control", control.toString()).start();
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmPidFile(), Long.toString(vm.pid()));
    }

    /**
     * A launcher makes its sockets once its machine runs, the control socket last: while it boots
     * there is neither, and nothing is said of a removed socket or a restart.
     */
    @Test
    void aBootingLauncherIsNotToldToRestart() {
        assertFalse(Files.exists(Environment.vmVsockSocket()));
        assertTrue(VmManager.unreachableAdvice().isEmpty());
        assertTrue(VmManager.detectLeakLayer().isEmpty());
    }

    /** A launcher stopping its machine still holds the disks: it is adopted, so none starts beside it. */
    @Test
    void aStoppingLauncherWhosePidFileWasRemovedStillCounts() throws IOException {
        Files.delete(Environment.vmPidFile());
        launcherAnswering(vm.pid(), "stopping");

        assertTrue(VmManager.isRunning());
        assertEquals(Long.toString(vm.pid()), Files.readString(Environment.vmPidFile()));
    }

    /** A running launcher whose Incus socket an older isx removed: only a restart brings it back. */
    @Test
    void aRunningLauncherWithoutItsIncusSocketIsToldToRestart() throws IOException {
        launcherAnswering(vm.pid());
        assertFalse(Files.exists(Environment.vmVsockSocket()));

        assertEquals(java.util.Optional.of(VmManager.LeakLayer.LAUNCHER), VmManager.detectLeakLayer());
        var advice = VmManager.unreachableAdvice().orElseThrow();
        assertTrue(advice.contains("isx vm restart") && advice.contains("vm.incus.sock"), advice);
        assertFalse(advice.contains("isx init") || advice.contains("systemctl"), advice);
    }

    /**
     * The macOS-role CI job boots the appliance under QEMU and records QEMU's pid as the VM's
     * (appliance/test-macos-role.sh): a pid taken for a stale one would have its sockets removed.
     */
    @Test
    void qemuStandingInForTheLauncherIsRunning() throws IOException {
        stopStandIn();
        startStandIn("qemu-system-x86_64");

        assertTrue(VmManager.isRunning());
        assertTrue(Files.exists(Environment.vmPidFile()));
    }

    /**
     * After an upgrade, the VM an older isx started under vfkit is still running on the VM's disks
     * (#2031). It must pass for the running VM, so no launcher is started beside it, and stop.
     */
    @Test
    void aVmAnOlderIsxStartedUnderVfkitIsRunningAndStops() throws IOException {
        stopStandIn();
        startStandIn("vfkit");

        assertTrue(VmManager.runsUnderLegacyHypervisor());
        assertEquals("vfkit", VmManager.state().launcher());
        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertFalse(vm.isAlive());
    }

    @Test
    void aVmThatIsNotRunningSaysSo() throws IOException {
        Files.delete(Environment.vmPidFile());
        assertEquals(VmManager.StopResult.NOT_RUNNING, VmManager.stop());
    }
}
