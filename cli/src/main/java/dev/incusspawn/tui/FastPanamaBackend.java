package dev.incusspawn.tui;

import dev.tamboui.backend.panama.PanamaBackend;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Skips the 500ms Mode 2027 (Kitty keyboard protocol) probe that
 * PanamaBackend performs on every enableRawMode() call. The probe
 * sends a DECRQM query and waits for a terminal response that only
 * a handful of terminals provide; on everything else it's a pure
 * 500ms stall. The TUI works identically without it.
 */
public final class FastPanamaBackend extends PanamaBackend {

    private static final MethodHandle ENABLE_RAW;
    static {
        try {
            var terminalField = PanamaBackend.class.getDeclaredField("terminal");
            terminalField.setAccessible(true);
            var platformTerminalClass = terminalField.getType();
            ENABLE_RAW = MethodHandles.lookup()
                    .findVirtual(platformTerminalClass, "enableRawMode", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object terminal;

    public FastPanamaBackend() throws IOException {
        super();
        try {
            var terminalField = PanamaBackend.class.getDeclaredField("terminal");
            terminalField.setAccessible(true);
            this.terminal = terminalField.get(this);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to access PanamaBackend terminal field", e);
        }
    }

    @Override
    public void enableRawMode() throws IOException {
        try {
            ENABLE_RAW.invoke(terminal);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Failed to enable raw mode", t);
        }
    }
}
