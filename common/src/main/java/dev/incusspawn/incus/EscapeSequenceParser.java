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

    /**
     * One parse event. {@code consumed} is how many of the fed bytes it accounts for: after an
     * F12 or a menu key the rest of the read is left unparsed, and the caller feeds it again.
     */
    public record Result(byte[] toForward, boolean f12Detected, byte menuKey, int consumed) {

        static Result forward(byte[] data, int len, int consumed) {
            var copy = new byte[len];
            System.arraycopy(data, 0, copy, 0, len);
            return new Result(copy, false, (byte) 0, consumed);
        }

        static Result f12(byte[] before, int len, int consumed) {
            var copy = new byte[len];
            System.arraycopy(before, 0, copy, 0, len);
            return new Result(copy, true, (byte) 0, consumed);
        }

        static Result menu(byte key, int consumed) {
            return new Result(new byte[0], false, key, consumed);
        }
    }

    private enum State { NORMAL, ESC, CSI, CSI_PARAM, SS3 }

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
     * partial reads — the parser buffers a split CSI sequence across calls.
     *
     * <p>Returns exactly one event per call. When F12 or a menu key is found
     * mid-buffer, the bytes before it are returned in {@code toForward} and
     * {@link Result#consumed()} stops after it: the caller feeds the rest again.
     *
     * <p>A read that ends in a bare ESC is the Escape key, not the start of a
     * sequence: terminals write a whole key sequence at once, and holding the
     * ESC back would delay it until the next keystroke.
     */
    public Result feed(byte[] data, int off, int len) {
        if (len == 0) return Result.forward(new byte[0], 0, 0);

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
                        return Result.menu(data[i], i + 1 - off);
                    }
                    break;
                case ESC:
                    if (b == 0x5B) {
                        state = State.CSI;
                        pending[pendingLen++] = data[i];
                    } else if (b == 0x4F) {
                        // SS3: ESC O <key>, e.g. arrows with application cursor keys on.
                        state = State.SS3;
                    } else {
                        state = State.NORMAL;
                        pendingLen = 0;
                        return Result.menu((byte) 0x1B, i + 1 - off);
                    }
                    break;
                case SS3:
                    state = State.NORMAL;
                    pendingLen = 0;
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
                            return Result.f12(pending, 0, i + 1 - off);
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
            return Result.menu((byte) 0x1B, len);
        }
        return Result.forward(pending, 0, len);
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
                    if (b >= 0x30 && b <= 0x3F && pendingLen == pending.length) {
                        // Too long to be F12 (e.g. a mouse report): pass it through as it comes.
                        System.arraycopy(pending, 0, out, outLen, pendingLen);
                        outLen += pendingLen;
                        out[outLen++] = data[i];
                        state = State.NORMAL;
                        pendingLen = 0;
                    } else if (b >= 0x30 && b <= 0x3F) {
                        state = State.CSI_PARAM;
                        pending[pendingLen++] = data[i];
                    } else if (b >= 0x40 && b <= 0x7E) {
                        // Final byte — check if this is F12
                        if (isF12Sequence(data[i])) {
                            state = State.NORMAL;
                            pendingLen = 0;
                            return Result.f12(out, outLen, i + 1 - off);
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

        if (state == State.ESC) {
            // A bare Escape key (see feed): forward it now.
            System.arraycopy(pending, 0, out, outLen, pendingLen);
            outLen += pendingLen;
            state = State.NORMAL;
            pendingLen = 0;
        }
        return Result.forward(out, outLen, len);
    }

    private boolean isF12Sequence(byte finalByte) {
        // F12 = ESC [ 2 4 ~ → pending should be: 1B 5B 32 34, finalByte = 7E
        if (finalByte != 0x7E) return false;
        if (pendingLen != 4) return false;
        return pending[0] == 0x1B && pending[1] == 0x5B
                && pending[2] == 0x32 && pending[3] == 0x34;
    }
}
