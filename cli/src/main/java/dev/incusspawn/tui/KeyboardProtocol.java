package dev.incusspawn.tui;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Turns the key encodings of the kitty keyboard protocol ({@code CSI code;mods u}) and of xterm's
 * modifyOtherKeys ({@code CSI 27;mods;code ~}) back into the bytes a legacy terminal sends, which is
 * all tamboui's parser reads.
 *
 * <p>A program in an instance (Pi, Claude Code) turns these on, and one that does not exit cleanly,
 * or a multiplexer between, leaves them on. tamboui then reads Esc ({@code CSI 27 u}) and Ctrl+C
 * ({@code CSI 99;5 u}) as an unknown key, so no dialog could be left. Only some terminals speak
 * either protocol, which is why Esc worked in one terminal and not another.
 *
 * <p>Any other sequence is handed on untouched, byte for byte; a key with no legacy byte (a
 * keypad or media key) and a key release are dropped, never handed on as an unknown key.
 */
final class KeyboardProtocol {

    /** The terminal's input, as the backend reads it: a code point, -1 at its end, -2 on timeout. */
    interface Input {
        int read(int timeoutMs) throws IOException;

        int peek(int timeoutMs) throws IOException;
    }

    static final int ESC = 27;
    private static final int TIMEOUT = -2;
    /** How long the rest of a sequence may take to arrive, as tamboui's parser waits for it. */
    private static final int SEQUENCE_MS = 50;
    private static final int SHIFT = 1;
    private static final int ALT = 2;
    private static final int CTRL = 4;
    /** Caps Lock and Num Lock: a state, not a modifier of the key. */
    private static final int LOCKS = 64 | 128;
    private static final int RELEASE = 3;

    private final Input input;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    /** The lookahead found nothing after the Esc it queued last. */
    private boolean nothingAfter;
    /** That Esc was just handed out, and tamboui is about to wait for what follows it again. */
    private boolean loneEscape;

    KeyboardProtocol(Input input) {
        this.input = input;
    }

    int read(int timeoutMs) throws IOException {
        loneEscape = false;
        while (pending.isEmpty()) {
            int c = input.read(timeoutMs);
            if (c != ESC) return c;
            decodeEscape();
        }
        int c = pending.poll();
        loneEscape = c == ESC && pending.isEmpty() && nothingAfter;
        return c;
    }

    int peek(int timeoutMs) throws IOException {
        if (!pending.isEmpty()) return pending.peek();
        // The lookahead already waited for what followed the Esc, and nothing did
        if (loneEscape) return TIMEOUT;
        return input.peek(timeoutMs);
    }

    /** Queues what the Esc just read stands for: a legacy key, nothing, or the Esc and what followed. */
    private void decodeEscape() throws IOException {
        int next = input.peek(SEQUENCE_MS);
        nothingAfter = next < 0;
        if (next != '[') {
            pending.add(ESC);
            return;
        }
        var read = new StringBuilder();
        read.appendCodePoint(input.read(SEQUENCE_MS));
        int c;
        while ((c = input.read(SEQUENCE_MS)) >= 0) {
            read.appendCodePoint(c);
            if (!(c >= '0' && c <= '9' || c == ';' || c == ':')) break;
        }
        var legacy = c < 0 ? null : legacy(read.substring(1, read.length() - 1), c);
        if (legacy == null) {
            pending.add(ESC);
            read.codePoints().forEach(pending::add);
        } else {
            for (int b : legacy) pending.add(b);
            // A decoded key is whole: tamboui's wait for more after an Esc would find the next key
            nothingAfter = true;
        }
    }

    /**
     * The legacy bytes for a sequence with these parameters and final byte, an empty array for a key
     * to drop, or null when it is no key encoding of either protocol.
     */
    static int[] legacy(String params, int last) {
        var fields = params.split(";", -1);
        String code;
        String mods;
        if (last == 'u' && fields.length >= 1 && fields.length <= 3) {
            code = fields[0];
            mods = fields.length > 1 ? fields[1] : "";
        } else if (last == '~' && fields.length == 3 && fields[0].equals("27")) {
            code = fields[2];
            mods = fields[1];
        } else {
            return null;
        }
        // kitty: the code may carry its shifted and base keys, the modifiers an event type
        int key = number(code.split(":", -1)[0]);
        var modParts = mods.split(":", -1);
        int modifiers = modParts[0].isEmpty() ? 1 : number(modParts[0]);
        int event = modParts.length > 1 ? number(modParts[1]) : 1;
        if (key < 0 || modifiers < 1 || event < 0) return null;
        if (event == RELEASE) return new int[0];
        return legacy(key, (modifiers - 1) & ~LOCKS);
    }

    private static int[] legacy(int key, int modifiers) {
        boolean alt = (modifiers & ALT) != 0;
        boolean ctrl = (modifiers & CTRL) != 0;
        boolean shift = (modifiers & SHIFT) != 0;
        int[] bytes;
        if (key == '\t' && shift) {
            bytes = new int[]{ESC, '[', 'Z'};
        } else if (key == ESC || key == '\r' || key == '\t' || key == 127 || key == '\b') {
            bytes = new int[]{key == '\b' ? 127 : key};
        } else if (key < 32 || key > 0x10FFFF || (key >= 0xE000 && key <= 0xF8FF)) {
            return new int[0]; // kitty's functional keys sit in the private use area
        } else if (ctrl && (key == ' ' || key == '@')) {
            bytes = new int[]{0};
        } else if (ctrl && (key >= 'a' && key <= 'z' || key >= '@' && key <= '_')) {
            bytes = new int[]{key & 0x1f};
        } else if (shift && key >= 'a' && key <= 'z') {
            bytes = new int[]{key - 'a' + 'A'};
        } else {
            bytes = new int[]{key};
        }
        if (!alt) return bytes;
        var prefixed = new int[bytes.length + 1];
        prefixed[0] = ESC;
        System.arraycopy(bytes, 0, prefixed, 1, bytes.length);
        return prefixed;
    }

    private static int number(String digits) {
        if (digits.isEmpty() || digits.length() > 7) return -1;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return -1;
        }
        return Integer.parseInt(digits);
    }
}
