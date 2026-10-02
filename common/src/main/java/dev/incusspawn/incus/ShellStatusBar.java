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

    private final String instanceName;
    private String templateName;
    private final OutputStream out;
    private final Object lock = new Object();

    // Guarded by lock. The bar draws only between setup() and cleanup(): after cleanup nothing
    // may bring the scroll region or the bar back (a late frame, a flash timer, an action that
    // finishes after the session), and the TUI path keeps the JVM alive to see it.
    private boolean open;
    private OutputBoundary childOutput = new OutputBoundary();
    /** A resize arrived while the child's output stood inside a sequence: clear at the next boundary. */
    private boolean clearPending;
    private int width;
    private int height;

    private List<ToolAction> menuActions = List.of();
    private ActionContext actionContext;
    private volatile boolean menuActive = false;
    private volatile String flashMessage;
    private volatile String menuHint;
    private volatile Thread flashThread;

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
            paintUnsync(true);
        }
    }

    public void writeOutput(byte[] data, int off, int len) throws IOException {
        synchronized (lock) {
            out.write(data, off, len);
            childOutput.scan(data, off, len);
            // Every frame re-asserts the scroll region and the bar, which the child may have
            // reset or scrolled over.
            paintUnsync(clearPending);
            flush();
        }
    }

    /**
     * Draw the scroll region and the bar, leaving the cursor where the shell has it -- or, with
     * {@code clear}, clear the screen first (after a resize, the old bar rows may be anywhere).
     * Nothing once closed. A frame can end inside an escape sequence or a UTF-8 character, and
     * anything written there would cut it short, so while the child's output stands inside one
     * the paint waits for the next frame that ends between them.
     */
    private void paintUnsync(boolean clear) {
        if (!open) return;
        if (!childOutput.atBoundary()) {
            // writeOutput paints again after every frame, so this one is only deferred.
            clearPending |= clear;
            return;
        }
        clearPending = false;
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
     * Just enough of a terminal's parser to tell whether the child's output stream stands between
     * escape sequences and characters, across frames: ESC, CSI, string sequences (OSC, DCS, APC,
     * PM, SOS, ended by BEL or ST) and UTF-8 continuation bytes.
     */
    static final class OutputBoundary {
        private enum State { GROUND, ESC, CSI, STRING, STRING_ESC }

        private State state = State.GROUND;
        private int continuationBytes;

        boolean atBoundary() {
            return state == State.GROUND && continuationBytes == 0;
        }

        void scan(byte[] data, int off, int len) {
            for (int i = off; i < off + len; i++) {
                int b = data[i] & 0xFF;
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
                    case ESC -> state = switch (b) {
                        case '[' -> State.CSI;
                        case ']', 'P', '_', '^', 'X' -> State.STRING;
                        default -> b >= 0x20 && b <= 0x2F ? State.ESC : State.GROUND;
                    };
                    case CSI -> {
                        if (b >= 0x40 && b <= 0x7E) state = State.GROUND;
                    }
                    case STRING -> {
                        if (b == 0x07) state = State.GROUND;
                        else if (b == 0x1B) state = State.STRING_ESC;
                    }
                    case STRING_ESC -> state = b == '\\' ? State.GROUND : State.STRING;
                }
            }
        }
    }

    private void repaint() {
        synchronized (lock) {
            paintUnsync(false);
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
            clearFlash();
            menuHint = null;
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
        menuActive = true;
        repaint();
    }

    public void hideMenu() {
        menuActive = false;
        clearFlash();
        repaint();
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
        menuHint = sb.toString();
        repaint();
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        flashThread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1500);
                menuHint = null;
                if (menuActive) repaint();
            } catch (InterruptedException ignored) {}
        });
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
            this.flashMessage = message.lines().findFirst().orElse("");
            paintUnsync(false);
        }
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        if (!isError) {
            flashThread = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(3000);
                    clearFlash();
                    if (!menuActive) repaint();
                } catch (InterruptedException ignored) {}
            });
        }
    }

    private void clearFlash() {
        flashMessage = null;
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        flashThread = null;
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

    private String padLine(String left, String right) {
        int padding = width - left.length() - right.length();
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
        // Cut by code points, never inside a surrogate pair; control characters would move the
        // cursor out of the row, so they show as spaces.
        int[] codePoints = content.codePoints()
                .map(c -> Character.isISOControl(c) ? ' ' : c)
                .limit(Math.max(width, 0))
                .toArray();
        var line = new String(codePoints, 0, codePoints.length);
        int pad = width - codePoints.length;
        if (pad > 0) {
            line = line + " ".repeat(pad);
        }

        emit("\033[" + row + ";1H");
        if (highlight) {
            emit(STYLE_REVERSE_VIDEO + line + RESET_STYLE);
        } else {
            emit(STYLE_DIM + line + RESET_STYLE);
        }
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
