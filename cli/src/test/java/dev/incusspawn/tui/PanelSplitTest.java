package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the Overview shares its height between the two panels (design/terminal.md "The main screen's tabs"). */
class PanelSplitTest {

    @Test
    void twoShortListsSplitTheScreenInItsMiddle() {
        assertEquals(13, Tui.templatePanelHeight(7, 8, 26));
        assertEquals(13, Tui.templatePanelHeight(3, 12, 26), "a short list keeps its half");
    }

    @Test
    void twoLongListsSplitItInItsMiddleToo() {
        assertEquals(13, Tui.templatePanelHeight(40, 90, 26));
        assertEquals(13, Tui.templatePanelHeight(90, 14, 26));
    }

    @Test
    void anOddRowGoesToTheInstances() {
        assertEquals(12, Tui.templatePanelHeight(7, 8, 25));
    }

    @Test
    void aListThatDoesNotFitTakesTheRowsTheOtherLeavesEmptyButOne() {
        assertEquals(17, Tui.templatePanelHeight(30, 8, 26), "the instances keep their 8 rows and an empty one");
        assertEquals(16, Tui.templatePanelHeight(16, 8, 26), "and the templates take no more than they need");
        assertEquals(8, Tui.templatePanelHeight(7, 30, 26), "the templates keep their 7 rows and an empty one");
        assertEquals(10, Tui.templatePanelHeight(7, 16, 26));
        assertEquals(13, Tui.templatePanelHeight(30, 13, 26), "a list that fills its half exactly gives none up");
        assertEquals(13, Tui.templatePanelHeight(13, 30, 26));
    }
}
