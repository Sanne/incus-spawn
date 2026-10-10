package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TemplateDetailViewTest {

    private static final TuiTheme THEME = TuiTheme.dark();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);

    private static final String ROOT = """
            name: tpl-root
            description: Base OS only
            image: fedora-44-base
            image_url: https://example.com/{tag}/fedora-44.tar.xz
            image_tag: f44-20260915
            pinned: true
            packages: [git]
            """;

    private static final String KVM = """
            name: tpl-kvm
            description: Nests VMs, so it needs KVM passthrough inside the container
            parent: tpl-root
            type: kvm
            gui: true
            shell-command: tmux new -A -s main
            default-action: claude
            packages: [qemu-kvm]
            remove_packages: [nano]
            mask_services: [dnf-makecache.timer]
            package_repos:
              - type: copr
                name: acme/tools
            tools: [claude]
            skills: [review]
            accounts: {claude: work}
            env:
              - name: MAVEN_OPTS
                value: -Xmx2g
              - name: API_TOKEN
                value: hunter2
              - name: PATH
                value: /opt/tools/bin
                strategy: prepend
                separator: ":"
            repos:
              - url: https://github.com/acme/app.git
            agent_note: |
              Nested guests share this container's memory limit, so size them before starting several.
            """;

    private static Map<String, ImageDef> defs() throws Exception {
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var yaml : List.of(ROOT, KVM)) {
            var def = ImageDef.parseYaml(yaml);
            defs.put(def.getName(), def);
        }
        return defs;
    }

    private static TemplateDetailView view(Set<String> defChanged) throws Exception {
        return view(defChanged, Map.of(), Map.of());
    }

    private static final String KVM_SOURCE = "/home/me/work/images/tpl-kvm.yaml";
    private static final String USER_KVM_SOURCE = "/home/me/.config/incus-spawn/images/tpl-kvm.yaml";

    private static TemplateDetailView view(Set<String> defChanged, Map<String, String> overridden,
                                           Map<String, String> builtFrom) throws Exception {
        return view(defChanged, overridden.isEmpty() && builtFrom.isEmpty() ? null : KVM_SOURCE,
                overridden, builtFrom);
    }

    private static TemplateDetailView view(Set<String> defChanged, String kvmSource,
                                           Map<String, String> overridden,
                                           Map<String, String> builtFrom) throws Exception {
        var defs = defs();
        if (kvmSource != null) defs.get("tpl-kvm").setSource(kvmSource);
        var source = new TemplateDetailView.Source() {
            @Override public Map<String, ImageDef> imageDefs() { return defs; }
            @Override public List<String> autoDeps(List<String> tools) {
                return tools.contains("claude") ? List.of("node") : List.of();
            }
            @Override public Path hostRepoMatch(String url) { return null; }
            @Override public boolean definitionChanged(String t) { return defChanged.contains(t); }
            @Override public boolean parentRebuilt(String t) { return false; }
            @Override public String overriddenSource(String t) { return overridden.get(t); }
            @Override public String builtFrom(String t) { return builtFrom.get(t); }
            @Override public String currentVersion() { return "1.4.0"; }
        };
        return new TemplateDetailView(new ModalRenderer(THEME), THEME, source, () -> NOW);
    }

    /** Built as a plain container by an older isx, before the definition asked for KVM. */
    private static final InstanceListing.TemplateInfo BUILT_STALE = new InstanceListing.TemplateInfo(
            "tpl-kvm", "", "2026-09-20T10:00:00", "container", "1.3.0", "sha", "", "tpl-root",
            3L * 1024 * 1024 * 1024, -1, "container");

    private static final InstanceListing.TemplateInfo NOT_BUILT = new InstanceListing.TemplateInfo(
            "tpl-kvm", "", InstanceListing.TemplateInfo.NOT_BUILT, "", "", "", "", "", -1, -1, "");

    private static String text(TemplateDetailView view, InstanceListing.TemplateInfo t, int w, int h) {
        return TuiSnapshot.toText(TuiSnapshot.render(w, h, f -> view.render(f, f.area(), t)));
    }

    @Test
    void builtTemplateAtMinimumTerminalSize() throws Exception {
        var view = view(Set.of("tpl-kvm"));
        TuiSnapshot.assertMatches("template-detail-built-80x24",
                TuiSnapshot.render(80, 24, f -> view.render(f, f.area(), BUILT_STALE)));
    }

    @Test
    void everySettingAtCommonTerminalSize() throws Exception {
        var view = view(Set.of());
        TuiSnapshot.assertMatches("template-detail-compact-120x50",
                TuiSnapshot.render(120, 50, f -> view.render(f, f.area(), NOT_BUILT)));
    }

    @Test
    void treeViewShowsEachSettingOnItsLayer() throws Exception {
        var view = view(Set.of());
        view.handleKey(KeyEvent.ofKey(KeyCode.TAB));
        TuiSnapshot.assertMatches("template-detail-tree-120x40",
                TuiSnapshot.render(120, 40, f -> view.render(f, f.area(), NOT_BUILT)));
    }

    @Test
    void typeAndStaleBuildAreFlagged() throws Exception {
        var screen = text(view(Set.of("tpl-kvm")), BUILT_STALE, 120, 50);
        assertTrue(screen.contains("Type:           container with KVM passthrough"), screen);
        assertTrue(screen.contains("built as container — rebuild to apply"), screen);
        assertTrue(screen.contains("! built with isx v1.3.0 (current: v1.4.0)"), screen);
        assertTrue(screen.contains("△ definition changed since last build"), screen);
        assertTrue(screen.contains("(7 days ago)") || screen.contains("(1 week ago)"), screen);
    }

    @Test
    void overrideAndOtherBuildFileAreShownUnderSource() throws Exception {
        var screen = text(view(Set.of("tpl-kvm"), Map.of("tpl-kvm", USER_KVM_SOURCE),
                Map.of("tpl-kvm", USER_KVM_SOURCE)), BUILT_STALE, 120, 50);
        assertTrue(screen.contains("Source:         " + KVM_SOURCE), screen);
        assertTrue(screen.contains("Overrides:      " + USER_KVM_SOURCE), screen);
        assertTrue(screen.contains("built from " + USER_KVM_SOURCE), screen);
    }

    @Test
    void buildFromTheBuiltInDefinitionIsSaidInWords() throws Exception {
        var screen = text(view(Set.of(), Map.of("tpl-kvm", "built-in"), Map.of("tpl-kvm", "built-in")),
                BUILT_STALE, 120, 50);
        assertTrue(screen.contains("built from the built-in definition"), screen);
        assertTrue(screen.contains("Overrides:      the built-in definition"), screen);
        assertFalse(screen.replace("the built-in definition", "").contains("built-in"), screen);
    }

    @Test
    void aBuildFromStoredMetadataIsSaidInWords() throws Exception {
        var screen = text(view(Set.of(), Map.of(), Map.of("tpl-kvm", "stored")), BUILT_STALE, 120, 50);
        assertTrue(screen.contains("built from the definition stored with an earlier build"), screen);
    }

    @Test
    void aBuiltInSourceIsSaidInWords() throws Exception {
        var screen = text(view(Set.of(), "built-in", Map.of(), Map.of("tpl-kvm", KVM_SOURCE)),
                BUILT_STALE, 120, 50);
        assertTrue(screen.contains("Source:         the built-in definition"), screen);
        assertTrue(screen.contains("built from " + KVM_SOURCE), screen);
    }

    @Test
    void sameBuildFileAndNoOverrideAddNothing() throws Exception {
        var screen = text(view(Set.of(), Map.of(),
                Map.of("tpl-kvm", KVM_SOURCE)), BUILT_STALE, 120, 50);
        assertFalse(screen.contains("Overrides:"), screen);
        assertFalse(screen.contains("built from"), screen);
    }

    @Test
    void emptyNewerSectionsAreHidden() throws Exception {
        var screen = String.join("\n", view(Set.of()).compactLines(
                new InstanceListing.TemplateInfo("tpl-root", "", InstanceListing.TemplateInfo.NOT_BUILT,
                        "", "", "", "", "", -1, -1, ""), 100)
                .stream().map(l -> l.spans().stream().map(s -> s.content()).reduce("", String::concat)).toList());
        assertTrue(screen.contains("Tools: (none)"), screen);
        assertFalse(screen.contains("Environment:"), screen);
        assertFalse(screen.contains("Accounts:"), screen);
        assertTrue(screen.contains("tag f44-20260915  (pinned)"), screen);
        assertTrue(screen.contains("from https://example.com/f44-20260915/fedora-44.tar.xz"), screen);
    }

    @Test
    void pageDownScrollsByTheVisibleHeight() throws Exception {
        var view = view(Set.of());
        var top = text(view, NOT_BUILT, 80, 24);
        view.handleKey(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
        var paged = text(view, NOT_BUILT, 80, 24);
        assertNotEquals(top, paged);
        assertTrue(paged.contains("Environment:") || paged.contains("Repos:"), paged);
        view.handleKey(KeyEvent.ofKey(KeyCode.HOME));
        assertEquals(top, text(view, NOT_BUILT, 80, 24));
    }

    @Test
    void escapeAndEditKeysAreLeftToTheCaller() throws Exception {
        var view = view(Set.of());
        assertFalse(view.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertFalse(view.handleKey(KeyEvent.ofKey(KeyCode.F4)));
        assertFalse(view.handleKey(KeyEvent.ofChar('n')));
    }
}
