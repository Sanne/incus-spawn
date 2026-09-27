package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TUI starts instances while it owns the terminal, so a pre-start repair's warnings go on the
 * status line rather than stderr, where they would be drawn over (#854).
 */
class ListCommandStartWarningTest {

    @Test
    void noWarningsLeaveTheStatusLineAlone() {
        assertNull(ListCommand.startWarningStatus(List.of()));
    }

    @Test
    void theIndentedTwoLineCliFormBecomesOneLine() {
        var warning = "    Warning: inbox directory not found: /home/me/inbox (device removed).\n"
                + "      To add it back, recreate it, then: incus config device add b1 inbox disk";

        assertEquals("Warning: inbox directory not found: /home/me/inbox (device removed)."
                        + " To add it back, recreate it, then: incus config device add b1 inbox disk",
                ListCommand.startWarningStatus(List.of(warning)));
    }

    @Test
    void furtherWarningsAreCountedNotDropped() {
        assertEquals("Warning: host-resource source not found: /a (device removed) (+1 more)",
                ListCommand.startWarningStatus(List.of(
                        "Warning: host-resource source not found: /a (device removed)",
                        "    Warning: inbox directory not found: /b")));
    }
}
