package dev.incusspawn.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * What a command's report is laid out with: the {@code table} format of a query command, and any
 * other answer that is a list of things rather than a sequence of steps ({@link BuildOutput} is
 * for those). Every such command builds its lines here, so the rules of design/terminal.md
 * "Reports" hold in one place and a change to them reaches every command.
 *
 * <p>Nothing here prints by itself: a {@link Table} gives its lines, or prints them to the
 * stream the command hands it, which is {@link BuildOutput#results()}.
 */
public final class Report {

    private Report() {}

    /** The width a report is laid out for where there is no terminal to ask (a pipe, a pager, a log). */
    public static final int FALLBACK_WIDTH = 100;

    /** A report this narrow fits every terminal, so its width is never asked for. */
    static final int ALWAYS_FITS = 80;

    /** The least of a last column worth keeping: narrower than this, a line wraps instead of being cut. */
    private static final int LEAST_CUT = 12;

    /** What a piece of a report is, which decides its colour and weight. The names are the TUI's roles. */
    public enum Tone {
        /** What is read first: the terminal's own foreground. */
        TEXT(""),
        /** The one thing a line is about. */
        STRONG(BuildOutput.BOLD),
        /** A header, a label, a count, an explanation. */
        QUIET(BuildOutput.DIM),
        /** Running, done, enough. */
        GOOD(BuildOutput.GREEN),
        /** Something to look at. */
        CAUTION(BuildOutput.YELLOW),
        /** A failure. */
        BAD(BuildOutput.RED);

        private final String style;

        Tone(String style) {
            this.style = style;
        }
    }

    /** A table cell with a tone. A cell given as anything else is {@link Tone#TEXT}. */
    public record Cell(String text, Tone tone) {}

    public static Cell cell(Object text, Tone tone) {
        return new Cell(String.valueOf(text), tone);
    }

    /** {@code text} in {@code tone} on an ANSI terminal, unchanged elsewhere, so nothing is said by colour alone. */
    public static String styled(Tone tone, String text) {
        return tone == Tone.TEXT || text.isEmpty() ? text : BuildOutput.styled(tone.style, text);
    }

    /** The title of a block of a report, such as one credential's accounts. */
    public static String heading(String text) {
        return styled(Tone.STRONG, OutputFormat.oneLine(text));
    }

    /** {@code 1 instance}, {@code 30 instances}: a count with its noun, for a list too long to be worth naming. */
    public static String count(long n, String noun) {
        return count(n, noun, noun + "s");
    }

    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** Whether the report is read on a terminal, whose width then bounds it. */
    public static boolean onTerminal() {
        return TerminalProgress.isTerminal();
    }

    /** The terminal's columns, or {@link #FALLBACK_WIDTH} where there is none. Asking costs a process, so ask once. */
    public static int width() {
        return onTerminal() ? TerminalProgress.terminalWidth() : FALLBACK_WIDTH;
    }

    /** {@link #width()}, asked at most once and only if needed: for a report of several tables. */
    public static IntSupplier widthOnce() {
        var known = new int[1];
        return () -> known[0] != 0 ? known[0] : (known[0] = width());
    }

    /**
     * {@code items} as a comma-separated list after {@code label}, broken between items to fit
     * {@code width}. Every line starts at {@code indent}, and the lines after the first continue
     * under the first item, so the label stands out.
     */
    public static List<String> wrap(String label, Collection<String> items, int indent, int width) {
        var lead = label.isEmpty() ? "" : label + " ";
        var first = " ".repeat(indent) + styled(Tone.QUIET, lead);
        var hang = " ".repeat(indent + length(lead));
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        int start = indent + length(lead);
        int col = start;
        var it = items.iterator();
        while (it.hasNext()) {
            var item = OutputFormat.oneLine(it.next());
            var chunk = it.hasNext() ? item + "," : item;
            if (col > start && col + 1 + length(chunk) > width) {
                lines.add((lines.isEmpty() ? first : hang) + line);
                line.setLength(0);
                col = start;
            }
            if (col > start) {
                line.append(' ');
                col++;
            }
            line.append(chunk);
            col += length(chunk);
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add((lines.isEmpty() ? first : hang) + line);
        return lines;
    }

    /** A table with these column headers, written in upper case; with none, a table without a header row. */
    public static Table table(String... headers) {
        return new Table(headers);
    }

    /** The columns {@code text} takes on a terminal, counting each code point as one. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String cut(String text, int width) {
        if (length(text) <= width) return text;
        return text.substring(0, text.offsetByCodePoints(0, Math.max(0, width - 1))) + "…";
    }

    /**
     * Rows under headers, each column as wide as its widest cell. The rules it holds: headers are
     * quiet and no rule is drawn under them, columns are two spaces apart, the last column is not
     * padded, every cell is one line that cannot drive the terminal ({@link OutputFormat#oneLine}),
     * and on a terminal the last column is cut to its width.
     */
    public static final class Table {
        private final String[] headers;
        private final List<Line> lines = new ArrayList<>();
        private final Set<Integer> right = new HashSet<>();
        private final Set<Integer> optional = new HashSet<>();
        private int indent;
        private int columns;
        private int detailColumn = 1;

        /** A row of cells, or the lines of detail under the row before it. */
        private record Line(Cell[] cells, String label, List<String> items) {}

        private Table(String[] headers) {
            this.headers = headers;
            this.columns = headers.length;
        }

        /** Spaces before every line, for a table under a {@link Report#heading}. */
        public Table indent(int spaces) {
            this.indent = spaces;
            return this;
        }

        /** These columns hold numbers, so their cells and headers end at the same column. */
        public Table right(String... headerNames) {
            for (var name : headerNames) right.add(column(name));
            return this;
        }

        /** These columns are left out when no row has a value in them (an empty cell or {@code -}). */
        public Table optional(String... headerNames) {
            for (var name : headerNames) optional.add(column(name));
            return this;
        }

        private int column(String header) {
            int i = Arrays.asList(headers).indexOf(header);
            if (i < 0) throw new IllegalArgumentException("No column " + header);
            return i;
        }

        /** A row: a {@link Cell}, or anything else as plain text; {@code null} is an empty cell. */
        public Table row(Object... cells) {
            var row = new Cell[cells.length];
            for (int i = 0; i < cells.length; i++) {
                var c = cells[i] instanceof Cell cell ? cell : new Cell(cells[i] == null ? "" : cells[i].toString(), Tone.TEXT);
                row[i] = new Cell(OutputFormat.oneLine(c.text()), c.tone());
            }
            columns = Math.max(columns, row.length);
            lines.add(new Line(row, null, null));
            return this;
        }

        /**
         * A list that belongs to the row above, such as the instances pinned to an account: after
         * {@code label}, wrapped to the report's width, under the column {@link #detailUnder} names.
         */
        public Table detail(String label, Collection<String> items) {
            lines.add(new Line(null, label, List.copyOf(items)));
            return this;
        }

        /** The column a {@link #detail} list starts under, counted from 0: the second unless said. */
        public Table detailUnder(int column) {
            this.detailColumn = column;
            return this;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** The table for a report read where {@link Report#width()} says. */
        public List<String> lines() {
            return lines(Report::width, onTerminal());
        }

        /**
         * The table's lines. {@code width} is asked only when a line is longer than every terminal
         * is wide. A detail list is wrapped to it, and with {@code cut} a row's last column is cut
         * to it; without, a row is as long as its cells, for a pipe that greps it.
         */
        public List<String> lines(IntSupplier width, boolean cut) {
            var shown = new ArrayList<Integer>();
            for (int c = 0; c < columns; c++) {
                // A column without a header that no row fills takes no room either
                if (optional.contains(c) || c >= headers.length ? hasValue(c) : true) shown.add(c);
            }
            var widths = new int[columns];
            for (int c : shown) widths[c] = c < headers.length ? length(headers[c]) : 0;
            for (var line : lines) {
                if (line.cells() == null) continue;
                for (int c : shown) {
                    if (c < line.cells().length) widths[c] = Math.max(widths[c], length(line.cells()[c].text()));
                }
            }
            int detailIndent = indent;
            for (int c : shown) {
                if (c < detailColumn) detailIndent += widths[c] + 2;
            }

            int natural = 0;
            for (var line : lines) {
                natural = Math.max(natural, line.cells() != null
                        ? rowLength(line.cells(), shown, widths)
                        : detailIndent + length(line.label()) + 1 + String.join(", ", line.items()).length());
            }
            int limit = natural <= ALWAYS_FITS ? natural : Math.max(width.getAsInt(), 40);

            var out = new ArrayList<String>();
            if (headers.length > 0) {
                var header = new Cell[headers.length];
                for (int c = 0; c < headers.length; c++) header[c] = new Cell(headers[c], Tone.QUIET);
                out.add(render(header, shown, widths, Integer.MAX_VALUE));
            }
            for (var line : lines) {
                if (line.cells() != null) out.add(render(line.cells(), shown, widths, cut ? limit : Integer.MAX_VALUE));
                else out.addAll(wrap(line.label(), line.items(), detailIndent, limit));
            }
            return out;
        }

        /** Prints {@link #lines()} to {@code out}, a command's {@link BuildOutput#results()}. */
        public void print(PrintStream out) {
            lines().forEach(out::println);
        }

        private boolean hasValue(int column) {
            return lines.stream().anyMatch(l -> l.cells() != null && column < l.cells().length
                    && !l.cells()[column].text().isEmpty() && !l.cells()[column].text().equals("-"));
        }

        private int rowLength(Cell[] cells, List<Integer> shown, int[] widths) {
            int last = lastFilled(cells, shown);
            int length = indent;
            for (int c : shown) {
                if (c > last) break;
                boolean padded = c != last || right.contains(c);
                length += (c == shown.getFirst() ? 0 : 2) + (padded ? widths[c] : length(cells[c].text()));
            }
            return length;
        }

        private static int lastFilled(Cell[] cells, List<Integer> shown) {
            int last = -1;
            for (int c : shown) {
                if (c < cells.length && !cells[c].text().isEmpty()) last = c;
            }
            return last;
        }

        private String render(Cell[] cells, List<Integer> shown, int[] widths, int limit) {
            int last = lastFilled(cells, shown);
            var line = new StringBuilder(" ".repeat(indent));
            int col = indent;
            for (int c : shown) {
                if (c > last) break;
                if (c != shown.getFirst()) {
                    line.append("  ");
                    col += 2;
                }
                var cell = c < cells.length ? cells[c] : new Cell("", Tone.TEXT);
                var text = cell.text();
                int room = limit - col;
                if (c == last && length(text) > room && room >= LEAST_CUT) text = cut(text, room);
                var pad = " ".repeat(Math.max(0, widths[c] - length(text)));
                if (right.contains(c)) line.append(pad).append(styled(cell.tone(), text));
                else if (c == last) line.append(styled(cell.tone(), text));
                else line.append(styled(cell.tone(), text)).append(pad);
                col += Math.max(widths[c], length(text));
            }
            return line.toString();
        }
    }
}
