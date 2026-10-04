package dev.incusspawn.incus;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ToolAction;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ShellStatusBar {

    static final int BAR_LINES = 2;

    private static final String SAVE_CURSOR = "\0337";
    private static final String RESTORE_CURSOR = "\0338";
    private static final String CLEAR_SCREEN = "\033[2J";
    private static final String CURSOR_HOME = "\033[1;1H";
    private static final String RESET_SCROLL_REGION = "\033[r";
    private static final String ERASE_BELOW = "\033[J";
    private static final String RESET_STYLE = "\033[0m";
    private static final String STYLE_REVERSE_VIDEO = "\033[0;7m";
    private static final String STYLE_DIM = "\033[0;2m";
    private static final String ERASE_LINE_RIGHT = "\033[K";
    /** CAN: aborts whatever escape sequence the terminal is in the middle of. */
    private static final String CANCEL_SEQUENCE = "\030";
    private static final byte ESC = 0x1B;
    static final long FORCED_PAINT_DELAY_MS = 150;

    private final String instanceName;
    private String templateName;
    private final OutputStream out;
    private final Object lock = new Object();

    // Guarded by lock. The bar draws only between setup() and cleanup(): after cleanup nothing
    // may bring the scroll region or the bar back (a late frame, a flash timer, an action that
    // finishes after the session), and the TUI path keeps the JVM alive to see it.
    private boolean open;
    private OutputBoundary childOutput = new OutputBoundary();
    /** A paint the user is waiting for, held back while the child's output stands inside a sequence. */
    private boolean userPaintPending;
    private boolean forcedPaintScheduled;
    /** The flash or hint timer; one at a time, and only the current one may act. */
    private Thread timer;
    private int width;
    private int height;

    private List<ToolAction> menuActions = List.of();
    private ActionContext actionContext;
    // Written under lock; volatile so the stdin thread can read menuActive without it.
    private volatile boolean menuActive = false;
    private volatile String flashMessage;
    private volatile String menuHint;

    public ShellStatusBar(String instanceName, OutputStream out) {
        this.instanceName = instanceName;
        // Buffer all writes so child output + scroll region fixup + bar repaint
        // reach the terminal in one write syscall (one rendering frame).
        this.out = new BufferedOutputStream(out, 131072);
    }

    public int effectiveHeight(int realHeight) {
        return Math.max(realHeight - BAR_LINES, 1);
    }

    /** The menu's actions, as {@code ShellMenu.of} selected them: host-side, opted in. */
    public void setMenuActions(List<ToolAction> actions, ActionContext context) {
        this.menuActions = actions != null ? actions : List.of();
        this.actionContext = context;
        this.templateName = context != null && !context.parent().isBlank() ? context.parent() : null;
    }

    public List<ToolAction> menuActions() {
        return menuActions;
    }

    /**
     * Whether F12 is the bar's. Not with an empty menu: then it is the shell's, as without the
     * feature (mc and others bind it).
     */
    public boolean interceptsF12() {
        return !menuActions.isEmpty();
    }

    public boolean isMenuActive() {
        return menuActive;
    }

    /** Start drawing, for a session (or a reconnect) whose output starts fresh. */
    public void setup(int width, int height) {
        synchronized (lock) {
            open = true;
            childOutput = new OutputBoundary();
            this.width = width;
            this.height = height;
            paintUnsync(true);
        }
    }

    public void resize(int newWidth, int newHeight) {
        synchronized (lock) {
            this.width = newWidth;
            this.height = newHeight;
            // Mid-sequence, a clear would land after the child's next frame -- its SIGWINCH
            // redraw -- and blank it. The child redraws anyway, so the region and bar suffice.
            paintUnsync(childOutput.atBoundary());
        }
    }

    public void writeOutput(byte[] data, int off, int len) throws IOException {
        synchronized (lock) {
            out.write(data, off, len);
            childOutput.scan(data, off, len);
            // Every frame re-asserts the scroll region and the bar, which the child may have
            // reset or scrolled over.
            paintUnsync(false);
            flush();
        }
    }

    /**
     * Draw the scroll region and the bar, leaving the cursor where the shell has it -- or, with
     * {@code clear}, clear the screen first (after a resize, the old bar rows may be anywhere).
     * Nothing once closed. A frame can end inside an escape sequence or a UTF-8 character, and
     * anything written there would cut it short, so while the child's output stands inside one
     * nothing is drawn: writeOutput paints again after every frame.
     */
    private void paintUnsync(boolean clear) {
        if (!open || !childOutput.atBoundary()) return;
        userPaintPending = false;
        if (clear) {
            emit(setScrollRegion(1, height - BAR_LINES));
            emit(CLEAR_SCREEN);
            emit(CURSOR_HOME);
            emit(SAVE_CURSOR);
        } else {
            emit(SAVE_CURSOR);
            emit(setScrollRegion(1, height - BAR_LINES));
        }
        renderBarUnsync();
        emit(RESTORE_CURSOR);
        flush();
    }

    /**
     * A paint the user is waiting for: the menu, a hint, an action's result. Mid-sequence it
     * waits like any other, usually only for the next frame; but a child that went quiet inside
     * a sequence sends no next frame, so after {@link #FORCED_PAINT_DELAY_MS} it is drawn anyway
     * -- a key the user pressed must show, and a stuck sequence is broken already.
     */
    private void requestPaintUnsync() {
        if (!open) return;
        if (childOutput.atBoundary()) {
            paintUnsync(false);
            return;
        }
        userPaintPending = true;
        if (forcedPaintScheduled) return;
        forcedPaintScheduled = true;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(FORCED_PAINT_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            } finally {
                synchronized (lock) { forcedPaintScheduled = false; }
            }
            synchronized (lock) {
                if (!open || !userPaintPending) return;
                childOutput = new OutputBoundary(); // the bar's own sequences end the stuck one
                paintUnsync(false);
            }
        });
    }

    /** After {@code millis}, run {@code expire} under the lock -- unless replaced or cancelled first. */
    private void restartTimerUnsync(long millis, Runnable expire) {
        cancelTimerUnsync();
        var started = Thread.ofVirtual().unstarted(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                if (timer != Thread.currentThread()) return;
                timer = null;
                expire.run();
            }
        });
        timer = started;
        started.start();
    }

    private void cancelTimerUnsync() {
        if (timer != null) timer.interrupt();
        timer = null;
    }

    /**
     * Just enough of a terminal's parser to tell whether the child's output stream stands between
     * escape sequences and characters, across frames: ESC, CSI, string sequences (OSC, ended by
     * BEL or ST; DCS, APC, PM and SOS, ended by ST alone) and UTF-8 continuation bytes.
     */
    static final class OutputBoundary {
        private enum State { GROUND, ESC, CSI, OSC, STRING, STRING_ESC }

        private State state = State.GROUND;
        private int continuationBytes;

        boolean atBoundary() {
            return state == State.GROUND && continuationBytes == 0;
        }

        void scan(byte[] data, int off, int len) {
            for (int i = off; i < off + len; i++) {
                int b = data[i] & 0xFF;
                if (state != State.GROUND && (b == 0x18 || b == 0x1A)) {
                    state = State.GROUND; // CAN and SUB abort any sequence
                    continue;
                }
                switch (state) {
                    case GROUND -> {
                        if (b == 0x1B) {
                            state = State.ESC;
                            continuationBytes = 0;
                        } else if ((b & 0xC0) == 0x80) {
                            if (continuationBytes > 0) continuationBytes--;
                        } else {
                            continuationBytes = b >= 0xF0 ? 3 : b >= 0xE0 ? 2 : b >= 0xC0 ? 1 : 0;
                        }
                    }
                    case ESC -> state = afterEscape(b);
                    case CSI -> {
                        if (b == 0x1B) state = State.ESC; // starts a new sequence
                        else if (b >= 0x40 && b <= 0x7E) state = State.GROUND;
                    }
                    case OSC -> {
                        if (b == 0x07) state = State.GROUND;
                        else if (b == 0x1B) state = State.STRING_ESC;
                    }
                    case STRING -> {
                        if (b == 0x1B) state = State.STRING_ESC;
                    }
                    // ST ends the string; ESC and anything else aborts it and starts a sequence.
                    case STRING_ESC -> state = b == '\\' ? State.GROUND : afterEscape(b);
                }
            }
        }

        private static State afterEscape(int b) {
            return switch (b) {
                case '[' -> State.CSI;
                case ']' -> State.OSC;
                case 'P', '_', '^', 'X' -> State.STRING;
                case 0x1B -> State.ESC;
                default -> b >= 0x20 && b <= 0x2F ? State.ESC : State.GROUND;
            };
        }
    }

    /**
     * Stop drawing, for good until the next {@link #setup}: erase the bar and give back the full
     * screen. Idempotent, since the shutdown hook and the session's end can both get here.
     */
    public void cleanup() {
        synchronized (lock) {
            if (!open) return;
            open = false;
            // A reconnect starts with a fresh input parser, which knows nothing of an open menu.
            menuActive = false;
            menuHint = null;
            flashMessage = null;
            userPaintPending = false;
            cancelTimerUnsync();
            // A child killed mid-sequence would swallow what follows into it.
            if (!childOutput.atBoundary()) emit(CANCEL_SEQUENCE);
            // Erase both bar rows, and give back the full screen with the cursor where the
            // shell left it: resetting the scroll region homes the cursor, hence the save.
            emit(SAVE_CURSOR);
            emit("\033[" + (height - BAR_LINES + 1) + ";1H");
            emit(ERASE_BELOW);
            emit(RESET_SCROLL_REGION);
            emit(RESTORE_CURSOR);
            flush();
        }
    }

    public void showMenu() {
        synchronized (lock) {
            menuActive = true;
            menuHint = null;
            requestPaintUnsync();
        }
    }

    public void hideMenu() {
        synchronized (lock) {
            menuActive = false;
            menuHint = null;
            flashMessage = null;
            cancelTimerUnsync();
            requestPaintUnsync();
        }
    }

    public void handleMenuKey(byte key) {
        if (key == ESC) {
            hideMenu();
            return;
        }
        char ch = (char) (key & 0xFF);
        for (var action : menuActions) {
            var shortcut = action.shortcut();
            if (shortcut.isPresent() && shortcut.get().length() == 1
                    && Character.toLowerCase(shortcut.get().charAt(0)) == Character.toLowerCase(ch)) {
                hideMenu();
                runAction(action);
                return;
            }
        }
        var sb = new StringBuilder("Press ");
        for (int i = 0; i < menuActions.size(); i++) {
            var s = menuActions.get(i).shortcut();
            if (s.isPresent()) {
                if (i > 0) sb.append("/");
                sb.append(s.get());
            }
        }
        sb.append(" or Esc");
        synchronized (lock) {
            menuHint = sb.toString();
            requestPaintUnsync();
            restartTimerUnsync(1500, () -> {
                menuHint = null;
                if (menuActive) requestPaintUnsync();
            });
        }
    }

    /**
     * Run a menu action off the stdin thread -- opening a URL can wait seconds on a launcher, and
     * the shell must keep getting keystrokes meanwhile -- and without prompting: the session owns
     * the terminal, so an action that read from it would fight the shell for keystrokes.
     */
    Thread runAction(ToolAction action) {
        return Thread.ofVirtual().start(() -> flashResult(action));
    }

    private void flashResult(ToolAction action) {
        var result = action.executeWithoutPrompting(actionContext);
        if (result.success()) {
            showFlash("✓ " + action.label(), false);
        } else {
            showFlash("✗ " + result.message(), true);
        }
    }

    private void showFlash(String message, boolean isError) {
        synchronized (lock) {
            if (!open) return;
            // One row: an action's error can span several lines, and the first says what failed.
            flashMessage = message.lines().findFirst().orElse("");
            requestPaintUnsync();
            if (isError) {
                cancelTimerUnsync(); // an error stays until the menu is next used
            } else {
                restartTimerUnsync(3000, () -> {
                    flashMessage = null;
                    if (!menuActive) requestPaintUnsync();
                });
            }
        }
    }

    private void renderBarUnsync() {
        int topRow = height - BAR_LINES + 1;
        int bottomRow = height;

        if (menuActive) {
            renderMenuUnsync(topRow, bottomRow);
            return;
        }

        // Top line: instance info + context (IP, network, repos)
        emitLine(topRow, padLine(header(), buildInfoLine()), true);

        // Bottom line: flash message or F12 hint
        String bottomContent;
        if (flashMessage != null) {
            bottomContent = " " + flashMessage;
        } else if (!menuActions.isEmpty()) {
            bottomContent = " F12: Menu";
        } else {
            bottomContent = "";
        }
        emitLine(bottomRow, bottomContent, false);
    }

    private String header() {
        return " isx " + instanceName + (templateName != null ? " [" + templateName + "]" : "");
    }

    private String buildInfoLine() {
        if (actionContext == null) return "";
        var parts = new java.util.ArrayList<String>();
        var ip = actionContext.ipv4();
        if (ip != null && !ip.isEmpty()) {
            parts.add(ip);
        }
        var mode = actionContext.networkMode();
        if (mode != null && !mode.isEmpty() && !"FULL".equalsIgnoreCase(mode)) {
            parts.add(mode.toLowerCase());
        }
        if (!actionContext.repos().isEmpty()) {
            var repoNames = actionContext.repos().stream()
                    .map(ActionContext.RepoInfo::name)
                    .toList();
            if (repoNames.size() <= 3) {
                parts.add(String.join(", ", repoNames));
            } else {
                parts.add(repoNames.get(0) + " +" + (repoNames.size() - 1));
            }
        }
        return parts.isEmpty() ? "" : String.join("  ", parts) + " ";
    }

    private void renderMenuUnsync(int topRow, int bottomRow) {
        // Top line: instance name
        emitLine(topRow, padLine(header(), "[Esc] Close "), true);

        // Bottom line: action shortcuts, or which keys work after a wrong one
        var hint = menuHint;
        if (hint != null) {
            emitLine(bottomRow, " " + hint, true);
            return;
        }
        var sb = new StringBuilder(" ");
        if (menuActions.isEmpty()) {
            sb.append("No actions available");
        } else {
            for (var action : menuActions) {
                var shortcut = action.shortcut();
                if (shortcut.isPresent()) {
                    sb.append("[").append(shortcut.get()).append("] ").append(action.label()).append("  ");
                }
            }
        }
        emitLine(bottomRow, sb.toString(), true);
    }

    /**
     * Terminal columns a code point takes: two for East Asian wide characters and emoji. Where
     * terminals disagree it errs wide, since a row counted too wide only ends early, while one
     * counted too narrow wraps and scrolls the screen.
     */
    static int columns(int codePoint) {
        int c = codePoint;
        boolean wide = (c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6) || (c >= 0x1F000 && c <= 0x1FAFF)
                || (c >= 0x20000 && c <= 0x3FFFD) || isWideBmpEmoji(c);
        return wide ? 2 : 1;
    }

    /** The emoji below U+1F000 that terminals draw two columns wide (Emoji_Presentation). */
    private static boolean isWideBmpEmoji(int c) {
        return switch (c) {
            case 0x231A, 0x231B, 0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3, 0x25FD, 0x25FE,
                 0x2614, 0x2615, 0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4,
                 0x26C5, 0x26CE, 0x26D4, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD, 0x2705,
                 0x270A, 0x270B, 0x2728, 0x274C, 0x274E, 0x2753, 0x2754, 0x2755, 0x2757, 0x2795,
                 0x2796, 0x2797, 0x27B0, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B55 -> true;
            default -> c >= 0x2648 && c <= 0x2653;
        };
    }

    static int columns(String s) {
        return s.codePoints().map(ShellStatusBar::columns).sum();
    }

    private String padLine(String left, String right) {
        int padding = width - columns(left) - columns(right);
        var content = new StringBuilder(left);
        if (padding > 0) {
            content.append(" ".repeat(padding));
        }
        if (padding >= 0) {
            content.append(right);
        }
        return content.toString();
    }

    private void emitLine(int row, String content, boolean highlight) {
        // Cut to the terminal's columns, never inside a character, so the row cannot wrap and
        // scroll the screen; control characters would move the cursor out of it, so they show
        // as spaces.
        var line = new StringBuilder();
        int used = 0;
        for (int c : content.codePoints().toArray()) {
            if (Character.isISOControl(c)) c = ' ';
            int w = columns(c);
            if (used + w > width) break;
            line.appendCodePoint(c);
            used += w;
        }
        if (used < width) {
            line.append(" ".repeat(width - used));
        }

        emit("\033[" + row + ";1H");
        emit((highlight ? STYLE_REVERSE_VIDEO : STYLE_DIM) + line + RESET_STYLE);
        emit(ERASE_LINE_RIGHT);
    }

    private void emit(String seq) {
        try {
            out.write(seq.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException ignored) {}
    }

    private static String setScrollRegion(int top, int bottom) {
        return "\033[" + top + ";" + bottom + "r";
    }
}
