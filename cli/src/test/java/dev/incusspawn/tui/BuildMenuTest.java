package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing.TemplateInfo;
import dev.incusspawn.config.ImageDef;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The build menu on its own: the options it offers, which keys it keeps, and the build it hands back. */
class BuildMenuTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static TemplateInfo template(String name, String buildStatus) {
        return new TemplateInfo(name, "", buildStatus, "container", null, null, null, "images:fedora/42", 0, 0, null);
    }

    /**
     * A built template with no definition, beside one not built, and nothing out of sync:
     * rebuild it, build the missing one, out of sync (disabled), and everything.
     */
    private static BuildMenu menu() {
        var dev = template("tpl-dev", "built");
        return new BuildMenu(new ModalRenderer(THEME), THEME, dev, Map.of(),
                List.of(dev, template("tpl-other", TemplateInfo.NOT_BUILT)), Set.of(), List.of());
    }

    private static ImageDef def(String name, String parent) {
        var def = new ImageDef();
        def.setName(name);
        def.setImage(parent == null ? "fedora-44" : null);
        def.setParent(parent);
        return def;
    }

    /** A root and two leaves on it. */
    private static LinkedHashMap<String, ImageDef> leaves() {
        var defs = new LinkedHashMap<String, ImageDef>();
        defs.put("tpl-minimal", def("tpl-minimal", null));
        defs.put("tpl-a", def("tpl-a", "tpl-minimal"));
        defs.put("tpl-b", def("tpl-b", "tpl-minimal"));
        return defs;
    }

    /** Both leaves marked, and the cursor on the root. */
    private static BuildMenu markedLeavesMenu() {
        var defs = leaves();
        var root = template("tpl-minimal", "built");
        return new BuildMenu(new ModalRenderer(THEME), THEME, root, defs,
                List.of(root, template("tpl-a", "built"), template("tpl-b", "built")), Set.of(),
                List.of("tpl-a", "tpl-b"));
    }

    private static BuildMenu.Outcome press(BuildMenu menu, KeyEvent... keys) {
        var outcome = BuildMenu.Outcome.HANDLED;
        for (var key : keys) outcome = menu.handleKey(key);
        return outcome;
    }

    @Test
    void escapeClosesAndAnUnknownKeyIsNotTheMenus() {
        assertEquals(BuildMenu.Outcome.CLOSE, press(menu(), KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(BuildMenu.Outcome.IGNORED, press(menu(), KeyEvent.ofChar('x')));
    }

    @Test
    void enterBuildsTheSelectedOption() {
        var menu = menu();
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofKey(KeyCode.ENTER)));
        assertArrayEquals(new String[]{"tpl-dev"}, menu.selected().buildArgs());
    }

    @Test
    void theCursorSkipsADisabledOption() {
        var menu = menu();
        // Down twice: past the missing templates, over the disabled out-of-sync line, to everything.
        press(menu, KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofKey(KeyCode.DOWN));
        assertArrayEquals(new String[]{"--all"}, menu.selected().buildArgs());
    }

    @Test
    void aNumberBuildsItsOptionUnlessDisabledOrMissing() {
        var menu = menu();
        assertEquals(BuildMenu.Outcome.HANDLED, press(menu, KeyEvent.ofChar('3')), "out of sync is disabled");
        assertEquals(BuildMenu.Outcome.HANDLED, press(menu, KeyEvent.ofChar('9')), "there is no ninth option");
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofChar('2')));
        assertArrayEquals(new String[]{"--missing"}, menu.selected().buildArgs());
    }

    @Test
    void markedTemplatesRebuildWithTheirParentsAsOneBatch() {
        var menu = markedLeavesMenu();
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofChar('1')));
        assertEquals("Rebuild 2 selected with parents", menu.selected().label());
        assertEquals("Rebuilds tpl-minimal, tpl-a, tpl-b", menu.selected().description(),
                "the shared parent once, before both");
        assertArrayEquals(new String[]{"tpl-a", "tpl-b", "--with-parents"}, menu.selected().buildArgs());
        assertTrue(menu.selected().marked());
    }

    @Test
    void markedLeavesOfferNoDescendantsAndCanBeRebuiltAlone() {
        var menu = markedLeavesMenu();
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofChar('2')));
        assertEquals("Rebuild 2 selected", menu.selected().label());
        assertArrayEquals(new String[]{"tpl-a", "tpl-b"}, menu.selected().buildArgs());
        // Then the row under the cursor, as without marks.
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofChar('3')));
        assertArrayEquals(new String[]{"tpl-minimal"}, menu.selected().buildArgs());
        assertFalse(menu.selected().marked());
    }

    @Test
    void markedTemplatesWithDescendantsRebuildTheirSubtrees() {
        var defs = leaves();
        defs.put("tpl-a-child", def("tpl-a-child", "tpl-a"));
        var a = template("tpl-a", "built");
        var menu = new BuildMenu(new ModalRenderer(THEME), THEME, a, defs,
                List.of(template("tpl-minimal", "built"), a, template("tpl-a-child", "built"), template("tpl-b", "built")),
                Set.of(), List.of("tpl-b", "tpl-a"));
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofChar('2')));
        assertEquals("Rebuild 2 selected with descendants", menu.selected().label());
        assertEquals("Rebuilds tpl-b, tpl-a, tpl-a-child", menu.selected().description());
        assertArrayEquals(new String[]{"tpl-b", "tpl-a", "--with-descendants"}, menu.selected().buildArgs());
    }

    @Test
    void aChildMarkedBeforeItsParentIsBuiltAfterIt() {
        var defs = leaves();
        var root = template("tpl-minimal", "built");
        var menu = new BuildMenu(new ModalRenderer(THEME), THEME, root, defs,
                List.of(root, template("tpl-a", "built"), template("tpl-b", "built")), Set.of(),
                List.of("tpl-a", "tpl-minimal"));
        // Nothing to add with parents: with descendants, then alone.
        assertEquals(BuildMenu.Outcome.BUILD, press(menu, KeyEvent.ofChar('2')));
        assertEquals("Rebuild 2 selected", menu.selected().label());
        assertArrayEquals(new String[]{"tpl-minimal", "tpl-a"}, menu.selected().buildArgs());
    }

    @Test
    void theCursorScrollsToAnOptionBelowTheFold() {
        var defs = leaves();
        var root = template("tpl-minimal", "built");
        // Seven options: two for the marks, two for the root, missing, out of sync and all.
        var menu = new BuildMenu(new ModalRenderer(THEME), THEME, root, defs,
                List.of(root, template("tpl-a", "built"), template("tpl-b", TemplateInfo.NOT_BUILT)),
                Set.of("tpl-a"), List.of("tpl-a", "tpl-b"));
        // Down to the last option, "Rebuild all templates", which is past the bottom at 80x24.
        for (int i = 0; i < 8; i++) press(menu, KeyEvent.ofKey(KeyCode.DOWN));
        assertArrayEquals(new String[]{"--all"}, menu.selected().buildArgs());
        TuiSnapshot.assertMatches("build-menu-marked-80x24",
                TuiSnapshot.render(80, 24, f -> menu.renderBuildMenu(f, f.area())));
    }

    @Test
    void rendersTheMenu() {
        var menu = menu();
        TuiSnapshot.assertMatches("build-menu-80x24", TuiSnapshot.render(80, 24, f -> menu.renderBuildMenu(f, f.area())));
    }
}
