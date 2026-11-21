package dev.incusspawn.tui;

/**
 * The TUI's glyphs, each with the one meaning it has (design/visual-language.md "Glyphs"). A state
 * is never shown by colour alone: its glyph says it on a terminal with no colour.
 */
final class Glyphs {
    private Glyphs() {}

    /** An instance that is running; in the cursor gutter, a marked row. */
    static final String RUNNING = "●";
    /** An instance that is stopped. */
    static final String STOPPED = "○";
    /** An instance between states that isx did not put there (frozen, in error). */
    static final String BETWEEN = "◐";
    /** The row the cursor is on, and the focused field of a dialog. */
    static final String CURSOR = "▸";
    /** Work that ended well, and work that failed. */
    static final String DONE = "✓";
    static final String FAILED = "✗";
    /** Something the person should look at. */
    static final String WARNING = "⚠";

    /** The logo, three rows of box drawing: the one decoration the TUI has. */
    static final String[] LOGO = {
            "╻ ┏━┓ ╻ ╻",
            "┃ ┗━┓ ╺╋╸",
            "╹ ┗━┛ ╹ ╹",
    };
}
