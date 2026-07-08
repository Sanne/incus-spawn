package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.*;
import static org.mockito.Mockito.*;

class GhSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void declaresGhPackage() {
        var setup = new GhSetup();
        assertEquals(java.util.List.of("gh"), setup.packages());
    }

    @Test
    void envEntriesSetsGhToken() {
        var entries = new GhSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "GH_TOKEN".equals(e.getName()) && "gho_placeholder".equals(e.getValue())
                        && e.getStrategy() == EnvEntry.Strategy.SET));
    }

    @Test
    void envEntriesUsesEnvVarNotHostsYml() {
        var entries = new GhSetup().envEntries(Map.of());

        assertEquals(1, entries.size());
        assertEquals("GH_TOKEN", entries.get(0).getName());
    }

    @Test
    void setsIdentityFromGitHubApi() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\t\n");
        ghApiEmailsReturns(incus, "12345+testuser@users.noreply.github.com\n");

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("The Octocat"));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("12345+testuser@users.noreply.github.com"));
    }

    @Test
    void existingConfigSkipsDefaults() {
        var incus = stubIncus();
        existingConfig(incus);
        existingIdentity(incus);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("push.default"));
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("alias."));
    }

    @Test
    void existingIdentitySkipsApiCalls() {
        var incus = stubIncus();
        noExistingConfig(incus);
        existingIdentity(incus);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("gh api user"));
    }

    @Test
    void noTokenFallbackStillWritesDefaults() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        // gitConfigGet calls contain "--get"; identity writes do not
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                and(contains("user.name"), not(contains("--get"))));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("push.default"));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("init.defaultBranch"));
    }

    @Test
    void prefersNoreplyOverPublicEmail() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\tpublic@example.com\n");
        ghApiEmailsReturns(incus, "12345+testuser@users.noreply.github.com\n");

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("12345+testuser@users.noreply.github.com"));
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("public@example.com"));
    }

    @Test
    void fallsBackToPublicEmailWhenNoreplyUnavailable() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\tpublic@example.com\n");
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("public@example.com"));
    }

    @Test
    void usesLoginAsNameWhenDisplayNameEmpty() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\t\t\n");
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("octocat"));
    }

    // --- Test helpers ---

    private static IncusClient stubIncus() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), any(String[].class))).thenReturn(OK);
        return incus;
    }

    private static void existingConfig(IncusClient incus) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("test -f")))
                .thenReturn(OK);
    }

    private static void noExistingConfig(IncusClient incus) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("test -f")))
                .thenReturn(FAIL);
    }

    private static void existingIdentity(IncusClient incus) {
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), contains("git config --global --get")))
                .thenReturn(OK);
    }

    private static void noExistingIdentity(IncusClient incus) {
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), contains("git config --global --get")))
                .thenReturn(FAIL);
    }

    private static void ghApiUserReturns(IncusClient incus, String tsv) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq")))
                .thenReturn(new IncusClient.ExecResult(0, tsv, ""));
    }

    private static void ghApiEmailsReturns(IncusClient incus, String email) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(new IncusClient.ExecResult(0, email, ""));
    }
}
