package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarningLogTest {

    private LocalTime now = LocalTime.of(10, 0);
    private final WarningLog log = new WarningLog(() -> now);

    private static List<String> messages(List<WarningLog.Entry> entries) {
        return entries.stream().map(WarningLog.Entry::message).toList();
    }

    @Test
    void warningsFromSeveralOperationsAreAllKept() {
        log.add("podman.yaml: unsupported env entry");
        log.add("inbox directory not found: /home/me/inbox");
        log.add("could not set up git remote");

        assertEquals(List.of("podman.yaml: unsupported env entry",
                        "inbox directory not found: /home/me/inbox",
                        "could not set up git remote"),
                messages(log.entries()));
        assertEquals(3, log.unread());
    }

    @Test
    void aRepeatedWarningMovesToTheEndInsteadOfDuplicating() {
        log.add("first");
        log.add("second");
        now = LocalTime.of(10, 5);
        log.add("first");

        assertEquals(List.of("second", "first"), messages(log.entries()));
        assertEquals(LocalTime.of(10, 5), log.entries().get(1).time());
    }

    @Test
    void theCliFormIsNormalisedButKeepsItsLines() {
        log.add("    Warning: inbox directory not found: /home/me/inbox (device removed).\n"
                + "      To add it back, recreate it, then: incus config device add b1 inbox disk");

        assertEquals("inbox directory not found: /home/me/inbox (device removed).\n"
                        + "  To add it back, recreate it, then: incus config device add b1 inbox disk",
                log.entries().get(0).message());
    }

    @Test
    void blankWarningsAreIgnored() {
        log.add(null);
        log.add("  ");
        log.add("Warning: ");

        assertEquals(List.of(), log.entries());
    }

    @Test
    void theStatusLineAnnouncesOnlyWhatArrivedSinceItLastLooked() {
        log.add("old");
        assertEquals(List.of("old"), messages(log.takeUnannounced()));

        assertFalse(log.hasUnannounced());
        log.add("host-resource source not found: /a (device removed)");
        assertTrue(log.hasUnannounced());
        log.add("inbox directory not found: /b\n  To add it back: ...");
        var fresh = log.takeUnannounced();

        assertEquals("⚠ 2 new warnings, latest: inbox directory not found: /b To add it back: ...",
                WarningLog.statusLine(fresh));
        assertNull(WarningLog.statusLine(log.takeUnannounced()));

        log.add("just one");
        assertEquals("⚠ just one", WarningLog.statusLine(log.takeUnannounced()));
    }

    @Test
    void readingClearsTheUnreadCountUntilSomethingNewArrives() {
        log.add("a");
        log.add("b");
        log.markRead();
        assertEquals(0, log.unread());

        log.add("a");
        assertEquals(1, log.unread());
        assertEquals(List.of("a"), messages(log.unreadEntries()));
    }

    @Test
    void theOldestWarningsAreDroppedBeyondCapacity() {
        for (int i = 0; i <= WarningLog.CAPACITY; i++) log.add("warning " + i);

        var entries = log.entries();
        assertEquals(WarningLog.CAPACITY, entries.size());
        assertEquals("warning 1", entries.get(0).message());
    }

    @Test
    void clearEmptiesTheLog() {
        log.add("a");
        log.clear();

        assertEquals(List.of(), log.entries());
        assertEquals(0, log.unread());
        assertEquals(List.of(), log.takeUnannounced());
    }
}
