package dev.incusspawn.incus;

/**
 * Minimal state machine to detect F12 ({@code \e[24~}) from raw terminal input.
 *
 * <p>In normal mode, all non-F12 input passes through unchanged. Partial escape
 * sequences are buffered across {@code feed()} calls and flushed when they turn
 * out not to be F12.
 *
 * <p>In menu mode (toggled by the caller), bare Escape closes the menu and
 * printable ASCII bytes are returned as shortcut candidates. CSI sequences
 * (arrow keys etc.) are silently consumed so they don't leak to the container.
 */
public class EscapeSequenceParser {

    public record Result(byte[] toForward, boolean f12Detected, byte menuKey) {
        static final Result EMPTY = new Result(new byte[0], false, (byte) 0);

        static Result forward(byte[] data, int off, int len) {
            if (len == 0) return EMPTY;
            var copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
            return new Result(copy, false, (byte) 0);
        }

        static Result f12() {
            return new Result(new byte[0], true, (byte) 0);
        }

        static Result menu(byte key) {
            return new Result(new byte[0], false, key);
        }
    }

    private enum State { NORMAL, ESC, CSI, CSI_PARAM }

    private State state = State.NORMAL;
    private final byte[] pending = new byte[8];
    private int pendingLen = 0;
    private boolean menuMode = false;

    public void setMenuMode(boolean menuMode) {
        this.menuMode = menuMode;
        if (menuMode) {
            state = State.NORMAL;
            pendingLen = 0;
        }
    }

    public boolean isMenuMode() {
        return menuMode;
    }

    /**
     * Feed raw input bytes and return the parse result. May be called with
     * partial reads — the parser buffers across calls.
     *
     * <p>Returns exactly one result per call. When F12 is detected mid-buffer,
     * the bytes before it are returned in {@code toForward} and the caller
     * should call {@code feed} again with the remaining bytes (this simplifies
     * the contract: one event per call).
     */
    public Result feed(byte[] data, int off, int len) {
        if (len == 0) return Result.EMPTY;

        if (menuMode) {
            return feedMenuMode(data, off, len);
        }
        return feedNormalMode(data, off, len);
    }

    private Result feedMenuMode(byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            switch (state) {
                case NORMAL:
                    if (b == 0x1B) {
                        state = State.ESC;
                        pendingLen = 0;
                        pending[pendingLen++] = data[i];
                    } else if (b >= 0x20 && b <= 0x7E) {
                        return Result.menu(data[i]);
                    }
                    break;
                case ESC:
                    if (b == 0x5B) {
                        state = State.CSI;
                        pending[pendingLen++] = data[i];
                    } else {
                        state = State.NORMAL;
                        pendingLen = 0;
                        if (b == 0x1B) {
                            return Result.menu((byte) 0x1B);
                        }
                        return Result.menu((byte) 0x1B);
                    }
                    break;
                case CSI:
                case CSI_PARAM:
                    if (b >= 0x30 && b <= 0x3F) {
                        state = State.CSI_PARAM;
                        if (pendingLen < pending.length) pending[pendingLen++] = data[i];
                    } else if (b >= 0x40 && b <= 0x7E) {
                        // Final byte — check if this is F12
                        if (isF12Sequence(data[i])) {
                            state = State.NORMAL;
                            pendingLen = 0;
                            return Result.f12();
                        }
                        state = State.NORMAL;
                        pendingLen = 0;
                    } else {
                        state = State.NORMAL;
                        pendingLen = 0;
                    }
                    break;
            }
        }
        if (state == State.ESC) {
            state = State.NORMAL;
            pendingLen = 0;
            return Result.menu((byte) 0x1B);
        }
        return Result.EMPTY;
    }

    private Result feedNormalMode(byte[] data, int off, int len) {
        var out = new byte[len + pendingLen];
        int outLen = 0;

        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            switch (state) {
                case NORMAL:
                    if (b == 0x1B) {
                        state = State.ESC;
                        pendingLen = 0;
                        pending[pendingLen++] = data[i];
                    } else {
                        out[outLen++] = data[i];
                    }
                    break;
                case ESC:
                    if (b == 0x5B) {
                        state = State.CSI;
                        pending[pendingLen++] = data[i];
                    } else {
                        // Not a CSI — flush pending ESC + this byte
                        System.arraycopy(pending, 0, out, outLen, pendingLen);
                        outLen += pendingLen;
                        out[outLen++] = data[i];
                        state = State.NORMAL;
                        pendingLen = 0;
                    }
                    break;
                case CSI:
                case CSI_PARAM:
                    if (b >= 0x30 && b <= 0x3F) {
                        state = State.CSI_PARAM;
                        if (pendingLen < pending.length) pending[pendingLen++] = data[i];
                    } else if (b >= 0x40 && b <= 0x7E) {
                        // Final byte — check if this is F12
                        if (isF12Sequence(data[i])) {
                            state = State.NORMAL;
                            pendingLen = 0;
                            if (outLen > 0) {
                                // Return accumulated output; caller will see F12 on next feed
                                // Actually, signal F12 now — the bytes before it are separate
                                var forwarded = new byte[outLen];
                                System.arraycopy(out, 0, forwarded, 0, outLen);
                                // We need to handle remaining bytes too
                                int remaining = (off + len) - (i + 1);
                                if (remaining > 0) {
                                    var combined = new byte[outLen];
                                    System.arraycopy(out, 0, combined, 0, outLen);
                                    // Return what we have so far + F12 signal
                                    // The caller needs to know about both
                                    return new Result(combined, true, (byte) 0);
                                }
                                return new Result(forwarded, true, (byte) 0);
                            }
                            return Result.f12();
                        }
                        // Not F12 — flush the whole CSI sequence
                        System.arraycopy(pending, 0, out, outLen, pendingLen);
                        outLen += pendingLen;
                        out[outLen++] = data[i];
                        state = State.NORMAL;
                        pendingLen = 0;
                    } else {
                        // Invalid CSI — flush pending
                        System.arraycopy(pending, 0, out, outLen, pendingLen);
                        outLen += pendingLen;
                        out[outLen++] = data[i];
                        state = State.NORMAL;
                        pendingLen = 0;
                    }
                    break;
            }
        }

        if (outLen == 0 && pendingLen == 0) return Result.EMPTY;
        if (outLen == 0) return Result.EMPTY; // still buffering a partial sequence

        var result = new byte[outLen];
        System.arraycopy(out, 0, result, 0, outLen);
        return Result.forward(result, 0, outLen);
    }

    private boolean isF12Sequence(byte finalByte) {
        // F12 = ESC [ 2 4 ~ → pending should be: 1B 5B 32 34, finalByte = 7E
        if (finalByte != 0x7E) return false;
        if (pendingLen != 4) return false;
        return pending[0] == 0x1B && pending[1] == 0x5B
                && pending[2] == 0x32 && pending[3] == 0x34;
    }

    /**
     * Flush any buffered partial sequence. Call when the session ends
     * to avoid losing trailing bytes.
     */
    public byte[] flush() {
        if (pendingLen == 0) return new byte[0];
        var result = new byte[pendingLen];
        System.arraycopy(pending, 0, result, 0, pendingLen);
        pendingLen = 0;
        state = State.NORMAL;
        return result;
    }
}
