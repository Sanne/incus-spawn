package dev.incusspawn.tui;

import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.tool.ToolAction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The F9 actions menu runs url actions while the TUI still owns the terminal (#982). */
class TuiActionDispatchTest {

    private static final ActionContext CONTEXT = new ActionContext("dev", MachineType.CONTAINER);

    @Test
    void urlActionRunsWithoutPromptingAndReportsInTheStatusLine() {
        var list = new Tui();
        var action = new ToolAction() {
            @Override public String toolName() { return "vscode-remote"; }
            @Override public String label() { return "Open in VS Code"; }
            @Override public Optional<String> type() { return Optional.of("url"); }
            @Override public ActionResult execute(ActionContext context) {
                throw new AssertionError("the TUI must not run an action that may read the terminal");
            }
            @Override public ActionResult executeWithoutPrompting(ActionContext context) {
                return ActionResult.error("install ms-vscode-remote.remote-ssh, then retry");
            }
        };

        assertFalse(list.dispatchAction(action, CONTEXT), "a url action does not leave the TUI");
        assertEquals("install ms-vscode-remote.remote-ssh, then retry", list.statusMessage);
    }
}
