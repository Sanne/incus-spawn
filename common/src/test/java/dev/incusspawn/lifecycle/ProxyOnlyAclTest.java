package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code --proxy-only} is enforced by an Incus network ACL on the instance's NIC, on the host
 * (#1137), not by iptables rules inside the guest, which its root user could flush and which were
 * gone after any restart (#1311). The NIC carries it from the branch's one write, before any
 * start, so whoever starts the instance later starts it restricted, and a host whose Incus cannot
 * enforce it gets no proxy-only instance at all.
 */
@ExtendWith(TempHome.class)
@ExtendWith(LinuxBranching.class)
class ProxyOnlyAclTest {

    private static final String GATEWAY = "10.166.11.1";

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
    }

    /** Branch {@code dev-2} from {@code source} without starting it, as {@code --no-start} does. */
    private static void branch(FakeIncusDaemon daemon, String source, NetworkMode mode) {
        var request = new BranchFlow.Request(source, "dev-2", false, false, mode,
                null, null, null, null, List.of(), false, Map.of());
        // A non-airgapped branch checks the host's proxy first; this host may not run one.
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
    }

    private static JqValue nic(FakeIncusDaemon daemon, String name) {
        return daemon.instance(name).path("expanded_devices").path("eth0");
    }

    private static void assertRestricted(JqValue nic) {
        assertEquals(ProxyOnlyAcl.NAME, nic.path("security.acls").asText(), nic::toString);
        assertEquals("drop", nic.path("security.acls.default.egress.action").asText(), nic::toString);
        assertEquals("allow", nic.path("security.acls.default.ingress.action").asText(), nic::toString);
    }

    /** The ACL's egress rules, each as "protocol destination ports". */
    private static List<String> allowed(FakeIncusDaemon daemon) {
        var acl = daemon.networkAcl(ProxyOnlyAcl.NAME);
        assertTrue(acl != null, "no ACL was written");
        assertEquals(0, acl.path("ingress").size(), acl::toString);
        var rules = new ArrayList<String>();
        for (var rule : acl.path("egress").asList()) {
            assertEquals("allow", rule.path("action").asText(), rule::toString);
            assertEquals("enabled", rule.path("state").asText(), rule::toString);
            rules.add(rule.path("protocol").asText() + " " + rule.path("destination").asText() + " "
                    + rule.path("destination_port").asText(rule.path("icmp_type").asText()));
        }
        return rules;
    }

    @Test
    void aProxyOnlyBranchIsRestrictedOnTheHostBeforeItIsEverStarted() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertRestricted(nic(daemon, "dev-2"));
        assertEquals(List.of("tcp " + GATEWAY + " 443,18443,18080", "udp " + GATEWAY + " 53",
                "tcp " + GATEWAY + " 53", "icmp4 " + GATEWAY + " 8"), allowed(daemon));
        // In the branch's one write, not a write of its own
        assertEquals(1, daemon.requests().stream().filter(r -> r.equals("PATCH /1.0/instances/dev-2")).count(),
                () -> String.join("\n", daemon.requests()));
        assertEquals(List.of(), daemon.stateActions(), "--no-start");
    }

    @Test
    void aHostWhoseIncusCannotEnforceItGetsNoProxyOnlyBranch() {
        var daemon = template().firewall("xtables");

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));

        assertTrue(e.getMessage().contains("nftables") && e.getMessage().contains("xtables"), e.getMessage());
        assertFalse(daemon.client().exists("dev-2"), "refused before anything was created");
        assertNull(daemon.networkAcl(ProxyOnlyAcl.NAME));
    }

    @Test
    void aHostWhoseIncusCannotPutAnAclOnABridgedNicGetsNoProxyOnlyBranch() {
        // Incus 6.0.0, Ubuntu 24.04's own package: no network_bridge_acl_devices (#1979)
        var daemon = template().apiExtensions();

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));

        assertTrue(e.getMessage().contains("network_bridge_acl_devices") && e.getMessage().contains("6.9")
                && e.getMessage().contains("6.0.4"), e.getMessage());
        assertFalse(daemon.client().exists("dev-2"), "refused before anything was created");
        assertNull(daemon.networkAcl(ProxyOnlyAcl.NAME));
    }

    @Test
    void aProxyOnlyBranchThatFailsAfterItsCopyLeavesNoCopyBehind() {
        // The branch's configuration write fails once the copy is made
        var daemon = template().refuseWritesContaining("limits.memory");

        assertThrows(RuntimeException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));

        assertTrue(daemon.requests().contains("POST /1.0/instances"), "precondition: the copy was made");
        assertFalse(daemon.client().exists("dev-2"), "a copy under the proxy-only name was left");
        assertTrue(daemon.client().exists("tpl-dev"), "the source stays");
    }

    @Test
    void anIncusThatRefusesTheAclOnlyOnceAskedMakesNoProxyOnlyCopy() {
        var daemon = template();
        var request = new BranchFlow.Request("tpl-dev", "dev-2", false, false, NetworkMode.PROXY_ONLY,
                null, null, null, null, List.of(), false, Map.of());
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            var preflight = BranchFlow.preflight(daemon.client(), request, Map.of());
            // Incus refuses the NIC's ACL keys after preflight, as #1979's did: the copy request carries them
            daemon.apiExtensions();

            var e = assertThrows(RuntimeException.class, () -> BranchFlow.create(daemon.client(), preflight));

            assertTrue(e.getMessage().contains("security.acls"), e.getMessage());
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
        assertTrue(daemon.requests().contains("POST /1.0/instances"), "precondition: the copy was asked for");
        assertFalse(daemon.client().exists("dev-2"), "an unrestricted copy under the proxy-only name was left");
        assertTrue(daemon.client().exists("tpl-dev"), "the source stays");
    }

    @Test
    void aProxyOnlyCopyIsRestrictedFromItsCopyRequestOnEvenIfNothingAfterItRuns() {
        // A branch killed right after its copy runs no cleanup (#2009): the configuration write
        // fails, and so does the delete, which leaves the copy as a kill would
        var daemon = template().refuseWritesContaining("limits.memory").refuseDeletes();

        assertThrows(RuntimeException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));

        assertTrue(daemon.client().exists("dev-2"), "precondition: the copy was left");
        assertEquals(NetworkMode.PROXY_ONLY.name(),
                daemon.instance("dev-2").path("config").path(Metadata.NETWORK_MODE).asText(),
                "an isx start must know it is proxy-only");
        // So is an 'incus start', which knows nothing of the stamp
        assertRestricted(nic(daemon, "dev-2"));
        assertFalse(nic(daemon, "dev-2").has("ipv4.address"), "and it takes no address of its source's");
    }

    @Test
    void aProxyOnlyCopyOfAnAirgappedSourceHasItsNicBackRestrictedFromItsCopyRequest() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name(), Metadata.AIRGAP_MASKED, "eth0"));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));

        // Killed after its copy: what the copy request alone made
        daemon.refuseWritesContaining("limits.memory").refuseDeletes();
        assertThrows(RuntimeException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));

        var nic = nic(daemon, "dev-2");
        assertEquals("nic", nic.path("type").asText(), nic::toString);
        assertEquals("incusbr0", nic.path("network").asText(), nic::toString);
        assertRestricted(nic);
        assertFalse(daemon.instance("dev-2").path("config").has(Metadata.AIRGAP_MASKED));
        assertEquals("none", daemon.instance("tpl-dev").path("devices").path("eth0").path("type").asText(),
                "the source stays airgapped");
    }

    @Test
    void aBranchWithNetworkOfAProxyOnlyInstanceIsNotRestricted() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);
        daemon.client().rename("dev-2", "dev-1");

        branch(daemon, "dev-1", NetworkMode.FULL);

        var nic = nic(daemon, "dev-2");
        for (var key : ProxyOnlyAcl.NIC_KEYS.keySet()) assertFalse(nic.has(key), nic::toString);
        assertEquals("true", nic.path("security.ipv4_filtering").asText(), "the rest of the NIC is kept");
        assertRestricted(nic(daemon, "dev-1"));
    }

    /** A proxy-only instance from before #1137: its rules were inside the guest, and its NIC has no ACL. */
    private static FakeIncusDaemon unenforced(String status) {
        var daemon = new FakeIncusDaemon().instance("box", "container", status, Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        return daemon.ipFiltering("box", "true");
    }

    @Test
    void aProxyOnlyInstanceBranchedBeforeIsRestrictedBeforeItsNextStart() {
        var daemon = unenforced("Stopped");
        var out = new RecordingOutput();

        // FakeIncusDaemon serves no exec, so the readiness wait after the start can only time out
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(daemon.clientWithShortReadyWait(), "box",
                daemon.instance("box"), MachineType.CONTAINER, out));

        assertRestricted(nic(daemon, "box"));
        assertEquals(4, allowed(daemon).size());
        var requests = daemon.requests();
        int write = requests.lastIndexOf("PATCH /1.0/instances/box");
        int start = requests.indexOf("PUT /1.0/instances/box/state");
        assertTrue(write >= 0 && start > write, "restricted before the start:\n" + String.join("\n", requests));
        // A warning, which the TUI's log keeps: a step would go unsaid there
        assertTrue(out.warnings().stream().anyMatch(s -> s.contains("now enforced on the host")), out.warnings()::toString);
        // A start is a header, the steps of the start under it
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.HEADER, "Starting box")),
                out.events(RecordingOutput.Kind.HEADER));
    }

    @Test
    void theAclAllowsTheBridgesGatewayNotOneAnInstanceRecordedBeforeASubnetMove() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Running", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, "10.99.0.1"))
                .ipFiltering("box", "true");

        InstanceLifecycle.ensureReady(daemon.client(), "box", daemon.instance("box"),
                MachineType.CONTAINER, RecordingOutput.QUIET);

        // Shared by every proxy-only instance: a stale record must not take the proxy from the others
        assertTrue(allowed(daemon).stream().allMatch(rule -> rule.contains(" " + GATEWAY + " ")), allowed(daemon)::toString);
    }

    @Test
    void oneWhoseNetworkIsNotOnTheBridgeIsNotUsed() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        daemon.device("box", "eth0", Map.of("type", "nic", "network", "lanbr0", "name", "eth0"));

        assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));
        assertEquals(List.of(), daemon.stateActions());
    }

    @Test
    void oneWithNoNetworkAtAllIsWithinTheMode() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name()));
        daemon.device("box", "eth0", Map.of("type", "none"));

        InstanceLifecycle.prepareForStart(daemon.client(), "box",
                daemon.instance("box"), RecordingOutput.FAIL_ON_WARN);
    }

    @Test
    void aRunningOneIsRestrictedBeforeAShellOpensInIt() {
        var daemon = unenforced("Running");

        InstanceLifecycle.ensureReady(daemon.client(), "box", daemon.instance("box"),
                MachineType.CONTAINER, RecordingOutput.QUIET);

        assertRestricted(nic(daemon, "box"));
        assertEquals(List.of(), daemon.stateActions());
    }

    @Test
    void oneThatCannotBeRestrictedIsNotStarted() {
        var daemon = unenforced("Stopped").firewall("xtables");

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));

        assertTrue(e.getMessage().contains("nftables"), e.getMessage());
        assertEquals(List.of(), daemon.stateActions());
        assertFalse(nic(daemon, "box").has("security.acls"));
    }

    @Test
    void aRestrictedOneOnAHostThatMovedToXtablesIsRefusedWithTheReason() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);
        daemon.firewall("xtables");

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "dev-2", daemon.instance("dev-2"), MachineType.CONTAINER, RecordingOutput.QUIET));

        assertTrue(e.getMessage().contains("cannot be enforced on the host"), e.getMessage());
        assertTrue(e.getMessage().contains("uses xtables"), e.getMessage());
        assertEquals(List.of(), daemon.stateActions());
        assertRestricted(nic(daemon, "dev-2"));
    }

    @Test
    void aRestrictedInstanceCostsNoRequestBeyondTheReadTheRepairsShare() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);
        int before = daemon.requests().size();

        InstanceLifecycle.prepareForStart(daemon.client(), "dev-2",
                daemon.instance("dev-2"), RecordingOutput.FAIL_ON_WARN);

        assertEquals(List.of(), daemon.requests().subList(before, daemon.requests().size()));
    }

    @Test
    void anInstanceThatIsNotProxyOnlyIsLeftAlone() {
        var daemon = new FakeIncusDaemon().container("box", new HashMap<>()).ipFiltering("box", "true");

        InstanceLifecycle.prepareForStart(daemon.client(), "box",
                daemon.instance("box"), RecordingOutput.FAIL_ON_WARN);

        assertFalse(nic(daemon, "box").has("security.acls"));
        assertNull(daemon.networkAcl(ProxyOnlyAcl.NAME));
    }

    @Test
    void theSpoofingFallbackNeverStripsARestrictedNic() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertFalse(InstanceNetwork.disableIpFiltering(daemon.client(), "dev-2", RecordingOutput.FAIL_ON_WARN));

        assertEquals("true", nic(daemon, "dev-2").path("security.ipv4_filtering").asText());
        assertRestricted(nic(daemon, "dev-2"));
    }

    /** Only the bridge NIC carries the ACL: a second one would have full egress (#1137). */
    @Test
    void aSourceWithASecondNicGetsNoProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth1"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("eth1"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /**
     * With no bridge NIC there is nothing for the ACL to go on, so a source whose network is
     * elsewhere is refused before its copy: one left by an interrupted branch would have direct
     * network on a plain {@code incus start} (#2016).
     */
    @Test
    void aSourceWhoseOnlyNicIsOffTheBridgeGetsNoProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "eth0", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth0"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("not on incusbr0 (eth0)") && e.getMessage().contains("--airgap"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
        assertFalse(daemon.client().exists("dev-2"));
    }

    /** No network device at all is no network, which is within the mode: preflight does not refuse it. */
    @Test
    void aSourceWithNoNetworkAtAllPassesTheProxyOnlyPreflight() {
        var daemon = template();
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        var request = new BranchFlow.Request("tpl-dev", "dev-2", false, false, NetworkMode.PROXY_ONLY,
                null, null, null, null, List.of(), false, Map.of());
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.preflight(daemon.client(), request, Map.of());
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
        // Past the NIC check to its last step
        assertTrue(daemon.networkAcl(ProxyOnlyAcl.NAME) != null, "the ACL was not ensured");
    }

    @Test
    void aSourceWithASecondNicStillBranchesWithFullNetwork() {
        var daemon = template();
        daemon.device("tpl-dev", "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth1"));

        branch(daemon, "tpl-dev", NetworkMode.FULL);

        var branched = daemon.instance("dev-2").path("expanded_devices");
        assertEquals("macvlan", branched.path("eth1").path("nictype").asText(), branched::toString);
        for (var key : ProxyOnlyAcl.NIC_KEYS.keySet()) assertTrue(branched.path("eth0").path(key).isMissingNode(), branched::toString);
    }

    @Test
    void aProxyOnlyInstanceThatGainedASecondNicIsNotStarted() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        var restricted = new HashMap<String, String>(ProxyOnlyAcl.NIC_KEYS);
        restricted.putAll(Map.of("type", "nic", "network", "incusbr0", "name", "eth0"));
        daemon.device("box", "eth0", restricted);
        daemon.device("box", "eth1", Map.of("type", "nic", "network", "incusbr0", "name", "eth1"));

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));
        assertTrue(e.getMessage().contains("eth1"), e::getMessage);
        assertEquals(List.of(), daemon.stateActions());
    }

    /** An airgapped source's NICs are masked, but the branch puts the profile's back: both count. */
    @Test
    void anAirgappedSourceWhoseProfileHasASecondNicGetsNoProxyOnlyBranch() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth1"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name()));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        daemon.device("tpl-dev", "eth1", Map.of("type", "none"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("eth1"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /** Unmasking puts a profile's InfiniBand device back too, so it counts as a second one. */
    @Test
    void anAirgappedSourceWhoseProfileHasInfinibandGetsNoProxyOnlyBranch() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name(), Metadata.AIRGAP_MASKED, "eth0,ib0"));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        daemon.device("tpl-dev", "ib0", Map.of("type", "none"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("ib0"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /** A mask the template set itself is not airgap's, so unmasking leaves it and it does not count. */
    @Test
    void anAirgappedSourceWhoseTemplateMasksTheProfilesInfinibandGetsAProxyOnlyBranch() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name(), Metadata.AIRGAP_MASKED, "eth0"));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        daemon.device("tpl-dev", "ib0", Map.of("type", "none"));

        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertRestricted(nic(daemon, "dev-2"));
        assertEquals("none", daemon.instance("dev-2").path("devices").path("ib0").path("type").asText());
    }

    /** An InfiniBand device reaches a network without any NIC, so it counts as a second one. */
    @Test
    void aSourceWithAnInfinibandDeviceGetsNoProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("ib0"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /** With no bridge NIC, an InfiniBand device is still a way out: the instance is not started. */
    @Test
    void aProxyOnlyInstanceWhoseOnlyNetworkIsInfinibandIsNotStarted() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        daemon.device("box", "eth0", Map.of("type", "none"));
        daemon.device("box", "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"));

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));
        assertTrue(e.getMessage().contains("a network device that is not on incusbr0 (ib0)"), e::getMessage);
        assertEquals(List.of(), daemon.stateActions());
    }

    @Test
    void aSourceWithAProxyThatBindsInTheInstanceGetsNoProxyOnlyBranch() {
        // #1518: the host's end of it connects out past the ACL on the NIC
        var daemon = template();
        daemon.device("tpl-dev", "fwd", Map.of("type", "proxy", "bind", "instance",
                "listen", "tcp:127.0.0.1:8080", "connect", "tcp:192.0.2.1:80"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("fwd"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    @Test
    void aSourceWithAProxyThatBindsOnTheHostGetsAProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "ssh", Map.of("type", "proxy",
                "listen", "tcp:127.0.0.1:2222", "connect", "tcp:127.0.0.1:22"));

        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertRestricted(nic(daemon, "dev-2"));
    }

    @Test
    void aSourceWithAPciDeviceGetsNoProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "card0", Map.of("type", "pci", "address", "0000:03:00.0"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("card0") && e.getMessage().contains("'gpu' device"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    @Test
    void aProxyOnlyInstanceThatGainedAUsbDeviceIsNotStarted() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        var restricted = new HashMap<String, String>(ProxyOnlyAcl.NIC_KEYS);
        restricted.putAll(Map.of("type", "nic", "network", "incusbr0", "name", "eth0"));
        daemon.device("box", "eth0", restricted);
        daemon.device("box", "dongle", Map.of("type", "usb", "vendorid", "0bda", "productid", "8153"));

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));
        assertTrue(e.getMessage().contains("dongle"), e::getMessage);
        assertEquals(List.of(), daemon.stateActions());
    }
}
