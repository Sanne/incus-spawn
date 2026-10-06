package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every isx start gives the instance a new secret (#934): its hash recorded before the start,
 * the secret itself put in place once the guest answers, and the proxy told.
 */
@ExtendWith(TempHome.class)
class InstanceSecretRotationTest {

    private static final String NAME = "box";

    private static String recordedHash(FakeIncusDaemon daemon, String name) {
        return daemon.instance(name).path("config").path(Metadata.INSTANCE_SECRET_SHA256).asText("");
    }

    /** The secret an exec hands the guest, or null. */
    private static String secretIn(FakeIncusDaemon.Exec exec) {
        var secret = exec.environment().get(InstanceSecret.DELIVERY_ENV);
        return secret != null && String.join(" ", exec.command()).contains(InstanceSecret.GUEST_SCRIPT) ? secret : null;
    }

    @Test
    void aStoppedStartRotatesTheSecretAndTheReadinessProbeDeliversIt() {
        var previous = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.INSTANCE_SECRET_SHA256, previous))
                .ipFiltering(NAME, "true");
        int signalsBefore = TempHome.proxySignals();

        // FakeIncusDaemon serves no exec, so the wait can only time out; what matters is what
        // was asked of Incus on the way
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {}));

        var recorded = recordedHash(daemon, NAME);
        assertNotEquals(previous, recorded, "a start must retire the previous start's secret");
        var requests = daemon.requests();
        int write = requests.indexOf("PATCH /1.0/instances/" + NAME);
        int start = requests.indexOf("PUT /1.0/instances/" + NAME + "/state");
        assertTrue(write >= 0 && start > write, "recorded before the guest can run:\n" + String.join("\n", requests));
        assertEquals(signalsBefore, TempHome.proxySignals(),
                "signalling the proxy costs every start a bridge read, a health call and a fork");

        var probes = daemon.execs();
        assertFalse(probes.isEmpty(), "the readiness probe ran");
        for (var probe : probes) {
            assertEquals(List.of("sh", "-c"), probe.command().subList(0, 2));
            var secret = secretIn(probe);
            assertNotNull(secret, "every probe carries the secret: any one of them may be the first to land");
            assertEquals(recorded, InstanceSecret.sha256(secret), "the guest gets the secret of the recorded hash");
            assertFalse(String.join(" ", probe.command()).contains(secret),
                    "any guest user can read a command line: the secret travels in the environment");
            assertTrue(probe.command().get(2).endsWith("\necho ready"), probe.command().get(2));
        }
        assertFalse(daemon.instance(NAME).path("config").toString().contains(secretIn(probes.getFirst())),
                "the guest can read its own user.* keys: the secret itself is never stored there");
    }

    @Test
    void everyStartMakesANewSecret() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var incus = daemon.client();
        var first = InstanceLifecycle.rotateInstanceSecret(incus, NAME);
        var firstHash = recordedHash(daemon, NAME);
        var second = InstanceLifecycle.rotateInstanceSecret(incus, NAME);

        assertNotEquals(first, second);
        assertNotEquals(firstHash, recordedHash(daemon, NAME));
        assertEquals(InstanceSecret.sha256(second), recordedHash(daemon, NAME));
    }

    @Test
    void aBranchHasItsOwnSecretFromTheCopyOn() {
        // A branch of a branch: the copy would otherwise carry its source's hash
        var sourceHash = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().container("dev-1",
                Map.of(Metadata.PROFILE, "tpl-dev", Metadata.INSTANCE_SECRET_SHA256, sourceHash));
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));

        var branchHash = recordedHash(daemon, "dev-2");
        assertTrue(branchHash.matches("[0-9a-f]{64}"), branchHash);
        assertNotEquals(sourceHash, branchHash);
        assertEquals(sourceHash, recordedHash(daemon, "dev-1"), "the source keeps its own");
    }

    @Test
    void theBranchSetupScriptDeliversTheSecret() {
        var script = InstanceLifecycle.buildSetupScript(null, null, NetworkMode.FULL, List.of(), true);
        assertTrue(script.contains(InstanceSecret.GUEST_SCRIPT), script);
        assertFalse(InstanceLifecycle.buildSetupScript(null, null, NetworkMode.FULL, List.of())
                .contains(InstanceSecret.GUEST_PATH), "no secret, no delivery");
    }

    @Test
    void aRestartedVmGetsANewSecretToo() {
        var previous = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running",
                Map.of(Metadata.INSTANCE_SECRET_SHA256, previous));
        assertThrows(IncusException.class,
                () -> VmAgentRecovery.restartForAgent(daemon.clientWithShortReadyWait(), NAME, msg -> {}));

        var recorded = recordedHash(daemon, NAME);
        assertNotEquals(previous, recorded);
        var delivered = daemon.execs().stream()
                .map(InstanceSecretRotationTest::secretIn).filter(java.util.Objects::nonNull).toList();
        assertFalse(delivered.isEmpty(), "the probe after the restart carries the secret");
        assertEquals(recorded, InstanceSecret.sha256(delivered.getLast()));
    }

    @Test
    void aRestartFromTheTuiRotatesTheSecretToo() {
        // The reboot empties the guest's /run: a restart that kept the hash would leave the box
        // with no secret at all
        var previous = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running",
                Map.of(Metadata.INSTANCE_SECRET_SHA256, previous));
        assertThrows(IncusException.class, () -> InstanceLifecycle.restartForUse(
                daemon.clientWithShortReadyWait(), NAME, MachineType.CONTAINER, false));

        var recorded = recordedHash(daemon, NAME);
        assertNotEquals(previous, recorded);
        assertEquals(List.of(NAME + " restart"), daemon.stateActions());
        assertEquals(recorded, InstanceSecret.sha256(secretIn(daemon.execs().getFirst())));
    }

    /**
     * The exec that delivers a start's secret carries the proof of every declared placeholder
     * (#1106), derived from that same secret: one exec, nothing the start did not already send.
     */
    @Test
    void aStartsProbeCarriesTheProofOfEveryDeclaredPlaceholder() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, daemon.instance(NAME), MachineType.CONTAINER, msg -> {}));

        var declared = ProofToken.declared();
        assertTrue(declared.stream().map(ProofToken.Placeholder::namespace).collect(java.util.stream.Collectors.toSet())
                .containsAll(List.of("github", "claude", "openai", "bob")), declared.toString());
        var probes = daemon.execs();
        assertFalse(probes.isEmpty());
        for (var probe : probes) {
            var secret = secretIn(probe);
            assertProofsFor(secret, declared, probe.environment().get(InstanceSecret.PROOFS_DELIVERY_ENV));
            assertFalse(String.join(" ", probe.command()).contains(ProofToken.derive(secret, "github")),
                    "a proof opens a credential: it travels in the environment, not on a command line");
        }
    }

    @Test
    void aBranchsFirstStartCarriesItsOwnProofs() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev"));
        var incus = org.mockito.Mockito.spy(daemon.client());
        var env = org.mockito.ArgumentCaptor.<Map<String, String>>captor();
        org.mockito.Mockito.doReturn(true).when(incus).pollUntilReady(org.mockito.ArgumentMatchers.eq("dev-2"),
                org.mockito.ArgumentMatchers.anyInt(), env.capture(), org.mockito.ArgumentMatchers.any(String[].class));
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), true, Map.of());
        try {
            BranchFlow.create(incus, BranchFlow.preflight(incus, request, Map.of()));
        } catch (RuntimeException afterTheSetupScript) {
            // What follows the setup exec is not what this test is about
        }

        var delivered = env.getAllValues().stream().filter(e -> e.containsKey(InstanceSecret.DELIVERY_ENV)).toList();
        assertFalse(delivered.isEmpty(), "the branch's setup script carries its secret");
        var secret = delivered.getFirst().get(InstanceSecret.DELIVERY_ENV);
        assertEquals(recordedHash(daemon, "dev-2"), InstanceSecret.sha256(secret));
        assertProofsFor(secret, ProofToken.declared(), delivered.getFirst().get(InstanceSecret.PROOFS_DELIVERY_ENV));
    }

    private static void assertProofsFor(String secret, List<ProofToken.Placeholder> declared, String exports) {
        assertNotNull(exports, "the probe that delivers the secret delivers its proofs");
        for (var placeholder : declared) {
            var line = "if [ -n \"${" + placeholder.env() + "+x}\" ]; then export " + placeholder.env() + "='"
                    + placeholder.prefix() + ProofToken.MARKER + ProofToken.derive(secret, placeholder.namespace()) + "'; fi";
            assertTrue(exports.lines().anyMatch(line::equals), () -> "no proof for " + placeholder + " in:\n" + exports);
        }
        assertEquals(declared.size(), exports.lines().count(), exports);
        assertFalse(exports.contains(secret), "never the secret itself");
    }
}
