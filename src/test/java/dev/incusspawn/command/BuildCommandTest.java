package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildCommandTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");

    @Test
    void expandHomeTilde() {
        assertEquals("/home/agentuser/quarkus", BuildCommand.expandHome("~/quarkus"));
    }

    @Test
    void expandHomeTildeOnly() {
        assertEquals("/home/agentuser", BuildCommand.expandHome("~"));
    }

    @Test
    void expandHomeAbsolutePathUnchanged() {
        assertEquals("/opt/something", BuildCommand.expandHome("/opt/something"));
    }

    @Test
    void parseGitHubOwnerRepoWithDotGit() {
        assertEquals("quarkusio/quarkus",
                BuildCommand.parseGitHubOwnerRepo("https://github.com/quarkusio/quarkus.git"));
    }

    @Test
    void parseGitHubOwnerRepoWithoutDotGit() {
        assertEquals("hibernate/hibernate-reactive",
                BuildCommand.parseGitHubOwnerRepo("https://github.com/hibernate/hibernate-reactive"));
    }

    @Test
    void parseGitHubOwnerRepoTrailingSlash() {
        assertEquals("owner/repo",
                BuildCommand.parseGitHubOwnerRepo("https://github.com/owner/repo/"));
    }

    @Test
    void parseGitHubOwnerRepoNonGitHub() {
        assertNull(BuildCommand.parseGitHubOwnerRepo("https://gitlab.com/some/repo.git"));
    }

    @Test
    void parseGitHubOwnerRepoNull() {
        assertNull(BuildCommand.parseGitHubOwnerRepo(null));
    }

    @Test
    void parseGitHubOwnerRepoSshFormat() {
        assertNull(BuildCommand.parseGitHubOwnerRepo("git@github.com:owner/repo.git"));
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

        var cmd = new BuildCommand();
        cmd.updateClaudeJsonTrust(container, imageDef);

        // Capture the writeFile call (sh -c "cat > ...")
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq("test"), eq("sh"), eq("-c"), captor.capture());

        // Find the cat > .claude.json call
        String writtenJson = null;
        for (var call : captor.getAllValues()) {
            if (call.contains(".claude.json")) {
                // Extract the content between heredoc markers
                var start = call.indexOf('\n') + 1;
                var end = call.lastIndexOf("\nINCUS_EOF");
                if (start > 0 && end > start) {
                    writtenJson = call.substring(start, end);
                }
            }
        }
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

        var cmd = new BuildCommand();
        cmd.updateClaudeJsonTrust(container, imageDef);

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

        var cmd = new BuildCommand();
        cmd.updateClaudeJsonTrust(container, imageDef);

        // Should check for file but not attempt to read/write it
        verify(incus).shellExec(eq("test"), eq("test"), eq("-f"), anyString());
        verify(incus, never()).shellExec(eq("test"), eq("cat"), anyString());
    }

    @Test
    void cloneReposRunsPrimeCommand() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        // git clone succeeds
        when(incus.shellExecInteractive(eq("test"), any(String[].class))).thenReturn(0);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        repo.setPrime("mvn -B dependency:go-offline");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        var cmd = new BuildCommand();
        cmd.cloneRepos(container, imageDef);

        verify(incus).shellExecInteractive("test",
                "su", "-l", "agentuser", "-c",
                "git clone --single-branch -- 'https://github.com/quarkusio/quarkus.git' '/home/agentuser/quarkus'");
        verify(incus).shellExecInteractive("test",
                "su", "-l", "agentuser", "-c",
                "git -C '/home/agentuser/quarkus' remote set-branches origin '*'");
        verify(incus).shellExecInteractive("test",
                "su", "-l", "agentuser", "-c",
                "cd '/home/agentuser/quarkus' && mvn -B dependency:go-offline");
    }

    // --- resolveSkillSource ---

    @Test
    void resolveSkillSourceUrl() {
        assertEquals("https://github.com/owner/repo",
                BuildCommand.resolveSkillSource("https://github.com/owner/repo", null));
    }

    @Test
    void resolveSkillSourceLocalRelativePath() {
        assertEquals("./my-local-skills",
                BuildCommand.resolveSkillSource("./my-local-skills", null));
    }

    @Test
    void resolveSkillSourceLocalAbsolutePath() {
        assertEquals("/opt/skills",
                BuildCommand.resolveSkillSource("/opt/skills", null));
    }

    @Test
    void resolveSkillSourceFullOwnerRepo() {
        assertEquals("myorg/other-catalog@special-skill",
                BuildCommand.resolveSkillSource("myorg/other-catalog@special-skill", null));
    }

    @Test
    void resolveSkillSourceOwnerRepoNoSkill() {
        assertEquals("myorg/catalog",
                BuildCommand.resolveSkillSource("myorg/catalog", null));
    }

    @Test
    void resolveSkillSourceShortNameWithRepo() {
        assertEquals("myorg/claude-skills@security-review",
                BuildCommand.resolveSkillSource("security-review", "myorg/claude-skills"));
    }

    @Test
    void resolveSkillSourceShortNameWithoutRepoThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> BuildCommand.resolveSkillSource("security-review", null));
    }

    @Test
    void resolveSkillSourceShortNameBlankRepoThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> BuildCommand.resolveSkillSource("security-review", ""));
    }

    // --- collectEffectiveSkills ---

    @Test
    void collectEffectiveSkillsNoSkills() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-empty");
        var cmd = new BuildCommand();
        assertTrue(cmd.collectEffectiveSkills(imageDef, java.util.Map.of()).isEmpty());
    }

    @Test
    void collectEffectiveSkillsNoParent() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-root");
        imageDef.setSkills(new ImageDef.SkillsDef(null, List.of("security-review", "code-review")));
        var cmd = new BuildCommand();
        assertEquals(List.of("security-review", "code-review"),
                cmd.collectEffectiveSkills(imageDef, java.util.Map.of()));
    }

    @Test
    void collectEffectiveSkillsDeduplicatesParentSkills() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setSkills(new ImageDef.SkillsDef(null, List.of("security-review")));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setSkills(new ImageDef.SkillsDef(null, List.of("security-review", "code-review")));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var cmd = new BuildCommand();
        var effective = cmd.collectEffectiveSkills(child, defs);

        assertEquals(List.of("code-review"), effective,
                "security-review already in parent should be excluded");
    }

    @Test
    void collectEffectiveSkillsDeduplicatesAcrossGrandparent() {
        var grandparent = new ImageDef();
        grandparent.setName("tpl-grandparent");
        grandparent.setSkills(new ImageDef.SkillsDef(null, List.of("base-skill")));

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setParent("tpl-grandparent");
        parent.setSkills(new ImageDef.SkillsDef(null, List.of("parent-skill")));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setSkills(new ImageDef.SkillsDef(null, List.of("base-skill", "parent-skill", "child-skill")));

        var defs = java.util.Map.of(
                "tpl-grandparent", grandparent,
                "tpl-parent", parent,
                "tpl-child", child);
        var cmd = new BuildCommand();
        var effective = cmd.collectEffectiveSkills(child, defs);

        assertEquals(List.of("child-skill"), effective,
                "Only child-skill should remain after deduplication");
    }

    @Test
    void shellQuoteWrapsInSingleQuotes() {
        assertEquals("'hello'", BuildCommand.shellQuote("hello"));
    }

    @Test
    void shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\"'\"'s'", BuildCommand.shellQuote("it's"));
    }

    @Test
    void shellQuoteHandlesEmpty() {
        assertEquals("''", BuildCommand.shellQuote(""));
    }

    @Test
    void cloneReposWithBranch() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.shellExecInteractive(eq("test"), any(String[].class))).thenReturn(0);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("feature/my branch");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = new BuildCommand();
        cmd.cloneRepos(container, imageDef);

        verify(incus).shellExecInteractive("test",
                "su", "-l", "agentuser", "-c",
                "git clone --single-branch --branch 'feature/my branch' -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        verify(incus).shellExecInteractive("test",
                "su", "-l", "agentuser", "-c",
                "git -C '/home/agentuser/repo' remote set-branches origin '*'");
    }

    @Test
    void cloneReposSkipsPrimeWhenNotSet() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        when(incus.shellExecInteractive(eq("test"), any(String[].class))).thenReturn(0);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        // no prime set

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = new BuildCommand();
        cmd.cloneRepos(container, imageDef);

        // Clone call + refspec restore, but no prime
        verify(incus, times(2)).shellExecInteractive(eq("test"), any(String[].class));
    }

    @Test
    void shouldSkipDueToFailedParentDirectParent() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var failedBuilds = new java.util.HashSet<String>();
        failedBuilds.add("tpl-parent");

        var cmd = new BuildCommand();
        assertTrue(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should be skipped when parent failed");
    }

    @Test
    void shouldSkipDueToFailedParentGrandparent() {
        var grandparent = new ImageDef();
        grandparent.setName("tpl-grandparent");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setParent("tpl-grandparent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of(
                "tpl-grandparent", grandparent,
                "tpl-parent", parent,
                "tpl-child", child);
        var failedBuilds = new java.util.HashSet<String>();
        failedBuilds.add("tpl-grandparent");

        var cmd = new BuildCommand();
        assertTrue(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should be skipped when grandparent failed");
    }

    @Test
    void shouldSkipDueToFailedParentNoFailures() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var failedBuilds = new java.util.HashSet<String>();

        var cmd = new BuildCommand();
        assertFalse(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should not be skipped when no failures");
    }

    @Test
    void shouldSkipDueToFailedParentUnrelatedFailure() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var unrelated = new ImageDef();
        unrelated.setName("tpl-unrelated");

        var defs = java.util.Map.of(
                "tpl-parent", parent,
                "tpl-child", child,
                "tpl-unrelated", unrelated);
        var failedBuilds = new java.util.HashSet<String>();
        failedBuilds.add("tpl-unrelated");

        var cmd = new BuildCommand();
        assertFalse(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should not be skipped when only unrelated template failed");
    }

    @Test
    void shouldSkipDueToFailedParentRootImage() {
        var root = new ImageDef();
        root.setName("tpl-root");
        root.setImage("fedora/41");

        var defs = java.util.Map.of("tpl-root", root);
        var failedBuilds = new java.util.HashSet<String>();

        var cmd = new BuildCommand();
        assertFalse(cmd.shouldSkipDueToFailedParent(root, defs, failedBuilds),
                "Root image should never be skipped due to parent");
    }

    @Test
    void isImageOutdatedNotExists() {
        var incus = mock(IncusClient.class);
        when(incus.exists("tpl-test")).thenReturn(false);

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        var toolDefLoader = mock(dev.incusspawn.tool.ToolDefLoader.class);
        var defs = java.util.Map.of("tpl-test", imageDef);

        assertFalse(BuildCommand.isImageOutdated("tpl-test", imageDef, incus, toolDefLoader, defs, false),
                "Non-existent image should not be considered outdated");
    }

    @Test
    void isImageOutdatedDifferentVersion() {
        var incus = mock(IncusClient.class);
        when(incus.exists("tpl-test")).thenReturn(true);
        when(incus.configGet("tpl-test", "user.incus-spawn.build-version")).thenReturn("0.0.1");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        var toolDefLoader = mock(dev.incusspawn.tool.ToolDefLoader.class);
        var defs = java.util.Map.of("tpl-test", imageDef);

        assertTrue(BuildCommand.isImageOutdated("tpl-test", imageDef, incus, toolDefLoader, defs, false),
                "Image with different version should be outdated");
    }

    @Test
    void isImageOutdatedMissingVersion() {
        var incus = mock(IncusClient.class);
        when(incus.exists("tpl-test")).thenReturn(true);
        when(incus.configGet("tpl-test", "user.incus-spawn.build-version")).thenReturn("");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        var toolDefLoader = mock(dev.incusspawn.tool.ToolDefLoader.class);
        var defs = java.util.Map.of("tpl-test", imageDef);

        assertTrue(BuildCommand.isImageOutdated("tpl-test", imageDef, incus, toolDefLoader, defs, false),
                "Image with missing version should be outdated");
    }

    @Test
    void isImageOutdatedSameVersionNoDefinitionChange() {
        var incus = mock(IncusClient.class);
        when(incus.exists("tpl-test")).thenReturn(true);
        when(incus.configGet("tpl-test", "user.incus-spawn.build-version"))
                .thenReturn(dev.incusspawn.BuildInfo.instance().version());
        when(incus.configGet("tpl-test", "user.incus-spawn.definition-sha")).thenReturn("");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        var toolDefLoader = mock(dev.incusspawn.tool.ToolDefLoader.class);
        var defs = java.util.Map.of("tpl-test", imageDef);

        assertFalse(BuildCommand.isImageOutdated("tpl-test", imageDef, incus, toolDefLoader, defs, false),
                "Image with same version and no definition SHA should not be outdated");
    }

    @Test
    void isImageOutdatedDefinitionChanged() {
        var incus = mock(IncusClient.class);
        when(incus.exists("tpl-test")).thenReturn(true);
        when(incus.configGet("tpl-test", "user.incus-spawn.build-version"))
                .thenReturn(dev.incusspawn.BuildInfo.instance().version());
        when(incus.configGet("tpl-test", "user.incus-spawn.definition-sha"))
                .thenReturn("old-sha-123");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        var toolDefLoader = mock(dev.incusspawn.tool.ToolDefLoader.class);
        var defs = java.util.Map.of("tpl-test", imageDef);

        assertTrue(BuildCommand.isImageOutdated("tpl-test", imageDef, incus, toolDefLoader, defs, false),
                "Image with changed definition should be outdated");
    }
}
