package dev.incusspawn.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A table's column widths, from what its cells hold (design/visual-language.md "Layout"). A column
 * is as wide as its widest cell. Where the table does not fit, whole columns are left out in a set
 * order ({@link #kept}), and then the columns left give width up in a set order, each down to its
 * floor, so what a person reads first is cut last. Where it has room to spare, the columns stand a
 * little apart and the rest goes to the column of free text.
 */
final class ColumnFit {

    /** What marks a cell that was cut. */
    static final String ELLIPSIS = "…";

    /** The most a column is widened beyond its content to stand apart from the next. */
    static final int PAD_MOST = 3;

    /**
     * A column: its header, the narrowest it is cut to, the width its content needs, and its turn
     * to be cut ({@code give}: 1 gives first, 0 gives only when every other column is at its
     * floor and the table still does not fit). A {@code fill} column holds free text and takes
     * the room no other column needs. {@code drop} is its turn to be left out whole where the
     * table does not fit: 1 goes first, 0 never goes.
     */
    record Column(String header, int floor, int natural, int give, boolean fill, int reserve, int drop) {
        Column(String header, int floor, int natural, int give, boolean fill) {
            this(header, floor, natural, give, fill, 0, 0);
        }

        /**
         * The same column, kept at least {@code width} wide: room for content it does not hold
         * yet, so the columns after it hold still when it arrives. {@link #kept} counts it, so
         * the content arriving leaves no column out; {@link #widths} grants it once every column
         * stands one cell apart, and not at all where the content alone fills the table.
         */
        Column reserving(int width) {
            return new Column(header, floor, natural, give, fill, width, drop);
        }

        /** The same column, left out whole in its {@code turn} where the table does not fit. */
        Column dropping(int turn) {
            return new Column(header, floor, natural, give, fill, reserve, turn);
        }

        /** The same column, holding content {@code width} cells wide. */
        Column holding(int width) {
            return new Column(header, floor, width, give, fill, reserve, drop);
        }

        /** The least this column takes before another is left out: free text is cut first. */
        int least() {
            return fill ? Math.max(floor, CharWidth.of(header)) : Math.max(want(), reserve);
        }

        static Column fixed(String header, int width) {
            return new Column(header, width, width, 0, false);
        }

        int want() {
            return Math.max(natural, CharWidth.of(header));
        }
    }

    private ColumnFit() {
    }

    /**
     * Which of {@code columns} a table {@code width} cells wide shows, as their indexes in order.
     * Columns are left out whole, in their {@code drop} order, until those left show their content
     * uncut, the free text aside. Leaving a column out says less than cutting three to stubs.
     */
    static List<Integer> kept(List<Column> columns, int width) {
        var kept = new ArrayList<Integer>();
        for (int i = 0; i < columns.size(); i++) kept.add(i);
        while (true) {
            int needed = kept.size() - 1;
            for (int i : kept) needed += columns.get(i).least();
            if (needed <= width) return kept;
            var next = kept.stream().filter(i -> columns.get(i).drop() > 0)
                    .min(Comparator.comparingInt(i -> columns.get(i).drop()));
            if (next.isEmpty()) return kept;
            kept.remove(next.get());
        }
    }

    /** The widths of {@code columns} in a table {@code width} cells wide, with one cell between two columns. */
    static int[] widths(List<Column> columns, int width) {
        int count = columns.size();
        int[] widths = new int[count];
        if (count == 0) return widths;
        int room = width - (count - 1);
        int needed = 0;
        for (int i = 0; i < count; i++) {
            widths[i] = columns.get(i).want();
            needed += widths[i];
        }
        if (needed > room) {
            cut(columns, widths, needed - room);
            return widths;
        }
        int spare = room - needed;
        // Every column but the last stands one cell apart from the next, then a column gets the
        // room it reserves, then the columns stand further apart as far as there is room. The
        // reserve is counted from the content's width, so the widths are the same before the
        // reserved content arrives and after.
        int gaps = count - 1;
        int first = gaps > 0 ? Math.min(1, spare / gaps) : 0;
        for (int i = 0; i < gaps; i++) widths[i] += first;
        spare -= first * gaps;
        for (int i = 0; i < count && spare > 0; i++) {
            int more = Math.min(spare, columns.get(i).reserve() - columns.get(i).want());
            if (more <= 0) continue;
            widths[i] += more;
            spare -= more;
        }
        int pad = gaps > 0 ? Math.min(PAD_MOST - first, spare / gaps) : 0;
        for (int i = 0; i < gaps; i++) widths[i] += pad;
        spare -= pad * gaps;
        // What is left is the free text's, or blank at the table's end
        var fills = IntStream.range(0, count).filter(i -> columns.get(i).fill()).toArray();
        if (fills.length == 0) {
            widths[count - 1] += spare;
        } else {
            for (int i = 0; i < fills.length; i++) {
                widths[fills[i]] += spare / fills.length + (i < spare % fills.length ? 1 : 0);
            }
        }
        return widths;
    }

    private static void cut(List<Column> columns, int[] widths, int deficit) {
        var order = IntStream.range(0, columns.size()).boxed()
                .filter(i -> columns.get(i).give() > 0)
                .sorted(Comparator.comparingInt(i -> columns.get(i).give())).toList();
        for (int i : order) {
            if (deficit <= 0) return;
            int taken = Math.min(deficit, Math.max(0, widths[i] - columns.get(i).floor()));
            widths[i] -= taken;
            deficit -= taken;
        }
        // Narrower than every floor: the columns give up the rest from the right, the first column
        // last, so every cut cell still ends in an ellipsis. Left to the table's own layout, the
        // columns were narrowed with no mark on what they cut.
        for (int i = widths.length - 1; i >= 0 && deficit > 0; i--) {
            int taken = Math.min(deficit, Math.max(0, widths[i] - 1));
            widths[i] -= taken;
            deficit -= taken;
        }
    }

    static List<Constraint> constraints(int[] widths) {
        var constraints = new ArrayList<Constraint>(widths.length);
        for (int width : widths) constraints.add(Constraint.length(width));
        return constraints;
    }

    /** The widest cell of column {@code column} over {@code rows}, in terminal cells. */
    static int natural(List<Row> rows, int column) {
        int widest = 0;
        for (var row : rows) {
            if (column < row.cells().size()) widest = Math.max(widest, row.cells().get(column).width());
        }
        return widest;
    }

    /** {@code rows} with every cell wider than its column cut to it, its last cell an ellipsis. */
    static List<Row> clipped(List<Row> rows, int[] widths) {
        List<Row> result = null;
        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            List<Cell> cells = null;
            for (int c = 0; c < row.cells().size() && c < widths.length; c++) {
                var cell = row.cells().get(c);
                if (cell.width() <= widths[c]) continue;
                if (cells == null) cells = new ArrayList<>(row.cells());
                cells.set(c, clipped(cell, widths[c]));
            }
            if (cells == null) continue;
            if (result == null) result = new ArrayList<>(rows);
            result.set(r, Row.from(cells).style(row.style()));
        }
        return result == null ? rows : result;
    }

    /** {@code cell}'s first line within {@code width} cells, ending in an ellipsis in its last span's style. */
    static Cell clipped(Cell cell, int width) {
        var lines = cell.content().lines();
        if (lines.isEmpty() || width <= 0) return Cell.empty().style(cell.style());
        var spans = new ArrayList<Span>();
        int left = width - 1;
        var last = Span.raw("");
        for (var span : lines.get(0).spans()) {
            last = span;
            int spanWidth = CharWidth.of(span.content());
            if (spanWidth <= left) {
                spans.add(span);
                left -= spanWidth;
                continue;
            }
            spans.add(Span.styled(CharWidth.substringByWidth(span.content(), left), span.style()));
            break;
        }
        spans.add(Span.styled(ELLIPSIS, last.style()));
        return Cell.from(Line.from(spans)).style(cell.style());
    }
}
