package dev.incusspawn.config;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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

        var bin = Files.createDirectories(tmp.resolve("bin"));
        var mountLog = tmp.resolve("mount.log");
        stub(bin.resolve("findmnt"), "true");
        for (boolean mounted : new boolean[] {true, false}) {
            Files.deleteIfExists(mountLog);
            stub(bin.resolve("mountpoint"), "exit " + (mounted ? 0 : 1));
            stub(bin.resolve("mount"), "echo \"$@\" >> " + mountLog);
            var cmd = new ArrayList<>(List.of(mountCall));
            var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            var proc = pb.start();
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

        var bin = Files.createDirectories(tmp.resolve("bin"));
        var mountLog = tmp.resolve("mount.log");
        stub(bin.resolve("mountpoint"), "exit 0");
        stub(bin.resolve("findmnt"), "[ \"$5\" = /opt/ov ] && echo overlay");
        stub(bin.resolve("mount"), "echo \"$@\" >> " + mountLog);
        var pb = new ProcessBuilder(List.of(mountCall)).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var proc = pb.start();
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

    private static void stub(Path path, String body) throws Exception {
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
