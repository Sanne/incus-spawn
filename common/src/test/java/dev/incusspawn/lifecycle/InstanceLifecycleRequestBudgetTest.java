package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    /** The instance writes (PATCH or PUT) a flow made, whatever it read around them. */
    private static List<String> writes(FakeIncusDaemon daemon) {
        return daemon.requests().stream()
                .filter(r -> r.equals("PATCH /1.0/instances/" + NAME)
                        || r.equals("PUT /1.0/instances/" + NAME))
                .toList();
    }

    private static InstanceLifecycle.BranchSettings branch(NetworkMode mode,
                                                           Map<String, String> accounts) {
        return new InstanceLifecycle.BranchSettings(
                null, "8GiB", "20GiB", mode, "tpl-java", accounts, false);
    }

    @Test
    void configuringABranchIsOneWrite() {
        // #804: this was nine writes, each one an Incus backup-file rewrite. Around the one
        // PATCH: the instance read, three bridge reads (gateway, allocation, prefix length), the
        // listing that finds free addresses, and the push of the static network config.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of("limits.cpu", "4"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertBudget(7, daemon, "configureBranch");
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));

        var after = daemon.instance(NAME);
        var config = after.path("config");
        assertFalse(config.has("limits.cpu"), "an unset CPU limit must clear the template's");
        assertEquals("8GiB", config.path("limits.memory").asText());
        assertEquals(Metadata.TYPE_CLONE, config.path(Metadata.TYPE).asText());
        assertEquals("tpl-java", config.path(Metadata.PARENT).asText());
        assertEquals("10.166.11.2", config.path(Metadata.STATIC_IP).asText());
        assertEquals("10.166.11.1", config.path(Metadata.STATIC_GATEWAY).asText());
        var root = after.path("devices").path("root");
        assertEquals("disk", root.path("type").asText(), "the profile's root disk, overridden whole");
        assertEquals("20GiB", root.path("size").asText());
        var nic = after.path("devices").path("eth0");
        assertEquals("nic", nic.path("type").asText());
        assertEquals("10.166.11.2", nic.path("ipv4.address").asText());
        assertEquals("true", nic.path("security.ipv4_filtering").asText());
    }

    @Test
    void droppingInheritedKvmFoldsEverythingIntoOnePut() {
        // PATCH cannot remove a device, so the removal needs a PUT -- which then carries every
        // other change too, rather than adding a PATCH next to it.
        var daemon = new FakeIncusDaemon()
                .container(NAME, Map.of(Metadata.KVM_ENABLED, "true",
                        Metadata.accountKey("github"), "work",
                        Metadata.accountKey("claude"), "personal"))
                .device(NAME, "kvm", Map.of("type", "unix-char", "source", "/dev/kvm"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME,
                branch(NetworkMode.PROXY_ONLY, Map.of("github", "oss")));
        assertEquals(List.of("PUT /1.0/instances/" + NAME), writes(daemon));

        var after = daemon.instance(NAME);
        var config = after.path("config");
        assertFalse(after.path("devices").has("kvm"));
        assertFalse(config.has(Metadata.KVM_ENABLED));
        assertEquals("oss", config.path(Metadata.accountKey("github")).asText());
        assertFalse(config.has(Metadata.accountKey("claude")),
                "a pin the selection does not name is cleared, as AccountSelection.stamp does");
        assertEquals(NetworkMode.PROXY_ONLY.name(), config.path(Metadata.NETWORK_MODE).asText());
        assertEquals("10.166.11.1", config.path(Metadata.PROXY_GATEWAY).asText());
        assertEquals("20GiB", after.path("devices").path("root").path("size").asText());
        assertEquals("10.166.11.2", after.path("devices").path("eth0").path("ipv4.address").asText());
    }

    @Test
    void anEmptyAccountSelectionKeepsTheCopiedPins() {
        // The TUI does not resolve a selection yet (#800); it must not wipe what the copy carried.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.accountKey("github"), "work"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals("work", daemon.instance(NAME).path("config")
                .path(Metadata.accountKey("github")).asText());
    }

    @Test
    void refusedIpFilteringStillPinsTheAddress() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of())
                .refuseWritesContaining("security.ipv4_filtering");
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals(2, writes(daemon).size(), "the refused write, then one without filtering");

        var after = daemon.instance(NAME);
        var nic = after.path("devices").path("eth0");
        assertEquals("10.166.11.2", nic.path("ipv4.address").asText());
        assertFalse(nic.has("security.ipv4_filtering"));
        assertEquals("10.166.11.2", after.path("config").path(Metadata.STATIC_IP).asText());
    }

    @Test
    void anyOtherRefusalFailsTheBranch() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of())
                .refuseWritesContaining("limits.memory");
        assertThrows(IncusException.class, () -> InstanceLifecycle.configureBranch(
                daemon.client(), NAME, branch(NetworkMode.FULL, Map.of())));
        assertFalse(daemon.instance(NAME).path("config").has(Metadata.STATIC_IP));
    }

    @Test
    void removingDevicesAnInstanceDoesNotHaveOnlyReadsIt() {
        // configureKvm clears stale KVM devices first; on a template without them that was a
        // full PUT of the instance for nothing.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        daemon.client().devicesRemoveAll(NAME, List.of("kvm", "vhost-vsock"));
        assertBudget(1, daemon, "devicesRemoveAll of absent devices");
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
