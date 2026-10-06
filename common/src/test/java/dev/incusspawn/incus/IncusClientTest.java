package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IncusClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rootDiskPoolFromProfileInheritedDevice() {
        var devices = MAPPER.createObjectNode();
        var root = MAPPER.createObjectNode();
        root.put("type", "disk");
        root.put("path", "/");
        root.put("pool", "cow");
        devices.set("root", root);

        assertEquals("cow", IncusClient.rootDiskPoolFromDevices(devices));
    }

    @Test
    void rootDiskPoolReturnsNullWhenNoRootDevice() {
        var devices = MAPPER.createObjectNode();
        var eth0 = MAPPER.createObjectNode();
        eth0.put("type", "nic");
        eth0.put("network", "incusbr0");
        devices.set("eth0", eth0);

        assertNull(IncusClient.rootDiskPoolFromDevices(devices));
    }

    @Test
    void rootDiskPoolReturnsNullWhenPoolKeyMissing() {
        var devices = MAPPER.createObjectNode();
        var root = MAPPER.createObjectNode();
        root.put("type", "disk");
        root.put("path", "/");
        devices.set("root", root);

        assertNull(IncusClient.rootDiskPoolFromDevices(devices));
    }

    @Test
    void rootDiskPoolReturnsNullForMissingNode() {
        assertNull(IncusClient.rootDiskPoolFromDevices(MAPPER.missingNode()));
    }

    @Test
    void rootDiskPoolReturnsNullForNull() {
        assertNull(IncusClient.rootDiskPoolFromDevices(null));
    }

    @Test
    void rootDiskPoolIgnoresNonRootDiskDevices() {
        var devices = MAPPER.createObjectNode();
        var data = MAPPER.createObjectNode();
        data.put("type", "disk");
        data.put("path", "/data");
        data.put("pool", "fast");
        devices.set("data", data);

        assertNull(IncusClient.rootDiskPoolFromDevices(devices));
    }

    @Test
    void rootDiskPoolFindsRootAmongMultipleDevices() {
        var devices = MAPPER.createObjectNode();
        var eth0 = MAPPER.createObjectNode();
        eth0.put("type", "nic");
        eth0.put("network", "incusbr0");
        devices.set("eth0", eth0);
        var root = MAPPER.createObjectNode();
        root.put("type", "disk");
        root.put("path", "/");
        root.put("pool", "cow");
        devices.set("root", root);

        assertEquals("cow", IncusClient.rootDiskPoolFromDevices(devices));
    }

    @Test
    void rootDiskDeviceNameFindsCorrectDevice() {
        var devices = MAPPER.createObjectNode();
        var eth0 = MAPPER.createObjectNode();
        eth0.put("type", "nic");
        devices.set("eth0", eth0);
        var myRoot = MAPPER.createObjectNode();
        myRoot.put("type", "disk");
        myRoot.put("path", "/");
        myRoot.put("pool", "default");
        devices.set("my-root", myRoot);

        assertEquals("my-root", IncusClient.rootDiskDeviceNameFromDevices(devices));
    }

    @Test
    void rootDiskDeviceNameReturnsNullWhenNoRootDisk() {
        var devices = MAPPER.createObjectNode();
        var data = MAPPER.createObjectNode();
        data.put("type", "disk");
        data.put("path", "/data");
        devices.set("data", data);

        assertNull(IncusClient.rootDiskDeviceNameFromDevices(devices));
    }

    @Test
    void rootDiskDeviceNameReturnsNullForEmptyDevices() {
        assertNull(IncusClient.rootDiskDeviceNameFromDevices(MAPPER.createObjectNode()));
        assertNull(IncusClient.rootDiskDeviceNameFromDevices(null));
        assertNull(IncusClient.rootDiskDeviceNameFromDevices(MAPPER.missingNode()));
    }

    @Test
    void isCowDriverRecognizesAllDrivers() {
        assertTrue(IncusClient.isCowDriver("btrfs"));
        assertTrue(IncusClient.isCowDriver("zfs"));
        assertTrue(IncusClient.isCowDriver("lvm"));
        assertFalse(IncusClient.isCowDriver("dir"));
        assertFalse(IncusClient.isCowDriver(null));
        assertFalse(IncusClient.isCowDriver(""));
    }

    @Test
    void hasApiExtensionReadsServerInfo() {
        var daemon = new FakeIncusDaemon().apiExtensions("storage", "storage_create_options");
        assertTrue(daemon.client().hasApiExtension("storage_create_options"));
        // Incus 6.x, as Fedora and Ubuntu package it, predates the extension (#820)
        var older = new FakeIncusDaemon().apiExtensions("storage");
        assertFalse(older.client().hasApiExtension("storage_create_options"));
    }

    @Test
    void instanceMetadataOrThrowTellsGoneFromUnreadable() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of("user.incus-spawn.type", "clone"));
        assertEquals("dev", daemon.client().instanceMetadataOrThrow("dev").path("name").asText());
        assertNull(daemon.client().instanceMetadataOrThrow("missing"), "a 404 means the instance is gone");
        // A daemon that refuses or fails the read has said nothing about whether it exists (#858)
        for (var status : new int[] {403, 500}) {
            var failing = new FakeIncusDaemon().container("dev", Map.of()).failInstanceReads(status);
            assertThrows(IncusException.class, () -> failing.client().instanceMetadataOrThrow("dev"),
                    "HTTP " + status);
        }
    }

    @Test
    void aProbeRunsTheScriptWithoutALoginShell() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        // This daemon serves no exec: the call fails, the request is what counts.
        assertThrows(RuntimeException.class, () -> daemon.client().execProbe("dev", 1000, "/home/agentuser",
                "echo hi", java.io.OutputStream.nullOutputStream(), java.time.Duration.ofMillis(100)));
        var exec = daemon.execs().getLast();
        assertEquals(java.util.List.of("sh", "-c", IncusClient.LOGIN_PATH_PREFIX + "echo hi"), exec.command(),
                "no su -: the user's profile never runs before isx's probe");
        assertEquals("/home/agentuser", exec.environment().get("HOME"));
    }

    @Test
    void anInteractiveShellWithoutATerminalStartsNoExec() {
        // Surefire's fork has pipes, not a terminal, on stdin and stdout: the case of #1027.
        var console = System.console();
        org.junit.jupiter.api.Assumptions.assumeTrue(console == null || !console.isTerminal());
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        var prep = IncusClient.ShellPrep.fromPrefetched(null, null, null, null, true, null);

        var e = assertThrows(IncusException.class,
                () -> daemon.client().interactiveShell("dev", "agentuser", prep));

        assertTrue(e.getMessage().contains("no terminal"), e.getMessage());
        // A PTY exec is never started, so there is no session to lose and nothing to reconnect to
        assertEquals(java.util.List.of(), daemon.execs());
        assertEquals(java.util.List.of(), daemon.requests());
    }

    @Test
    void deleteImageOrThrowWaitsForTheDeleteAndReportsAFailure() {
        var daemon = new FakeIncusDaemon().image("fp");
        var client = daemon.client();

        client.deleteImageOrThrow("fp");

        assertFalse(daemon.hasImage("fp"));
        assertTrue(daemon.requests().stream().anyMatch(r -> r.contains("/wait")), daemon.requests().toString());
        assertThrows(IncusException.class, () -> client.deleteImageOrThrow("fp"));
    }
}
