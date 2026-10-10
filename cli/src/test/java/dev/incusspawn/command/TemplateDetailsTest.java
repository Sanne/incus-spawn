package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateDetailsTest {

    private static Map<String, ImageDef> defs(String... yamls) throws Exception {
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var yaml : yamls) {
            var def = ImageDef.parseYaml(yaml);
            defs.put(def.getName(), def);
        }
        return defs;
    }

    @Test
    void typeIsInheritedFromTheNearestAncestorThatSetsIt() throws Exception {
        var defs = defs(
                "name: tpl-root\n",
                "name: tpl-kvm\nparent: tpl-root\ntype: kvm\n",
                "name: tpl-leaf\nparent: tpl-kvm\n");
        assertEquals("kvm", TemplateDetails.resolve(defs.get("tpl-leaf"), defs).type());
        assertEquals("container", TemplateDetails.resolve(defs.get("tpl-root"), defs).type());
    }

    @Test
    void listsAccumulateDownTheChainAndScalarsTakeTheNearest() throws Exception {
        var defs = defs(
                """
                name: tpl-root
                image_url: https://example.com/base.tar.xz
                image_tag: v7
                pinned: true
                shell-command: tmux
                remove_packages: [nano]
                mask_services: [dnf-makecache.timer]
                skills:
                  repo: acme/skills
                  list: [review]
                agent_note: Root note.
                accounts: {claude: work, github: bot}
                env:
                  - name: A
                    value: "1"
                """,
                """
                name: tpl-leaf
                parent: tpl-root
                gui: true
                shell-command: zsh
                default-action: claude
                workdir: ~/src
                remove_packages: [vim-minimal]
                package_repos:
                  - type: copr
                    name: acme/tools
                skills: [review, other/skills@x]
                agent_note: Leaf note.
                accounts: {claude: personal}
                env:
                  - name: B
                    value: "2"
                """);
        var d = TemplateDetails.resolve(defs.get("tpl-leaf"), defs);

        assertTrue(d.gui());
        assertEquals("zsh", d.shellCommand());
        assertEquals("claude", d.defaultAction());
        assertEquals("/home/agentuser/src", d.workdir());
        assertEquals(Map.of("claude", "personal", "github", "bot"), d.accounts());
        assertEquals(List.of("nano", "vim-minimal"), d.removePackages());
        assertEquals(List.of("dnf-makecache.timer"), d.maskServices());
        assertEquals(List.of("copr:acme/tools"), d.packageRepos());
        assertEquals(List.of("review", "other/skills@x"), d.skills());
        assertEquals(List.of("acme/skills"), d.skillRepos());
        assertEquals(List.of("Root note.", "Leaf note."), d.agentNotes());
        assertEquals(List.of("tpl-root", "tpl-leaf"), d.env().stream().map(TemplateDetails.LayerEnv::layer).toList());
        assertEquals("v7", d.root().getImageTag());
        assertTrue(d.root().isPinned());
    }

    @Test
    void workdirCarriesOverFromAnAncestorsStamp() throws Exception {
        // tpl-b is copied from tpl-a's instance, whose workdir stamp its own build leaves alone.
        var defs = defs("name: tpl-a\nworkdir: /srv/app\n", "name: tpl-b\nparent: tpl-a\n");
        assertEquals("/srv/app", TemplateDetails.resolve(defs.get("tpl-b"), defs).workdir());
    }

    @Test
    void guiIsTheTemplatesOwnFlag() throws Exception {
        // BranchCommand reads def.isGui() of the source template only.
        var defs = defs("name: tpl-root\ngui: true\n", "name: tpl-leaf\nparent: tpl-root\n");
        assertFalse(TemplateDetails.resolve(defs.get("tpl-leaf"), defs).gui());
    }

    @Test
    void unsetScalarsResolveToNull() throws Exception {
        var defs = defs("name: tpl-root\n");
        var d = TemplateDetails.resolve(defs.get("tpl-root"), defs);
        assertNull(d.workdir());
        assertNull(d.shellCommand());
        assertNull(d.defaultAction());
        assertTrue(d.env().isEmpty());
    }

    @Test
    void builtTypePrefersTheStampAndFallsBackToTheIncusType() {
        assertEquals("kvm", TemplateDetails.builtType("kvm", "container"));
        assertEquals("vm", TemplateDetails.builtType("", "virtual-machine"));
        assertEquals("container", TemplateDetails.builtType("", "container"));
        assertNull(TemplateDetails.builtType("", ""));
    }

    @Test
    void aTemplateBuiltAsAnotherTypeIsFlagged() {
        assertEquals("container", TemplateDetails.staleBuiltType("kvm", "container", "container"));
        assertEquals("vm", TemplateDetails.staleBuiltType("container", "", "virtual-machine"));
        assertNull(TemplateDetails.staleBuiltType("kvm", "kvm", "container"));
        assertNull(TemplateDetails.staleBuiltType("kvm", "", ""));
    }

    @Test
    void typeLabelNamesKvmPassthrough() {
        assertEquals("container with KVM passthrough", TemplateDetails.typeLabel("kvm"));
        assertEquals("virtual machine", TemplateDetails.typeLabel("vm"));
        assertEquals("container", TemplateDetails.typeLabel(null));
    }

}
