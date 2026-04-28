package dev.incusspawn.tui;

import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.bindings.DefaultBindings;
import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Custom Tamboui bindings that recognize Shift+Tab.
 *
 * Tamboui's JLine3 backend doesn't parse the backtab sequence (\x1b[Z), so it comes
 * through as KeyCode.UNKNOWN with character=0 and no modifiers. We add a custom binding
 * that maps this specific pattern to the "backtab" action, which we can then
 * detect in our event handlers.
 */
public class ShiftTabBindings {

    private static final String ACTION_BACKTAB = "backtab";

    /**
     * InputTrigger that matches Shift+Tab (coming through as UNKNOWN).
     */
    private static class BacktabTrigger implements InputTrigger {
        @Override
        public boolean matches(Event event) {
            if (!(event instanceof KeyEvent key)) return false;
            // Detect the specific pattern of Shift+Tab: UNKNOWN with character=0, no modifiers
            return key.code() == KeyCode.UNKNOWN
                    && key.character() == 0
                    && !key.hasCtrl()
                    && !key.hasAlt()
                    && !key.hasShift();
        }

        @Override
        public String describe() {
            return "Shift+Tab";
        }
    }

    /**
     * Create bindings with backtab support added to the defaults.
     */
    public static Bindings createWithBacktab() {
        return DefaultBindings.builder()
                .bind(new BacktabTrigger(), ACTION_BACKTAB)
                .build();
    }

    /**
     * Check if a key event is Shift+Tab.
     *
     * Detects Shift+Tab in multiple ways:
     * 1. Via the "backtab" action bound by our custom InputTrigger
     * 2. As TAB with Shift modifier (standard representation, if Tamboui is fixed)
     * 3. As UNKNOWN with character=0 (current Tamboui bug workaround)
     */
    public static boolean isShiftTab(KeyEvent key) {
        // Check if the event has the backtab action bound
        var action = key.action();
        if (action.isPresent() && ACTION_BACKTAB.equals(action.get())) {
            return true;
        }
        // Check for standard representation: TAB + Shift modifier
        if (key.isKey(KeyCode.TAB) && key.hasShift()) {
            return true;
        }
        // Fallback: Tamboui bug workaround - UNKNOWN with character=0, no modifiers
        return key.code() == KeyCode.UNKNOWN
                && key.character() == 0
                && !key.hasCtrl()
                && !key.hasAlt()
                && !key.hasShift();
    }
}
