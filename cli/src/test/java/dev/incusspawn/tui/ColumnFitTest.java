package dev.incusspawn.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A table's widths follow its content (design/visual-language.md "Layout"). */
class ColumnFitTest {

    private static final List<ColumnFit.Column> COLUMNS = List.of(
            new ColumnFit.Column("NAME", 10, 14, 3, false),
            new ColumnFit.Column("PARENT", 8, 11, 2, false),
            ColumnFit.Column.fixed("DISK", 6),
            new ColumnFit.Column("PURPOSE", 12, 30, 1, true));

    @Test
    void aColumnIsAsWideAsItsContentAndTheFreeTextTakesTheRest() {
        // 61 for the content and 3 between the columns leave 36: 3 to each column but the last
        var widths = ColumnFit.widths(COLUMNS, 100);
        assertArrayEquals(new int[] {17, 14, 9, 57}, widths);
        assertEquals(100 - 3, widths[0] + widths[1] + widths[2] + widths[3]);
    }

    @Test
    void aHeaderWiderThanItsCellsSetsTheWidth() {
        var widths = ColumnFit.widths(List.of(new ColumnFit.Column("RUNTIME", 2, 2, 1, false)), 40);
        assertEquals(40, widths[0], "one column has the whole table");
        assertEquals(7, new ColumnFit.Column("RUNTIME", 2, 2, 1, false).want());
    }

    @Test
    void withNoFreeTextTheRoomLeftIsBlankAtTheEnd() {
        var widths = ColumnFit.widths(COLUMNS.subList(0, 3), 100);
        assertArrayEquals(new int[] {17, 14, 67}, widths);
    }

    @Test
    void theColumnsGiveWidthUpInTheirOrderEachDownToItsFloor() {
        // 61 + 3 = 64 are needed
        assertArrayEquals(new int[] {14, 11, 6, 20}, ColumnFit.widths(COLUMNS, 54), "the free text first");
        assertArrayEquals(new int[] {14, 9, 6, 12}, ColumnFit.widths(COLUMNS, 44), "then the parent");
        assertArrayEquals(new int[] {11, 8, 6, 12}, ColumnFit.widths(COLUMNS, 40), "the name last");
        assertArrayEquals(new int[] {10, 8, 6, 12}, ColumnFit.widths(COLUMNS, 39), "and none below its floor");
    }

    /** The table's own layout narrowed the columns with no mark on what they cut. */
    @Test
    void narrowerThanEveryFloorTheColumnsStillFitAndTheFirstGivesLast() {
        assertArrayEquals(new int[] {10, 5, 1, 1}, ColumnFit.widths(COLUMNS, 20));
        for (int width = 8; width < 40; width++) {
            int sum = java.util.Arrays.stream(ColumnFit.widths(COLUMNS, width)).sum();
            assertEquals(width - 3, sum, "at " + width);
        }
    }

    @Test
    void wholeColumnsAreLeftOutInTheirOrderBeforeAnyIsCut() {
        var columns = List.of(new ColumnFit.Column("NAME", 10, 20, 0, false),
                new ColumnFit.Column("IP", 7, 13, 2, false).dropping(2),
                new ColumnFit.Column("RUNTIME", 9, 9, 1, false).dropping(1),
                new ColumnFit.Column("PURPOSE", 12, 40, 1, true).dropping(3));
        // 20 + 13 + 9 + 12 for the free text at its floor, and 3 between them
        assertEquals(List.of(0, 1, 2, 3), ColumnFit.kept(columns, 57), "free text is cut before a column goes");
        assertEquals(List.of(0, 1, 3), ColumnFit.kept(columns, 56), "the first to go");
        assertEquals(List.of(0, 3), ColumnFit.kept(columns, 46));
        assertEquals(List.of(0), ColumnFit.kept(columns, 32));
        assertEquals(List.of(0), ColumnFit.kept(columns, 5), "a column with no turn is never left out");
        var shown = ColumnFit.kept(columns, 46).stream().map(columns::get).toList();
        assertEquals(20, ColumnFit.widths(shown, 46)[0], "and the name is whole");
    }

    @Test
    void aReservedWidthIsKeptOnceTheColumnsStandOneCellApart() {
        var columns = List.of(new ColumnFit.Column("NAME", 10, 12, 2, false),
                new ColumnFit.Column("IP", 2, 2, 1, false).reserving(13),
                new ColumnFit.Column("AGE", 6, 10, 3, false));
        assertArrayEquals(new int[] {12, 2, 10}, ColumnFit.widths(columns, 26), "no room: the content alone");
        assertArrayEquals(new int[] {13, 7, 10}, ColumnFit.widths(columns, 32), "one cell apart, then what room there is");
        assertArrayEquals(new int[] {14, 15, 11}, ColumnFit.widths(columns, 42), "the address has its room");
        var arrived = List.of(columns.get(0), columns.get(1).holding(13), columns.get(2));
        assertArrayEquals(ColumnFit.widths(columns, 42), ColumnFit.widths(arrived, 42), "and no column moves when it arrives");
    }

    @Test
    void aReservedWidthCountsWhenColumnsAreLeftOut() {
        var columns = List.of(new ColumnFit.Column("NAME", 10, 12, 0, false),
                new ColumnFit.Column("IP", 2, 2, 1, false).reserving(13),
                new ColumnFit.Column("RUNTIME", 9, 9, 2, false).dropping(1));
        // 12 + 13 + 9 and 2 between them: an address arriving must not be what leaves RUNTIME out
        assertEquals(List.of(0, 1, 2), ColumnFit.kept(columns, 36));
        assertEquals(List.of(0, 1), ColumnFit.kept(columns, 35));
    }

    @Test
    void aFixedColumnNeverGives() {
        for (int width = 39; width < 120; width++) {
            assertEquals(6 + (width >= 67 ? Math.min(3, (width - 64) / 3) : 0), ColumnFit.widths(COLUMNS, width)[2], "at " + width);
        }
    }

    @Test
    void aCutCellEndsInAnEllipsisInItsOwnStyle() {
        var green = Style.EMPTY.fg(Color.GREEN);
        var cell = Cell.from(Line.from(Span.styled("● ", green), Span.styled("Running", Style.EMPTY.bold())));
        var cut = ColumnFit.clipped(cell, 6).content().lines().get(0);
        assertEquals("● Run…", cut.rawContent());
        assertEquals(Style.EMPTY.bold(), cut.spans().get(cut.spans().size() - 1).style());
        assertEquals(6, cut.width());
    }

    @Test
    void aWideCharacterIsNeverCutInHalf() {
        var cut = ColumnFit.clipped(Cell.from("日本語日本"), 6);
        assertEquals("日本…", cut.content().lines().get(0).rawContent());
    }

    @Test
    void rowsThatFitAreLeftAsTheyAre() {
        var rows = List.of(Row.from("a", "b"), Row.from("abcdefgh", "b"));
        assertSame(rows, ColumnFit.clipped(rows, new int[] {8, 1}));
        var clipped = ColumnFit.clipped(rows, new int[] {4, 1});
        assertSame(rows.get(0), clipped.get(0));
        assertEquals("abc…", clipped.get(1).cells().get(0).content().lines().get(0).rawContent());
    }
}
