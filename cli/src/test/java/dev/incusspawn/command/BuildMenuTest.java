package dev.incusspawn.command;

import dev.incusspawn.command.InstanceListing.TemplateInfo;
import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

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
                List.of(dev, template("tpl-other", TemplateInfo.NOT_BUILT)), Set.of());
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
    void rendersTheMenu() {
        var menu = menu();
        TuiSnapshot.assertMatches("build-menu-80x24", TuiSnapshot.render(80, 24, f -> menu.renderBuildMenu(f, f.area())));
    }
}
