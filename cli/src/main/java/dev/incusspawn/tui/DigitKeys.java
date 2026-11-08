package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

/**
 * The function keys for a keyboard without an F row (#1824): a digit is its F-key, 0 being F10, and
 * Alt with a digit is Shift with that F-key.
 *
 * <p>The same on every keyboard layout: a terminal sends a digit as the digit however the layout
 * types it, and Alt as an Esc before it, which tamboui reports as {@code ALT}. A terminal never
 * reports Shift on a digit, only the layout's own symbol above it, so Shift cannot be the modifier.
 */
final class DigitKeys {

    private DigitKeys() {
    }

    /**
     * The F-key {@code key} stands for, or {@code key} itself when it is not a digit or Alt with one.
     * Shift is not looked at: on AZERTY it is how a digit is typed, should a terminal ever report it.
     */
    static KeyEvent asFunctionKey(KeyEvent key) {
        if (key.code() != KeyCode.CHAR || key.hasCtrl()) return key;
        int c = key.codePoint();
        if (c < '0' || c > '9') return key;
        var modifiers = key.hasAlt() ? KeyModifiers.SHIFT : KeyModifiers.NONE;
        return KeyEvent.ofKey(functionKey(c - '0'), modifiers, key.bindings());
    }

    /**
     * A switch rather than a static table: an application class is initialized when the native image
     * is built, and tamboui's {@code KeyCode} constants may not be put in its heap.
     */
    private static KeyCode functionKey(int digit) {
        return switch (digit) {
            case 1 -> KeyCode.F1;
            case 2 -> KeyCode.F2;
            case 3 -> KeyCode.F3;
            case 4 -> KeyCode.F4;
            case 5 -> KeyCode.F5;
            case 6 -> KeyCode.F6;
            case 7 -> KeyCode.F7;
            case 8 -> KeyCode.F8;
            case 9 -> KeyCode.F9;
            default -> KeyCode.F10;
        };
    }
}
