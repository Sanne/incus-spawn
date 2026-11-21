package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The confirmation before F7 / Shift+F7 on the marked instances: only y confirms, and it names every one. */
class MarkedStopConfirmTest {

    private static MarkedStopConfirm dialog(List<String> names, List<String> notRunning, boolean restart) {
        return new MarkedStopConfirm(new ModalRenderer(TuiTheme.dark()), names, notRunning, restart);
    }

    @Test
    void onlyYConfirms() {
        var dialog = dialog(List.of("alpha"), List.of(), false);
        assertEquals(MarkedStopConfirm.Outcome.CONFIRM, dialog.handleKey(KeyEvent.ofChar('y')));
        assertEquals(MarkedStopConfirm.Outcome.CONFIRM, dialog.handleKey(KeyEvent.ofChar('Y')));
        assertEquals(MarkedStopConfirm.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofChar('n')));
        assertEquals(MarkedStopConfirm.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(MarkedStopConfirm.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
    }

    @Test
    void rendersEveryInstanceItStopsAndTheOnesItLeaves() {
        var dialog = dialog(List.of("alpha", "beta", "mcp-tpl-isx-agent-1482-design-a1b2"), List.of("gamma", "delta"), false);
        TuiSnapshot.assertMatches("marked-stop-confirm-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    @Test
    void aRestartSaysSo() {
        var dialog = dialog(List.of("alpha"), List.of(), true);
        assertEquals("This will restart:\n  alpha", dialog.message(24));
        var screen = TuiSnapshot.toText(TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
        assertTrue(screen.contains("Restart 1 instance") && screen.contains("y Restart"), screen);
    }

    @Test
    void aNameTooLongForTheDialogWidensItAndIsShownWhole() {
        var name = "mcp-tpl-isx-agent-vm-1482-implement-followup-a1b2c3d4";
        var dialog = dialog(List.of("alpha", name), List.of(), false);
        var screen = TuiSnapshot.toText(TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
        assertTrue(screen.contains("  " + name + " "), screen);
    }

    @Test
    void aListTallerThanTheScreenKeepsTheButtonsAndSaysWhatIsHidden() {
        var names = new java.util.ArrayList<String>();
        for (int i = 1; i <= 60; i++) names.add("mcp-tpl-isx-agent-vm-1482-implement-" + i);
        var dialog = dialog(names, List.of(), false);
        // The buttons stay, and the last text line says how much is hidden
        TuiSnapshot.assertMatches("marked-stop-confirm-truncated-80x24",
                TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
        // Even a thousand hidden lines leave the hint within the dialog's narrowest text
        assertTrue(ModalRenderer.moreLinesHint(1000).length() <= ModalRenderer.CONFIRM_TEXT_WIDTH);
        // A tall enough terminal shows every one
        var tall = TuiSnapshot.toText(TuiSnapshot.render(80, 80, f -> dialog.render(f, f.area())));
        for (var n : names) assertTrue(tokens(tall).contains(n), n + ":\n" + tall);
    }

    /** The words on a rendered screen, without the frame and the list's punctuation. */
    static java.util.Set<String> tokens(String screen) {
        return new java.util.HashSet<>(List.of(screen.split("[\\s,.│╭╮╰╯─]+")));
    }

    @Test
    void aListTooLongForOneNameALineIsWrappedButComplete() {
        var names = new java.util.ArrayList<String>();
        for (int i = 1; i <= 30; i++) names.add("box-" + i);
        var dialog = dialog(names, List.of(), false);
        assertTrue(dialog.message(24).startsWith("This will stop box-1, box-2,"));
        assertTrue(dialog.message(24).endsWith("box-30."));
        assertTrue(dialog.message(60).startsWith("This will stop:\n  box-1\n  box-2\n"));
        TuiSnapshot.assertMatches("marked-stop-confirm-wrapped-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }
}
