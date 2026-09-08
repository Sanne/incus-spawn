package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Host-side repair of the {@code zmx-sockets} disk device.  The device source
 * lives on a tmpfs that disappears on reboot/logout, and Incus refuses to
 * start an instance whose disk source is missing, so the repair has to happen
 * before every start — without slowing the common case down.
 */
class ZmxSocketForwardTest {

    private static final String DEVICE = "zmx-sockets";
    private static final String NAME = "demo";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path zmxDir;

    private IncusClient incus;

    @BeforeEach
    void setUp() {
        assumeFalse(Platform.isMacOS(), "host socket sharing is Linux-only");
        incus = mock(IncusClient.class);
    }

    private Path containerDir() {
        return zmxDir.resolve("containers").resolve(NAME);
    }

    private Path symlink() {
        return zmxDir.resolve("isx-" + NAME);
    }

    /** An instance as {@code IncusClient.instanceMetadata} returns it. */
    private JsonNode instanceWithZmxDevice(String source) {
        var metadata = JSON.createObjectNode();
        metadata.putObject("devices").putObject(DEVICE)
                .put("type", "disk")
                .put("source", source);
        return metadata;
    }

    @Test
    void ensureHostDirForStart_directoryPresent_touchesNothing() throws IOException {
        Files.createDirectories(containerDir());

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(containerDir().toAbsolutePath().toString()));

        // The healthy path must cost a single stat and no Incus call at all,
        // or every start pays for the repair.
        verifyNoInteractions(incus);
    }

    @Test
    void ensureHostDirForStart_noZmxDevice_createsNothing() {
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir, JSON.createObjectNode());

        assertFalse(Files.exists(containerDir()),
                "instances without the device must not get a stray directory");
        assertFalse(Files.exists(symlink()));
        verifyNoInteractions(incus);
    }

    @Test
    void ensureHostDirForStart_wipedTmpfs_recreatesDirectoryAndSymlink() throws IOException {
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(containerDir().toAbsolutePath().toString()));

        assertTrue(Files.isDirectory(containerDir()));
        assertTrue(Files.isSymbolicLink(symlink()));
        assertEquals(Path.of("containers", NAME, "isx"), Files.readSymbolicLink(symlink()));
        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    @Test
    void ensureHostDirForStart_staleSourceAfterRename_repointsDevice() {
        var oldDir = zmxDir.resolve("containers").resolve("previous-name");

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(oldDir.toString()));

        assertTrue(Files.isDirectory(containerDir()));
        verify(incus).devicesRemoveAll(NAME, List.of(DEVICE));
        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    /**
     * The directory for this name exists but the device still points at the
     * one a rename left behind: Incus would fail start validation, so the
     * present directory must not be taken as proof of health.
     */
    @Test
    void ensureHostDirForStart_rightDirectoryWrongSource_repointsDevice() throws IOException {
        Files.createDirectories(containerDir());
        var oldDir = zmxDir.resolve("containers").resolve("previous-name");

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(oldDir.toString()));

        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    /**
     * A stale source that still exists on disk — the old name was recreated
     * since — would otherwise let the instance start against another
     * instance's sockets.
     */
    @Test
    void ensureHostDirForStart_staleSourceStillOnDisk_repointsDevice() throws IOException {
        var oldDir = zmxDir.resolve("containers").resolve("previous-name");
        Files.createDirectories(oldDir);

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(oldDir.toString()));

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    @Test
    void configure_addsDiskDeviceForContainerZmxDir() throws IOException {
        ZmxSocketForward.configure(incus, NAME, zmxDir);

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals(Path.of("containers", NAME, "isx"), Files.readSymbolicLink(symlink()));
        verify(incus).devicesRemoveAll(NAME, List.of(DEVICE));
        assertEquals(
                List.of("source=" + containerDir().toAbsolutePath(),
                        "path=/home/agentuser/.zmx",
                        "shift=true"),
                deviceAddArgs());
    }

    /**
     * The pre-start hook runs both repairs — a host-resource source that
     * vanished and the zmx directory — off a single instance fetch, since
     * either one failing start validation is what it exists to prevent.
     */
    @Test
    void prepareHostDevicesForStart_bothRepairsShareOneFetch() {
        // A name no real host directory can collide with: this call resolves
        // the zmx dir from the environment, unlike the tests above.
        var name = "wiring-" + java.util.UUID.randomUUID();
        var instance = JSON.createObjectNode();
        instance.putObject("config").put(Metadata.HOST_RESOURCES, "");
        when(incus.instanceMetadata(name)).thenReturn(instance);

        InstanceLifecycle.prepareHostDevicesForStart(incus, name);

        verify(incus).instanceMetadata(name);
        verifyNoMoreInteractions(incus);
    }

    private List<String> deviceAddArgs() {
        var args = ArgumentCaptor.forClass(String[].class);
        verify(incus).deviceAdd(eq(NAME), eq(DEVICE), eq("disk"), args.capture());
        return List.of(args.getValue());
    }
}
