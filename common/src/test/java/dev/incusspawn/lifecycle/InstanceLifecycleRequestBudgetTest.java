package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

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
@ExtendWith(TempHome.class)
class InstanceLifecycleRequestBudgetTest {

    private static final String NAME = "dev-1";
    /** A key a caller stamps on its branch; any key works. */
    private static final String OWNER = Metadata.PREFIX + "owner";

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
    void aStartForUseAddsOneWriteForItsSecret() {
        // The prep's read, the secret's hash (#934), the start and its wait; the secret itself
        // rides in the readiness probe, which costs nothing it did not cost before.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        assertThrows(IncusException.class, () -> InstanceLifecycle.startForUse(
                daemon.clientWithShortReadyWait(), NAME, dev.incusspawn.incus.MachineType.CONTAINER, msg -> {}));
        var requests = daemon.requests();
        var beforeProbe = requests.subList(0, requests.indexOf("POST /1.0/instances/" + NAME + "/exec"));
        assertEquals(List.of("GET /1.0/instances/" + NAME, "PATCH /1.0/instances/" + NAME,
                "PUT /1.0/instances/" + NAME + "/state"), beforeProbe.subList(0, 3), String.join("\n", requests));
        assertEquals(4, beforeProbe.size(), () -> "startForUse before its first probe:\n" + String.join("\n", requests)
                + "\nMore is a latency regression; fewer is an improvement -- lower the budget.");
    }

    @Test
    void aContainerStartAndItsCaCheckAddOneWriteForTheBoot() {
        // isx shell's and the TUI's start, then their CA check. The start reads the instance once
        // the guest answers, for the boot Incus recorded, and stamps it (#1024); the CA check
        // reuses that read. So the boot costs one PATCH: 6, where it was 5 before #1024.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var incus = spy(daemon.client());
        doNothing().when(incus).waitForReady(eq(NAME), any(), any(), anyMap());
        var started = InstanceLifecycle.startForUse(incus, NAME, MachineType.CONTAINER, msg -> {});
        CertificateAuthority.fixContainerCaIfNeeded(incus, NAME, started);
        var requests = daemon.requests();
        assertEquals(List.of("GET /1.0/instances/" + NAME, "PATCH /1.0/instances/" + NAME),
                requests.subList(requests.size() - 2, requests.size()),
                () -> "the CA check must not read the instance again:\n" + String.join("\n", requests));
        assertBudget(6, daemon, "startForUse + fixContainerCaIfNeeded (container, guest answering)");
    }

    @Test
    void aBranchsCaCheckReusesTheReadThatRecordsItsBoot() {
        // After the branch's start: the read that finds its boot and the stamp (#1024), then the
        // CA check with nothing of its own, then resolv.conf's bridge lookup and exec (which this
        // daemon cannot serve, so the flow ends there).
        var daemon = new FakeIncusDaemon().container("dev-0", Map.of(Metadata.PROFILE, "tpl-dev"));
        var incus = spy(daemon.client());
        doReturn(true).when(incus).pollUntilReady(eq(NAME), anyInt(), anyMap(), any(String[].class));
        var request = new BranchFlow.Request("dev-0", NAME, false, false, NetworkMode.FULL,
                null, null, null, null, List.of(), true, Map.of());
        // A non-airgapped branch checks the host's proxy first; this host may not run one.
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            var preflight = BranchFlow.preflight(incus, request, Map.of());
            assertThrows(IncusException.class, () -> BranchFlow.create(incus, preflight));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }

        var requests = daemon.requests();
        var afterStart = requests.subList(requests.indexOf("PUT /1.0/instances/" + NAME + "/state") + 2, requests.size());
        assertEquals(List.of("GET /1.0/instances/" + NAME, "PATCH /1.0/instances/" + NAME,
                "GET /1.0/networks/incusbr0", "POST /1.0/instances/" + NAME + "/exec"), afterStart,
                () -> "the CA check must not read the instance again:\n" + String.join("\n", requests));
    }

    @Test
    void preparingHostDevicesReadsTheInstanceOnce() {
        // Filtering already on, as on every instance branched since #905's check: re-arming it
        // must cost nothing then.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
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
                "user.incus-spawn.ssh-setup", "done",
                Metadata.STATIC_IP, "10.166.11.20"));
        var config = InstanceLifecycle.prefetchRuntimeConfig(daemon.client(), NAME);
        assertBudget(2, daemon, "prefetchRuntimeConfig");

        assertEquals("/home/agentuser/project", config.workdir());
        // isx mcp answers create_instance from this, rather than reading the instance again.
        assertEquals("10.166.11.20", config.staticIp());
        assertEquals("zsh", config.shellCommand());
        assertEquals("", config.buildSourceJson());
        assertTrue(config.hasSshKeys());
    }

    /**
     * Every branch and shell asks whether a baked identity is stale. The stamps and the pins are
     * on the same instance, so that is one read -- it used to be two for any instance with a
     * stamp, which since the no-identity marker includes a template built without a token.
     */
    @Test
    void checkingForAStaleIdentityReadsTheInstanceOnce() {
        var config = new dev.incusspawn.config.SpawnConfig();
        var setups = Map.<String, dev.incusspawn.tool.ToolSetup>of("github", new dev.incusspawn.tool.GhSetup());
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.accountIdentityKey("github"), Metadata.ACCOUNT_IDENTITY_NONE,
                Metadata.accountKey("claude"), "work"));
        var warnings = new java.util.ArrayList<String>();

        InstanceLifecycle.reconcileAccountIdentities(daemon.client(), NAME, config, setups,
                msg -> { }, warnings::add);

        assertBudget(1, daemon, "reconcileAccountIdentities, nothing stale");
        assertEquals(List.of(), warnings);
        assertTrue(daemon.execs().isEmpty(), "and nothing is asked of the guest");
    }

    @Test
    void anInstanceWithNoStampsIsReadOnce() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.reconcileAccountIdentities(daemon.client(), NAME,
                new dev.incusspawn.config.SpawnConfig(),
                Map.of("github", new dev.incusspawn.tool.GhSetup()), msg -> { }, msg -> { });
        assertBudget(1, daemon, "reconcileAccountIdentities, no stamps");
        assertTrue(daemon.execs().isEmpty());
    }

    @Test
    void preparingAShellReadsTheInstanceOnce() {
        // isx shell, isx run and the TUI's shell: one instance read, plus the bridge lookup.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.WORKDIR, "/home/agentuser/project",
                Metadata.SHELL_COMMAND, "zsh",
                Metadata.PARENT, "dev-0",
                Metadata.PROFILE, "tpl-dev"));
        var prep = IncusClient.ShellPrep.from(daemon.client(), NAME);
        assertBudget(2, daemon, "ShellPrep.from");

        assertEquals("/home/agentuser/project", prep.workdir());
        assertEquals("zsh", prep.shellCommand());
        assertFalse(prep.autoAttachTmux());
        assertEquals("tpl-dev", prep.templateName(), "the leaf template, not the clone it came from");
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
        InstanceLifecycle.prefetchAndStart(daemon.client(), NAME, dev.incusspawn.incus.MachineType.CONTAINER);
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
        // PATCH: the instance read, one bridge read (gateway, subnet and prefix length together),
        // the listing that finds free addresses, and the push of the static network config.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of("limits.cpu", "4"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertBudget(5, daemon, "configureBranch");
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
    void aVmBranchGetsFreePageReportingInTheSameWrite() {
        // A VM gets no static network config pushed before start: one request fewer than a
        // container.
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped", Map.of());
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertBudget(4, daemon, "configureBranch (VM)");
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));
        assertEquals(InstanceLifecycle.FREE_PAGE_REPORTING_CONF, daemon.instance(NAME)
                .path("config").path(InstanceLifecycle.RAW_QEMU_CONF).asText());
    }

    @Test
    void aVmBranchKeepsSomeoneElsesRawQemuConf() {
        var custom = "[machine]\nfoo = \"bar\"\n";
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped",
                Map.of(InstanceLifecycle.RAW_QEMU_CONF, custom));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals(custom, daemon.instance(NAME)
                .path("config").path(InstanceLifecycle.RAW_QEMU_CONF).asText());
    }

    @Test
    void aContainerBranchGetsNoRawQemuConf() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertFalse(daemon.instance(NAME).path("config").has(InstanceLifecycle.RAW_QEMU_CONF));
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
    void aCopyCarriesItsOwnerFromTheCopyRequestItself() {
        // A caller's metadata is stamped by the copy request, so no extra write and no moment in
        // which the new instance exists without it.
        var daemon = new FakeIncusDaemon().container("tpl-java",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-java"));
        daemon.client().copy("tpl-java", NAME,
                new IncusClient.CopyPlan("cow", "btrfs", "cow", true, Map.of()),
                Map.of(OWNER, "someone"));
        assertBudget(2, daemon, "copy (the POST and its operation wait)");

        var config = daemon.instance(NAME).path("config");
        assertEquals("someone", config.path(OWNER).asText());
        assertEquals("tpl-java", config.path(Metadata.PROFILE).asText(),
                "the override is laid over the source's config, not in place of it");
    }

    @Test
    void anEmptyValueInTheCopyRequestRemovesTheSourcesKey() {
        // How BranchFlow drops its source's isx mcp keys from a copy (#1011): no write of its own.
        var daemon = new FakeIncusDaemon().container("mcp-src",
                Map.of(Metadata.MCP_IDEMPOTENCY_KEY, "k1", Metadata.MCP_OWNER, "alice"));
        daemon.client().copy("mcp-src", NAME,
                new IncusClient.CopyPlan("cow", "btrfs", "cow", true, Map.of()),
                Map.of(Metadata.MCP_IDEMPOTENCY_KEY, ""));
        assertBudget(2, daemon, "copy (the POST and its operation wait)");
        var config = daemon.instance(NAME).path("config");
        assertFalse(config.has(Metadata.MCP_IDEMPOTENCY_KEY), config.toString());
        assertEquals("alice", config.path(Metadata.MCP_OWNER).asText());
    }

    @Test
    void extraConfigRidesInTheBranchsOneWrite() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.configureBranch(daemon.client(), NAME, new InstanceLifecycle.BranchSettings(
                null, "8GiB", "20GiB", NetworkMode.FULL, "tpl-java", Map.of(), Map.of(), false,
                Map.of(OWNER, "someone", Metadata.PREFIX + "note", "x")));
        assertBudget(5, daemon, "configureBranch with extra config");
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));
        var config = daemon.instance(NAME).path("config");
        assertEquals("someone", config.path(OWNER).asText());
        assertEquals("x", config.path(Metadata.PREFIX + "note").asText());
    }

    @Test
    void aBranchOfAnAgentsInstanceIsNotTheAgents() {
        // A kept instance still carries the session that created it. A user's branch of it must
        // not inherit that, or the orphan reaper would take it for the dead session's.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.MCP_SESSION, "4242-1700000000000", Metadata.MCP_OWNER, "alice",
                Metadata.MCP_KEPT, "2026-09-27"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));
        var config = daemon.instance(NAME).path("config");
        assertFalse(config.has(Metadata.MCP_SESSION));
        assertFalse(config.has(Metadata.MCP_OWNER));
        assertFalse(config.has(Metadata.MCP_KEPT));
    }

    @Test
    void noCopyOfACoordinatorMayCallIsxMcp() {
        // #915: only `isx branch --mcp-client` grants it, on the branch it makes; a copy of a
        // coordinator, an agent's fork included, is an ordinary instance.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.MCP_CALLER, "2026-10-05T10:00:00"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));
        assertFalse(daemon.instance(NAME).path("config").has(Metadata.MCP_CALLER));
    }

    @Test
    void anEmptyAccountSelectionKeepsTheCopiedPins() {
        // A caller passing no selection must not wipe what the copy carried.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.accountKey("github"), "work"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals("work", daemon.instance(NAME).path("config")
                .path(Metadata.accountKey("github")).asText());
    }

    /** Every NIC Incus would attach at start, whatever it comes from. */
    private static List<String> attachedNics(FakeIncusDaemon daemon) {
        return daemon.instance(NAME).path("expanded_devices").properties().stream()
                .filter(e -> IncusClient.isNic(e.getValue())).map(Map.Entry::getKey).toList();
    }

    @Test
    void theFakeBringsBackAProfileDeviceWhoseOverrideIsRemoved() {
        // #813 went unnoticed because the fake dropped a removed override from expanded_devices
        // instead of letting the profile's device apply again, as Incus does.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of())
                .device(NAME, "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"));
        daemon.client().deviceRemove(NAME, "eth0");
        assertEquals(List.of("eth0"), attachedNics(daemon));
    }

    @Test
    void anAirgappedBranchHasNoNicInOneWrite() {
        // #813: override-then-remove only dropped the override, and the default profile's eth0
        // came back. A type: none device of the same name is what masks it.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.AIRGAP, Map.of()));
        assertBudget(2, daemon, "configureBranch (airgap)");
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));

        assertEquals(List.of(), attachedNics(daemon));
        var after = daemon.instance(NAME);
        assertEquals("none", after.path("devices").path("eth0").path("type").asText());
        assertEquals(NetworkMode.AIRGAP.name(), after.path("config").path(Metadata.NETWORK_MODE).asText());
    }

    @Test
    void airgappingACopyOfAClone() {
        // A clone overrides the profile's NIC with its static IP, and carries the address in its
        // metadata: removing that override would bring the profile's NIC back, and the stale
        // address would make the proxy take this instance for its source.
        var daemon = new FakeIncusDaemon()
                .container(NAME, Map.of(Metadata.STATIC_IP, "10.166.11.7",
                        Metadata.STATIC_GATEWAY, "10.166.11.1",
                        Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(),
                        Metadata.PROXY_GATEWAY, "10.166.11.1"))
                .device(NAME, "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0",
                        "ipv4.address", "10.166.11.7", "security.ipv4_filtering", "true"))
                .device(NAME, "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.AIRGAP, Map.of()));

        assertEquals(List.of(), attachedNics(daemon));
        var config = daemon.instance(NAME).path("config");
        assertEquals(NetworkMode.AIRGAP.name(), config.path(Metadata.NETWORK_MODE).asText());
        assertFalse(config.has(Metadata.STATIC_IP));
        assertFalse(config.has(Metadata.STATIC_GATEWAY));
        assertFalse(config.has(Metadata.PROXY_GATEWAY));
    }

    @Test
    void branchingWithNetworkFromAnAirgappedInstanceReconnects() {
        // The profile's NIC overwrites the mask in the one write; a mask the template set on a
        // device of its own is not airgap's to lift.
        var daemon = new FakeIncusDaemon()
                .container(NAME, Map.of(Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name()))
                .device(NAME, "eth0", Map.of("type", "none"))
                .device(NAME, "cache", Map.of("type", "none"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertBudget(6, daemon, "configureBranch (full, from an airgapped instance)");
        assertEquals(List.of("PATCH /1.0/instances/" + NAME), writes(daemon));

        assertEquals(List.of("eth0"), attachedNics(daemon));
        var after = daemon.instance(NAME);
        assertEquals("10.166.11.2", after.path("devices").path("eth0").path("ipv4.address").asText());
        assertEquals("incusbr0", after.path("devices").path("eth0").path("network").asText());
        assertFalse(after.path("config").has(Metadata.NETWORK_MODE),
                "full internet is the absence of a mode, not the airgap the source had");
        assertEquals("none", after.path("devices").path("cache").path("type").asText());
    }

    @Test
    void aFullBranchOfAProxyOnlyInstanceDropsTheMode() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(),
                Metadata.PROXY_GATEWAY, "10.166.11.1"));
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        var config = daemon.instance(NAME).path("config");
        assertFalse(config.has(Metadata.NETWORK_MODE));
        assertFalse(config.has(Metadata.PROXY_GATEWAY));
    }

    @Test
    void refusedIpFilteringStillPinsTheAddress() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of())
                .refuseWritesContaining("security.ipv4_filtering");
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        assertEquals(3, writes(daemon).size(),
                "the refused write, one without filtering, then filtering alone, refused again");

        var after = daemon.instance(NAME);
        var nic = after.path("devices").path("eth0");
        assertEquals("10.166.11.2", nic.path("ipv4.address").asText());
        assertFalse(nic.has("security.ipv4_filtering"));
        assertEquals("10.166.11.2", after.path("config").path(Metadata.STATIC_IP).asText());
    }

    @Test
    void aTransientRefusalDoesNotCostIpFiltering() {
        // A first write refused for a reason unrelated to filtering must not leave the branch
        // without spoofing protection: the proxy identifies callers by source address.
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).refuseNextWrite();
        InstanceLifecycle.configureBranch(daemon.client(), NAME, branch(NetworkMode.FULL, Map.of()));
        var nic = daemon.instance(NAME).path("devices").path("eth0");
        assertEquals("10.166.11.2", nic.path("ipv4.address").asText());
        assertEquals("true", nic.path("security.ipv4_filtering").asText());
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
    void enablingFreePageReportingOnAVmWithoutRawQemuConf() {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped", Map.of());
        var incus = daemon.client();
        InstanceLifecycle.enableFreePageReporting(incus, NAME);

        assertBudget(2, daemon, "enableFreePageReporting (unset)");
        assertEquals(InstanceLifecycle.FREE_PAGE_REPORTING_CONF,
                incus.configGet(NAME, InstanceLifecycle.RAW_QEMU_CONF));
    }

    @Test
    void freePageReportingLeavesSomeoneElsesRawQemuConfAlone() {
        var custom = "[machine]\nfoo = \"bar\"\n";
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped",
                Map.of(InstanceLifecycle.RAW_QEMU_CONF, custom));
        var incus = daemon.client();
        InstanceLifecycle.enableFreePageReporting(incus, NAME);

        assertBudget(1, daemon, "enableFreePageReporting (already set)");
        assertEquals(custom, incus.configGet(NAME, InstanceLifecycle.RAW_QEMU_CONF));
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
