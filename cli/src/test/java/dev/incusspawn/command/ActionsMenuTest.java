package dev.incusspawn.command;

import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The F9 actions dialog on its own: its selection keys, the keys it hands back, and how it looks. */
class ActionsMenuTest {

    private static final TuiTheme THEME = TuiTheme.dark();
    private static final ToolAction OPEN = action("vscode", "Open in VS Code");
    private static final ToolAction COPY = action("ssh", "Copy SSH command");

    private static ToolAction action(String tool, String label) {
        return new ToolAction() {
            @Override public String toolName() { return tool; }
            @Override public String label() { return label; }
            @Override public ActionResult execute(ActionContext context) {
                throw new AssertionError("the menu never runs an action itself");
            }
        };
    }

    private static ActionsMenu menu() {
        var menu = new ActionsMenu(new ModalRenderer(THEME), THEME);
        menu.open(List.of(OPEN, COPY), new ActionContext("dev-1", MachineType.CONTAINER));
        return menu;
    }

    @Test
    void enterHandsBackTheSelectedActionAndItsContext() {
        var menu = menu();
        assertSame(OPEN, menu.selectedAction());
        assertEquals(ActionsMenu.Outcome.HANDLED, menu.handleKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertEquals(ActionsMenu.Outcome.HANDLED, menu.handleKey(KeyEvent.ofKey(KeyCode.DOWN)), "it stops at the last");
        assertEquals(ActionsMenu.Outcome.RUN, menu.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertSame(COPY, menu.selectedAction());
        assertEquals("dev-1", menu.context().instanceName());
        menu.handleKey(KeyEvent.ofChar('g'));
        assertSame(OPEN, menu.selectedAction());
    }

    @Test
    void escapeAndF9CloseAndOtherKeysAreNotItsOwn() {
        var menu = menu();
        assertEquals(ActionsMenu.Outcome.CLOSE, menu.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(ActionsMenu.Outcome.CLOSE, menu.handleKey(KeyEvent.ofKey(KeyCode.F9)));
        assertEquals(ActionsMenu.Outcome.UNHANDLED, menu.handleKey(KeyEvent.ofChar('x')));
    }

    @Test
    void rendersTwoActionsWithTheSecondSelected() {
        var menu = menu();
        menu.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        TuiSnapshot.assertMatches("actions-menu-80x24", TuiSnapshot.render(80, 24, f -> menu.render(f, f.area())));
    }
}
