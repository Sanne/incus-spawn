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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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

    @Test void aPlainCopyNeverInheritsAnyCredentialGrant() {
        var config = new LinkedHashMap<String, String>();
        InstanceSecret.stampInto(config);
        config.put(Metadata.INSTANCE_SECRET_BOOT, "old boot");
        var daemon = new FakeIncusDaemon().container("source", config);
        daemon.client().copy("source", "copy");
        var copied = daemon.instance("copy").path("config");
        for (var key : List.of(Metadata.INSTANCE_PROOF_ID, Metadata.INSTANCE_PROOF_SHA256,
                Metadata.INSTANCE_SECRET_SHA256, Metadata.INSTANCE_SECRET_BOOT)) {
            assertEquals("", copied.path(key).asText(""), key);
            assertFalse(daemon.instance("source").path("config").path(key).asText("").isBlank());
        }
    }

    @Test void aPrefetchedStartCannotRestoreAResetGrant() {
        var config = new LinkedHashMap<String, String>();
        InstanceSecret.stampInto(config);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        var daemon = new FakeIncusDaemon().container("box", config);
        var beforeReset = JacksonTrees.fromJsonNode(daemon.instance("box"));
        CredentialProofReset.reset(daemon.client(), "box", (name, generation, hash) -> true);
        var after = daemon.instance("box").path("config").deepCopy();
        var delivered = InstanceLifecycle.rotateInstanceSecret(daemon.client(), "box", beforeReset, Map.of(), ProofToken.declared());
        var current = daemon.instance("box").path("config");
        assertEquals(after.path(Metadata.INSTANCE_PROOF_ID), current.path(Metadata.INSTANCE_PROOF_ID));
        assertEquals(after.path(Metadata.INSTANCE_PROOF_SHA256), current.path(Metadata.INSTANCE_PROOF_SHA256));
        assertEquals(current.path(Metadata.INSTANCE_PROOF_ID).asText(), delivered.proofGeneration());
        assertEquals(current.path(Metadata.INSTANCE_PROOF_SHA256).asText(),
                ProofVerifier.stamp(delivered.proofSecret(), ProofToken.declared()));
    }

    @Test void aResetDuringBootDeliversTheReplacementProofAndRecordsThatGeneration() {
        var config = new LinkedHashMap<String, String>();
        var original = InstanceSecret.stampInto(config);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        var daemon = new FakeIncusDaemon().container("box", config).ipFiltering("box", "true");
        var incus = spy(daemon.client());
        doAnswer(inv -> {
            // Reset after the start's metadata write but before the readiness/delivery exec.
            CredentialProofReset.reset(daemon.client(), "box", (name, generation, hash) -> true);
            return inv.callRealMethod();
        }).when(incus).start("box");
        doAnswer(inv -> {
            Map<String, String> env = inv.getArgument(3);
            var current = daemon.instance("box").path("config");
            var seed = env.get(CredentialProof.DELIVERY_ENV);
            assertNotEquals(original.proofSecret(), seed);
            assertEquals(current.path(Metadata.INSTANCE_PROOF_SHA256).asText(), ProofVerifier.stamp(seed, ProofToken.declared()));
            assertEquals(current.path(Metadata.INSTANCE_SECRET_SHA256).asText(),
                    InstanceSecret.sha256(env.get(InstanceSecret.DELIVERY_ENV)));
            return null;
        }).when(incus).waitForReady(eq("box"), eq(MachineType.CONTAINER), anyString(), anyMap());
        InstanceLifecycle.startForUse(incus, "box", MachineType.CONTAINER, msg -> {});
        var current = daemon.instance("box");
        assertEquals(InstanceLifecycle.secretBootStamp(current.path("last_used_at").asText(),
                current.path("config").path(Metadata.INSTANCE_PROOF_ID).asText()),
                current.path("config").path(Metadata.INSTANCE_SECRET_BOOT).asText());
    }

    @Test void resetWaitsUntilGuestDeliveryHasFinished() throws Exception {
        var config = new LinkedHashMap<String, String>();
        var planned = InstanceSecret.stampInto(config);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        var daemon = new FakeIncusDaemon().container("box", config);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var resetAttempted = new CountDownLatch(1);
        var acknowledged = new CountDownLatch(1);
        var delivery = CompletableFuture.runAsync(() -> InstanceLifecycle.withCurrentProofs(daemon.client(), "box", planned, current -> {
            entered.countDown();
            try { assertTrue(finish.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return null;
        }));
        CompletableFuture<Void> reset = null;
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            reset = CompletableFuture.runAsync(() -> {
                resetAttempted.countDown();
                CredentialProofReset.reset(daemon.client(), "box", (name, generation, hash) -> {
                    acknowledged.countDown();
                    return true;
                });
            });
            assertTrue(resetAttempted.await(10, TimeUnit.SECONDS));
            assertFalse(acknowledged.await(150, TimeUnit.MILLISECONDS), "reset must not overtake delivery");
        } finally {
            finish.countDown();
            delivery.get(10, TimeUnit.SECONDS);
            if (reset != null) reset.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, acknowledged.getCount());
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
