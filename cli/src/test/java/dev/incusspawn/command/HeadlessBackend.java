package dev.incusspawn.command;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

/** A terminal that is never drawn on: lets a test hold a real {@code TuiRunner} to quit or draw. */
final class HeadlessBackend implements Backend {
    private final Size size;

    HeadlessBackend(int width, int height) {
        this.size = new Size(width, height);
    }

    @Override public void draw(DiffResult diff) {}
    @Override public void flush() {}
    @Override public void clear() {}
    @Override public Size size() { return size; }
    @Override public void showCursor() {}
    @Override public void hideCursor() {}
    @Override public Position getCursorPosition() { return new Position(0, 0); }
    @Override public void setCursorPosition(Position position) {}
    @Override public void enterAlternateScreen() {}
    @Override public void leaveAlternateScreen() {}
    @Override public void enableRawMode() {}
    @Override public void disableRawMode() {}
    @Override public void onResize(Runnable handler) {}
    @Override public int read(int timeoutMs) { return -2; }
    @Override public int peek(int timeoutMs) { return -2; }
    @Override public void close() {}
}
