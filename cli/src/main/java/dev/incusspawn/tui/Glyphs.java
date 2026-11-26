package dev.incusspawn.tui;

/**
 * The TUI's glyphs, each with the one meaning it has (design/visual-language.md "Glyphs"). A state
 * is never shown by colour alone: its glyph says it on a terminal with no colour.
 */
final class Glyphs {
    private Glyphs() {}

    /** An instance that is running, before its name. */
    static final String RUNNING = "●";
    /** A marked row, in the second cell of the cursor gutter: a shape apart from {@link #RUNNING}, which sits beside it. */
    static final String MARKED = "◆";
    /** An instance that is stopped. */
    static final String STOPPED = "○";
    /** An instance between states that isx did not put there (frozen, in error). */
    static final String BETWEEN = "◐";
    /** The row the cursor is on, and the focused field of a dialog. */
    static final String CURSOR = "▸";
    /** Work that ended well, and work that failed. */
    static final String DONE = "✓";
    static final String FAILED = "✗";
    /** On the Work tab: changes not committed ({@code ±3}), commits not pushed ({@code ↑1}), stashes alone ({@code ≡2}), not read ({@code –}). */
    static final String UNCOMMITTED = "±";
    static final String UNPUSHED = "↑";
    static final String STASHED = "≡";
    static final String NOT_READ = "–";
    /** A pushed branch, on a Work row's second line and in the preview. */
    static final String PUSHED = "⇡";
    /** Between a Work row's purpose and its references. */
    static final String BETWEEN_PARTS = "·";
    /** Something the person should look at. */
    static final String WARNING = "⚠";

    /** The logo, three rows of box drawing: the one decoration the TUI has. {@link Logo} holds it, for the first run's welcome too. */
    static final String[] LOGO = Logo.ROWS;
}
