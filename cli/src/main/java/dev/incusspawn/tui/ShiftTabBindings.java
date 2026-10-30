package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Shift+Tab, which tamboui reads from {@code ESC [ Z} as Tab with Shift.
 *
 * <p>Older tamboui read that sequence as an unknown key, so this once took every unknown key for
 * Shift+Tab. That made any sequence tamboui cannot parse, such as Esc in the kitty keyboard
 * protocol, move the focus instead of being ignored. An unknown key is never Shift+Tab now.
 */
public final class ShiftTabBindings {

    private ShiftTabBindings() {
    }

    /** Whether {@code key} is Shift+Tab. */
    public static boolean isShiftTab(KeyEvent key) {
        return key.isKey(KeyCode.TAB) && key.hasShift();
    }
}
