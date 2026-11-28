package dev.incusspawn.config;

import dev.incusspawn.Platform;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A build attaches every host-resource device while the instance is stopped, and only does
 * in-guest work after start (#828): on a VM a hot-plugged device takes one of 8 spare PCIe
 * slots, while a device present at boot gets its own port.
 */
class HostResourceBuildDevicesTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    private MockedStatic<Platform> platform;

    /**
     * These tests describe a Linux host whatever host runs them. On a macOS host overlay mode is
     * refused (#157) and device sources are translated to the VM's view of the host, so without
     * this they failed there, though nothing they check depends on the machine.
     */
    @BeforeEach
    void onALinuxHost() {
        platform = mockStatic(Platform.class, CALLS_REAL_METHODS);
        host(false);
    }

    private void host(boolean macOs) {
        platform.when(Platform::isMacOS).thenReturn(macOs);
        platform.when(Platform::isLinux).thenReturn(!macOs);
    }

    @AfterEach
    void releasePlatform() {
        platform.close();
    }

    /** Both halves of a build refuse it: the devices before the start, the mount after it. */
    @Test
    void overlayIsRefusedOnAMacOsHost(@TempDir Path tmp) throws Exception {
        host(true);
        var ov = Files.createDirectories(tmp.resolve("ov"));
        var overlay = List.of(new ImageDef.HostResource(ov.toString(), "/home/agentuser/ov", "overlay"));
        var incus = mock(IncusClient.class);

        var attach = assertThrows(IllegalStateException.class, () ->
                HostResourceSetup.attachBuildDevices(incus, "b", overlay, MachineType.VM));
        var apply = assertThrows(IllegalStateException.class, () ->
                HostResourceSetup.applyForBuild(incus, new Container(incus, "b"), overlay, MachineType.VM));

        assertTrue(attach.getMessage().contains("not yet supported on macOS"), attach.getMessage());
        assertTrue(apply.getMessage().contains("not yet supported on macOS"), apply.getMessage());
        verify(incus, never()).deviceAdd(anyString(), anyString(), anyString(), any(String[].class));
        verify(incus, never()).shellExec(anyString(), any(String[].class));
    }

    @Test
    void attachAddsReadonlyAndOverlayLowerDevicesWithoutTouchingTheGuest(@TempDir Path tmp) throws Exception {
        var ro = Files.createDirectories(tmp.resolve("ro"));
        var ov = Files.createDirectories(tmp.resolve("ov"));
        var copied = Files.createDirectories(tmp.resolve("cp"));
        var incus = mock(IncusClient.class);

        HostResourceSetup.attachBuildDevices(incus, "b", List.of(
                new ImageDef.HostResource(ro.toString(), "/home/agentuser/ro", "readonly"),
                new ImageDef.HostResource(ov.toString(), "/home/agentuser/ov", "overlay"),
                new ImageDef.HostResource(copied.toString(), "/home/agentuser/cp", "copy")), MachineType.VM);

        verify(incus).deviceAdd(eq("b"), eq(HostResourceSetup.deviceName("/home/agentuser/ro")), eq("disk"),
                eq("source=" + ro), eq("path=/home/agentuser/ro"), eq("readonly=true"));
        verify(incus).deviceAdd(eq("b"), eq(HostResourceSetup.overlayDeviceName("/home/agentuser/ov")),
                eq("disk"), eq("source=" + ov),
                eq("path=/var/lib/incus-spawn/overlays/home/agentuser/ov/lower"), eq("readonly=true"));
        verify(incus, times(2)).deviceAdd(anyString(), anyString(), anyString(), any(String[].class));
        verify(incus, never()).shellExec(anyString(), any(String[].class));
    }

    /**
     * Inside an isx instance whose template mounted {@code ~/.m2/repository} as an overlay, that
     * directory is on overlayfs, which the kernel cannot idmap, and a shifted device on it stops the
     * nested build from starting (#595). There it belongs to uid 1000, which the build's raw.idmap
     * maps unshifted. A source on any other filesystem, or owned by anyone else, is still shifted.
     */
    @Test
    void containerDevicesAreShiftedUnlessTheirSourceIsOnOverlayfs(@TempDir Path tmp) throws Exception {
        var plain = Files.createDirectories(tmp.resolve("plain"));
        var onOverlayRo = Files.createDirectories(tmp.resolve("overlay-ro"));
        var onOverlay = Files.createDirectories(tmp.resolve("overlay-ov"));
        var othersOverlay = Files.createDirectories(tmp.resolve("overlay-other"));
        var incus = mock(IncusClient.class);

        try (var fs = mockStatic(HostResourceSetup.class, CALLS_REAL_METHODS)) {
            fs.when(() -> HostResourceSetup.filesystemType(any(Path.class))).thenReturn("ext4");
            fs.when(() -> HostResourceSetup.ownerUid(any(Path.class))).thenReturn(1000);
            for (var p : List.of(onOverlayRo, onOverlay, othersOverlay)) {
                fs.when(() -> HostResourceSetup.filesystemType(p)).thenReturn("overlay");
            }
            fs.when(() -> HostResourceSetup.ownerUid(othersOverlay)).thenReturn(1001);
            HostResourceSetup.attachBuildDevices(incus, "b", List.of(
                    new ImageDef.HostResource(plain.toString(), "/home/agentuser/plain", "readonly"),
                    new ImageDef.HostResource(onOverlayRo.toString(), "/home/agentuser/ro", "readonly"),
                    new ImageDef.HostResource(onOverlay.toString(), "/home/agentuser/ov", "overlay"),
                    new ImageDef.HostResource(othersOverlay.toString(), "/home/agentuser/other", "readonly")),
                    MachineType.CONTAINER);
        }

        verify(incus).deviceAdd(eq("b"), eq(HostResourceSetup.deviceName("/home/agentuser/plain")), eq("disk"),
                eq("source=" + plain), eq("path=/home/agentuser/plain"), eq("readonly=true"), eq("shift=true"));
        verify(incus).deviceAdd(eq("b"), eq(HostResourceSetup.deviceName("/home/agentuser/other")), eq("disk"),
                eq("source=" + othersOverlay), eq("path=/home/agentuser/other"), eq("readonly=true"), eq("shift=true"));
        verify(incus).deviceAdd(eq("b"), eq(HostResourceSetup.deviceName("/home/agentuser/ro")), eq("disk"),
                eq("source=" + onOverlayRo), eq("path=/home/agentuser/ro"), eq("readonly=true"));
        verify(incus).deviceAdd(eq("b"), eq(HostResourceSetup.overlayDeviceName("/home/agentuser/ov")),
                eq("disk"), eq("source=" + onOverlay),
                eq("path=/var/lib/incus-spawn/overlays/home/agentuser/ov/lower"), eq("readonly=true"));
        verify(incus, times(4)).deviceAdd(anyString(), anyString(), anyString(), any(String[].class));
    }

    @Test
    void attachSkipsSourcesIncusCouldNotStartWith(@TempDir Path tmp) throws Exception {
        // A missing source fails Incus start validation ("Missing source path"), and a VM cannot
        // mount a single file: that one falls back to copy, which needs no device.
        var file = Files.writeString(tmp.resolve("file"), "x");
        var incus = mock(IncusClient.class);

        HostResourceSetup.attachBuildDevices(incus, "b", List.of(
                new ImageDef.HostResource(tmp.resolve("gone").toString(), "/opt/gone", "readonly"),
                new ImageDef.HostResource(tmp.resolve("gone2").toString(), "/opt/gone2", "overlay"),
                new ImageDef.HostResource(file.toString(), "/opt/file", "readonly")), MachineType.VM);

        verify(incus, never()).deviceAdd(anyString(), anyString(), anyString(), any(String[].class));
    }

    @Test
    void applyAfterStartMountsOverlayWithoutAddingDevicesOrPolling(@TempDir Path tmp) throws Exception {
        var ro = Files.createDirectories(tmp.resolve("ro"));
        var ov = Files.createDirectories(tmp.resolve("ov"));
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("b"), any(String[].class))).thenReturn(OK);

        HostResourceSetup.applyForBuild(incus, new Container(incus, "b"), List.of(
                new ImageDef.HostResource(ro.toString(), "/home/agentuser/ro", "readonly"),
                new ImageDef.HostResource(ov.toString(), "/home/agentuser/ov", "overlay")), MachineType.VM);

        verify(incus, never()).deviceAdd(anyString(), anyString(), anyString(), any(String[].class));
        verify(incus, never()).pollUntilReady(anyString(), anyInt(), any(String[].class));
        var lower = "/var/lib/incus-spawn/overlays/home/agentuser/ov/lower";
        verify(incus).shellExec(eq("b"), eq("sh"), eq("-c"), contains("mount -t overlay"), eq("sh"),
                eq(lower), anyString(), anyString(), eq("/home/agentuser/ov"));
    }

    @Test
    void overlayRefusesToMountOverAnUnmountedLowerDir(@TempDir Path tmp) throws Exception {
        // incus-agent only logs a share it failed to mount; an overlay over the empty mount point
        // would silently hide the host content. Run the real script with stubbed tools.
        var ov = Files.createDirectories(tmp.resolve("ov"));
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("b"), any(String[].class))).thenReturn(OK);
        HostResourceSetup.applyForBuild(incus, new Container(incus, "b"), List.of(
                new ImageDef.HostResource(ov.toString(), "/opt/ov", "overlay")), MachineType.VM);
        var captor = ArgumentCaptor.forClass(String[].class);
        verify(incus, atLeastOnce()).shellExec(eq("b"), captor.capture());
        var mountCall = captor.getAllValues().stream()
                .filter(args -> args.length > 2 && args[2].contains("mount -t overlay"))
                .findFirst().orElseThrow();

        var mountLog = tmp.resolve("mount.log");
        for (boolean mounted : new boolean[] {true, false}) {
            Files.deleteIfExists(mountLog);
            var proc = runStubbed(mountCall,
                    "mountpoint() { return " + (mounted ? 0 : 1) + "; }",
                    "findmnt() { true; }",
                    "mount() { echo \"$@\" >> '" + mountLog + "'; }");
            var out = new String(proc.getInputStream().readAllBytes());
            int rc = proc.waitFor();

            if (mounted) {
                assertEquals(0, rc, out);
                assertTrue(Files.readString(mountLog).contains(
                        "lowerdir=/var/lib/incus-spawn/overlays/opt/ov/lower,upperdir="), out);
            } else {
                assertNotEquals(0, rc, out);
                assertFalse(Files.exists(mountLog), "mounted over an empty lower dir");
                assertTrue(out.contains("is not mounted"), out);
            }
        }
    }

    @Test
    void overlayAlreadyMountedByTheInheritedServiceIsNotStacked(@TempDir Path tmp) throws Exception {
        // A template derived from one with overlays boots with the parent's overlay service,
        // which has mounted the overlay by the time the build gets to it.
        var ov = Files.createDirectories(tmp.resolve("ov"));
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("b"), any(String[].class))).thenReturn(OK);
        HostResourceSetup.applyForBuild(incus, new Container(incus, "b"), List.of(
                new ImageDef.HostResource(ov.toString(), "/opt/ov", "overlay")), MachineType.VM);
        var captor = ArgumentCaptor.forClass(String[].class);
        verify(incus, atLeastOnce()).shellExec(eq("b"), captor.capture());
        var mountCall = captor.getAllValues().stream()
                .filter(args -> args.length > 2 && args[2].contains("mount -t overlay"))
                .findFirst().orElseThrow();

        var mountLog = tmp.resolve("mount.log");
        var proc = runStubbed(mountCall,
                "mountpoint() { true; }",
                "findmnt() { [ \"$5\" = /opt/ov ] && echo overlay; }",
                "mount() { echo \"$@\" >> '" + mountLog + "'; }");
        var out = new String(proc.getInputStream().readAllBytes());

        assertEquals(0, proc.waitFor(), out);
        assertFalse(Files.exists(mountLog), "stacked a second overlay");
    }

    @Test
    void overlayServiceWaitsForIncusAgentMounts() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("b"), any(String[].class))).thenReturn(OK);
        var container = spy(new Container(incus, "b"));
        doNothing().when(container).writeFile(anyString(), anyString());
        var ov = System.getProperty("java.io.tmpdir");

        HostResourceSetup.applyForBuild(incus, container, List.of(
                new ImageDef.HostResource(ov, "/opt/ov", "overlay")), MachineType.VM);

        var unit = ArgumentCaptor.forClass(String.class);
        verify(container).writeFile(eq("/etc/systemd/system/incus-spawn-overlays.service"), unit.capture());
        assertTrue(unit.getValue().contains("After=local-fs.target incus-agent.service"), unit.getValue());
    }

    /**
     * Runs the {@code sh -c} script of {@code call} with the guest's tools replaced by shell
     * functions, which the shell prefers to any command on {@code PATH}. They were scripts written
     * to a directory on {@code PATH}, but a process another test thread forks while one is open for
     * writing holds it, so its exec fails with ETXTBSY; dash then quietly ran the host's real
     * {@code mountpoint} instead (#1846).
     */
    private static Process runStubbed(String[] call, String... functions) throws Exception {
        var cmd = call.clone();
        cmd[2] = String.join("\n", functions) + "\n" + call[2];
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }
}
