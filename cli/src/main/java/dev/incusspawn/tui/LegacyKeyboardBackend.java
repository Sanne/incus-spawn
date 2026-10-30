package dev.incusspawn.tui;

import dev.tamboui.backend.panama.PanamaBackend;

import java.io.IOException;

/**
 * The TUI's terminal, which always reads keys in their legacy encoding, whatever a program before
 * it left the keyboard set to.
 *
 * <p>On its alternate screen it pushes kitty keyboard flags of zero, and pops them before leaving,
 * so a terminal that speaks the protocol sends Esc as Esc again even when a program left it on.
 * What still arrives in either protocol's encoding, from a multiplexer that holds its own mode or a
 * terminal that ignores the push, {@link KeyboardProtocol} turns back into legacy keys.
 */
final class LegacyKeyboardBackend extends PanamaBackend {

    /** Push kitty keyboard flags 0, on the current screen's stack. */
    static final String PUSH_LEGACY_KEYS = "\u001b[>0u"; // raw ANSI: written only on the TUI's own alternate screen
    /** Pop that entry again. */
    static final String POP_LEGACY_KEYS = "\u001b[<u"; // raw ANSI: written only on the TUI's own alternate screen

    private final KeyboardProtocol keys = new KeyboardProtocol(new KeyboardProtocol.Input() {
        @Override
        public int read(int timeoutMs) throws IOException {
            return LegacyKeyboardBackend.super.read(timeoutMs);
        }

        @Override
        public int peek(int timeoutMs) throws IOException {
            return LegacyKeyboardBackend.super.peek(timeoutMs);
        }
    });
    private boolean pushed;

    LegacyKeyboardBackend() throws IOException {
        super();
    }

    @Override
    public void enterAlternateScreen() throws IOException {
        super.enterAlternateScreen();
        writeRaw(PUSH_LEGACY_KEYS);
        flush();
        pushed = true;
    }

    @Override
    public void leaveAlternateScreen() throws IOException {
        // Popped only once, and only what was pushed: the main screen's stack is another program's
        if (pushed) {
            writeRaw(POP_LEGACY_KEYS);
            pushed = false;
        }
        super.leaveAlternateScreen();
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        return keys.read(timeoutMs);
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        return keys.peek(timeoutMs);
    }
}
