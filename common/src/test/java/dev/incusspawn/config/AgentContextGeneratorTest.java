package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextGeneratorTest {

    private static String minimal() {
        return AgentContextGenerator.generate("tpl-minimal", List.of(), List.of(), List.of());
    }

    @Test
    void preambleIsPresentEvenWithNothingElse() {
        var out = minimal();
        assertTrue(out.startsWith("# incus-spawn environment\n"));
        assertTrue(out.contains("built from the `tpl-minimal` template"));
        assertTrue(out.contains("Passwordless `sudo` is available"));
        assertTrue(out.contains("injected by a host-side TLS proxy"));
        assertTrue(out.contains("don't push branches, open PRs"));
    }

    @Test
    void makesNoClaimThatIsOnlyConditionallyTrue() {
        // host-resources can mount real credentials into the box (a template sharing
        // ~/.config/gh or ~/.m2/settings.xml), and the file is written for VMs too.
        var out = minimal();
        assertFalse(out.contains("not present here in any form"));
        assertFalse(out.contains("isx container"));
    }

    @Test
    void doesNotClaimPermissionPromptsAreOff() {
        // That is only true when ClaudeSetup wrote managed settings, so the preamble
        // states the autonomy without asserting the mechanism behind it.
        var out = minimal();
        assertFalse(out.contains("bypassPermissions"));
        assertFalse(out.contains("permission prompts"));
        assertTrue(out.contains("You are running autonomously here"));
    }

    @Test
    void emptyListsOmitTheirHeadingsEntirely() {
        var out = minimal();
        assertFalse(out.contains("Already installed"));
        assertFalse(out.contains("Already cloned"));
        assertFalse(out.contains("## Notes"));
    }

    @Test
    void toolsRenderAsASingleImperativeLineWithNamesOnly() {
        var out = AgentContextGenerator.generate("tpl-dev", List.of("claude", "gh", "tmux"),
                List.of(), List.of());
        assertTrue(out.contains("Already installed, don't reinstall: claude, gh, tmux\n"));
    }

    @Test
    void duplicateToolsFromSeveralLayersAppearOnce() {
        var out = AgentContextGenerator.generate("tpl-dev", List.of("gh", "claude", "gh"),
                List.of(), List.of());
        assertTrue(out.contains("Already installed, don't reinstall: gh, claude\n"));
    }

    @Test
    void reposReadAsAStatementNotACloneInstruction() {
        // `<path> — <url>` is a git clone with its arguments swapped, so in isolation it
        // reads as an instruction to clone — the opposite of why the list exists.
        var out = AgentContextGenerator.generate("tpl-openjdk", List.of(),
                List.of(new AgentContextGenerator.Repo("~/jdk", "https://github.com/openjdk/jdk.git")),
                List.of());
        assertTrue(out.contains("Already cloned, work in these rather than cloning again:\n"));
        assertTrue(out.contains("- https://github.com/openjdk/jdk.git is checked out at `~/jdk`\n"));
    }

    @Test
    void repoWithoutUrlRendersPathAlone() {
        var out = AgentContextGenerator.generate("tpl-x", List.of(),
                List.of(new AgentContextGenerator.Repo("~/scratch", null)), List.of());
        assertTrue(out.contains("- `~/scratch`\n"));
    }

    @Test
    void notesAppearInTheOrderGiven() {
        var out = AgentContextGenerator.generate("tpl-openjdk", List.of(), List.of(),
                List.of("root note", "image note", "tool note"));
        var notes = out.substring(out.indexOf("## Notes"));
        assertTrue(notes.indexOf("root note") < notes.indexOf("image note"));
        assertTrue(notes.indexOf("image note") < notes.indexOf("tool note"));
    }

    @Test
    void blankNotesAreDropped() {
        var out = AgentContextGenerator.generate("tpl-x", List.of(), List.of(),
                java.util.Arrays.asList("  ", null, ""));
        assertFalse(out.contains("## Notes"));
    }

    @Test
    void identicalNotesFromSeveralLayersCollapse() {
        var out = AgentContextGenerator.generate("tpl-x", List.of(), List.of(),
                List.of("same note", "same note\n", "other"));
        assertEquals(1, out.lines().filter(l -> l.equals("same note")).count());
        assertTrue(out.contains("other"));
    }

    @Test
    void noDescriptionsAreEverRendered() {
        // Names and paths are fingerprinted; descriptions are not, so rendering them
        // could assert something that stopped being true. Guard against regression.
        var out = AgentContextGenerator.generate("tpl-dev", List.of("gh"),
                List.of(new AgentContextGenerator.Repo("~/jdk", "https://example.com/jdk.git")),
                List.of());
        assertEquals(1, out.lines().filter(l -> l.startsWith("Already installed")).count());
    }
}
