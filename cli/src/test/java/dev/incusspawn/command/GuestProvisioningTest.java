package dev.incusspawn.command;

import dev.incusspawn.Platform;
import dev.incusspawn.config.AgentContextGenerator;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestProvisioningTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");

    @Test
    void expandHomeTilde() {
        assertEquals("/home/agentuser/quarkus", GuestProvisioning.expandHome("~/quarkus"));
    }

    @Test
    void expandHomeTildeOnly() {
        assertEquals("/home/agentuser", GuestProvisioning.expandHome("~"));
    }

    @Test
    void expandHomeAbsolutePathUnchanged() {
        assertEquals("/opt/something", GuestProvisioning.expandHome("/opt/something"));
    }

    @Test
    void parseGitHubOwnerRepoWithDotGit() {
        assertEquals("quarkusio/quarkus",
                GuestProvisioning.parseGitHubOwnerRepo("https://github.com/quarkusio/quarkus.git"));
    }

    @Test
    void parseGitHubOwnerRepoWithoutDotGit() {
        assertEquals("hibernate/hibernate-reactive",
                GuestProvisioning.parseGitHubOwnerRepo("https://github.com/hibernate/hibernate-reactive"));
    }

    @Test
    void parseGitHubOwnerRepoTrailingSlash() {
        assertEquals("owner/repo",
                GuestProvisioning.parseGitHubOwnerRepo("https://github.com/owner/repo/"));
    }

    @Test
    void parseGitHubOwnerRepoNonGitHub() {
        assertNull(GuestProvisioning.parseGitHubOwnerRepo("https://gitlab.com/some/repo.git"));
    }

    @Test
    void parseGitHubOwnerRepoNull() {
        assertNull(GuestProvisioning.parseGitHubOwnerRepo(null));
    }

    @Test
    void parseGitHubOwnerRepoSshFormat() {
        assertNull(GuestProvisioning.parseGitHubOwnerRepo("git@github.com:owner/repo.git"));
    }

    private static ImageDef coprImage(String name) {
        var repo = new ImageDef.PackageRepo();
        repo.setType("copr");
        repo.setName(name);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-mise");
        imageDef.setPackageRepos(List.of(repo));
        return imageDef;
    }

    /**
     * The commands {@code enablePackageRepos} runs in a guest whose dnf is {@code dnf} (and
     * {@code dnf5} too when {@code dnf5}), and whose rpm database provides {@code provides}.
     * The copr plugin probe runs for real, under {@code sh} with stub commands on its path.
     */
    private static List<String> coprCommands(Path dir, boolean dnf5, String... provides) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var rpm = new StringBuilder("#!/bin/sh\ncase \"$3\" in\n");
        for (var provide : provides) rpm.append("  '").append(provide).append("') exit 0 ;;\n");
        rpm.append("esac\nexit 1\n");
        Files.writeString(bin.resolve("rpm"), rpm);
        bin.resolve("rpm").toFile().setExecutable(true);
        if (dnf5) {
            Files.writeString(bin.resolve("dnf5"), "#!/bin/sh\n");
            bin.resolve("dnf5").toFile().setExecutable(true);
        }
        var incus = mock(IncusClient.class);
        var commands = new java.util.ArrayList<String>();
        when(incus.shellExec(eq("test"), any(String[].class))).thenAnswer(call -> {
            var args = List.of(call.getArguments()).subList(1, call.getArguments().length)
                    .stream().map(String::valueOf).toList();
            commands.add(String.join(" ", args));
            if (!args.equals(List.of("sh", "-c", GuestProvisioning.MISSING_COPR_PLUGIN))) return OK;
            var process = new ProcessBuilder("/bin/sh", "-c", args.get(2)).redirectErrorStream(true);
            process.environment().put("PATH", bin.toString());
            var started = process.start();
            var out = new String(started.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return new IncusClient.ExecResult(started.waitFor(), out, "");
        });
        when(incus.shellExecStreaming(eq("test"), any(), any(), any(String[].class))).thenAnswer(call -> {
            commands.add(String.join(" ", List.of(call.getArguments()).subList(3, call.getArguments().length)
                    .stream().map(String::valueOf).toList()));
            return 0;
        });
        GuestProvisioning.enablePackageRepos(new Container(incus, "test"), coprImage("jdxcode/mise"),
                List.of(), List.of(), Map.of());
        assertEquals("sh -c " + GuestProvisioning.MISSING_COPR_PLUGIN, commands.get(0));
        assertEquals("dnf copr enable -y jdxcode/mise", commands.get(commands.size() - 1));
        return commands.subList(1, commands.size() - 1);
    }

    private static String install(String provide) {
        return String.join(" ", BuildProgress.dnfCommand("install", "-y", provide));
    }

    @Test
    void enablePackageReposInstallsTheCoprPluginFirstWhenMissing(@TempDir Path dir) throws Exception {
        // isx's base image ships dnf5 without its plugins, so `dnf copr enable` fails
        // with "try: dnf5 install 'dnf5-command(copr)'" unless the build installs it (#1736).
        assertEquals(List.of(install("dnf5-command(copr)")), coprCommands(dir, true));
    }

    @Test
    void enablePackageReposInstallsDnf5sPluginWhenOnlyDnf4sIsPresent(@TempDir Path dir) throws Exception {
        // A package can pull dnf-plugins-core into a dnf5 guest; dnf5 does not use its copr
        assertEquals(List.of(install("dnf5-command(copr)")), coprCommands(dir, true, "dnf-command(copr)"));
    }

    @Test
    void enablePackageReposKeepsTheCoprPluginTheGuestHas(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), coprCommands(dir, true, "dnf5-command(copr)"));
        assertEquals(List.of(), coprCommands(dir.resolve("dnf4"), false, "dnf-command(copr)"));
    }

    @Test
    void enablePackageReposInstallsDnf4sPluginOnADnf4Guest(@TempDir Path dir) throws Exception {
        assertEquals(List.of(install("dnf-command(copr)")), coprCommands(dir, false, "dnf5-command(copr)"));
    }

    @Test
    void enablePackageReposSkipsThePluginInstallWhenPresent() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(eq("test"), any(String[].class))).thenReturn(OK);

        GuestProvisioning.enablePackageRepos(container, coprImage("jdxcode/mise"), List.of(), List.of(), Map.of());

        verify(incus, never()).shellExecStreaming(any(), any(), any(), any(String[].class));
        verify(incus).shellExec("test", "dnf", "copr", "enable", "-y", "jdxcode/mise");
    }

    @Test
    void enablePackageReposWarnsOfAnUnknownTypeThroughTheOutput() {
        var incus = mock(IncusClient.class);
        var imageDef = coprImage("owner/project");
        imageDef.getPackageRepos().get(0).setType("ppa");

        var said = RecordingOutput.during(() -> GuestProvisioning.enablePackageRepos(
                new Container(incus, "test"), imageDef, List.of(), List.of(), Map.of()));

        assertEquals(List.of("Unknown package_repos type 'ppa' for 'owner/project', skipping it"), said.warnings());
        verifyNoInteractions(incus);
    }

    @Test
    void enablePackageReposWarnsOfAMissingTypeInsteadOfThrowing() {
        var incus = mock(IncusClient.class);
        var imageDef = coprImage("owner/project");
        imageDef.getPackageRepos().get(0).setType(null);

        var said = RecordingOutput.during(() -> GuestProvisioning.enablePackageRepos(
                new Container(incus, "test"), imageDef, List.of(), List.of(), Map.of()));

        assertEquals(List.of("package_repos entry 'owner/project' has no type, skipping it"), said.warnings());
        verifyNoInteractions(incus);
    }

    @Test
    void enablePackageReposWarnsOfAMissingNameInsteadOfThrowing() {
        var incus = mock(IncusClient.class);
        var imageDef = coprImage(null);

        var said = RecordingOutput.during(() -> GuestProvisioning.enablePackageRepos(
                new Container(incus, "test"), imageDef, List.of(), List.of(), Map.of()));

        assertEquals(List.of("A package_repos entry of type 'copr' has no name, skipping it"), said.warnings());
        verifyNoInteractions(incus);
    }

    @Test
    void updateClaudeJsonTrustAddsProjectsAndGithubPaths() throws Exception {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        // Simulate existing .claude.json with projects section
        var existingJson = """
                {
                  "hasCompletedOnboarding": true,
                  "projects": {
                    "/home/agentuser": {
                      "allowedTools": [],
                      "hasTrustDialogAccepted": true
                    }
                  }
                }
                """;
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(
                new IncusClient.ExecResult(0, existingJson, ""));
        // writeFile uses sh -c
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        // chown
        when(incus.shellExec(eq("test"), eq("chown"), anyString(), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef, Map.of());

        var writtenJson = capturedWrite(incus, ".claude.json");
        assertNotNull(writtenJson, "Expected .claude.json to be written");

        var root = io.hyperfoil.tools.jjq.value.JqValues.parse(writtenJson);

        // Original fields preserved
        assertTrue(root.pathBoolean("hasCompletedOnboarding", false));

        // Original project trust preserved
        var projects = root.getField("projects");
        assertTrue(projects.getField("/home/agentuser").getField("hasTrustDialogAccepted").asBoolean(false));
        assertTrue(projects instanceof io.hyperfoil.tools.jjq.value.JqObject projectsObj
                && projectsObj.keys().contains("/home/agentuser"));

        // New repo project trust added
        assertTrue(projects.getField("/home/agentuser/quarkus").getField("hasTrustDialogAccepted").asBoolean(false));

        // GitHub repo path added
        var githubPaths = root.getField("githubRepoPaths");
        assertTrue(githubPaths instanceof io.hyperfoil.tools.jjq.value.JqObject pathsObj
                && pathsObj.keys().contains("quarkusio/quarkus"));
        assertEquals("/home/agentuser/quarkus", githubPaths.getField("quarkusio/quarkus").getElement(0).asText());
    }

    @Test
    void updateClaudeJsonTrustReadsAFileALoginApprovedAKeyIn() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        // The login script's approval repeats the field on the last line (ClaudeSetup.LOGIN_AUTH_SCRIPT)
        var existingJson = """
                {
                  "customApiKeyResponses": {"approved": ["sk-ant-placeholder"], "rejected": []},
                  "projects": {}
                ,"customApiKeyResponses":{"approved":["0123456789abcdef0123"],"rejected":[]}}
                """;
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(
                new IncusClient.ExecResult(0, existingJson, ""));
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("chown"), anyString(), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef, Map.of());

        var written = capturedWrite(incus, ".claude.json");
        assertNotNull(written, "the repos are trusted");
        var root = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(written);
        assertTrue(root.path("projects").path("/home/agentuser/quarkus").path("hasTrustDialogAccepted").asBoolean(false));
        assertEquals("sk-ant-placeholder", root.path("customApiKeyResponses").path("approved").getElement(0).asText());
    }

    /**
     * A {@code .claude.json} that is not the object Claude Code writes is left alone, with a
     * warning: rewriting it from what could be read would replace the user's file with a
     * {@code projects} map alone.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "not json at all",
            "[1, 2]",
            "\"a string\"",
            "{\"hasCompletedOnboarding\": true, \"projects\": \"oops\"}",
            "{\"hasCompletedOnboarding\": true, \"githubRepoPaths\": [1]}",
    })
    void updateClaudeJsonTrustLeavesAnUnreadableFileAlone(String existingJson) {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(
                new IncusClient.ExecResult(0, existingJson, ""));
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef, Map.of());

        // An unreadable .claude.json must not be rewritten.
        verify(incus, never()).shellExec(eq("test"), eq("sh"), eq("-c"), contains(".claude.json"));
    }

    @Test
    void updateClaudeJsonTrustRestoresInheritedReposAfterReconfiguration() throws Exception {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        // A child overriding Claude's model rewrites .claude.json, removing parent repo trust.
        new ClaudeSetup().reconfigure(container, Map.of("model", "child-model"));
        var rewrittenJson = capturedWrite(incus, ".claude.json");
        assertNotNull(rewrittenJson);
        assertFalse(rewrittenJson.contains("/home/agentuser/parent-repo"));
        clearInvocations(incus);
        when(incus.shellExec("test", "cat", "/home/agentuser/.claude.json"))
                .thenReturn(new IncusClient.ExecResult(0, rewrittenJson, ""));

        var parentRepo = new ImageDef.RepoEntry();
        parentRepo.setUrl("https://github.com/owner/parent-repo.git");
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(parentRepo));
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent(parent.getName());

        GuestProvisioning.updateClaudeJsonTrust(container, child, Map.of(parent.getName(), parent));

        var root = io.hyperfoil.tools.jjq.value.JqValues.parse(capturedWrite(incus, ".claude.json"));
        assertTrue(root.path("projects").path("/home/agentuser/parent-repo").path("hasTrustDialogAccepted").asBoolean(false));
        assertEquals("/home/agentuser/parent-repo", root.path("githubRepoPaths").path("owner/parent-repo").getElement(0).asText());
        assertTrue(root.path("projects").path("/home/agentuser").path("hasTrustDialogAccepted").asBoolean(false),
                "Preserve the reconfigured file's own entries");
    }

    @Test
    void updateClaudeJsonTrustSkipsWriteWhenAlreadyTrusted() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(new IncusClient.ExecResult(0, """
                {
                  "projects": {"/home/agentuser/repo": {"hasTrustDialogAccepted": true}},
                  "githubRepoPaths": {"owner/repo": ["/home/agentuser/repo"]}
                }
                """, ""));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-repo");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef, Map.of());

        verify(incus, never()).shellExec(eq("test"), eq("sh"), eq("-c"), anyString());
    }

    @Test
    void updateClaudeJsonTrustNoopWhenNoRepos() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-empty");
        // repos defaults to empty

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef, Map.of());

        verifyNoInteractions(incus);
    }

    @Test
    void updateClaudeJsonTrustNoopWhenClaudeNotInstalled() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(FAIL);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-nonclaude");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef, Map.of());

        // Should check for file but not attempt to read/write it
        verify(incus).shellExec(eq("test"), eq("test"), eq("-f"), anyString());
        verify(incus, never()).shellExec(eq("test"), eq("cat"), anyString());
    }

    // --- writeAgentContext ---

    /** Every script run in the container {@code test} through {@code sh -c}, in order. */
    private static List<String> shellCalls(IncusClient incus) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq("test"), eq("sh"), eq("-c"), captor.capture());
        return captor.getAllValues();
    }

    /** Pull the first heredoc body out of the last script that names {@code path}. */
    private static String capturedWrite(IncusClient incus, String path) {
        String written = null;
        for (var call : shellCalls(incus)) {
            if (call.contains(path)) {
                var start = call.indexOf('\n') + 1;
                var opener = call.substring(0, Math.max(start - 1, 0));
                var marker = opener.contains("<< '") ? opener.substring(opener.lastIndexOf("<< '") + 4, opener.length() - 1) : "INCUS_EOF";
                var end = call.indexOf("\n" + marker + "\n", start);
                if (end < 0 && call.endsWith("\n" + marker)) end = call.length() - marker.length() - 1;
                if (start > 0 && end > start) {
                    written = call.substring(start, end);
                }
            }
        }
        return written;
    }

    /**
     * Claude Code's managed policy is Claude's: a template without the claude tool gets no
     * {@code /etc/claude-code/CLAUDE.md}, and one with it gets a link to the one stored copy.
     */
    @Test
    void managedPolicyOnlyWithClaudeTool() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), any(String[].class))).thenReturn(OK);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-pi");
        var pi = new BuildTools.ResolvedTool("pi", new dev.incusspawn.tool.PiSetup(), Map.of());

        GuestProvisioning.writeAgentContext(new Container(incus, "test"), imageDef, Map.of(), List.of(pi), "tpl-pi");

        assertTrue(shellCalls(incus).stream().noneMatch(call -> call.contains("cat > '/etc/claude-code/CLAUDE.md'")
                        || call.contains("isx_link '/etc/claude-code/CLAUDE.md'")),
                "a template without claude gets no managed policy");
        assertTrue(shellCalls(incus).stream().anyMatch(call -> call.contains("isx_link '/AGENTS.md'")));

        var withClaude = mock(IncusClient.class);
        when(withClaude.shellExec(eq("test"), any(String[].class))).thenReturn(OK);
        var claude = new BuildTools.ResolvedTool("claude", new dev.incusspawn.tool.ClaudeSetup(), Map.of());
        GuestProvisioning.writeAgentContext(new Container(withClaude, "test"), imageDef, Map.of(),
                List.of(claude, pi), "tpl-pi");
        assertTrue(shellCalls(withClaude).stream().anyMatch(call -> call.contains("isx_link '/etc/claude-code/CLAUDE.md'")
                && call.contains("isx_link '/AGENTS.md'")), "both are links to the one stored copy");
    }

    /** With no agent installed the instructions are still stored once, and placed nowhere. */
    @Test
    void writeAgentContextStoresInstructionsOnce() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-solo");

        GuestProvisioning.writeAgentContext(container, imageDef, Map.of(), List.of(), "tpl-solo");

        var written = capturedWrite(incus, AgentInstructions.STORE);
        assertNotNull(written, "should store the instructions");
        assertTrue(written.contains("built from the `tpl-solo` template"));
        var script = shellCalls(incus).getLast();
        assertFalse(script.contains("isx_link '") || script.contains("isx_copy '"), script);
        // An older build's managed policy is checked, and goes if isx wrote it (AgentInstructionsTest).
        assertTrue(script.contains("printf '%s\\n' '/etc/claude-code/CLAUDE.md' '/AGENTS.md'"), script);
    }

    @Test
    void geminiContextIncludesInheritedNotesAndUsesChildTemplateName() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), any(String[].class))).thenReturn(OK);
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setAgentNote("parent environment instructions");
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setAgentNote("child environment instructions");
        // allTools includes tools inherited from the parent, even without a child reconfiguration.
        var tools = List.of(new BuildTools.ResolvedTool("gemini-cli",
                new dev.incusspawn.tool.GeminiSetup(), Map.of()));

        GuestProvisioning.writeAgentContext(new Container(incus, "test"), child,
                Map.of("tpl-parent", parent), tools, "tpl-child");

        var path = dev.incusspawn.tool.GeminiSetup.CONTEXT_PATH;
        var gemini = capturedWrite(incus, AgentInstructions.STORE);
        assertNotNull(gemini);
        assertTrue(gemini.contains("built from the `tpl-child` template"));
        assertTrue(gemini.contains("parent environment instructions"));
        assertTrue(gemini.contains("child environment instructions"));
        // In the user's home: agentuser's own copy, never a link to the root-owned store.
        assertTrue(shellCalls(incus).getLast().contains("isx_copy '" + path + "'"));
    }

    /** Pi alone, no claude tool: it still gets the instructions, in the root AGENTS.md it reads from every directory. */
    @Test
    void piGetsItsOwnContextWithoutClaude() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), any(String[].class))).thenReturn(OK);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-pi");
        imageDef.setAgentNote("pi environment instructions");
        var tools = List.of(new BuildTools.ResolvedTool("pi", new dev.incusspawn.tool.PiSetup(), Map.of()));

        GuestProvisioning.writeAgentContext(new Container(incus, "test"), imageDef, Map.of(), tools, "tpl-pi");

        // The root's AGENTS.md, which Pi loads from every working directory, owned by root:
        // the user's own ~/.pi/agent/AGENTS.md is left to them.
        var pi = capturedWrite(incus, AgentInstructions.STORE);
        assertNotNull(pi, "pi should get its own context file");
        assertTrue(pi.contains("built from the `tpl-pi` template"));
        assertTrue(pi.contains("pi environment instructions"));
        assertTrue(shellCalls(incus).getLast().contains("isx_link '/AGENTS.md'"));
        assertTrue(shellCalls(incus).stream().noneMatch(call -> call.contains("/.pi/")),
                "the user's own Pi files are not touched");
        assertFalse(shellCalls(incus).getLast().contains("isx_copy '" + dev.incusspawn.tool.GeminiSetup.CONTEXT_PATH));
    }

    /**
     * Codex reads no Markdown outside the user's own {@code ~/.codex/AGENTS.md}: it gets the
     * instructions as managed developer instructions in its system requirements (#1783).
     */
    @Test
    void codexGetsTheInstructionsInItsRequirements() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), any(String[].class))).thenReturn(OK);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-codex");
        imageDef.setAgentNote("codex environment instructions");
        var tools = List.of(new BuildTools.ResolvedTool("codex", new dev.incusspawn.tool.CodexSetup(), Map.of()));

        GuestProvisioning.writeAgentContext(new Container(incus, "test"), imageDef, Map.of(), tools, "tpl-codex");

        var script = shellCalls(incus).getLast();
        var opener = "isx_render '/etc/codex/requirements.toml' << '";
        assertTrue(script.contains(opener), script);
        var requirements = script.substring(script.indexOf('\n', script.indexOf(opener)) + 1);
        assertTrue(requirements.startsWith(AgentContextGenerator.HEADING + "\n"), requirements);
        assertTrue(requirements.contains("additional_developer_instructions = \"\"\"\n" + AgentContextGenerator.HEADING),
                requirements);
        assertTrue(requirements.contains("built from the `tpl-codex` template"));
        assertTrue(requirements.contains("codex environment instructions"));
        assertTrue(shellCalls(incus).stream().noneMatch(call -> call.contains("/.codex/AGENTS.md")),
                "the user's own AGENTS.md is theirs");
    }

    @Test
    void writeAgentContextCollectsNotesFromAncestorsImageAndTools() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        var container = new Container(incus, "test");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setAgentNote("parent-level note");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setAgentNote("child-level note");

        var tools = List.of(
                new BuildTools.ResolvedTool("jtreg", SkillInstallerTest.namedTool("jtreg", "tool-level note"), Map.of()));

        GuestProvisioning.writeAgentContext(container, child,
                Map.of("tpl-parent", parent, "tpl-child", child), tools, "tpl-child");

        var written = capturedWrite(incus, AgentInstructions.STORE);
        assertNotNull(written);
        // Root-first: ancestors, then the image, then tools.
        assertTrue(written.indexOf("parent-level note") < written.indexOf("child-level note"));
        assertTrue(written.indexOf("child-level note") < written.indexOf("tool-level note"));
        assertTrue(written.contains("Already installed, don't reinstall: jtreg"));
    }

    @Test
    void writeAgentContextIncludesAncestorRepos() {
        // cloneRepos runs per-layer, so an ancestor's clones exist in the built image
        // even though they are absent from the leaf's own repo list.
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        var container = new Container(incus, "test");

        var parentRepo = new ImageDef.RepoEntry();
        parentRepo.setUrl("https://github.com/openjdk/jdk.git");
        parentRepo.setPath("~/jdk");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(parentRepo));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        GuestProvisioning.writeAgentContext(container, child,
                Map.of("tpl-parent", parent, "tpl-child", child), List.of(), "tpl-child");

        var written = capturedWrite(incus, AgentInstructions.STORE);
        assertNotNull(written);
        assertTrue(written.contains("Already cloned, work in these rather than cloning again:"));
        assertTrue(written.contains("- https://github.com/openjdk/jdk.git is checked out at `~/jdk`"));
    }

    @Test
    void writeAgentContextDerivesRepoPathFromUrlAndSkipsUnclonable() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        var container = new Container(incus, "test");

        var derived = new ImageDef.RepoEntry();
        derived.setUrl("https://github.com/quarkusio/quarkus.git"); // no explicit path
        var broken = new ImageDef.RepoEntry();                      // neither url nor path
        var blank = new ImageDef.RepoEntry();
        blank.setUrl("https://github.com/owner/repo.git");
        blank.setPath("");
        // Same clone as `derived`, written the long way — must not be listed twice.
        var sameClone = new ImageDef.RepoEntry();
        sameClone.setUrl("https://github.com/quarkusio/quarkus.git");
        sameClone.setPath("/home/agentuser/quarkus");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");
        imageDef.setRepos(List.of(derived, broken, blank, sameClone));

        GuestProvisioning.writeAgentContext(container, imageDef, Map.of(), List.of(), "tpl-x");

        var written = capturedWrite(incus, AgentInstructions.STORE);
        assertNotNull(written);
        assertTrue(written.contains("`~/quarkus`"), "path should be derived from the url");
        assertFalse(written.contains("null"), "an unclonable entry must not be listed");
        assertFalse(written.contains("``"), "a blank path must not render an empty bullet");
        assertEquals(1, written.lines().filter(l -> l.contains("is checked out at")).count(),
                "~/quarkus and /home/agentuser/quarkus are one clone, not two");
    }

    // --- updateCodexTrust ---

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateCodexTrustRestoresInheritedReposAfterReconfiguration(boolean childHasRepos) {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        // A child overriding Codex's model rewrites config.toml, removing parent repo trust.
        new CodexSetup().reconfigure(container, Map.of("model", "child-model"));
        var rewrittenConfig = capturedWrite(incus, ".codex/config.toml");
        assertNotNull(rewrittenConfig);
        assertFalse(rewrittenConfig.contains("/home/agentuser/parent-repo"));
        clearInvocations(incus);
        when(incus.shellExec("test", "cat", CodexSetup.CONFIG_PATH))
                .thenReturn(new IncusClient.ExecResult(0, rewrittenConfig, ""));

        var parentRepo = new ImageDef.RepoEntry();
        parentRepo.setUrl("https://github.com/owner/parent-repo.git");
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(parentRepo));
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent(parent.getName());
        if (childHasRepos) {
            var childRepo = new ImageDef.RepoEntry();
            childRepo.setPath("~/child-repo");
            // Explicit and derived paths for the same clone must produce just one table.
            var duplicate = new ImageDef.RepoEntry();
            duplicate.setPath("/home/agentuser/child-repo");
            child.setRepos(List.of(childRepo, duplicate));
        }

        GuestProvisioning.updateCodexTrust(container, child, Map.of(parent.getName(), parent));

        var written = capturedWrite(incus, ".codex/config.toml");
        assertNotNull(written);
        assertTrue(written.startsWith(rewrittenConfig), "Preserve the child's Codex settings");
        assertTrue(written.contains("[projects.\"/home/agentuser/parent-repo\"]\ntrust_level = \"trusted\""));
        assertEquals(1, written.lines().filter(l -> l.equals("[projects.\"/home/agentuser/parent-repo\"]")).count());
        assertEquals(childHasRepos, written.contains("[projects.\"/home/agentuser/child-repo\"]\ntrust_level = \"trusted\""));
        assertEquals(childHasRepos ? 1 : 0,
                written.lines().filter(l -> l.equals("[projects.\"/home/agentuser/child-repo\"]")).count());
        verify(incus).shellExec("test", "cat", CodexSetup.CONFIG_PATH);
    }

    @Test
    void chainReposSkipsEntriesWithoutPathAndDedupesResolvedPaths() {
        var parentRepo = new ImageDef.RepoEntry();
        parentRepo.setPath("~/repo");
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(parentRepo, new ImageDef.RepoEntry()));
        var sameClone = new ImageDef.RepoEntry();
        sameClone.setPath("/home/agentuser/repo");
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent(parent.getName());
        child.setRepos(List.of(sameClone));

        var repos = GuestProvisioning.chainRepos(child, Map.of(parent.getName(), parent));

        assertEquals(List.of(parentRepo), repos);
    }

    @Test
    void updateCodexTrustAddsRepoDirectories() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var existingConfig = """
                model = "o4-mini"
                approval_policy = "never"

                [projects."/home/agentuser"]
                trust_level = "trusted"
                """;
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(
                new IncusClient.ExecResult(0, existingConfig, ""));
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("chown"), anyString(), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateCodexTrust(container, imageDef, Map.of());

        var writtenContent = capturedWrite(incus, ".codex/config.toml");
        assertNotNull(writtenContent, "Expected config.toml to be written");
        assertTrue(writtenContent.contains("[projects.\"/home/agentuser/quarkus\"]"));
        assertTrue(writtenContent.contains("trust_level = \"trusted\""));
        // Original content preserved
        assertTrue(writtenContent.contains("model = \"o4-mini\""));
        assertTrue(writtenContent.contains("[projects.\"/home/agentuser\"]"));
    }

    @Test
    void updateCodexTrustNoopWhenNoRepos() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-empty");

        GuestProvisioning.updateCodexTrust(container, imageDef, Map.of());

        verifyNoInteractions(incus);
    }

    @Test
    void updateCodexTrustNoopWhenCodexNotInstalled() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(FAIL);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-nocodex");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateCodexTrust(container, imageDef, Map.of());

        verify(incus).shellExec(eq("test"), eq("test"), eq("-f"), anyString());
        verify(incus, never()).shellExec(eq("test"), eq("cat"), anyString());
    }

    @Test
    void updateCodexTrustSkipsAlreadyTrustedPath() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var existingConfig = """
                model = "o4-mini"

                [projects."/home/agentuser"]
                trust_level = "trusted"

                [projects."/home/agentuser/quarkus"]
                trust_level = "trusted"
                """;
        when(incus.shellExec(eq("test"), eq("test"), eq("-f"), anyString())).thenReturn(OK);
        when(incus.shellExec(eq("test"), eq("cat"), anyString())).thenReturn(
                new IncusClient.ExecResult(0, existingConfig, ""));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        GuestProvisioning.updateCodexTrust(container, imageDef, Map.of());

        // Should not write since path already trusted
        verify(incus, never()).shellExec(eq("test"), eq("sh"), eq("-c"), anyString());
    }

    // --- refreshInheritedTools ---

    @Test
    void inheritedGeminiGetsAPlaceholderAndLosesTheLoginNotice() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.shellExec("test", "cat", "/home/agentuser/.gemini/settings.json"))
                .thenReturn(new IncusClient.ExecResult(0, "{\"model\":{\"name\":\"inherited-model\"}}", ""));
        var container = new Container(incus, "test");
        var ancestors = List.of(new BuildTools.ResolvedTool("gemini-cli",
                new dev.incusspawn.tool.GeminiSetup(), Map.of()));
        var child = new ImageDef();
        child.setName("tpl-child");

        GuestProvisioning.refreshInheritedTools(container, new BuildTools.ToolResolution(List.of(), ancestors));
        GuestProvisioning.writeEnvFile(container, child, Map.of(), ancestors, "tpl-child");

        verify(incus).shellExec("test", "rm", "-f", "/etc/profile.d/isx-gemini-login.sh");
        var environment = capturedWrite(incus, "/etc/profile.d/isx-env.sh");
        assertNotNull(environment);
        assertTrue(environment.contains("GEMINI_API_KEY="), environment);
        assertTrue(environment.contains("gemini-placeholder"), environment);
        var settings = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(
                capturedWrite(incus, "/home/agentuser/.gemini/settings.json"));
        assertEquals("inherited-model", settings.getField("model").pathText("name", null));
        assertEquals("gemini-api-key", settings.getField("security").getField("auth").pathText("selectedType", null));
    }

    @Test
    void refreshInheritedToolsRewritesWhatInheritedClaudeAndCodexOwn() {
        // A parent built before #1108 has neither script, and a child that only inherits the
        // tools never runs their setup: without these its model calls present a stale token
        var claudeSetup = spy(new ClaudeSetup());
        doNothing().when(claudeSetup).syncGcloudStub(any(), any());

        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        var container = new Container(incus, "test");

        var ancestors = List.of(
                new BuildTools.ResolvedTool("claude", claudeSetup, Map.of()),
                new BuildTools.ResolvedTool("codex", new CodexSetup(), Map.of()));
        var resolution = new BuildTools.ToolResolution(List.of(), ancestors);

        GuestProvisioning.refreshInheritedTools(container, resolution);

        verify(claudeSetup).syncGcloudStub(eq(container), any());
        verify(incus).shellExec(eq("test"), eq("sh"), eq("-c"), contains("cat > '/etc/profile.d/isx-zz-claude-auth.sh'"));
        verify(incus).shellExec(eq("test"), eq("sh"), eq("-c"), contains("cat > '/etc/profile.d/isx-zz-codex-auth.sh'"));
    }

    @Test
    void refreshInheritedToolsSkipsAToolTheLayerSetsUpItself() {
        var claudeSetup = spy(new ClaudeSetup());

        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var resolved = new BuildTools.ResolvedTool("claude", claudeSetup, java.util.Map.of());
        var ancestors = List.of(resolved);
        var effective = List.of(resolved);
        var resolution = new BuildTools.ToolResolution(effective, ancestors);

        GuestProvisioning.refreshInheritedTools(container, resolution);

        verify(claudeSetup, never()).refreshInherited(any());
        verifyNoInteractions(incus);
    }

    @Test
    void refreshInheritedToolsLeavesAToolWithNothingToRefreshAlone() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var mavenSetup = BuildCommandTest.simpleToolSetup("maven");
        var ancestors = List.of(
                new BuildTools.ResolvedTool("maven", mavenSetup, java.util.Map.of()));
        var effective = List.<BuildTools.ResolvedTool>of();
        var resolution = new BuildTools.ToolResolution(effective, ancestors);

        GuestProvisioning.refreshInheritedTools(container, resolution);

        verifyNoInteractions(incus);
    }

    /** The auto-added note opens the Tools group it describes, before any tool's own lines (#1935). */
    @Test
    void runToolSetupNotesAutoAddedToolsInsideTheToolsGroup() {
        var container = new Container(mock(IncusClient.class), "test");
        var orca = new ToolSetup() {
            @Override public String name() { return "orca"; }
            @Override public void install(Container c, Map<String, String> params) {
                BuildOutput.step("Installing orca");
            }
        };
        var resolution = new BuildTools.ToolResolution(
                List.of(new BuildTools.ResolvedTool("sshd", BuildCommandTest.simpleToolSetup("sshd"), Map.of()),
                        new BuildTools.ResolvedTool("orca", orca, Map.of())),
                List.of(),
                List.of(new BuildTools.AutoAdded("sshd", List.of("orca"))));

        var recorded = RecordingOutput.during(() -> GuestProvisioning.runToolSetup(container, resolution, Map.of()));

        assertEquals(List.of(
                        new RecordingOutput.Event(RecordingOutput.Kind.GROUP, "Tools", "sshd, orca"),
                        new RecordingOutput.Event(RecordingOutput.Kind.NOTE,
                                "Adding the sshd tool automatically: the orca tool requires it"),
                        new RecordingOutput.Event(RecordingOutput.Kind.STEP, "Installing orca"),
                        new RecordingOutput.Event(RecordingOutput.Kind.GROUP_END, null)),
                recorded.events());
    }

    // --- Shared DNF cache volume: VM mount, teardown, and the cleanup guard ---

    private static GuestProvisioning dnfCacheCommand(IncusClient incus) {
        var cmd = new GuestProvisioning(incus);
        when(incus.findCowPool()).thenReturn("cow");
        return cmd;
    }

    @Test
    void attachDnfCacheAddsTheVolumeDeviceWithoutWaitingForTheGuest() {
        // Attached before start (#828): a VM mounts boot-time devices before incus-agent serves
        // the first exec, so there is nothing to poll for.
        var incus = mock(IncusClient.class);
        assertNull(dnfCacheCommand(incus).attachDnfCache("b"));

        verify(incus).ensureStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME);
        verify(incus).deviceAdd(eq("b"), eq("dnf-cache"), eq("disk"),
                eq("pool=cow"), eq("source=" + GuestProvisioning.DNF_CACHE_VOLUME),
                eq("path=" + GuestProvisioning.DNF_CACHE_PATH));
        verify(incus, never()).pollUntilReady(anyString(), anyInt(), any(String[].class));
        verify(incus, never()).shellExec(anyString(), any(String[].class));
    }

    @Test
    void attachDnfCacheReportsFailureInsteadOfPrintingInsideTheLaunchStep() {
        var incus = mock(IncusClient.class);
        doThrow(new RuntimeException("pool gone")).when(incus).ensureStorageVolume(anyString(), anyString());

        assertEquals("pool gone", dnfCacheCommand(incus).attachDnfCache("b"));
        verify(incus, never()).deviceAdd(anyString(), anyString(), anyString(), any(String[].class));
    }

    @Test
    void unmountDnfCacheUnmountsInVmGuestBeforeRemovingDevice() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("b"), any(String[].class))).thenReturn(OK);
        dnfCacheCommand(incus).unmountDnfCache("b", MachineType.VM);

        var captor = ArgumentCaptor.forClass(String.class);
        var order = inOrder(incus);
        order.verify(incus).shellExec(eq("b"), eq("sh"), eq("-c"), captor.capture());
        order.verify(incus).deviceRemove("b", "dnf-cache");
        assertTrue(captor.getValue().contains("umount " + GuestProvisioning.DNF_CACHE_PATH),
                captor.getValue());
    }

    @Test
    void unmountDnfCacheSkipsGuestUnmountForContainers() {
        var incus = mock(IncusClient.class);
        dnfCacheCommand(incus).unmountDnfCache("b", MachineType.CONTAINER);

        verify(incus, never()).shellExec(anyString(), any(String[].class));
        verify(incus).deviceRemove("b", "dnf-cache");
    }

    @Test
    void cleanCachesNeverCleansThroughALiveCacheMount(@TempDir Path tempDir) throws Exception {
        // Run the real script against a stand-in cache dir, with `mountpoint` and `dnf`
        // stubbed on PATH, so the guard is checked by behaviour rather than by string shape.
        var script = cleanCachesScript(tempDir);
        for (boolean mounted : new boolean[] {true, false}) {
            var cache = Files.createDirectories(tempDir.resolve("cache"));
            Files.writeString(cache.resolve("repomd.xml"), "x");
            var dnfLog = tempDir.resolve("dnf.log");
            Files.deleteIfExists(dnfLog);
            assertEquals(0, runWithStubs(tempDir, script, mounted, "echo \"$@\" >> " + dnfLog));

            assertEquals(mounted, Files.exists(cache.resolve("repomd.xml")),
                    "mounted=" + mounted);
            assertEquals(!mounted, Files.exists(dnfLog), "dnf clean ran, mounted=" + mounted);
        }
    }

    @Test
    void cleanCachesEmptiesTmpHiddenFilesIncluded(@TempDir Path tempDir) throws Exception {
        // /tmp is on disk now (#1561): whatever the build leaves there is saved into the template
        var script = cleanCachesScript(tempDir);
        var dirs = new Path[] {tempDir.resolve("tmp"), tempDir.resolve("var-tmp")};
        for (var dir : dirs) {
            Files.writeString(dir.resolve("visible"), "x");
            Files.writeString(dir.resolve(".hidden"), "x");
            Files.writeString(Files.createDirectories(dir.resolve(".cache/sub")).resolve(".f"), "x");
        }
        assertEquals(0, runWithStubs(tempDir, script, false, "true"));

        for (var dir : dirs) {
            assertTrue(Files.isDirectory(dir), dir + " itself stays");
            try (var left = Files.list(dir)) {
                assertEquals(List.of(), left.toList(), dir + " is empty");
            }
        }
    }

    /**
     * The script cleanCaches runs, with the DNF cache, /tmp and /var/tmp pointed at stand-ins
     * under {@code tempDir} ({@code cache}, {@code tmp}, {@code var-tmp}), never the real ones.
     */
    private static String cleanCachesScript(Path tempDir) throws Exception {
        var incus = mock(IncusClient.class);
        dnfCacheCommand(incus).cleanCaches("b");
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus).shellExec(eq("b"), eq("sh"), eq("-c"), captor.capture());
        // Placeholders first: the stand-ins themselves sit under the real /tmp
        var script = captor.getValue().replace("/var/tmp", "@VARTMP@").replace("/tmp", "@TMP@");
        assertFalse(script.contains("/tmp"), script);
        return script
                .replace("@VARTMP@", Files.createDirectories(tempDir.resolve("var-tmp")).toString())
                .replace("@TMP@", Files.createDirectories(tempDir.resolve("tmp")).toString())
                .replace(GuestProvisioning.DNF_CACHE_PATH, tempDir.resolve("cache").toString());
    }

    /** Runs {@code script} with `mountpoint` answering {@code mounted} and `dnf` running {@code dnf}. */
    private static int runWithStubs(Path tempDir, String script, boolean mounted, String dnf) throws Exception {
        var bin = Files.createDirectories(tempDir.resolve("bin"));
        writeStub(bin.resolve("mountpoint"), "exit " + (mounted ? 0 : 1));
        writeStub(bin.resolve("dnf"), dnf);
        var pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        return pb.start().waitFor();
    }

    @Test
    void disableSelinuxSeedsAConfigThatSelinuxPolicyPostKeeps(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("etc/selinux/config");
        assertEquals(0, runSh(GuestProvisioning.disableSelinuxScript(config.toString())));
        assertTrue(Files.readString(config).lines().anyMatch("SELINUX=disabled"::equals));

        // selinux-policy's %post writes SELINUX=enforcing only when the file is missing or empty (#842)
        var post = "if [ ! -s " + config + " ]; then echo SELINUX=enforcing > " + config + "; fi";
        assertEquals(0, runSh(post));
        assertEquals(0, runSh(GuestProvisioning.selinuxNotEnforcingScript(config.toString())));
    }

    @Test
    void disableSelinuxRewritesAnEnforcingConfigFromAnOlderParent(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("config");
        Files.writeString(config, "# comment SELINUX=enforcing\n  SELINUX=enforcing\nSELINUXTYPE=targeted\n");
        assertEquals(1, runSh(GuestProvisioning.selinuxNotEnforcingScript(config.toString())));

        assumeTrue(Platform.isLinux(), "the rest runs a guest script with the host's tools, and BSD sed -i"
                + " takes a backup suffix");
        assertEquals(0, runSh(GuestProvisioning.disableSelinuxScript(config.toString())));
        assertEquals("# comment SELINUX=enforcing\nSELINUX=disabled\nSELINUXTYPE=targeted\n",
                Files.readString(config));
        assertEquals(0, runSh(GuestProvisioning.selinuxNotEnforcingScript(config.toString())));
    }

    @Test
    void selinuxGuardFlagsOnlyEnforcing(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("config");
        var check = GuestProvisioning.selinuxNotEnforcingScript(config.toString());
        assertEquals(0, runSh(check), "no config: no policy is loaded");
        for (var line : new String[] {"SELINUX=disabled", "SELINUX=permissive", "#SELINUX=enforcing"}) {
            Files.writeString(config, line + "\n");
            assertEquals(0, runSh(check), line);
        }
        for (var line : new String[] {"SELINUX=enforcing", "SELINUX=\"enforcing\"", "SELINUX=Enforcing"}) {
            Files.writeString(config, line + "\n");
            assertEquals(1, runSh(check), line);
        }
    }

    @Test
    void selinuxGuardFailsTheBuildWhenTheGuestWouldBootEnforcing() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "b");
        var check = GuestProvisioning.selinuxNotEnforcingScript(GuestProvisioning.SELINUX_CONFIG);

        when(incus.shellExec("b", "sh", "-c", check)).thenReturn(OK);
        assertDoesNotThrow(() -> GuestProvisioning.assertGuestSelinuxNotEnforcing(container));

        when(incus.shellExec("b", "sh", "-c", check)).thenReturn(FAIL);
        var e = assertThrows(IllegalStateException.class, () -> GuestProvisioning.assertGuestSelinuxNotEnforcing(container));
        assertTrue(e.getMessage().contains("enforcing"), e.getMessage());
    }

    private static int runSh(String script) throws Exception {
        return new ProcessBuilder("sh", "-c", script).inheritIO().start().waitFor();
    }

    private static void writeStub(Path path, String body) throws Exception {
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        path.toFile().setExecutable(true);
    }

    @Test
    void packageRepoNamesAreShownAsText() {
        // A template, project-local included, names its package repositories (#1610 follow-up).
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(new IncusClient.ExecResult(1, "", "no"));
        // The copr plugin is present, so the failure comes from enabling the COPR repo itself
        when(incus.shellExec("test", "sh", "-c", GuestProvisioning.MISSING_COPR_PLUGIN)).thenReturn(OK);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-app");
        imageDef.setPackageRepos(List.of(
                new ImageDef.PackageRepo("x\033]0;pwned\007", "a\nb"),
                new ImageDef.PackageRepo("copr", "owner/r\nERROR: forged\033[2J")));
        var ex = new dev.incusspawn.incus.IncusException[1];
        var said = RecordingOutput.during(() -> ex[0] = assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> GuestProvisioning.enablePackageRepos(new Container(incus, "test"), imageDef, List.of(), List.of(), Map.of())));
        assertTrue(said.warnings().contains("Unknown package_repos type 'x ]0;pwned ' for 'a b', skipping it"), said.warnings().toString());
        // Everything reported, a live step's progress, the failed COPR step and its output included, shows the names as text
        said.events().stream().map(RecordingOutput.Event::text).filter(java.util.Objects::nonNull).forEach(w -> assertEquals(dev.incusspawn.util.TerminalLink.plainText(w), w, w.replace("\033", "ESC")));
        assertTrue(ex[0].getMessage().startsWith("Failed to enable COPR repo "), ex[0].getMessage());
        assertFalse(ex[0].getMessage().contains("\n"), "a name cannot forge a line in the failure report: " + ex[0].getMessage());
    }
}
