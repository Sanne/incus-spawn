package dev.incusspawn.incus;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.tool.ToolAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellStatusBarTest {

    private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();
    private final ShellStatusBar bar = new ShellStatusBar("dev-1", terminal);

    private String drained() {
        var s = terminal.toString(StandardCharsets.UTF_8);
        terminal.reset();
        return s;
    }

    private static ActionContext context() {
        return new ActionContext("dev-1", MachineType.CONTAINER, Set.of(), List.of(),
                new ActionContext.InstanceState("10.0.0.5", "RUNNING", "tpl-dev", "FULL"));
    }

    private static ToolAction urlAction(String shortcut, ActionResult result, AtomicInteger runs) {
        return new ToolAction() {
            @Override public String toolName() { return "vscode-remote"; }
            @Override public String label() { return "Open in VS Code"; }
            @Override public Optional<String> type() { return Optional.of("url"); }
            @Override public Optional<String> shortcut() { return Optional.of(shortcut); }
            @Override public ActionResult execute(ActionContext context) {
                runs.incrementAndGet();
                return result;
            }
        };
    }

    @Test
    void theShellGetsTheRowsAboveTheBar() {
        assertEquals(22, bar.effectiveHeight(24));
        bar.setup(80, 24);
        var out = drained();
        assertTrue(out.contains("\033[1;22r"), "scroll region must stop above the bar: " + out);
        assertTrue(out.contains("\033[23;1H") && out.contains("\033[24;1H"), "bar on the last two rows");
    }

    @Test
    void theBarShowsTheInstanceAndItsTemplate() {
        bar.setMenuActions(List.of(), context());
        bar.setup(80, 24);
        var out = drained();
        assertTrue(out.contains(" isx dev-1 [tpl-dev]"), out);
        assertTrue(out.contains("10.0.0.5"), out);
    }

    @Test
    void leavingRemovesTheWholeBarAndPutsTheCursorBack() {
        bar.setup(80, 24);
        drained();
        bar.cleanup();
        var out = drained();
        // From the bar's first row down, then the full screen back, then the shell's cursor.
        assertEquals("\0337\033[23;1H\033[J\033[r\0338", out);
    }

    @Test
    void aShortcutRunsItsActionAndFlashesTheResultAsUtf8() {
        var runs = new AtomicInteger();
        bar.setMenuActions(List.of(urlAction("v", ActionResult.error("no handler"), runs)), context());
        bar.setup(80, 24);
        bar.showMenu();
        drained();

        bar.handleMenuKey((byte) 'V');
        assertEquals(1, runs.get());
        assertFalse(bar.isMenuActive());
        assertTrue(drained().contains("✗ no handler"));
    }

    @Test
    void anUnknownKeyKeepsTheMenuOpenAndSaysWhatToPress() {
        var runs = new AtomicInteger();
        bar.setMenuActions(List.of(urlAction("v", ActionResult.ok("ok"), runs)), context());
        bar.setup(80, 24);
        bar.showMenu();
        drained();

        bar.handleMenuKey((byte) 'x');
        assertEquals(0, runs.get());
        assertTrue(bar.isMenuActive());
        assertTrue(drained().contains("Press v or Esc"));
    }

    @Test
    void aBarWiderThanTheTerminalIsCut() {
        var bar = new ShellStatusBar("an-instance-with-a-very-long-name", terminal);
        bar.setup(20, 10);
        var out = drained();
        assertTrue(out.contains(" isx an-instance-wit"), out);
        assertFalse(out.contains("very-long-name"), out);
    }
}
