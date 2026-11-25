package dev.incusspawn.tui;

import dev.incusspawn.util.BuildOutput;
import dev.tamboui.style.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The isx logo, three rows of box drawing, in the one place both its users read: the TUI's empty
 * instance panel, which draws it through its theme ({@link Glyphs#LOGO}), and the welcome of
 * {@code isx init}'s first run, which draws it in a plain terminal ({@link #forTerminal}).
 */
public final class Logo {

    private Logo() {}

    /** The rows, each as wide as the others. */
    public static final String[] ROWS = {
            "╻ ┏━┓ ╻ ╻",
            "┃ ┗━┓ ╺╋╸",
            "╹ ┗━┛ ╹ ╹",
    };

    /** The width of every row, in columns. */
    public static final int WIDTH = ROWS[0].length();

    /**
     * The two ends of the gradient outside the TUI. The TUI asks the terminal for its background
     * and has a pair for a dark one and a pair for a light one ({@code TuiTheme}); a command that
     * prints lines does not ask, since the answer arrives on the stream its prompts read. So
     * these lie halfway between the two themes' accents, and read on either background.
     */
    static final Color.Rgb FROM = new Color.Rgb(0x37, 0xa6, 0xbb);
    static final Color.Rgb TO = new Color.Rgb(0x92, 0x63, 0xf4);

    /**
     * The rows for a terminal that is not the TUI's, coloured as far as that terminal is known
     * to draw: the gradient in 24-bit colour on proof of it ({@code COLORTERM}, or
     * {@code ISX_COLORS}), in the 256-colour palette where {@code TERM} names it, else bold in
     * the terminal's own cyan, the colour of the first run's frames. Plain where
     * {@link BuildOutput#ansi()} says colour is off.
     *
     * @param env reads an environment variable, null or empty when unset
     */
    public static List<String> forTerminal(UnaryOperator<String> env) {
        if (!BuildOutput.ansi()) return List.of(ROWS);
        var depth = depth(env);
        return Arrays.stream(ROWS).map(row -> depth == ColorDepth.ANSI
                ? BuildOutput.styled(BuildOutput.BOLD + BuildOutput.CYAN, row) : gradient(row, depth)).toList();
    }

    /** The gap between the logo and the text beside it. */
    private static final String GAP = "    ";

    /**
     * {@code lines} with the logo to their left, a row to a line, for a person at a terminal;
     * {@code lines} as they are anywhere else. So what a script reads from a pipe or a command
     * substitution, where {@link BuildOutput#ansi()} is false, never holds the logo: output a
     * script reads is a contract (design/terminal.md "Output for scripts"). Lines beyond the
     * logo's three rows continue in the text's column.
     *
     * @param env reads an environment variable, null or empty when unset
     */
    public static List<String> beside(List<String> lines, UnaryOperator<String> env) {
        return BuildOutput.ansi() ? rowsBeside(lines, env) : lines;
    }

    /**
     * {@code lines} with the logo to their left whatever the terminal: for the first run's
     * welcome, whose box is laid out around the logo, so colour off draws its rows plain.
     */
    public static List<String> rowsBeside(List<String> lines, UnaryOperator<String> env) {
        var rows = forTerminal(env);
        var out = new ArrayList<String>();
        for (int i = 0; i < Math.max(rows.size(), lines.size()); i++) {
            var row = i < rows.size() ? rows.get(i) : " ".repeat(WIDTH);
            var line = i < lines.size() ? lines.get(i) : "";
            out.add(line.isEmpty() ? row.stripTrailing() : row + GAP + line);
        }
        return out;
    }

    /** As the TUI decides it, less the question to the terminal: truecolor only on proof, the palette only where named. */
    static ColorDepth depth(UnaryOperator<String> env) {
        var named = ColorDepth.named(env.apply(ColorDepth.OVERRIDE));
        if (named != null) return named;
        var colorterm = lower(env.apply("COLORTERM"));
        if (colorterm.contains("truecolor") || colorterm.contains("24bit")) return ColorDepth.TRUECOLOR;
        return lower(env.apply("TERM")).contains("256color") ? ColorDepth.INDEXED : ColorDepth.ANSI;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * {@code row} from {@link #FROM} to {@link #TO}, cell by cell; a space takes no colour. Only
     * for a terminal {@link BuildOutput#ansi()} holds for, which {@link #forTerminal} asked.
     */
    private static String gradient(String row, ColorDepth depth) {
        var out = new StringBuilder();
        int last = Math.max(1, row.length() - 1);
        for (int i = 0; i < row.length(); i++) {
            var cell = row.charAt(i);
            if (cell == ' ') {
                out.append(cell);
                continue;
            }
            var colour = ColorDepth.mix(FROM, TO, i / (double) last);
            out.append(depth == ColorDepth.TRUECOLOR
                    ? "\033[1;38;2;" + colour.r() + ";" + colour.g() + ";" + colour.b() + "m" // raw ANSI: the logo's gradient, a colour per cell
                    : "\033[1;38;5;" + ColorDepth.nearestIndexed(colour.r(), colour.g(), colour.b()) + "m") // raw ANSI: the logo's gradient in the palette
                    .append(cell).append(BuildOutput.RESET);
        }
        return out.toString();
    }
}
