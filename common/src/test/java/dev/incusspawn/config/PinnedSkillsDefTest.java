package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A skill pack pinned to a release: {@code ref}, {@code commit} and {@code path} on {@code skills} (#2354). */
class PinnedSkillsDefTest {
    private static final String COMMIT = "ab6833d6f9676e22e0dfa059324cd487203cb922";

    @Test
    void aPinnedPackParses() throws Exception {
        var def = ImageDef.parseYaml("""
                name: tpl-x
                skills:
                  repo: rgctl/rgctl
                  ref: v0.4.29
                  commit: %s
                  path: skills
                  list: [rgctl, rgctl-review]
                """.formatted(COMMIT));
        var skills = def.getSkills();
        assertTrue(skills.isPinned());
        assertEquals("v0.4.29", skills.getRef());
        assertEquals(COMMIT, skills.getCommit());
        assertEquals("skills", skills.getPath());
        assertEquals(List.of("rgctl", "rgctl-review"), skills.getList());
        assertEquals(List.of(), def.unknownKeyWarnings());
    }

    @Test
    void anUnpinnedPackIsUnchanged() throws Exception {
        var def = ImageDef.parseYaml("name: tpl-x\nskills:\n  repo: o/r\n  list: [a]\n");
        assertFalse(def.getSkills().isPinned());
        assertNull(def.getSkills().getCommit());
    }

    @Test
    void aRefWithoutItsCommitIsRefused() {
        var problem = SkillsDefConverter.pinProblem("o/r", "v1", null, null);
        assertNotNull(problem);
        assertTrue(problem.contains("'ref' needs 'commit'"), problem);
    }

    @Test
    void aCommitOrPathWithoutARefIsRefused() {
        assertNotNull(SkillsDefConverter.pinProblem("o/r", null, COMMIT, null));
        assertNotNull(SkillsDefConverter.pinProblem("o/r", null, null, "skills"));
    }

    @Test
    void anAbbreviatedCommitIsRefused() {
        var problem = SkillsDefConverter.pinProblem("o/r", "v1", "ab6833d", null);
        assertTrue(problem.contains("40-character"), problem);
    }

    @Test
    void aPinNeedsARepoInOwnerRepoForm() {
        assertNotNull(SkillsDefConverter.pinProblem(null, "v1", COMMIT, null));
        assertNotNull(SkillsDefConverter.pinProblem("https://github.com/o/r", "v1", COMMIT, null));
        assertNotNull(SkillsDefConverter.pinProblem("../r", "v1", COMMIT, null));
    }

    @Test
    void aPathLeavingTheRepositoryIsRefused() {
        assertNotNull(SkillsDefConverter.pinProblem("o/r", "v1", COMMIT, "../x"));
        assertNotNull(SkillsDefConverter.pinProblem("o/r", "v1", COMMIT, "skills/./x"));
        assertNull(SkillsDefConverter.pinProblem("o/r", "v1", COMMIT, "agent/skills"));
    }

    @Test
    void anInvalidPinFailsTheDefinition() {
        var e = assertThrows(java.io.IOException.class, () -> ImageDef.parseYaml("""
                name: tpl-x
                skills:
                  repo: o/r
                  ref: v1
                  list: [a]
                """));
        assertTrue(e.getMessage().contains("'ref' needs 'commit'"), e.getMessage());
    }

    @Test
    void aToolsPinIsPartOfItsFingerprint() throws Exception {
        var unpinned = Jjq.toolDef("name: t\nskills:\n  repo: o/r\n  list: [a]\n");
        var pinned = Jjq.toolDef("name: t\nskills:\n  repo: o/r\n  ref: v1\n  commit: " + COMMIT + "\n  list: [a]\n");
        var repinned = Jjq.toolDef("name: t\nskills:\n  repo: o/r\n  ref: v2\n  commit: " + "1".repeat(40) + "\n  list: [a]\n");
        assertNotEquals(unpinned.contentFingerprint(), pinned.contentFingerprint());
        assertNotEquals(pinned.contentFingerprint(), repinned.contentFingerprint());
    }

    // --- a project-local template's local skill source stays in its project ---

    private static ImageDef projectLocal(Path project) {
        var def = new ImageDef();
        def.setName("tpl-project");
        def.setProjectRoot(project);
        return def;
    }

    @Test
    void aLocalSkillSourceInsideTheProjectResolvesThere(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("skills/mine"));
        Files.writeString(project.resolve("skills/mine/SKILL.md"), "x");
        var real = HostResourceSetup.confinedSkillSource(projectLocal(project), "./skills");
        assertEquals(project.toRealPath().resolve("skills").toString(), real);
    }

    @Test
    void aLocalSkillSourceOutsideTheProjectIsRefused(@TempDir Path project, @TempDir Path elsewhere) {
        assertThrows(HostResourceSetup.HostPathOutsideProjectException.class,
                () -> HostResourceSetup.confinedSkillSource(projectLocal(project), elsewhere.toString()));
        assertThrows(HostResourceSetup.HostPathOutsideProjectException.class,
                () -> HostResourceSetup.confinedSkillSource(projectLocal(project), "./../x"));
    }

    @Test
    void aSymlinkOutOfTheProjectInsideALocalSkillSourceIsRefused(@TempDir Path project, @TempDir Path elsewhere)
            throws Exception {
        Files.writeString(elsewhere.resolve("secret"), "key");
        Files.createDirectories(project.resolve("skills/mine"));
        Files.createSymbolicLink(project.resolve("skills/mine/SKILL.md"), elsewhere.resolve("secret"));
        var e = assertThrows(HostResourceSetup.HostPathOutsideProjectException.class,
                () -> HostResourceSetup.confinedSkillSource(projectLocal(project), "./skills"));
        assertTrue(e.getMessage().contains("symlink"), e.getMessage());
    }

    @Test
    void aTrustedTemplatesLocalSourceIsLeftAsWritten() {
        var def = new ImageDef();
        def.setName("tpl-user");
        assertEquals("/opt/skills", HostResourceSetup.confinedSkillSource(def, "/opt/skills"));
    }
}
