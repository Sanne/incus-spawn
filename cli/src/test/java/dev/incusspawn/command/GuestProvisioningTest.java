package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.Platform;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.ToolSetup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef);

        var writtenJson = capturedWrite(incus, ".claude.json");
        assertNotNull(writtenJson, "Expected .claude.json to be written");

        var mapper = new ObjectMapper();
        var root = (ObjectNode) mapper.readTree(writtenJson);

        // Original fields preserved
        assertTrue(root.get("hasCompletedOnboarding").asBoolean());

        // Original project trust preserved
        var projects = (ObjectNode) root.get("projects");
        assertTrue(projects.has("/home/agentuser"));
        assertTrue(projects.get("/home/agentuser").get("hasTrustDialogAccepted").asBoolean());

        // New repo project trust added
        assertTrue(projects.has("/home/agentuser/quarkus"));
        assertTrue(projects.get("/home/agentuser/quarkus").get("hasTrustDialogAccepted").asBoolean());

        // GitHub repo path added
        var githubPaths = (ObjectNode) root.get("githubRepoPaths");
        assertTrue(githubPaths.has("quarkusio/quarkus"));
        assertEquals("/home/agentuser/quarkus", githubPaths.get("quarkusio/quarkus").get(0).asText());
    }

    @Test
    void updateClaudeJsonTrustNoopWhenNoRepos() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-empty");
        // repos defaults to empty

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef);

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

        GuestProvisioning.updateClaudeJsonTrust(container, imageDef);

        // Should check for file but not attempt to read/write it
        verify(incus).shellExec(eq("test"), eq("test"), eq("-f"), anyString());
        verify(incus, never()).shellExec(eq("test"), eq("cat"), anyString());
    }

    // --- writeAgentContext ---

    /** Pull the heredoc body out of the last writeFile call that targeted {@code path}. */
    private static String capturedWrite(IncusClient incus, String path) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq("test"), eq("sh"), eq("-c"), captor.capture());
        String written = null;
        for (var call : captor.getAllValues()) {
            if (call.contains(path)) {
                var start = call.indexOf('\n') + 1;
                var end = call.lastIndexOf("\nINCUS_EOF");
                if (start > 0 && end > start) {
                    written = call.substring(start, end);
                }
            }
        }
        return written;
    }

    @Test
    void writeAgentContextWritesManagedPolicyPath() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-solo");

        GuestProvisioning.writeAgentContext(container, imageDef, Map.of(), List.of(), "tpl-solo");

        var written = capturedWrite(incus, "/etc/claude-code/CLAUDE.md");
        assertNotNull(written, "should write to the managed policy location");
        assertTrue(written.contains("built from the `tpl-solo` template"));
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

        var written = capturedWrite(incus, "/etc/claude-code/CLAUDE.md");
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

        var written = capturedWrite(incus, "/etc/claude-code/CLAUDE.md");
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

        var written = capturedWrite(incus, "/etc/claude-code/CLAUDE.md");
        assertNotNull(written);
        assertTrue(written.contains("`~/quarkus`"), "path should be derived from the url");
        assertFalse(written.contains("null"), "an unclonable entry must not be listed");
        assertFalse(written.contains("``"), "a blank path must not render an empty bullet");
        assertEquals(1, written.lines().filter(l -> l.contains("is checked out at")).count(),
                "~/quarkus and /home/agentuser/quarkus are one clone, not two");
    }

    // --- updateCodexTrust ---

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

        GuestProvisioning.updateCodexTrust(container, imageDef);

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

        GuestProvisioning.updateCodexTrust(container, imageDef);

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

        GuestProvisioning.updateCodexTrust(container, imageDef);

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

        GuestProvisioning.updateCodexTrust(container, imageDef);

        // Should not write since path already trusted
        verify(incus, never()).shellExec(eq("test"), eq("sh"), eq("-c"), anyString());
    }

    // --- refreshInheritedTools ---

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
        var incus = mock(IncusClient.class);
        var cmd = dnfCacheCommand(incus);
        cmd.cleanCaches("b");
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus).shellExec(eq("b"), eq("sh"), eq("-c"), captor.capture());
        var script = captor.getValue()
                .replace(GuestProvisioning.DNF_CACHE_PATH, tempDir.resolve("cache").toString())
                .replace("rm -rf /tmp/* /var/tmp/*", "true");

        for (boolean mounted : new boolean[] {true, false}) {
            var cache = Files.createDirectories(tempDir.resolve("cache"));
            Files.writeString(cache.resolve("repomd.xml"), "x");
            var bin = Files.createDirectories(tempDir.resolve("bin"));
            var dnfLog = tempDir.resolve("dnf.log");
            Files.deleteIfExists(dnfLog);
            writeStub(bin.resolve("mountpoint"), "exit " + (mounted ? 0 : 1));
            writeStub(bin.resolve("dnf"), "echo \"$@\" >> " + dnfLog);

            var pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
            pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            assertEquals(0, pb.start().waitFor());

            assertEquals(mounted, Files.exists(cache.resolve("repomd.xml")),
                    "mounted=" + mounted);
            assertEquals(!mounted, Files.exists(dnfLog), "dnf clean ran, mounted=" + mounted);
        }
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
}
