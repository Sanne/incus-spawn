package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

/** A registered loopback guest. Tests still send its proof through the real request handler. */
final class ProofTestSupport {
    static final String SECRET = "42".repeat(32);

    static String token(String namespace, String prefix) {
        return prefix + ProofToken.MARKER + ProofToken.derive(SECRET, namespace);
    }

    static void register(MitmProxy proxy) {
        var registry = new InstanceRegistry(new IncusClient() {
            @Override public JqValue listJsonConfig() {
                var config = JqObject.builder(2).put(Metadata.STATIC_IP, "127.0.0.1")
                        .put(Metadata.INSTANCE_PROOF_SHA256, ProofVerifier.stamp(SECRET, ProofToken.declared())).build();
                return JqValues.parse("[" + JqValues.toPrettyJsonString(JqObject.builder(2)
                        .put("name", "test-guest").put("config", config).build()) + "]");
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);
    }
}
