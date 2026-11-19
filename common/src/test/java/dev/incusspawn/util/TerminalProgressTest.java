package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TerminalProgressTest {

    // Tests run headless (no console), so run() takes the plain, non-animated path.

    @Test
    void noColorTurnsTheTerminalPlain() {
        Map<String, String> xterm = Map.of("TERM", "xterm-256color");
        assertTrue(TerminalProgress.isAnsiTerminal(true, xterm::get));
        assertFalse(TerminalProgress.isAnsiTerminal(false, xterm::get), "no console");
        Map<String, String> dumb = Map.of("TERM", "dumb");
        assertFalse(TerminalProgress.isAnsiTerminal(true, dumb::get));
        Map<String, String> noColor = Map.of("TERM", "xterm-256color", "NO_COLOR", "1");
        assertFalse(TerminalProgress.isAnsiTerminal(true, noColor::get), "NO_COLOR set");
        // no-color.org: an empty NO_COLOR does not count.
        Map<String, String> emptyNoColor = Map.of("TERM", "xterm-256color", "NO_COLOR", "");
        assertTrue(TerminalProgress.isAnsiTerminal(true, emptyNoColor::get));
    }

    @Test
    void runExecutesEveryTask() {
        var ran = Collections.synchronizedSet(new java.util.HashSet<Integer>());
        TerminalProgress.run(5, 2, ran::add, (i, f) -> "", i -> {});
        assertEquals(java.util.Set.of(0, 1, 2, 3, 4), ran);
    }

    @Test
    void runReportsEachTaskOnceItEnds() {
        var done = new CopyOnWriteArrayList<Integer>();
        TerminalProgress.run(3, 3, i -> {}, (i, f) -> "", done::add);
        assertEquals(java.util.Set.of(0, 1, 2), java.util.Set.copyOf(done));
        assertEquals(3, done.size());
    }

    @Test
    void runRespectsConcurrencyBound() {
        int maxConcurrency = 2;
        var inFlight = new AtomicInteger(0);
        var peak = new AtomicInteger(0);
        TerminalProgress.run(20, maxConcurrency, i -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            inFlight.decrementAndGet();
        }, (i, f) -> "", i -> {});
        assertTrue(peak.get() <= maxConcurrency,
                "concurrency peaked at " + peak.get() + ", expected <= " + maxConcurrency);
    }

    @Test
    void aTaskThatThrowsIsRethrownOnceEveryTaskHasEnded() {
        var done = new CopyOnWriteArrayList<Integer>();
        var thrown = assertThrows(OutOfMemoryError.class, () -> TerminalProgress.run(3, 3, i -> {
            if (i == 1) throw new OutOfMemoryError("test");
        }, (i, f) -> "", done::add));
        assertEquals("test", thrown.getMessage());
        assertEquals(java.util.Set.of(0, 1, 2), java.util.Set.copyOf(done), "every task was still reported");
    }

    @Test
    void runWithZeroTasksDoesNothing() {
        var done = new CopyOnWriteArrayList<Integer>();
        assertDoesNotThrow(() -> TerminalProgress.run(0, 4, i -> {
            throw new IllegalStateException("should not run");
        }, (i, f) -> "", done::add));
        assertTrue(done.isEmpty());
    }

    @Test
    void truncateToWidthLeavesShortStringsAlone() {
        assertEquals("hello", TerminalProgress.truncateToWidth("hello", 80));
    }

    @Test
    void truncateToWidthTruncatesLongPlainText() {
        var result = TerminalProgress.truncateToWidth("abcdefghij", 5);
        assertEquals("abcde\033[0m", result);
    }

    @Test
    void truncateToWidthPreservesAnsiEscapes() {
        var input = "\033[32m✓\033[0m \033[2mReady\033[0m label (https://example.com/very-long-url)";
        var result = TerminalProgress.truncateToWidth(input, 20);
        // ANSI escapes should not count toward visible width
        assertTrue(result.contains("\033[32m✓\033[0m"));
        assertTrue(result.endsWith("\033[0m"));
        // Visible content should be at most 20 chars
        var stripped = result.replaceAll("\033\\[[0-9;]*[A-Za-z]", "");
        assertTrue(stripped.length() <= 20, "visible length " + stripped.length() + " > 20");
    }

    @Test
    void truncateToWidthDoesNotTruncateExactFit() {
        var input = "12345";
        assertEquals("12345", TerminalProgress.truncateToWidth(input, 5));
    }

    @Test
    void truncateToWidthHandlesOnlyAnsiCodes() {
        var input = "\033[2m\033[0m";
        assertEquals(input, TerminalProgress.truncateToWidth(input, 5));
    }
}
