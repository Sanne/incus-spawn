package dev.incusspawn.incus;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ToolAction;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ShellStatusBar {

    static final int BAR_LINES = 2;

    private static final String TYPE_URL = "url";
    private static final String TYPE_COMMAND = "command";
    private static final byte[] CLEAR_SCREEN = {0x1B, 0x5B, 0x32, 0x4A};
    private static final byte[] SCROLL_REGION_RESET = {0x1B, 0x5B, 0x72};

    private static final byte[][] ALT_SCREEN_ENTER = {
            {0x1B, 0x5B, 0x3F, 0x31, 0x30, 0x34, 0x39, 0x68}, // \e[?1049h
            {0x1B, 0x5B, 0x3F, 0x31, 0x30, 0x34, 0x37, 0x68}, // \e[?1047h
            {0x1B, 0x5B, 0x3F, 0x34, 0x37, 0x68},             // \e[?47h
    };
    private static final byte[][] ALT_SCREEN_LEAVE = {
            {0x1B, 0x5B, 0x3F, 0x31, 0x30, 0x34, 0x39, 0x6C}, // \e[?1049l
            {0x1B, 0x5B, 0x3F, 0x31, 0x30, 0x34, 0x37, 0x6C}, // \e[?1047l
            {0x1B, 0x5B, 0x3F, 0x34, 0x37, 0x6C},             // \e[?47l
    };

    private final String instanceName;
    private final String templateName;
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

    public ShellStatusBar(String instanceName, String templateName, OutputStream out) {
        this.instanceName = instanceName;
        this.templateName = templateName;
        this.out = out;
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
            emit("\033[2J");
            emit("\033[1;1H");
            renderBarUnsync();
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        synchronized (lock) {
            emit(setScrollRegion(1, newHeight - BAR_LINES));
            emit("\033[2J");
            emit("\033[1;1H");
            renderBarUnsync();
        }
    }

    public void writeOutput(byte[] data, int off, int len) throws IOException {
        synchronized (lock) {
            boolean hasScrollReset = containsBytes(data, off, len, SCROLL_REGION_RESET);
            boolean hasAltEnter = hasAnySequence(data, off, len, ALT_SCREEN_ENTER);

            if (hasScrollReset || hasAltEnter) {
                writeProtectingScrollRegion(data, off, len);
            } else {
                out.write(data, off, len);
            }
            out.flush();
            if (hasScrollReset || hasAltEnter
                    || hasAnySequence(data, off, len, ALT_SCREEN_LEAVE)
                    || containsBytes(data, off, len, CLEAR_SCREEN)) {
                emit(setScrollRegion(1, height - BAR_LINES));
                renderBarUnsync();
            }
        }
    }

    private void writeProtectingScrollRegion(byte[] data, int off, int len) throws IOException {
        byte[] scrollRegion = setScrollRegion(1, height - BAR_LINES).getBytes(StandardCharsets.US_ASCII);
        int pos = off;
        int end = off + len;

        while (pos < end) {
            int matchIdx = -1;
            int matchLen = 0;
            boolean replace = false;

            int idx = indexOf(data, pos, end, SCROLL_REGION_RESET);
            if (idx >= 0) {
                matchIdx = idx;
                matchLen = SCROLL_REGION_RESET.length;
                replace = true;
            }

            for (var seq : ALT_SCREEN_ENTER) {
                int i = indexOf(data, pos, end, seq);
                if (i >= 0 && (matchIdx < 0 || i < matchIdx)) {
                    matchIdx = i;
                    matchLen = seq.length;
                    replace = false;
                }
            }

            if (matchIdx < 0) {
                out.write(data, pos, end - pos);
                break;
            }

            if (replace) {
                if (matchIdx > pos) {
                    out.write(data, pos, matchIdx - pos);
                }
                out.write(scrollRegion);
            } else {
                out.write(data, pos, matchIdx + matchLen - pos);
                out.write(scrollRegion);
            }
            pos = matchIdx + matchLen;
        }
    }

    private static boolean hasAnySequence(byte[] data, int off, int len, byte[][] sequences) {
        for (var seq : sequences) {
            if (containsBytes(data, off, len, seq)) return true;
        }
        return false;
    }

    private static int indexOf(byte[] data, int from, int to, byte[] pattern) {
        int searchEnd = to - pattern.length;
        outer:
        for (int i = from; i <= searchEnd; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public void renderBar() {
        synchronized (lock) {
            renderBarUnsync();
        }
    }

    public void cleanup() {
        synchronized (lock) {
            emit("\033[r");
            emit("\033[" + height + ";1H");
            emit("\033[J");
            flush();
        }
    }

    public void showMenu() {
        menuActive = true;
        synchronized (lock) {
            renderBarUnsync();
        }
    }

    public void hideMenu() {
        menuActive = false;
        clearFlash();
        synchronized (lock) {
            renderBarUnsync();
        }
    }

    public void handleMenuKey(byte key) {
        if (key == 0x1B) {
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
        emitLine(bottomRow, " " + hint, true);
        flush();
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        flashThread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1500);
                synchronized (lock) {
                    if (menuActive) renderBarUnsync();
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
            renderBarUnsync();
        }
        var prev = flashThread;
        if (prev != null) prev.interrupt();
        if (!isError) {
            flashThread = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(3000);
                    clearFlash();
                    synchronized (lock) {
                        if (!menuActive) renderBarUnsync();
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
        flush();
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
        flush();
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

        emit("\0337");
        emit("\033[" + row + ";1H");
        if (highlight) {
            emit("\033[0;7m" + line + "\033[0m");
        } else {
            emit("\033[0;2m" + line + "\033[0m");
        }
        emit("\033[K");
        emit("\0338");
    }

    private void emit(String seq) {
        try {
            out.write(seq.getBytes(StandardCharsets.US_ASCII));
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

    private static boolean containsBytes(byte[] data, int off, int len, byte[] pattern) {
        int end = off + len - pattern.length;
        outer:
        for (int i = off; i <= end; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
