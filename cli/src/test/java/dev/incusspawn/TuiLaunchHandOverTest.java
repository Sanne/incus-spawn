package dev.incusspawn;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.command.StandInTui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bare {@code isx} end to end, the terminal stood in for: what is said while isx gets ready must
 * reach the TUI before it draws, not stderr after it quits (#1154).
 */
// The real doExecute runs, which loads RuntimeServices: never against the developer's home.
@ExtendWith(IsolatedHome.class)
class TuiLaunchHandOverTest {

    private static final String SKEW =
            "VM is running appliance 0.3.9; 0.3.10 is installed — run 'isx vm restart' to apply it.";

    private PrintStream realOut;
    private PrintStream realErr;
    private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();

    @BeforeEach
    void captureTerminal() {
        realOut = System.out;
        realErr = System.err;
        var term = new PrintStream(terminal, true, StandardCharsets.UTF_8);
        System.setOut(term);
        System.setErr(term);
    }

    @AfterEach
    void restoreTerminal() {
        System.setOut(realOut);
        System.setErr(realErr);
    }

    private String terminal() {
        return terminal.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aWarningWhileGettingReadyIsInTheTuiWhenItStartsDrawing() {
        var tui = new StandInTui();
        assertTrue(IncusSpawn.launchTui(() -> {
            Warnings.warn(SKEW); // what VmManager.warnOfSkew raises while the VM is checked
            return true;
        }, () -> tui));

        assertEquals(List.of(SKEW), tui.warningsAtStart);
        assertEquals(List.of("draw"), tui.events, "a warning the TUI shows needs no pause");
        assertFalse(terminal().contains(SKEW), "not printed where the TUI draws over it");
    }

    @Test
    void textPrintedWhileGettingReadyIsWaitedForBeforeTheTuiDraws() {
        var tui = new StandInTui();
        assertTrue(IncusSpawn.launchTui(() -> {
            System.err.println("Starting incus-spawn VM... ");
            Warnings.warn(SKEW);
            return true;
        }, () -> tui));

        assertEquals(List.of("wait", "draw"), tui.events);
        assertEquals(List.of(SKEW), tui.warningsAtStart);
        var shown = terminal();
        assertTrue(shown.contains("Starting incus-spawn VM"), shown);
        assertTrue(shown.contains("Warning: " + SKEW), shown);
        assertTrue(shown.indexOf("Starting incus-spawn VM") < shown.indexOf("Warning: " + SKEW),
                "the warning is shown above the pause as well, so Ctrl-C there does not lose it");
    }

    @Test
    void aTuiThatNeverOpensStillShowsTheWarning() {
        assertFalse(IncusSpawn.launchTui(() -> {
            Warnings.warn(SKEW);
            return false;
        }, () -> fail("no TUI when isx is not ready")));
        assertTrue(terminal().contains("Warning: " + SKEW));
    }
}
