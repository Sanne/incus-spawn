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
                List.of(new BuildTools.ResolvedTool("zmx", namedTool("zmx", null), Map.of())));

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
                List.of(new BuildTools.ResolvedTool("mvnd", tool, Map.of("version", "1.1"), true)));

        // No mkdir, no write, and crucially no fetch — it never got past collection.
        verifyNoInteractions(incus);
    }
}
