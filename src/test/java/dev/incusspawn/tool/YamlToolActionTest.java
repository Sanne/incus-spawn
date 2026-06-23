package dev.incusspawn.tool;

import dev.incusspawn.tool.ToolDef.ActionEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YamlToolActionTest {

    @Test
    void testLabelInterpolation() {
        var entry = new ActionEntry();
        entry.setLabel("Open ${repo_name} in Gateway");
        entry.setType("url");

        var repo = new ActionContext.RepoInfo("my-project", "/home/agentuser/my-project", "https://github.com/user/my-project");
        var action = new YamlToolAction("idea-backend", entry, repo);

        assertEquals("Open my-project in Gateway", action.label());
    }

    @Test
    void testUrlInterpolation() {
        var entry = new ActionEntry();
        entry.setLabel("Gateway");
        entry.setType("url");
        entry.setUrl("jetbrains-gateway://connect#host=${ip}&port=22&user=agentuser&projectPath=${repo_path}");

        var repo = new ActionContext.RepoInfo("my-project", "/home/agentuser/my-project", "https://github.com/user/my-project");
        var action = new YamlToolAction("idea-backend", entry, repo);

        var context = new ActionContext("test-instance", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of("idea-backend"), "host", List.of(repo));

        var resolvedUrl = action.resolveUrl(context);
        assertTrue(resolvedUrl.contains("10.0.0.1"));
        assertTrue(resolvedUrl.contains("/home/agentuser/my-project"));
    }

    @Test
    void testCommandNeedsDeferredExecution() {
        var entry = new ActionEntry();
        entry.setLabel("Run script");
        entry.setType("command");
        entry.setCommand("echo hello");

        var action = new YamlToolAction("test-tool", entry);

        assertTrue(action.needsDeferredExecution());
    }

    @Test
    void testUrlDoesNotNeedDeferredExecution() {
        var entry = new ActionEntry();
        entry.setLabel("Open URL");
        entry.setType("url");
        entry.setUrl("http://example.com");

        var action = new YamlToolAction("test-tool", entry);

        assertFalse(action.needsDeferredExecution());
    }

    @Test
    void testAutoReturnDefault() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("command");
        entry.setCommand("echo test");

        var action = new YamlToolAction("test-tool", entry);

        assertFalse(action.shouldAutoReturn());
    }

    @Test
    void testAutoReturnExplicit() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("command");
        entry.setCommand("echo test");
        entry.setAutoReturn(true);

        var action = new YamlToolAction("test-tool", entry);

        assertTrue(action.shouldAutoReturn());
    }

    @Test
    void testMissingUrlReturnsError() {
        var entry = new ActionEntry();
        entry.setLabel("Open URL");
        entry.setType("url");
        // No URL set

        var action = new YamlToolAction("test-tool", entry);
        var context = new ActionContext("test", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of(), "host", List.of());

        var result = action.execute(context);

        assertFalse(result.success());
        assertTrue(result.message().contains("Missing URL"));
    }

    @Test
    void testMissingCommandReturnsError() {
        var entry = new ActionEntry();
        entry.setLabel("Run command");
        entry.setType("command");
        // No command set

        var action = new YamlToolAction("test-tool", entry);
        var context = new ActionContext("test", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of(), "host", List.of());

        var result = action.execute(context);

        assertFalse(result.success());
        assertTrue(result.message().contains("Missing command"));
    }

    @Test
    void testMissingTextReturnsError() {
        var entry = new ActionEntry();
        entry.setLabel("Copy text");
        entry.setType("copy-to-clipboard");
        // No text set

        var action = new YamlToolAction("test-tool", entry);
        var context = new ActionContext("test", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of(), "host", List.of());

        var result = action.execute(context);

        assertFalse(result.success());
        assertTrue(result.message().contains("Missing text"));
    }

    @Test
    void testMissingTypeReturnsError() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        // No type set

        var action = new YamlToolAction("test-tool", entry);
        var context = new ActionContext("test", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of(), "host", List.of());

        var result = action.execute(context);

        assertFalse(result.success());
        assertTrue(result.message().contains("Missing action type"));
    }

    @Test
    void testUnknownTypeReturnsError() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("unknown-type");

        var action = new YamlToolAction("test-tool", entry);
        var context = new ActionContext("test", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of(), "host", List.of());

        var result = action.execute(context);

        assertFalse(result.success());
        assertTrue(result.message().contains("Unknown action type"));
    }

    @Test
    void testRequiresRunningDefault() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("url");
        entry.setUrl("http://example.com");

        var action = new YamlToolAction("test-tool", entry);

        assertTrue(action.requiresRunning());
    }

    @Test
    void testRequiresRunningExplicit() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("url");
        entry.setUrl("http://example.com");
        entry.setRequiresRunning(false);

        var action = new YamlToolAction("test-tool", entry);

        assertFalse(action.requiresRunning());
    }

    @Test
    void testExtractScheme() {
        assertEquals("vscode", YamlToolAction.extractScheme("vscode://vscode-remote/ssh-remote+test/path"));
        assertEquals("jetbrains-gateway", YamlToolAction.extractScheme("jetbrains-gateway://connect#host=10.0.0.1"));
        assertEquals("http", YamlToolAction.extractScheme("http://example.com"));
        assertEquals("https", YamlToolAction.extractScheme("https://example.com"));
        assertNull(YamlToolAction.extractScheme("no-scheme-here"));
        assertNull(YamlToolAction.extractScheme(""));
    }

    @Test
    void testExtractSchemeCaseInsensitive() {
        assertEquals("vscode", YamlToolAction.extractScheme("VSCode://something"));
    }

    @Test
    void testCheckUrlPrerequisitesSkipsUnknownSchemes() {
        assertNull(YamlToolAction.checkUrlPrerequisites("http://example.com"));
        assertNull(YamlToolAction.checkUrlPrerequisites("https://example.com"));
        assertNull(YamlToolAction.checkUrlPrerequisites("custom://something"));
    }

    @Test
    void testAllVariableInterpolation() {
        var entry = new ActionEntry();
        entry.setLabel("${repo_name}");
        entry.setType("copy-to-clipboard");
        entry.setText("Instance: ${name}, IP: ${ip}, Parent: ${parent}, Repo: ${repo_name} at ${repo_path} (${repo_url})");

        var repo = new ActionContext.RepoInfo("my-project", "/home/agentuser/my-project", "https://github.com/user/my-project");
        var action = new YamlToolAction("test-tool", entry, repo);

        var context = new ActionContext("test-instance", "10.0.0.1", "RUNNING", "tpl-dev",
                Set.of(), "host", List.of(repo));

        assertEquals("my-project", action.label());
        assertEquals("test-tool", action.toolName());
        assertFalse(action.isUrl());
        assertFalse(action.needsDeferredExecution());
    }

    // --- shell action type ---

    @Test
    void testShellDoesNotNeedDeferredExecution() {
        var entry = new ActionEntry();
        entry.setLabel("Claude Code");
        entry.setType("shell");
        entry.setCommand("claude");
        var action = new YamlToolAction("claude", entry);
        assertFalse(action.needsDeferredExecution());
    }

    @Test
    void testShellCommandReturnsCommand() {
        var entry = new ActionEntry();
        entry.setLabel("Claude Code");
        entry.setType("shell");
        entry.setCommand("claude --continue");
        var action = new YamlToolAction("claude", entry);
        assertEquals("claude --continue", action.shellCommand(null).orElse(null));
    }

    @Test
    void testShellCommandBlankReturnsEmptyAndExecuteShowsUserError() {
        var entry = new ActionEntry();
        entry.setLabel("Broken");
        entry.setType("shell");
        var action = new YamlToolAction("test", entry);
        assertTrue(action.shellCommand(null).isEmpty());
        var result = action.execute(new ActionContext("t", "10.0.0.1", "RUNNING", "tpl",
                Set.of(), "host", List.of()));
        assertFalse(result.success());
        assertTrue(result.message().contains("Missing command"));
    }

    @Test
    void testNonShellReturnsEmptyShellCommand() {
        var entry = new ActionEntry();
        entry.setLabel("Open");
        entry.setType("url");
        entry.setUrl("http://example.com");
        var action = new YamlToolAction("test", entry);
        assertTrue(action.shellCommand(null).isEmpty());
    }

    // --- action id ---

    @Test
    void testIdWithoutRepo() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("shell");
        entry.setId("launch");
        var action = new YamlToolAction("claude", entry);
        assertEquals("launch", action.id().orElse(null));
    }

    @Test
    void testIdWithRepoExpansion() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("url");
        entry.setId("open-ide");
        var repo = new ActionContext.RepoInfo("my-project", "/home/user/my-project", "https://github.com/u/p");
        var action = new YamlToolAction("vscode", entry, repo);
        assertEquals("open-ide/my-project", action.id().orElse(null));
    }

    @Test
    void testIdNotSet() {
        var entry = new ActionEntry();
        entry.setLabel("Test");
        entry.setType("shell");
        var action = new YamlToolAction("test", entry);
        assertTrue(action.id().isEmpty());
    }
}
