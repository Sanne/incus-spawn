package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A command asks the terminal for its theme, and does not keep asking one that stays silent. */
class TerminalThemeProbeTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Map<String, String> XTERM = Map.of("TERM", "xterm-256color");

    @Test
    void theTerminalsAnswerIsTheTheme(@TempDir Path dir) {
        assertEquals(Boolean.FALSE, TerminalThemeDetector.probeDark(XTERM::get, dir, NOW, () -> false));
        assertEquals(Boolean.TRUE, TerminalThemeDetector.probeDark(XTERM::get, dir, NOW, () -> true));
    }

    /** Silence costs half a second, so it is paid once a day for a kind of terminal, not on every command. */
    @Test
    void aSilentTerminalIsNotAskedAgainForADay(@TempDir Path dir) {
        var asked = new AtomicInteger();
        for (int i = 0; i < 3; i++) { // three commands, an hour apart
            assertNull(TerminalThemeDetector.probeDark(XTERM::get, dir, NOW.plusSeconds(i * 3600L), () -> {
                asked.incrementAndGet();
                return null;
            }));
        }
        assertEquals(1, asked.get());
        // Another kind of terminal is asked for itself
        assertEquals(Boolean.TRUE, TerminalThemeDetector.probeDark(Map.of("TERM", "xterm-kitty")::get, dir, NOW, () -> true));
        assertEquals(Boolean.TRUE, TerminalThemeDetector.probeDark(
                Map.of("TERM", "xterm-256color", "VTE_VERSION", "8003")::get, dir, NOW, () -> true));
        assertEquals(Boolean.TRUE, TerminalThemeDetector.probeDark(
                Map.of("TERM", "xterm-256color", "SSH_CONNECTION", "10.0.0.1 1 10.0.0.2 22")::get, dir, NOW, () -> true));
        // After a day it is asked again, and an answer forgets the silence
        var later = NOW.plus(TerminalThemeDetector.SILENCE_REMEMBERED).plusSeconds(1);
        assertEquals(Boolean.FALSE, TerminalThemeDetector.probeDark(XTERM::get, dir, later, () -> false));
        assertEquals(Boolean.TRUE, TerminalThemeDetector.probeDark(XTERM::get, dir, later.plusSeconds(1), () -> true));
    }

    @Test
    void aTerminalWithNoColourToReportIsNeverAsked(@TempDir Path dir) {
        for (var term : new String[] {"linux", "dumb"}) {
            assertNull(TerminalThemeDetector.probeDark(Map.of("TERM", term)::get, dir, NOW, () -> {
                throw new AssertionError("asked " + term);
            }));
        }
    }

    @Test
    void aProbeThatFailsIsSilence(@TempDir Path dir) {
        assertNull(TerminalThemeDetector.probeDark(XTERM::get, dir, NOW, () -> {
            throw new IllegalStateException("no tty");
        }));
    }
}
