package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how many Incus API round trips the flows behind {@code isx shell}, {@code isx branch}
 * and the TUI's start action cost. Each round trip is a few milliseconds over the Unix socket
 * and far more over the macOS vsock tunnel, and they are paid in sequence before the user gets
 * a prompt -- so a refactor that turns one GET into four is a latency regression even though
 * every functional test still passes.
 *
 * <p>Budgets are exact, so they ratchet: above budget is a regression to fix; below budget is
 * an improvement, so lower the number here in the same change.
 */
class InstanceLifecycleRequestBudgetTest {

    private static final String NAME = "dev-1";

    private static void assertBudget(int expected, FakeIncusDaemon daemon, String flow) {
        var requests = daemon.requests();
        assertEquals(expected, requests.size(), () -> flow + " should make " + expected
                + " Incus request(s), made " + requests.size() + ":\n  "
                + String.join("\n  ", requests)
                + "\nMore is a latency regression; fewer is an improvement -- lower the budget.");
    }

    @Test
    void startCostsOneStateChangeAndItsWait() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.startInstance(daemon.client(), NAME);
        assertBudget(2, daemon, "startInstance");
    }

    @Test
    void preparingHostDevicesReadsTheInstanceOnce() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.prepareHostDevicesForStart(daemon.client(), NAME);
        assertBudget(1, daemon, "prepareHostDevicesForStart");
    }

    @Test
    void checkingAHealthyStaticIpBeforeStart() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.STATIC_IP, "10.166.11.20"));
        InstanceLifecycle.fixStaticIpIfNeeded(daemon.client(), NAME);
        assertBudget(2, daemon, "fixStaticIpIfNeeded (IP already on the bridge subnet)");
    }

    @Test
    void prefetchingRuntimeConfig() {
        // One instance read for every config key, plus the bridge lookup.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.WORKDIR, "/home/agentuser/project",
                Metadata.SHELL_COMMAND, "zsh",
                "user.incus-spawn.ssh-setup", "done"));
        var config = InstanceLifecycle.prefetchRuntimeConfig(daemon.client(), NAME);
        assertBudget(2, daemon, "prefetchRuntimeConfig");

        assertEquals("/home/agentuser/project", config.workdir());
        assertEquals("zsh", config.shellCommand());
        assertEquals("", config.buildSourceJson());
        assertTrue(config.hasSshKeys());
    }

    @Test
    void prefetchingRuntimeConfigOfAMissingInstanceFails() {
        var daemon = new FakeIncusDaemon();
        assertThrows(IncusException.class,
                () -> InstanceLifecycle.prefetchRuntimeConfig(daemon.client(), NAME));
    }

    @Test
    void staleSubnetScanCostDoesNotGrowWithInstanceCount() {
        // The bridge lookup and one listing, which already carries every instance's config.
        for (int n : new int[] {1, 10}) {
            var daemon = new FakeIncusDaemon();
            for (int i = 0; i < n; i++) {
                daemon.container("dev-" + i, Map.of(Metadata.STATIC_IP, "10.166.11." + (20 + i)));
            }
            InstanceLifecycle.findStaleSubnetInstances(daemon.client());
            assertBudget(2, daemon, "findStaleSubnetInstances with " + n + " instance(s)");
        }
    }
}
