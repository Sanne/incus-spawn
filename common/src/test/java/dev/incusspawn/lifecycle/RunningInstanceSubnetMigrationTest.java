package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.StaticIpAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

/**
 * Incus validates a running instance's NIC update against the address the NIC holds now, which a
 * bridge subnet change left off the subnet: it refuses the very update that would repair it
 * (#1009). {@code isx doctor} and {@code isx init} therefore stop a running instance, move it,
 * and start it again, and say so.
 */
@ExtendWith(TempHome.class)
class RunningInstanceSubnetMigrationTest {

    private static final String OLD_IP = "10.99.0.7";

    /** An instance branched while the bridge was on 10.99.0.0/24; the bridge has since moved. */
    private static FakeIncusDaemon running(String type) {
        return box(type, "Running");
    }

    private static FakeIncusDaemon box(String type, String status) {
        return new FakeIncusDaemon()
                .instance("box", type, status, Map.of(Metadata.STATIC_IP, OLD_IP,
                        Metadata.STATIC_GATEWAY, "10.99.0.1", "volatile.eth0.hwaddr", "10:66:6a:3c:9e:01"))
                .device("box", "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0",
                        "ipv4.address", OLD_IP));
    }

    /** FakeIncusDaemon serves no exec: stand in for a guest that answers once started. */
    private static IncusClient answering(FakeIncusDaemon daemon) {
        var incus = spy(daemon.client());
        doNothing().when(incus).waitForReady(eq("box"), any(), any(), anyMap());
        return incus;
    }

    @Test
    void aRunningContainerIsStoppedMovedAndStartedAgain() {
        assertMigratedWithARestart("container");
    }

    @Test
    void aRunningVmIsStoppedMovedAndStartedAgain() {
        assertMigratedWithARestart("virtual-machine");
    }

    private static void assertMigratedWithARestart(String type) {
        var daemon = running(type);
        var warnings = new ArrayList<String>();
        var output = new StaticIpAllocator.Output(msg -> {}, warnings::add);

        assertEquals(1, InstanceNetwork.migrateAllInstancesToNewSubnet(answering(daemon), output));

        var box = daemon.instance("box");
        assertEquals("10.166.11.2", box.path("config").path(Metadata.STATIC_IP).asText());
        assertEquals("10.166.11.2", box.path("expanded_devices").path("eth0").path("ipv4.address").asText());
        assertEquals("Running", box.path("status").asText(), "left as it was found");
        assertEquals(List.of("box stop", "box start"), daemon.stateActions());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Stopping box")), warnings::toString);
    }

    @Test
    void aGuestIgnoringTheShutdownIsLeftRunningAndUntouched() {
        var daemon = running("virtual-machine").ignoreShutdown("box");
        var warnings = new ArrayList<String>();

        assertEquals(0, InstanceNetwork.migrateAllInstancesToNewSubnet(answering(daemon),
                new StaticIpAllocator.Output(msg -> {}, warnings::add)));

        var box = daemon.instance("box");
        assertEquals("Running", box.path("status").asText(), "never forced");
        assertEquals(OLD_IP, box.path("config").path(Metadata.STATIC_IP).asText());
        assertEquals(List.of("box stop"), daemon.stateActions());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("did not stop")), warnings::toString);
    }

    @Test
    void aStoppedInstanceIsNotStarted() {
        var daemon = box("container", "Stopped");
        var warnings = new ArrayList<String>();

        assertEquals(1, InstanceNetwork.migrateAllInstancesToNewSubnet(daemon.client(),
                new StaticIpAllocator.Output(msg -> {}, warnings::add)));

        assertEquals(List.of(), daemon.stateActions());
        assertEquals(List.of(), warnings);
    }

    /** Paused on purpose: a stop would wait on a guest that cannot answer, so it is only reported. */
    @Test
    void aFrozenInstanceIsLeftAsItIs() {
        var daemon = box("container", "Frozen");
        var warnings = new ArrayList<String>();

        assertEquals(0, InstanceNetwork.migrateAllInstancesToNewSubnet(daemon.client(),
                new StaticIpAllocator.Output(msg -> {}, warnings::add)));

        assertEquals(List.of(), daemon.stateActions());
        assertEquals(OLD_IP, daemon.instance("box").path("config").path(Metadata.STATIC_IP).asText());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("frozen")), warnings::toString);
    }
}
