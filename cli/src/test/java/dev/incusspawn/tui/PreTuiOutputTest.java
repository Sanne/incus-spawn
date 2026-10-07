package dev.incusspawn.tui;

import dev.incusspawn.Warnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What bare {@code isx} says before the TUI takes the terminal must not be drawn over (#1154):
 * a warning reaches the TUI's warning log, and anything else printed makes isx wait for the user.
 */
class PreTuiOutputTest {

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
    void warningBeforeTheTuiGoesToItsWarningLogWithoutPausing() {
        var log = new WarningLog();
        var waits = new AtomicInteger();
        try (var preTui = PreTuiOutput.begin()) {
            Warnings.warn("VM is running appliance 0.3.9; 0.3.10 is installed — run 'isx vm restart' to apply it.");
            preTui.handOver(log::add, waits::incrementAndGet);
        }

        assertEquals(1, log.entries().size());
        assertTrue(log.entries().get(0).message().startsWith("VM is running appliance 0.3.9"));
        assertEquals(0, waits.get(), "a warning the TUI shows needs no pause");
        assertEquals("", terminal(), "the warning is not printed under the TUI");
    }

    @Test
    void outputPrintedBeforeTheTuiWaitsForTheUser() {
        var waits = new AtomicInteger();
        try (var preTui = PreTuiOutput.begin()) {
            System.out.println("  First-time setup required.");
            System.err.println("VM is running but Incus is not reachable; attempting recovery...");
            preTui.handOver(new WarningLog()::add, waits::incrementAndGet);
            preTui.handOver(new WarningLog()::add, waits::incrementAndGet);
        }

        assertEquals(1, waits.get(), "printed text is not covered by the TUI before it is read, once");
        assertTrue(terminal().contains("First-time setup required."), "stdout still reaches the terminal");
        assertTrue(terminal().contains("attempting recovery"), "stderr still reaches the terminal");
    }

    @Test
    void streamsAndWarningsAreRestoredAtHandOver() {
        var termOut = System.out;
        var termErr = System.err;
        var log = new ArrayList<String>();
        try (var preTui = PreTuiOutput.begin()) {
            preTui.handOver(log::add, () -> {});
            assertSame(termOut, System.out);
            assertSame(termErr, System.err);
            Warnings.warn("raised after the hand-over (#1154 test)");
        }
        assertTrue(log.isEmpty(), "after the hand-over, warnings go where the TUI sends them");
        assertTrue(terminal().contains("Warning: raised after the hand-over (#1154 test)"));
    }

    @Test
    void warningsAreNotLostWhenTheTuiNeverOpens() {
        try (var ignored = PreTuiOutput.begin()) {
            Warnings.warn("raised before init failed (#1154 test)");
        }
        assertTrue(terminal().contains("Warning: raised before init failed (#1154 test)"));
    }
}
