package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * An instance rebooted without isx -- {@code incus restart}, {@code reboot} in the guest, a host
 * reboot with autostart -- comes back with an empty {@code /run}, so without its secret (#1024).
 * The next {@code isx shell} or {@code isx run} gives it a new one, at no cost while the boot
 * still holds the one it was given.
 */
@ExtendWith(TempHome.class)
class InstanceSecretRebootTest {

    private static final String NAME = "box";
    private static final String STARTED = "2026-10-01T09:00:00.123456789Z";
    private static final String REBOOTED = "2026-10-05T07:30:00.987654321Z";

    private static String config(FakeIncusDaemon daemon, String key) {
        return daemon.instance(NAME).path("config").path(key).asText("");
    }

    /** The secrets handed to the guest, in order. */
    private static List<String> delivered(FakeIncusDaemon daemon) {
        return daemon.execs().stream()
                .filter(e -> String.join(" ", e.command()).contains(InstanceSecret.GUEST_SCRIPT))
                .map(e -> e.environment().get(InstanceSecret.DELIVERY_ENV))
                .filter(Objects::nonNull).toList();
    }

    /**
     * A client whose guest takes the secret it is handed, into {@code delivered}, and checks it was
     * recorded first; FakeIncusDaemon serves no exec.
     */
    private static IncusClient guestTaking(FakeIncusDaemon daemon, List<String> delivered) {
        var incus = spy(daemon.client());
        doAnswer(call -> {
            Map<String, String> env = call.getArgument(1);
            var secret = env.get(InstanceSecret.DELIVERY_ENV);
            assertEquals(config(daemon, Metadata.INSTANCE_SECRET_SHA256), InstanceSecret.sha256(secret),
                    "recorded before the guest is handed it");
            delivered.add(secret);
            return new IncusClient.ExecResult(0, "", "");
        }).when(incus).shellExec(eq(NAME), anyMap(), any(String[].class));
        return incus;
    }

    private static FakeIncusDaemon runningContainer(String bootedAt, String stampedBoot) {
        var config = new HashMap<String, String>();
        config.put(Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(InstanceSecret.generate()));
        if (stampedBoot != null) config.put(Metadata.INSTANCE_SECRET_BOOT, stampedBoot);
        return new FakeIncusDaemon().instance(NAME, "container", "Running", config).lastUsedAt(NAME, bootedAt);
    }

    @Test
    void aContainerRebootedOutsideIsxGetsANewSecret() {
        var daemon = runningContainer(REBOOTED, STARTED);
        var previous = config(daemon, Metadata.INSTANCE_SECRET_SHA256);

        var secrets = new ArrayList<String>();
        InstanceLifecycle.ensureReady(guestTaking(daemon, secrets), NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {});

        var recorded = config(daemon, Metadata.INSTANCE_SECRET_SHA256);
        assertNotEquals(previous, recorded, "the reboot emptied /run: the box needs a new secret");
        assertEquals(REBOOTED, config(daemon, Metadata.INSTANCE_SECRET_BOOT), "recorded with the boot it went to");
        assertEquals(1, secrets.size(), daemon.requests()::toString);
        assertEquals(recorded, InstanceSecret.sha256(secrets.getFirst()));
    }

    @Test
    void aDeliveryThatFailsIsTriedAgainAtTheNextShell() {
        // FakeIncusDaemon serves no exec, so every delivery fails
        var daemon = runningContainer(REBOOTED, STARTED);
        var incus = daemon.client();
        var said = new ArrayList<String>();

        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, said::add);
        assertEquals("", config(daemon, Metadata.INSTANCE_SECRET_BOOT),
                "a boot whose guest never got the secret is not recorded as holding it");
        assertEquals(1, said.size(), said::toString);
        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {});

        assertEquals(2, delivered(daemon).size());
    }

    @Test
    void aContainerOnTheBootItsSecretWasMadeForCostsNothing() {
        var daemon = runningContainer(STARTED, STARTED);
        var previous = config(daemon, Metadata.INSTANCE_SECRET_SHA256);

        InstanceLifecycle.ensureReady(daemon.client(), NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {});

        assertEquals(List.of(), daemon.requests(), "the shell path reads the boot from the instance it holds");
        assertEquals(previous, config(daemon, Metadata.INSTANCE_SECRET_SHA256));
    }

    @Test
    void aContainerFromBeforeTheBootWasRecordedGetsOneSecretForIt() {
        var daemon = runningContainer(STARTED, null);
        var secrets = new ArrayList<String>();
        var incus = guestTaking(daemon, secrets);

        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {});
        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {});

        assertEquals(1, secrets.size(), "once per boot, not once per shell");
        assertEquals(STARTED, config(daemon, Metadata.INSTANCE_SECRET_BOOT));
    }

    @Test
    void aStoppedStartRecordsTheBootItsSecretWentTo() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var incus = spy(daemon.client());
        // FakeIncusDaemon serves no exec: stand in for a guest that answers
        doNothing().when(incus).waitForReady(eq(NAME), eq(MachineType.CONTAINER), any(), anyMap());

        InstanceLifecycle.startForUse(incus, NAME, MachineType.CONTAINER, msg -> {});

        var bootedAt = daemon.instance(NAME).path("last_used_at").asText("");
        assertFalse(bootedAt.isEmpty());
        assertEquals(bootedAt, config(daemon, Metadata.INSTANCE_SECRET_BOOT));

        // So the shell that follows finds nothing to do
        daemon.clearRequests();
        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {});
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void aBranchRecordsItsFirstBoot() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev"));
        var incus = spy(daemon.client());
        doReturn(true).when(incus).pollUntilReady(eq(NAME), anyInt(), anyMap(), any(String[].class));
        var request = new BranchFlow.Request("dev-1", NAME, false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), true, Map.of());

        BranchFlow.create(incus, BranchFlow.preflight(incus, request, Map.of()));

        var bootedAt = daemon.instance(NAME).path("last_used_at").asText("");
        assertFalse(bootedAt.isEmpty());
        assertEquals(bootedAt, config(daemon, Metadata.INSTANCE_SECRET_BOOT));
    }

    @Test
    void aRestartFromTheTuiRecordsItsBootToo() {
        var daemon = runningContainer(STARTED, STARTED);
        var incus = spy(daemon.client());
        doNothing().when(incus).waitForReady(eq(NAME), eq(MachineType.CONTAINER), any(), anyMap());

        InstanceLifecycle.restartForUse(incus, NAME, MachineType.CONTAINER);

        var bootedAt = daemon.instance(NAME).path("last_used_at").asText("");
        assertNotEquals(STARTED, bootedAt);
        assertEquals(bootedAt, config(daemon, Metadata.INSTANCE_SECRET_BOOT));
    }

    /**
     * A guest reboot in a VM is often handled inside QEMU: no new process, no new start, nothing
     * the host sees changes. Only the guest can tell, and the agent probe already asks it.
     */
    @Test
    void aVmRebootedInPlaceGetsANewSecret() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running",
                Map.of(Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(InstanceSecret.generate())));
        var previous = config(daemon, Metadata.INSTANCE_SECRET_SHA256);
        var incus = vmWhoseGuestAnswers(daemon, InstanceSecret.MISSING + "\n");

        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.VM, msg -> {});

        var recorded = config(daemon, Metadata.INSTANCE_SECRET_SHA256);
        assertNotEquals(previous, recorded);
        var secrets = delivered(daemon);
        assertEquals(1, secrets.size(), daemon.requests()::toString);
        assertEquals(recorded, InstanceSecret.sha256(secrets.getFirst()));
        assertTrue(daemon.stateActions().isEmpty(), "an agent that answers is never restarted for");
    }

    @Test
    void aVmHoldingItsSecretCostsNoRequestBeyondTheAgentProbe() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running",
                Map.of(Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(InstanceSecret.generate())));
        var previous = config(daemon, Metadata.INSTANCE_SECRET_SHA256);
        var incus = vmWhoseGuestAnswers(daemon, "");

        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.VM, msg -> {});

        assertEquals(List.of(), daemon.requests());
        assertEquals(previous, config(daemon, Metadata.INSTANCE_SECRET_SHA256));
    }

    /** A running VM whose agent answers the probe with {@code stdout}, which FakeIncusDaemon cannot serve. */
    private static IncusClient vmWhoseGuestAnswers(FakeIncusDaemon daemon, String stdout) {
        var incus = spy(daemon.client());
        doReturn(new IncusClient.ExecResult(0, stdout, "")).when(incus).shellExec(eq(NAME), any(String[].class));
        return incus;
    }
}
