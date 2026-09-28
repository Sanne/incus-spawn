package dev.incusspawn.command;

import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.incusspawn.tui.WarningLog;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarningsModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private LocalTime now = LocalTime.of(9, 41, 7);
    private final WarningLog log = new WarningLog(() -> now);
    private int cleared;
    private final WarningsModal modal = new WarningsModal(new ModalRenderer(THEME), THEME, log,
            () -> cleared++);

    /** Warnings from three unrelated operations, one of them with an indented fix to copy. */
    private void addWarnings() {
        log.add("podman.yaml: Env entry 'export DOCKER_HOST=unix:///var/run/docker.sock' is a shell"
                + " string, which is not supported; use a structured entry:\n"
                + "  - name: DOCKER_HOST\n"
                + "    value: unix:///var/run/docker.sock");
        now = LocalTime.of(9, 44, 30);
        log.add("    Warning: inbox directory not found: /home/me/inbox (device removed).\n"
                + "      To add it back, recreate it, then: incus config device add b1 inbox disk"
                + " source=/home/me/inbox path=/home/agentuser/inbox");
        now = LocalTime.of(9, 50, 2);
        log.add("tool 'gradle' is defined twice in ~/.config/incus-spawn/tools");
    }

    @Test
    void listsEveryWarningNewestFirst() {
        addWarnings();
        modal.open();

        TuiSnapshot.assertMatches("warnings-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
        TuiSnapshot.assertMatches("warnings-120x40",
                TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void saysSoWhenThereAreNone() {
        modal.open();

        TuiSnapshot.assertMatches("warnings-empty-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void openingMarksEverythingRead() {
        addWarnings();
        assertEquals(3, log.unread());

        modal.open();

        assertEquals(0, log.unread());
    }

    @Test
    void clearingEmptiesTheLogAndLetsWarningsBeReportedAgain() {
        addWarnings();
        modal.open();

        assertTrue(modal.handleKey(KeyEvent.ofChar('c')));

        assertEquals(List.of(), log.entries());
        assertEquals(1, cleared);
    }

    @Test
    void escapeAndTheOpeningKeyClose() {
        assertFalse(modal.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertFalse(modal.handleKey(KeyEvent.ofChar('w')));
        assertTrue(modal.handleKey(KeyEvent.ofChar('j')));
    }
}
