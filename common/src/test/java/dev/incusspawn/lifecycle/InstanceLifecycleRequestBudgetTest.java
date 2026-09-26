package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void branchStartPushesNothingIntoTheStoppedInstance() {
        // Incus stops its forkfile file server on start, and one still finishing a push makes
        // the start wait a full second. The config read, the bridge lookup, then the start.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.prefetchAndStart(daemon.client(), NAME, false);
        assertBudget(4, daemon, "prefetchAndStart");

        var requests = daemon.requests();
        assertTrue(requests.stream().noneMatch(r -> r.contains("/files")),
                () -> "nothing may be pushed between prefetch and start: " + requests);
        assertEquals("PUT /1.0/instances/" + NAME + "/state", requests.get(requests.size() - 2));
    }

    @Test
    void removingGuiFromAnInstanceWithoutGuiOnlyReadsIt() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        GuiPassthrough.removeGui(daemon.client(), NAME);
        assertBudget(1, daemon, "removeGui without GUI state");
    }

    @Test
    void removingGuiStillClearsInheritedGuiState() {
        var daemon = new FakeIncusDaemon()
                .container(NAME, Map.of("environment.WAYLAND_DISPLAY", "/mnt/host-xdg/wayland-0",
                        "environment.GDK_BACKEND", "wayland"))
                .device(NAME, "gpu", Map.of("type", "gpu"));
        var incus = daemon.client();
        GuiPassthrough.removeGui(incus, NAME);

        // Read, device removal (read + full write), config write, two script pushes.
        assertBudget(6, daemon, "removeGui with GUI state");
        var after = incus.instanceMetadata(NAME);
        assertFalse(after.path("devices").has("gpu"));
        assertFalse(after.path("config").has("environment.WAYLAND_DISPLAY"));
        assertFalse(after.path("config").has("environment.GDK_BACKEND"));
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
