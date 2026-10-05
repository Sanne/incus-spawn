package dev.incusspawn.mcp;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tui.InstanceLockManager;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncusInstanceBackendTest {

    private final IncusClient incus = mock(IncusClient.class);
    private final IncusInstanceBackend backend = new IncusInstanceBackend(incus, mock(InstanceLockManager.class));

    @Test
    void onlyAnInstanceIncusSaysIsMissingReadsAsGone() {
        when(incus.instanceMetadataOrThrow("gone")).thenReturn(null);
        assertNull(backend.metadata("gone"));
    }

    @Test
    void theStatusComesFromTheSameReadAsTheConfig() {
        var instance = JsonRpc.JSON.createObjectNode();
        instance.put("status", "Stopped");
        instance.putObject("config").put(Metadata.MCP_SESSION, "2-200").put("volatile.uuid", "x");
        when(incus.instanceMetadataOrThrow("dev")).thenReturn(instance);
        var metadata = backend.metadata("dev");
        assertTrue(InstanceBackend.stopped(metadata));
        assertEquals(java.util.Set.of(Metadata.MCP_SESSION, InstanceBackend.STATUS), metadata.keySet());
    }

    @Test
    void aFailedReadIsNotMistakenForAGoneInstance() {
        // A 403 or 500 said nothing about the instance: reading it as gone abandoned live ones (#858).
        when(incus.instanceMetadataOrThrow("dev")).thenThrow(new IncusException("Failed to read instance 'dev' (HTTP 500)"));
        assertThrows(ToolError.class, () -> backend.metadata("dev"));
    }

    @Test
    void theSweepMarksBeforeItReadsTheHolderAndBacksOffWhenAdopted() {
        var locks = mock(InstanceLockManager.class);
        when(locks.tryAcquire("orphan", Metadata.OP_DELETING)).thenReturn(Optional.of(() -> { }));
        var sweeper = new IncusInstanceBackend(incus, locks);
        var adopted = JsonRpc.JSON.createObjectNode();
        adopted.put("name", "orphan");
        adopted.putObject("config").put(Metadata.MCP_SESSION, "3-300");
        when(incus.instanceMetadataOrThrow("orphan")).thenReturn(adopted);

        assertFalse(sweeper.destroyIfHeldBy("orphan", "2-200", false));

        // The mark first, then the read: an adoption stamping in between is seen here, and one
        // stamping after sees the mark (McpSession.adopt).
        var order = inOrder(incus);
        order.verify(incus).configSet("orphan", Metadata.PENDING_OP, Metadata.OP_DELETING); // strictly, not setPendingOperation
        order.verify(incus).instanceMetadataOrThrow("orphan");
        order.verify(incus).configUnset("orphan", Metadata.PENDING_OP); // strictly: a mark left behind would never go
        verify(incus, never()).delete(anyString(), anyBoolean());
    }

    @Test
    void aTemplatesDelegateModelIsTheNearestOneItsChainSets() throws Exception {
        var base = dev.incusspawn.config.ImageDef.parseYaml("name: tpl-base\ntools:\n  - claude: {model: claude-sonnet-5-5}\n");
        var child = dev.incusspawn.config.ImageDef.parseYaml("name: tpl-child\nparent: tpl-base\ntools:\n  - claude: {model: claude-opus-5-5}\n");
        var plain = dev.incusspawn.config.ImageDef.parseYaml("name: tpl-plain\ntools:\n  - claude\n");
        // A child re-listing the tool reconfigures it with its own parameters only.
        var relisted = dev.incusspawn.config.ImageDef.parseYaml(
                "name: tpl-relisted\nparent: tpl-base\ntools:\n  - claude: {attribution-commit: x}\n");
        var unlisted = dev.incusspawn.config.ImageDef.parseYaml("name: tpl-unlisted\nparent: tpl-base\n");
        var defs = java.util.Map.of("tpl-base", base, "tpl-child", child, "tpl-plain", plain,
                "tpl-relisted", relisted, "tpl-unlisted", unlisted);
        assertNull(IncusInstanceBackend.delegateModel(relisted, defs), "the build drops the parent's model");
        org.junit.jupiter.api.Assertions.assertEquals("claude-sonnet-5-5", IncusInstanceBackend.delegateModel(unlisted, defs));
        org.junit.jupiter.api.Assertions.assertEquals("claude-opus-5-5", IncusInstanceBackend.delegateModel(child, defs));
        org.junit.jupiter.api.Assertions.assertEquals("claude-sonnet-5-5", IncusInstanceBackend.delegateModel(base, defs));
        assertNull(IncusInstanceBackend.delegateModel(plain, defs), "unset: Claude Code's own default");
    }

    @Test
    void aProbeRunsAsAgentuserWithoutItsLoginShell() {
        var out = new java.io.ByteArrayOutputStream();
        var limit = java.time.Duration.ofSeconds(10);
        backend.probe("dev", "echo hi", out, limit);
        // execProbe: uid 1000, no su -, so the user's profile cannot hang it.
        verify(incus).execProbe("dev", 1000, "/home/agentuser", "echo hi", out, limit);
    }

    @Test
    void theMcpListingCarriesEachInstancesStatus() {
        // The per-user task count asks only running instances, from this same listing.
        when(incus.listJsonConfig()).thenReturn("""
                [{"name":"mcp-a","status":"Stopped","config":{"%s":"2-200","volatile.x":"y"}},
                 {"name":"plain","status":"Running","config":{}}]""".formatted(Metadata.MCP_SESSION));
        var listing = backend.mcpInstances();
        assertEquals(java.util.Set.of("mcp-a"), listing.keySet());
        assertEquals("Stopped", listing.get("mcp-a").get(InstanceBackend.STATUS));
        assertEquals("2-200", listing.get("mcp-a").get(Metadata.MCP_SESSION));
        assertEquals(2, listing.get("mcp-a").size(), "only isx's config, and the status");
    }

    @Test
    void aListingThatDoesNotSayReadsAsRunning() {
        when(incus.listJsonConfig()).thenReturn("""
                [{"name":"mcp-a","config":{"%s":"2-200"}}]""".formatted(Metadata.MCP_SESSION));
        var listed = backend.mcpInstances().get("mcp-a");
        assertFalse(listed.containsKey(InstanceBackend.STATUS));
        assertTrue(InstanceBackend.running(listed), "asked, as the fake backend's instances are");
    }

    @Test
    void aPreflightRefusalIsRefusedNotUnavailable() {
        // The user has to act on it (here: free the name), so a client must not retry it as is.
        when(incus.exists("dev")).thenReturn(true);
        var info = new InstanceBackend.TemplateInfo("tpl-a", "", true, false, java.util.List.of(), null, false,
                java.util.Map.of());
        var e = assertThrows(ToolError.class, () -> backend.create(info, "dev", java.util.Map.of()));
        assertEquals(ToolError.Code.REFUSED, e.code);
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
    }
}
