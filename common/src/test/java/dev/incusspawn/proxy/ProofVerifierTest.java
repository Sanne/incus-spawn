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
}
