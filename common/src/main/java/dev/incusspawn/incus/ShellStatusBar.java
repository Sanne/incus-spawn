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

    private static final String TYPE_URL = "url";
    private static final String TYPE_COMMAND = "command";

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
    private static final byte ESC = 0x1B;

    private final String instanceName;
    private String templateName;
    private final OutputStream out;
    private final Object lock = new Object();

    private int width;
    private int height;

    private List<ToolAction> menuActions = List.of();
    private ActionContext actionContext;
    private volatile boolean menuActive = false;
    private volatile String flashMessage;
    private volatile boolean flashIsError;
    private volatile Thread flashThread;

    public ShellStatusBar(String instanceName, OutputStream out) {
        this.instanceName = instanceName;
        // Buffer all writes so child output + scroll region fixup + bar repaint
        // reach the terminal in one write syscall (one rendering frame).
        this.out = new BufferedOutputStream(out, 131072);
    }

    public Object renderLock() {
        return lock;
    }

    public int effectiveHeight(int realHeight) {
        return Math.max(realHeight - BAR_LINES, 1);
    }

    public void setMenuActions(List<ToolAction> actions, ActionContext context) {
        this.menuActions = actions != null ? actions.stream()
                .filter(a -> {
                    var type = a.type();
                    return type.isPresent() && (TYPE_URL.equals(type.get()) || TYPE_COMMAND.equals(type.get()));
                })
                .toList() : List.of();
        this.actionContext = context;
        this.templateName = context != null && !context.parent().isBlank() ? context.parent() : null;
    }

    public List<ToolAction> menuActions() {
        return menuActions;
    }

    public boolean isMenuActive() {
        return menuActive;
    }

    public void setup(int width, int height) {
        this.width = width;
        this.height = height;
        synchronized (lock) {
            emit(setScrollRegion(1, height - BAR_LINES));
            emit(CLEAR_SCREEN);
            emit(CURSOR_HOME);
            emit(SAVE_CURSOR);
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        synchronized (lock) {
            emit(setScrollRegion(1, newHeight - BAR_LINES));
            emit(CLEAR_SCREEN);
            emit(CURSOR_HOME);
            emit(SAVE_CURSOR);
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
        }
    }

    public void writeOutput(byte[] data, int off, int len) throws IOException {
        synchronized (lock) {
            out.write(data, off, len);
            emit(SAVE_CURSOR);
            emit(setScrollRegion(1, height - BAR_LINES));
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
        }
    }

    public void renderBar() {
        synchronized (lock) {
            emit(SAVE_CURSOR);
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
        }
    }

    public void cleanup() {
        synchronized (lock) {
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
        synchronized (lock) {
            emit(SAVE_CURSOR);
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
        }
    }

    public void hideMenu() {
        menuActive = false;
        clearFlash();
        synchronized (lock) {
            emit(SAVE_CURSOR);
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
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
                executeAction(action);
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
            renderMenuFlashUnsync(sb.toString());
        }
    }

    private void renderMenuFlashUnsync(String hint) {
        int bottomRow = height;
        emit(SAVE_CURSOR);
        emitLine(bottomRow, " " + hint, true);
        emit(RESTORE_CURSOR);
        flush();
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        flashThread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1500);
                synchronized (lock) {
                    if (menuActive) {
                        emit(SAVE_CURSOR);
                        renderBarUnsync();
                        emit(RESTORE_CURSOR);
                        flush();
                    }
                }
            } catch (InterruptedException ignored) {}
        });
    }

    private void executeAction(ToolAction action) {
        var type = action.type().orElse("");
        if (TYPE_URL.equals(type)) {
            var result = action.execute(actionContext);
            if (result.success()) {
                showFlash("✓ " + action.label(), false);
            } else {
                showFlash("✗ " + result.message(), true);
            }
        } else if (TYPE_COMMAND.equals(type)) {
            showFlash("Running: " + action.label() + "...", false);
            Thread.ofVirtual().start(() -> {
                var result = action.execute(actionContext);
                if (result.success()) {
                    showFlash("✓ " + action.label(), false);
                } else {
                    showFlash("✗ " + result.message(), true);
                }
            });
        }
    }

    private void showFlash(String message, boolean isError) {
        this.flashMessage = message;
        this.flashIsError = isError;
        synchronized (lock) {
            emit(SAVE_CURSOR);
            renderBarUnsync();
            emit(RESTORE_CURSOR);
            flush();
        }
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        if (!isError) {
            flashThread = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(3000);
                    clearFlash();
                    synchronized (lock) {
                        if (!menuActive) {
                            emit(SAVE_CURSOR);
                            renderBarUnsync();
                            emit(RESTORE_CURSOR);
                            flush();
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
        }
    }

    private void clearFlash() {
        flashMessage = null;
        flashIsError = false;
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
        var left = " isx " + instanceName;
        if (templateName != null) {
            left += " [" + templateName + "]";
        }
        var info = buildInfoLine();
        emitLine(topRow, padLine(left, info), true);

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
        var left = " isx " + instanceName;
        if (templateName != null) {
            left += " [" + templateName + "]";
        }
        emitLine(topRow, padLine(left, "[Esc] Close "), true);

        // Bottom line: action shortcuts
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
        var line = content;
        if (line.length() > width) {
            line = line.substring(0, width);
        }
        int pad = width - line.length();
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
