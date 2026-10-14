package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CredentialProof;
import dev.incusspawn.proxy.InstanceSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(TempHome.class)
class CredentialProofResetTest {
    private FakeIncusDaemon instance(String status) {
        var config = new LinkedHashMap<String, String>();
        InstanceSecret.stampInto(config);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        return new FakeIncusDaemon().instance("box", "container", status, config);
    }

    @Test void resetWaitsForThePersistedGenerationBeforeDeliveringAndKeepsTheMcpSecret() {
        var daemon = instance("Running");
        var old = daemon.instance("box").path("config").deepCopy();
        var incus = spy(daemon.client());
        var confirmed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            assertTrue(confirmed.get());
            Map<String, String> env = call.getArgument(1);
            assertFalse(env.containsKey(InstanceSecret.DELIVERY_ENV));
            assertNotNull(env.get(CredentialProof.DELIVERY_ENV));
            return new IncusClient.ExecResult(0, "", "");
        }).when(incus).shellExec(eq("box"), anyMap(), any(String[].class));
        CredentialProofReset.reset(incus, "box", (name, generation, hash) -> {
            var stored = daemon.instance("box").path("config");
            assertEquals(stored.path(Metadata.INSTANCE_PROOF_ID).asText(), generation);
            assertEquals(dev.incusspawn.tool.ToolDef.sha256hex(stored.path(Metadata.INSTANCE_PROOF_SHA256).asText()), hash);
            assertNotEquals(old.path(Metadata.INSTANCE_PROOF_ID).asText(), generation);
            assertEquals(1, TempHome.proxySignals());
            confirmed.set(true);
            return true;
        });
        assertEquals(old.path(Metadata.INSTANCE_SECRET_SHA256), daemon.instance("box").path("config").path(Metadata.INSTANCE_SECRET_SHA256));
        verify(incus).shellExec(eq("box"), anyMap(), any(String[].class));
    }

    @Test void anUnavailableOrOldProxyNeverProducesSuccessfulRevocation() {
        var daemon = instance("Stopped");
        var oldId = daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_ID).asText();
        var error = assertThrows(IllegalStateException.class, () -> CredentialProofReset.reset(daemon.client(), "box",
                (name, generation, hash) -> false));
        assertTrue(error.getMessage().contains("revocation is not confirmed"));
        assertNotEquals(oldId, daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_ID).asText());
        assertTrue(daemon.execs().isEmpty());
        CredentialProofReset.reset(daemon.client(), "box", (name, generation, hash) -> true);
        assertTrue(daemon.execs().isEmpty(), "a stopped instance is delivered on its next start");
    }

    @Test void failedDeliveryLeavesRecoveryPendingAndNeverRestoresTheRevokedGrant() {
        var daemon = instance("Running");
        var oldId = daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_ID).asText();
        var incus = spy(daemon.client());
        doReturn(new IncusClient.ExecResult(1, "", "")).when(incus).shellExec(eq("box"), anyMap(), any(String[].class));
        var error = assertThrows(IllegalStateException.class, () -> CredentialProofReset.reset(incus, "box", (n, g, h) -> true));
        assertTrue(error.getMessage().contains("Previous proofs were revoked"));
        assertNotEquals(oldId, daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_ID).asText());
        assertEquals("", daemon.instance("box").path("config").path(Metadata.INSTANCE_SECRET_BOOT).asText());
    }

    @Test void templatesCannotAcquireAGrantThroughReset() {
        var daemon = new FakeIncusDaemon().container("box", Map.of());
        assertThrows(IllegalArgumentException.class, () -> CredentialProofReset.reset(daemon.client(), "box", (n, g, h) -> true));
        assertTrue(daemon.requests().stream().noneMatch(r -> r.startsWith("PATCH")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void resetKeepsPendingBootRecoveryUntilTheMcpSecretHasBeenDelivered(boolean delivered) {
        var boot = "2026-10-14T10:00:00Z";
        var daemon = instance("Running").lastUsedAt("box", boot);
        var incus = spy(daemon.client());
        if (delivered) {
            var generation = daemon.instance("box").path("config").path(Metadata.INSTANCE_PROOF_ID).asText();
            incus.configSet("box", Metadata.INSTANCE_SECRET_BOOT, InstanceLifecycle.secretBootStamp(boot, generation));
        }
        doReturn(new IncusClient.ExecResult(0, "", "")).when(incus).shellExec(eq("box"), anyMap(), any(String[].class));
        CredentialProofReset.reset(incus, "box", (n, g, h) -> true);
        var config = daemon.instance("box").path("config");
        assertEquals(delivered ? InstanceLifecycle.secretBootStamp(boot, config.path(Metadata.INSTANCE_PROOF_ID).asText()) : "",
                config.path(Metadata.INSTANCE_SECRET_BOOT).asText());
    }
}
