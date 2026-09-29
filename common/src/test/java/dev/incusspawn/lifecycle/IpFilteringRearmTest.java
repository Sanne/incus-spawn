package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Re-arming IP spoofing protection when a stopped instance is started for use (#905).
 *
 * <p>{@link InstanceLifecycle#startInstance} starts an instance with {@code security.ipv4_filtering}
 * off when the host cannot enforce it -- on macOS, every branch, until the appliance kernel gained
 * the nft bridge family. The proxy picks a credential account by source address, so such an
 * instance can spend a neighbour's account for as long as it stays off. It has to come back on at
 * the next stopped start once the host can enforce it; otherwise the fix only helps instances
 * branched after the appliance update.
 *
 * <p>This is tested at {@link InstanceLifecycle#prepareHostDevicesForStart} because that is the
 * one step every path starting an existing instance for use goes through: {@code isx shell} and
 * {@code isx run} (via {@link InstanceLifecycle#ensureReady} and {@code InstancePrep}), the TUI's
 * shell, and VM agent recovery. The TUI used to start with a bare {@code incus.start}, so only the
 * CLI re-armed. <b>Whatever those paths are merged into must still call it.</b>
 */
class IpFilteringRearmTest {

    private static final String NAME = "box";

    private static String filtering(FakeIncusDaemon daemon) {
        return daemon.instance(NAME).path("expanded_devices").path("eth0")
                .path("security.ipv4_filtering").asText();
    }

    @Test
    void protectionTheFallbackTurnedOffIsTurnedBackOnBeforeTheNextStart() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "false");
        var said = new ArrayList<String>();

        // FakeIncusDaemon serves no exec, so the readiness wait after the start can only time
        // out; what matters here is what reached Incus before it.
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, "Stopped", false, said::add));

        assertEquals("true", filtering(daemon));
        var requests = daemon.requests();
        int write = requests.indexOf("PATCH /1.0/instances/" + NAME);
        int start = requests.indexOf("PUT /1.0/instances/" + NAME + "/state");
        assertTrue(write >= 0 && start > write, "must be on before the start that applies it:\n"
                + String.join("\n", requests));
        assertEquals(List.of(NAME + " start"), daemon.stateActions());
    }

    @Test
    void anInstanceBranchedBeforeTheCheckIsArmedToo() {
        // No override at all: only the profile's NIC, as an instance from before isx set it.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());

        InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME, w -> fail(w));

        assertEquals("true", filtering(daemon));
    }

    @Test
    void anArmedInstanceCostsNoRequestBeyondTheReadTheRepairsShare() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");

        InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME, w -> fail(w));

        assertEquals(List.of("GET /1.0/instances/" + NAME), daemon.requests());
    }

    @Test
    void anInstanceWithNoBridgeNicIsLeftAlone() {
        // Airgap: the profile's bridge NIC masked, so there is nothing to filter.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of())
                .device(NAME, "eth0", Map.of("type", "none"));

        InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME, w -> fail(w));

        assertEquals(List.of("GET /1.0/instances/" + NAME), daemon.requests());
    }

    @Test
    void aRefusedWriteIsReportedToTheCallersSink() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "false")
                .refuseWritesContaining("ipv4_filtering");
        var warnings = new ArrayList<String>();

        // The TUI passes its warning log: stderr would be drawn over while it owns the terminal.
        InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME, warnings::add);

        assertEquals("false", filtering(daemon));
        assertFalse(warnings.isEmpty());
        assertTrue(String.join("\n", warnings).contains("impersonate each other's credential accounts"),
                warnings.toString());
    }
}
