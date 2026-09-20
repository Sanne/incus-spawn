package dev.incusspawn.config;

import dev.incusspawn.tool.BobSetup;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretRegistryTest {

    private static Map<String, ToolSetup> builtInCredentialTools() {
        return Map.of(
                "claude", new ClaudeSetup(),
                "gh", new GhSetup(),
                "bob", new BobSetup(),
                "codex", new CodexSetup());
    }

    @Test
    void collectsDeclaredSecretsFromBuiltInTools() {
        var paths = SecretRegistry.locations(builtInCredentialTools()).stream()
                .map(SecretRegistry.SecretLocation::path)
                .toList();
        assertEquals(List.of("bob.apiKey", "claude.apiKey", "claude.oauthToken",
                "github.token", "openai.apiKey"), paths);
    }

    @Test
    void secretLocationNamesTheDeclaringTool() {
        var github = SecretRegistry.locations(builtInCredentialTools()).stream()
                .filter(l -> l.path().equals("github.token"))
                .findFirst().orElseThrow();
        assertEquals("gh", github.toolName());
        assertFalse(github.description().isBlank(), "description helps a reader of the manifest");
    }

    @Test
    void confirmEntriesAreNotSecrets() {
        // bob declares licenseConsent as a confirm alongside its API key — a yes/no answer,
        // not a credential.
        var paths = SecretRegistry.locations(Map.of("bob", new BobSetup())).stream()
                .map(SecretRegistry.SecretLocation::path)
                .toList();
        assertEquals(List.of("bob.apiKey"), paths);
    }

    @Test
    void yamlDeclaredToolContributesItsOwnSecret() {
        // The point of the registry: a tool nobody hardcoded shows up by declaring itself.
        var entry = new ToolDef.ConfigEntry();
        entry.setConfigPath("apiKey");
        entry.setSecret(true);
        entry.setDescription("Acme API key");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("acme");
        proxy.setConfiguration(Map.of("api-key", entry));

        var tool = new ToolDef();
        tool.setName("acme");
        tool.setProxy(proxy);

        var locations = SecretRegistry.locations(Map.of("acme", new YamlToolSetup(tool)));
        assertEquals(1, locations.size());
        assertEquals("acme.apiKey", locations.getFirst().path());
    }

    @Test
    void malformedConfigPathsAreDropped() {
        // "." is not blank but navigates nowhere; keeping it crashed bundle generation.
        var entry = new ToolDef.ConfigEntry();
        entry.setConfigPath(".");
        entry.setSecret(true);

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfiguration(Map.of("broken", entry));

        var tool = new ToolDef();
        tool.setName("broken");
        tool.setProxy(proxy);

        assertTrue(SecretRegistry.locations(Map.of("broken", new YamlToolSetup(tool))).isEmpty());
    }

    @Test
    void toolsWithoutAProxyContributeNothing() {
        var tool = new ToolDef();
        tool.setName("tmux");
        assertTrue(SecretRegistry.locations(Map.of("tmux", new YamlToolSetup(tool))).isEmpty());
    }

    @Test
    void nonSecretConfigEntriesAreIgnored() {
        var entry = new ToolDef.ConfigEntry();
        entry.setConfigPath("region");
        entry.setDescription("Deployment region");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("acme");
        proxy.setConfiguration(Map.of("region", entry));

        var tool = new ToolDef();
        tool.setName("acme");
        tool.setProxy(proxy);

        assertTrue(SecretRegistry.locations(Map.of("acme", new YamlToolSetup(tool))).isEmpty());
    }

}
