package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProofVerifierTest {
    static final String SECRET = "12".repeat(32);
    static final List<ProofToken.Placeholder> DECLARED = List.of(
            new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"),
            new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude"),
            new ProofToken.Placeholder("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-", "claude"),
            new ProofToken.Placeholder("ISX_VERTEX_ACCESS_TOKEN", "ya29.", "claude"));

    @Test
    void onlyTheCurrentNamespaceDigestMatchesItsHash() {
        var stamp = ProofVerifier.stamp(SECRET, DECLARED);
        var hashes = ProofVerifier.parse(stamp);
        assertEquals(2, hashes.size());
        assertFalse(stamp.contains(SECRET));
        for (var placeholder : DECLARED) {
            var digest = ProofToken.derive(SECRET, placeholder.namespace());
            var hash = hashes.get(placeholder.namespace());
            assertTrue(ProofVerifier.matches(digest, hash));
            assertFalse(stamp.contains(digest));
            assertFalse(ProofVerifier.matches(ProofToken.derive("34".repeat(32), placeholder.namespace()), hash));
            assertFalse(ProofVerifier.matches(ProofToken.derive(InstanceSecret.sha256(SECRET), placeholder.namespace()), hash),
                    "the /dev/incus-readable secret hash cannot derive a proof");
        }
        assertFalse(ProofVerifier.matches(ProofToken.derive(SECRET, "claude"), hashes.get("github")));
        assertEquals(Map.of(), ProofVerifier.parse("github:bad"));
    }

    @Test
    void registryBindsProofsToTheirAddressAndReplacesRemovedNamespacesOnRefresh() {
        var stamp = new String[]{ProofVerifier.stamp(SECRET, DECLARED)};
        var unavailable = new boolean[1];
        var registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                if (unavailable[0]) throw new IllegalStateException("test daemon unavailable");
                var config = JqObject.builder(2).put(Metadata.STATIC_IP, "10.1.2.3")
                        .put(Metadata.INSTANCE_PROOF_SHA256, stamp[0]).build();
                return JqValues.parse("[" + JqValues.toPrettyJsonString(JqObject.builder(2)
                        .put("name", "guest").put("config", config).build()) + "]");
            }
        });
        registry.refresh();
        var proofs = Map.of("github", List.of(ProofToken.derive(SECRET, "github")));
        assertNotNull(registry.prove("10.1.2.3", proofs));
        assertNull(registry.prove("10.1.2.4", proofs));
        assertNull(registry.prove(null, proofs));
        assertNull(registry.prove("10.1.2.3", Map.of()));
        stamp[0] = ProofVerifier.stamp("34".repeat(32), DECLARED.subList(1, 4));
        registry.refresh();
        assertNull(registry.prove("10.1.2.3", proofs));
        assertNull(registry.prove("10.1.2.3", Map.of("claude", List.of(ProofToken.derive(SECRET, "claude")))));
        assertNotNull(registry.prove("10.1.2.3", Map.of("claude", List.of(ProofToken.derive("34".repeat(32), "claude")))));
        unavailable[0] = true;
        assertFalse(registry.refresh());
        assertNotNull(registry.lookup("10.1.2.3"), "account attribution survives a transient daemon failure");
        assertNull(registry.prove("10.1.2.3", Map.of("claude", List.of(ProofToken.derive("34".repeat(32), "claude")))),
                "cached verifiers cannot authorize after a failed refresh");
    }

    /**
     * The proxy's one client could not reach Incus when it started, as a Mac's VM still booting:
     * once Incus answers, a refresh lists again and proofs are accepted, with no restart (#2386).
     */
    @Test
    void proofsAreAcceptedAgainOnceIncusAnswersAfterAFailedFirstConnection() {
        var up = new java.util.concurrent.atomic.AtomicBoolean();
        var now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .instance("guest", "container", "Running", Map.of(Metadata.STATIC_IP, "10.1.2.3",
                        Metadata.INSTANCE_PROOF_SHA256, ProofVerifier.stamp(SECRET, DECLARED)));
        var registry = new InstanceRegistry(daemon.clientReachableWhen(up::get, now::get), now::get);
        var proofs = Map.of("github", List.of(ProofToken.derive(SECRET, "github")));

        assertFalse(registry.refresh(), "Incus does not answer yet");
        assertNull(registry.prove("10.1.2.3", proofs));
        assertTrue(registry.listingProblem().matches("The listing at \\d\\d:\\d\\d:\\d\\d failed: the Incus API did not answer"),
                "what isx proxy status shows: " + registry.listingProblem());

        up.set(true);
        now.addAndGet(60_000);
        assertTrue(registry.refresh(), "the same client connects now");
        assertNotNull(registry.prove("10.1.2.3", proofs));
        assertNull(registry.listingProblem());
    }

    @Test
    void whereTheAddressNamesNoInstanceTheProofAloneIdentifiesTheCaller() {
        // macOS: every container reaches the proxy from the VM's address (#1582)
        var other = "56".repeat(32);
        var unavailable = new boolean[1];
        var registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                if (unavailable[0]) throw new IllegalStateException("test daemon unavailable");
                return JqValues.parse("[" + guest("guest", "10.1.2.3", SECRET, "Running") + ","
                        + guest("neighbour", "10.1.2.5", other, "Running") + "]");
            }
        });
        registry.refresh();
        var vm = "192.168.64.21";
        var mine = Map.of("github", List.of(ProofToken.derive(SECRET, "github")),
                "claude", List.of(ProofToken.derive(SECRET, "claude")));
        assertNull(registry.prove(vm, mine), "the address check stays the default");
        assertNull(registry.prove(vm, mine, false));
        assertEquals("guest", registry.prove(vm, mine, true).accounts().instanceName());
        assertEquals("neighbour", registry.prove(vm, Map.of("github",
                List.of(ProofToken.derive(other, "github"))), true).accounts().instanceName());
        assertNull(registry.prove(vm, Map.of("github", List.of(ProofToken.derive(SECRET, "github")),
                "claude", List.of(ProofToken.derive(other, "claude"))), true), "proofs of two instances name none");
        assertNull(registry.prove(vm, Map.of("github", List.of(ProofToken.derive("34".repeat(32), "github"))), true));
        assertNull(registry.prove(vm, Map.of("github", List.of(ProofToken.derive(SECRET, "claude"))), true),
                "a proof counts only for its own namespace");
        assertNull(registry.prove("10.1.2.5", mine, true), "a known address is held to its own instance's proofs");
        unavailable[0] = true;
        assertFalse(registry.refresh());
        assertNull(registry.prove(vm, mine, true), "cached verifiers cannot authorize after a failed refresh");
    }

    @Test
    void aProofSharedByACopyIdentifiesOnlyTheOneRunning() {
        // Only an incus copy made outside isx carries another instance's verifiers
        var status = new String[]{"Stopped"};
        var registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                return JqValues.parse("[" + guest("guest", "10.1.2.3", SECRET, "Running") + ","
                        + guest("copy", "10.1.2.4", SECRET, status[0]) + "]");
            }
        });
        registry.refresh();
        var proofs = Map.of("github", List.of(ProofToken.derive(SECRET, "github")));
        assertEquals("guest", registry.prove("192.168.64.21", proofs, true).accounts().instanceName());
        status[0] = "Running";
        registry.refresh();
        assertNull(registry.prove("192.168.64.21", proofs, true), "two running instances: refuse rather than guess");
        assertEquals("copy", registry.prove("10.1.2.4", proofs, true).accounts().instanceName(),
                "the address still identifies each of them");
    }

    @Test
    void aStoppedInstancesProofIdentifiesNobodyByProofAlone() {
        // A stopped instance sends nothing: its proof arriving from elsewhere is a copy's
        var status = new String[]{"Stopped"};
        var registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                return JqValues.parse("[" + guest("guest", "10.1.2.3", SECRET, status[0]) + "]");
            }
        });
        registry.refresh();
        var proofs = Map.of("github", List.of(ProofToken.derive(SECRET, "github")));
        assertNull(registry.prove("192.168.64.21", proofs, true));
        assertNotNull(registry.prove("10.1.2.3", proofs, true), "its own address is checked as before");
        status[0] = "Running";
        registry.refresh();
        assertEquals("guest", registry.prove("192.168.64.21", proofs, true).accounts().instanceName());
    }

    @Test
    void aRunningCopyOfAStoppedInstanceIsTheOneItsSharedProofNames() {
        var registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                return JqValues.parse("[" + guest("guest", "10.1.2.3", SECRET, "Stopped") + ","
                        + guest("copy", "10.1.2.4", SECRET, "Running") + "]");
            }
        });
        registry.refresh();
        var proofs = Map.of("github", List.of(ProofToken.derive(SECRET, "github")));
        assertEquals("copy", registry.prove("192.168.64.21", proofs, true).accounts().instanceName());
    }

    private static String guest(String name, String address, String secret, String status) {
        var config = JqObject.builder(2).put(Metadata.STATIC_IP, address)
                .put(Metadata.INSTANCE_PROOF_SHA256, ProofVerifier.stamp(secret, DECLARED)).build();
        return JqValues.toPrettyJsonString(JqObject.builder(3)
                .put("name", name).put("status", status).put("config", config).build());
    }
}
