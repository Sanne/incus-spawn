package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TerminalProgressTest {

    // Tests run headless (no console), so run() takes the plain, non-animated path.

    @Test
    void runExecutesEveryTask() {
        var ran = Collections.synchronizedSet(new java.util.HashSet<Integer>());
        TerminalProgress.run(5, 2, ran::add, (i, f) -> "", i -> null, s -> {});
        assertEquals(java.util.Set.of(0, 1, 2, 3, 4), ran);
    }

    @Test
    void runEmitsPlainLinesToSink() {
        var lines = new CopyOnWriteArrayList<String>();
        TerminalProgress.run(3, 3, i -> {}, (i, f) -> "", i -> "done " + i, lines::add);
        assertEquals(3, lines.size());
        assertTrue(lines.contains("done 0"));
        assertTrue(lines.contains("done 2"));
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
        }, (i, f) -> "", i -> null, s -> {});
        assertTrue(peak.get() <= maxConcurrency,
                "concurrency peaked at " + peak.get() + ", expected <= " + maxConcurrency);
    }

    @Test
    void runWithZeroTasksDoesNothing() {
        var lines = new CopyOnWriteArrayList<String>();
        assertDoesNotThrow(() -> TerminalProgress.run(0, 4, i -> {
            throw new IllegalStateException("should not run");
        }, (i, f) -> "", i -> "x", lines::add));
        assertTrue(lines.isEmpty());
    }

    @Test
    void runSuppressesNullPlainLines() {
        var lines = new CopyOnWriteArrayList<String>();
        TerminalProgress.run(3, 3, i -> {}, (i, f) -> "",
                i -> i == 1 ? "only one" : null, lines::add);
        assertEquals(List.of("only one"), lines);
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
