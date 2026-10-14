package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CredentialProof;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDef;

import java.util.LinkedHashMap;

/** Explicit revocation: metadata is authoritative and success requires proxy acknowledgement. */
public final class CredentialProofReset {
    private CredentialProofReset() {}

    @FunctionalInterface
    public interface Confirmation {
        boolean await(String instance, String generation, String verifierHash);
    }

    public static void reset(IncusClient incus, String name) {
        var address = ProxyHealthCheck.healthAddress(incus);
        reset(incus, name, (instance, generation, hash) ->
                ProxyHealthCheck.awaitCredentialProofs(address, instance, generation, hash));
    }

    public static void reset(IncusClient incus, String name, Confirmation confirmation) {
        try (var ignored = CredentialProof.lock(name)) {
            var instance = incus.instanceMetadataOrThrow(name);
            var config = instance.path("config");
            if (!Metadata.TYPE_CLONE.equals(config.pathText(Metadata.TYPE, ""))) {
                throw new IllegalArgumentException("Proof reset requires a branched isx instance, not a template or build");
            }
            var placeholders = ProofToken.declared();
            var seed = CredentialProof.fresh();
            var updates = new LinkedHashMap<String, String>();
            CredentialProof.stampInto(updates, seed, placeholders);
            // Keep recovery pending until delivery succeeds; stopped instances receive proofs on start.
            updates.put(Metadata.INSTANCE_SECRET_BOOT, "");
            incus.configSetAll(name, updates);
            ProxyService.signalAccountRefresh();
            var hash = ToolDef.sha256hex(updates.get(Metadata.INSTANCE_PROOF_SHA256));
            if (!confirmation.await(name, seed.generation(), hash)) {
                throw new IllegalStateException("New proof state was saved, but proxy acknowledgement failed; revocation is not confirmed. "
                        + "Check or upgrade isx-proxy and retry 'isx account reset-proofs " + name + "'.");
            }
            if ("Running".equalsIgnoreCase(instance.pathText("status", ""))) {
                IncusClient.ExecResult result;
                try {
                    result = incus.shellExec(name, InstanceSecret.proofEnv(seed, placeholders),
                            "sh", "-c", InstanceSecret.RESET_PROOFS_SCRIPT);
                } catch (RuntimeException e) {
                    throw deliveryFailed(name, e);
                }
                if (!result.success()) {
                    throw deliveryFailed(name, null);
                }
                var boot = instance.pathText("last_used_at", "");
                // Reset only delivers credential material. Never mark a raw reboot as fully
                // provisioned while its MCP/host-service secret still needs recovery.
                var previousDelivery = InstanceLifecycle.secretBootStamp(boot, config.pathText(Metadata.INSTANCE_PROOF_ID, ""));
                if (!boot.isEmpty() && "container".equals(instance.pathText("type", "container"))
                        && previousDelivery.equals(config.pathText(Metadata.INSTANCE_SECRET_BOOT, ""))) {
                    incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, InstanceLifecycle.secretBootStamp(boot, seed.generation()));
                }
            }
        }
    }

    private static IllegalStateException deliveryFailed(String name, RuntimeException cause) {
        return new IllegalStateException("Previous proofs were revoked, but delivery failed; run 'isx shell " + name
                + "' to restore proofs, then restart tools and nested services with their new tokens.", cause);
    }
}
