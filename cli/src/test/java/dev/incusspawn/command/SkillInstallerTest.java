package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.ToolSetup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillInstallerTest {

    // --- resolveSkillSource ---

    @Test
    void resolveSkillSourceUrl() {
        assertEquals("https://github.com/owner/repo",
                SkillInstaller.resolveSkillSource("https://github.com/owner/repo", null));
    }

    @Test
    void resolveSkillSourceLocalRelativePath() {
        assertEquals("./my-local-skills",
                SkillInstaller.resolveSkillSource("./my-local-skills", null));
    }

    @Test
    void resolveSkillSourceLocalAbsolutePath() {
        assertEquals("/opt/skills",
                SkillInstaller.resolveSkillSource("/opt/skills", null));
    }

    @Test
    void resolveSkillSourceFullOwnerRepo() {
        assertEquals("myorg/other-catalog@special-skill",
                SkillInstaller.resolveSkillSource("myorg/other-catalog@special-skill", null));
    }

    @Test
    void resolveSkillSourceOwnerRepoNoSkill() {
        assertEquals("myorg/catalog",
                SkillInstaller.resolveSkillSource("myorg/catalog", null));
    }

    @Test
    void resolveSkillSourceShortNameWithRepo() {
        assertEquals("myorg/claude-skills@security-review",
                SkillInstaller.resolveSkillSource("security-review", "myorg/claude-skills"));
    }

    @Test
    void resolveSkillSourceShortNameWithoutRepoThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.resolveSkillSource("security-review", null));
    }

    @Test
    void resolveSkillSourceShortNameBlankRepoThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.resolveSkillSource("security-review", ""));
    }

    // --- collectEffectiveSkills ---

    @Test
    void collectEffectiveSkillsNoSkills() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-empty");
        assertTrue(SkillInstaller.collectEffectiveSkills(imageDef, java.util.Map.of()).isEmpty());
    }

    @Test
    void collectEffectiveSkillsNoParent() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-root");
        imageDef.setSkills(new ImageDef.SkillsDef(null, List.of("security-review", "code-review")));
        assertEquals(List.of("security-review", "code-review"),
                SkillInstaller.collectEffectiveSkills(imageDef, java.util.Map.of()));
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
        var effective = SkillInstaller.collectEffectiveSkills(child, defs);

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
        var effective = SkillInstaller.collectEffectiveSkills(child, defs);

        assertEquals(List.of("child-skill"), effective,
                "Only child-skill should remain after deduplication");
    }

    static ToolSetup namedTool(String name, String agentNote) {
        return new ToolSetup() {
            @Override public String name() { return name; }
            @Override public String agentNote() { return agentNote; }
            @Override public void install(Container c, Map<String, String> p) {}
        };
    }

    private static ToolSetup toolWithSkills(String name, ImageDef.SkillsDef skills) {
        return new ToolSetup() {
            @Override public String name() { return name; }
            @Override public ImageDef.SkillsDef skills() { return skills; }
            @Override public void install(Container c, Map<String, String> p) {}
        };
    }

    @Test
    void toolSkillsDefaultToNone() {
        assertTrue(namedTool("zmx", null).skills().getList().isEmpty(),
                "skills must stay opt-in: most tools need none");
    }

    @Test
    void toolSkillsResolveAgainstTheirOwnRepo() {
        // A bare name in a tool must resolve against the tool's skills.repo, not the
        // image's — the tool travels into templates that never heard of its catalog.
        var skills = new ImageDef.SkillsDef("owner/catalog", List.of("mvnd-builds"));
        var tool = toolWithSkills("mvnd", skills);
        assertEquals("owner/catalog@mvnd-builds",
                SkillInstaller.resolveSkillSource(tool.skills().getList().get(0), tool.skills().getRepo()));
    }

    @Test
    void installSkillsIsANoOpWhenNeitherImageNorToolsDeclareAny() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-bare");

        SkillInstaller.installSkills(container, imageDef, Map.of(),
                List.of(new BuildTools.ResolvedTool("zmx", namedTool("zmx", null), Map.of())), SkillInstaller.ProjectTools.NONE);

        verifyNoInteractions(incus);
    }

    @Test
    void reconfigureOnlyToolsDoNotRefetchTheirSkills() {
        // A reconfigureOnly tool was installed by an ancestor, so its skills are already
        // in the image. Re-fetching would make a parameter-only rebuild fail whenever the
        // skill source happens to be unreachable.
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-child");

        var tool = toolWithSkills("mvnd", new ImageDef.SkillsDef("owner/catalog", List.of("mvnd-builds")));
        SkillInstaller.installSkills(container, imageDef, Map.of(),
                List.of(new BuildTools.ResolvedTool("mvnd", tool, Map.of("version", "1.1"), true)), SkillInstaller.ProjectTools.NONE);

        // No mkdir, no write, and crucially no fetch — it never got past collection.
        verifyNoInteractions(incus);
    }

    // --- pinned skill packs (#2354) ---

    private static final String COMMIT = dev.incusspawn.tool.FakeGitHub.COMMIT;

    private static ImageDef.SkillsDef pinned(String... names) {
        return new ImageDef.SkillsDef("o/r", List.of(names), "v1", COMMIT, "skills");
    }

    private static IncusClient execSucceeding() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class)))
                .thenReturn(new IncusClient.ExecResult(0, "", ""));
        return incus;
    }

    /** An image whose agy was built before AntigravitySetup linked its skills still gets the link (#2472). */
    @Test
    void installingSkillsLinksThemForClaudeCodeAndAntigravityCliInOneExec(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cache)
            throws Exception {
        try (var github = new dev.incusspawn.tool.FakeGitHub("o/r").file("skills/rgctl/SKILL.md", "rgctl")) {
            var incus = execSucceeding();
            var imageDef = new ImageDef();
            imageDef.setName("tpl-x");

            SkillInstaller.installSkills(new Container(incus, "b"), imageDef, Map.of(),
                    List.of(new BuildTools.ResolvedTool("rgctl", toolWithSkills("rgctl", pinned("rgctl")), Map.of())),
                    SkillInstaller.ProjectTools.NONE, github.skills(cache));

            verify(incus).shellExec(eq("b"), eq("sh"), eq("-c"), argThat(script ->
                    script.contains("ln -sfn /home/agentuser/.agents/skills /home/agentuser/.claude/skills")
                            && script.contains("! [ -d /home/agentuser/.gemini/antigravity-cli ] || { "
                                    + dev.incusspawn.tool.AntigravitySetup.linkSkillsScript())));
        }
    }

    @Test
    void aPinnedToolSkillReplacesItsDirectoryWhole(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cache)
            throws Exception {
        try (var github = new dev.incusspawn.tool.FakeGitHub("o/r")
                .file("skills/rgctl/SKILL.md", "rgctl")
                .file("skills/rgctl/references/workflows.md", "workflows")) {
            var incus = execSucceeding();
            var imageDef = new ImageDef();
            imageDef.setName("tpl-x");

            SkillInstaller.installSkills(new Container(incus, "b"), imageDef, Map.of(),
                    List.of(new BuildTools.ResolvedTool("rgctl", toolWithSkills("rgctl", pinned("rgctl")), Map.of())),
                    SkillInstaller.ProjectTools.NONE, github.skills(cache));

            // A re-pin's dropped files must not linger: the old directory goes before the new one arrives.
            var order = inOrder(incus);
            order.verify(incus).shellExec("b", "rm", "-rf", "--", "/home/agentuser/.agents/skills/rgctl");
            order.verify(incus).filePushRecursive(
                    cache.resolve("o/r").resolve(COMMIT).resolve("skills/rgctl").toString(),
                    "b", "/home/agentuser/.agents/skills");
            verify(incus, never()).shellExec(eq("b"), eq("sh"), eq("-c"),
                    argThat(script -> script.contains("cat > '/home/agentuser/.agents/skills/rgctl/SKILL.md'")));
        }
    }

    @Test
    void oneSkillFromTwoDifferentPinsFailsTheBuild(@org.junit.jupiter.api.io.TempDir java.nio.file.Path cache)
            throws Exception {
        try (var github = new dev.incusspawn.tool.FakeGitHub("o/r").file("skills/rgctl/SKILL.md", "rgctl")) {
            var incus = execSucceeding();
            var imageDef = new ImageDef();
            imageDef.setName("tpl-x");
            var other = new ImageDef.SkillsDef("o/r", List.of("rgctl"), "v1", COMMIT, "agent-skills");
            var otherGithub = github.file("agent-skills/rgctl/SKILL.md", "other rgctl");

            var recording = dev.incusspawn.util.RecordingOutput.during(() ->
                    assertThrows(BuildCommand.BuildFailedException.class, () -> SkillInstaller.installSkills(
                            new Container(incus, "b"), imageDef, Map.of(),
                            List.of(new BuildTools.ResolvedTool("a", toolWithSkills("a", pinned("rgctl")), Map.of()),
                                    new BuildTools.ResolvedTool("b", toolWithSkills("b", other), Map.of())),
                            SkillInstaller.ProjectTools.NONE, otherGithub.skills(cache))));
            assertTrue(recording.events(dev.incusspawn.util.RecordingOutput.Kind.ERROR).stream()
                    .anyMatch(e -> e.msg().contains("skill 'rgctl' is declared from both")), recording.events().toString());
        }
    }

    @Test
    void aChildThatRepinsAnInheritedSkillInstallsIt() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setSkills(pinned("rgctl"));
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setSkills(new ImageDef.SkillsDef("o/r", List.of("rgctl"), "v2", "2".repeat(40), "skills"));
        var same = new ImageDef();
        same.setName("tpl-same");
        same.setParent("tpl-parent");
        same.setSkills(pinned("rgctl"));
        var defs = Map.of("tpl-parent", parent, "tpl-child", child, "tpl-same", same);

        assertEquals(List.of("rgctl"), SkillInstaller.collectEffectiveSkills(child, defs));
        assertEquals(List.of(), SkillInstaller.collectEffectiveSkills(same, defs));
    }

    @Test
    void aProjectLocalToolsLocalSkillSourceStaysInItsProject(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path elsewhere) throws Exception {
        java.nio.file.Files.createDirectories(elsewhere.resolve("keys"));
        java.nio.file.Files.writeString(elsewhere.resolve("keys/SKILL.md"), "a host file");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");
        var tool = toolWithSkills("x", new ImageDef.SkillsDef(null, List.of(elsewhere.toString())));
        var incus = execSucceeding();

        var recording = dev.incusspawn.util.RecordingOutput.during(() ->
                assertThrows(BuildCommand.BuildFailedException.class, () -> SkillInstaller.installSkills(
                        new Container(incus, "b"), imageDef, Map.of(),
                        List.of(new BuildTools.ResolvedTool("x", tool, Map.of())),
                        new SkillInstaller.ProjectTools(java.util.Set.of("x"), project))));
        assertTrue(recording.events(dev.incusspawn.util.RecordingOutput.Kind.ERROR).stream()
                .anyMatch(e -> e.msg().contains("Tool 'x' is project-local")), recording.events().toString());
        verifyNoInteractions(incus);
    }

    @Test
    void aProjectLocalTemplatesLocalSkillSourceStaysInItsProject(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path project,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path elsewhere) throws Exception {
        java.nio.file.Files.createDirectories(elsewhere.resolve("keys"));
        java.nio.file.Files.writeString(elsewhere.resolve("keys/SKILL.md"), "a host file");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-project");
        imageDef.setProjectRoot(project);
        imageDef.setSkills(new ImageDef.SkillsDef(null, List.of(elsewhere.toString())));
        var incus = execSucceeding();

        var recording = dev.incusspawn.util.RecordingOutput.during(() ->
                assertThrows(BuildCommand.BuildFailedException.class, () -> SkillInstaller.installSkills(
                        new Container(incus, "b"), imageDef, Map.of(), List.of(), SkillInstaller.ProjectTools.NONE)));
        assertTrue(recording.events(dev.incusspawn.util.RecordingOutput.Kind.ERROR).stream()
                .anyMatch(e -> e.msg().contains("Template 'tpl-project' is project-local")), recording.events().toString());
        verifyNoInteractions(incus);
    }
}
