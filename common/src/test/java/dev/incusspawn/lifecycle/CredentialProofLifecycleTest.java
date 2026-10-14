package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.JacksonTrees;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CredentialProof;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ProofVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TempHome.class)
class CredentialProofLifecycleTest {
    @Test void startsRotateOnlyTheBootSecretAndPreserveTheCredentialGrant() {
        var daemon = new FakeIncusDaemon().container("box", Map.of());
        var first = InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box");
        var stamp = daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_SHA256).asText();
        var second = InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box");
        assertNotEquals(first.secret(), second.secret());
        assertEquals(first.proofSecret(), second.proofSecret());
        assertEquals(first.proofGeneration(), second.proofGeneration());
        assertEquals(stamp, daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_SHA256).asText());
        assertNotEquals(second.secret(), second.proofSecret());
    }

    @Test void aCopyHasANewProofBeforeItCanRun() {
        var source = new LinkedHashMap<String, String>();
        var first = InstanceSecret.stampInto(source);
        var copy = new LinkedHashMap<>(source);
        var second = InstanceSecret.stampInto(copy);
        assertNotEquals(first.proofGeneration(), second.proofGeneration());
        assertNotEquals(first.proofSecret(), second.proofSecret());
        assertNotEquals(source.get(Metadata.INSTANCE_PROOF_SHA256), copy.get(Metadata.INSTANCE_PROOF_SHA256));
    }

    @Test void aPrefetchedStartCannotRestoreAResetGrant() {
        var config = new LinkedHashMap<String, String>();
        InstanceSecret.stampInto(config);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        var daemon = new FakeIncusDaemon().container("box", config);
        var beforeReset = JacksonTrees.fromJsonNode(daemon.instance("box"));
        CredentialProofReset.reset(daemon.client(), "box", (name, generation, hash) -> true);
        var after = daemon.instance("box").path("config").deepCopy();
        InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box", beforeReset, Map.of(), ProofToken.declared());
        var current = daemon.instance("box").path("config");
        assertEquals(after.path(Metadata.INSTANCE_PROOF_ID), current.path(Metadata.INSTANCE_PROOF_ID));
        assertEquals(after.path(Metadata.INSTANCE_PROOF_SHA256), current.path(Metadata.INSTANCE_PROOF_SHA256));
    }

    @Test void migrationAndChangedDeclarationsRereadBeforeWritingProofState() {
        var daemon = new FakeIncusDaemon().container("box", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var beforeMigration = JacksonTrees.fromJsonNode(daemon.instance("box"));
        var first = InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box");
        CredentialProofReset.reset(daemon.client(), "box", (name, generation, hash) -> true);
        var currentId = daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_ID).asText();
        var started = InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box", beforeMigration, Map.of(), ProofToken.declared());
        assertEquals(currentId, started.proofGeneration());
        assertNotEquals(first.proofSecret(), started.proofSecret());

        var instance = JacksonTrees.fromJsonNode(daemon.instance("box"));
        var changed = java.util.List.of(new ProofToken.Placeholder("NEW_TOKEN", "new_", "new"));
        var updated = InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box", instance, Map.of(), changed);
        assertEquals(started.proofSecret(), updated.proofSecret());
        var verifiers = ProofVerifier.parse(daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_SHA256).asText());
        assertEquals(java.util.Set.of("new"), verifiers.keySet());
        assertTrue(ProofVerifier.matches(ProofToken.derive(updated.proofSecret(), "new"), verifiers.get("new")));
    }
}
