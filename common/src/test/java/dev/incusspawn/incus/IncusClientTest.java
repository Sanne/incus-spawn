package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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
}
