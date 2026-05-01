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
}
