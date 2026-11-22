package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of design/terminal.md "Reports", which every query command's table gets from {@link Report}. */
class ReportTest {

    private static List<String> lines(Report.Table table, int width, boolean cut) {
        return table.lines(() -> width, cut);
    }

    @Test
    void columnsFitTheirWidestCellAndTheLastIsNotPadded() {
        var table = Report.table("NAME", "STATUS", "DESCRIPTION")
                .row("a-rather-long-name", "Running", "one")
                .row("b", "Stopped", "");
        assertEquals(List.of(
                "NAME                STATUS   DESCRIPTION",
                "a-rather-long-name  Running  one",
                "b                   Stopped"), lines(table, 80, true));
    }

    @Test
    void numbersEndAtTheSameColumnAsTheirHeader() {
        var table = Report.table("INSTANCE", "REQUESTS", "LAST").right("REQUESTS")
                .row("one", 5, "now")
                .row("two", 12345678901L, "1m ago");
        assertEquals(List.of(
                "INSTANCE     REQUESTS  LAST",
                "one                 5  now",
                "two       12345678901  1m ago"), lines(table, 80, true));
    }

    @Test
    void anOptionalColumnIsDrawnOnlyWhenARowHasAValue() {
        var none = Report.table("NAME", "OWNER", "AGE").optional("OWNER").row("a", "-", "1h").row("b", "", "2h");
        assertEquals(List.of("NAME  AGE", "a     1h", "b     2h"), lines(none, 80, true));
        var one = Report.table("NAME", "OWNER", "AGE").optional("OWNER").row("a", "-", "1h").row("b", "orca", "2h");
        assertEquals("NAME  OWNER  AGE", lines(one, 80, true).getFirst());
    }

    @Test
    void theLastColumnIsCutToTheTerminalButNeverForAPipe() {
        var table = Report.table("NAME", "DESCRIPTION").row("tpl", "x".repeat(200));
        var cut = lines(table, 90, true).get(1);
        assertEquals(90, cut.length(), cut);
        assertTrue(cut.endsWith("x…"), cut);
        assertEquals(6 + 200, lines(table, 90, false).get(1).length());
    }

    /** The width costs a process on a terminal, so a report every terminal fits never asks for it. */
    @Test
    void aNarrowReportNeverAsksForTheWidth() {
        Report.table("NAME").row("a").lines(() -> {
            throw new AssertionError("asked for the width");
        }, true);
    }

    @Test
    void aCellCannotDriveTheTerminalOrBreakTheRow() {
        var table = Report.table("NAME", "PURPOSE").row("a", "one\ntwo\u001b[2J");
        assertEquals("a     one two [2J", lines(table, 80, true).get(1));
    }

    @Test
    void aListWrapsUnderItsFirstItem() {
        assertEquals(List.of(
                "       pinned to it: alpha, beta,",
                "                     gamma, delta"),
                Report.wrap("pinned to it:", List.of("alpha", "beta", "gamma", "delta"), 7, 34));
    }

    @Test
    void aDetailListStartsUnderTheSecondColumnAndWrapsToTheWidth() {
        var names = java.util.stream.IntStream.range(0, 8).mapToObj(i -> "instance-" + i).toList();
        var table = Report.table().indent(2).row("api", "Anthropic API key").detail("pinned to it:", names);
        assertEquals(List.of(
                "  api  Anthropic API key",
                "       pinned to it: instance-0, instance-1, instance-2, instance-3,",
                "                     instance-4, instance-5, instance-6, instance-7"), table.lines(() -> 68, true));
    }

    @Test
    void toneIsWrittenOnlyOnAnAnsiTerminal() {
        var previous = BuildOutput.forceAnsi;
        try {
            BuildOutput.forceAnsi = false;
            assertEquals("Running", Report.styled(Report.Tone.GOOD, "Running"));
            BuildOutput.forceAnsi = true;
            assertEquals(BuildOutput.GREEN + "Running" + BuildOutput.RESET, Report.styled(Report.Tone.GOOD, "Running"));
            // Padding is counted from the text, not from its escapes
            var lines = lines(Report.table("NAME", "STATUS", "AGE")
                    .row("a", Report.cell("Running", Report.Tone.GOOD), "1h"), 80, true);
            assertEquals("a     Running  1h", BuildOutput.stripAnsi(lines.get(1)));
            assertFalse(lines.getFirst().contains("----"));
        } finally {
            BuildOutput.forceAnsi = previous;
        }
    }

    /** The header is a band as wide as the table, and plain text where there is no colour. */
    @Test
    void theHeaderIsABandAsWideAsTheWidestRow() {
        var previous = BuildOutput.forceAnsi;
        try {
            BuildOutput.forceAnsi = true;
            var table = Report.table("NAME", "AGE").indent(2).row("a", "3 days ago").row("a-long-name", "1h");
            var header = lines(table, 80, true).getFirst();
            assertTrue(header.startsWith("  \033[") && header.endsWith(BuildOutput.RESET), header);
            assertEquals("  NAME         AGE       ", BuildOutput.stripAnsi(header));
            BuildOutput.forceAnsi = false;
            assertEquals("  NAME         AGE", lines(table, 80, true).getFirst());
        } finally {
            BuildOutput.forceAnsi = previous;
        }
    }

    @Test
    void theThemeIsReadFromTheEnvironmentAndDarkWhenItSaysNothing() {
        assertTrue(Report.darkTerminal(null, null));
        assertFalse(Report.darkTerminal("light", "15;0"));
        assertTrue(Report.darkTerminal("dark", "0;15"));
        assertFalse(Report.darkTerminal(null, "0;15"));
        assertFalse(Report.darkTerminal(null, "0;default;7"));
        assertTrue(Report.darkTerminal(null, "15;0"));
    }

    @Test
    void aCountNamesItsNoun() {
        assertEquals("1 instance", Report.count(1, "instance"));
        assertEquals("30 instances follow it", Report.count(30, "instance follows it", "instances follow it"));
    }
}
