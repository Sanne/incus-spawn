package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** #2563: the hint that names 'isx overview' shows once a day, and again when the pool gets shorter. */
class DiskHintTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    @TempDir
    Path dir;

    private Path stamp() {
        return dir.resolve("state/disk-hint");
    }

    /** 87% of 60 GiB: the hint level. */
    private static IncusClient.PoolUsage hint() {
        return new IncusClient.PoolUsage(52 * GIB + GIB / 2, 60 * GIB);
    }

    /** 92% of 60 GiB, 4.8 GiB free: critical. */
    private static IncusClient.PoolUsage critical() {
        return new IncusClient.PoolUsage(55 * GIB + GIB / 5, 60 * GIB);
    }

    @Test
    void showsOnceThenNotWithinADay() {
        assertTrue(DiskHint.due(hint(), true, stamp(), NOW));
        DiskHint.shown(stamp(), hint().level(), NOW);
        assertFalse(DiskHint.due(hint(), true, stamp(), NOW.plus(Duration.ofHours(23))));
        assertTrue(DiskHint.due(hint(), true, stamp(), NOW.plus(Duration.ofHours(24))));
    }

    @Test
    void showsAgainAtOnceWhenTheLevelRises() {
        DiskHint.shown(stamp(), hint().level(), NOW);
        assertTrue(DiskHint.due(critical(), true, stamp(), NOW.plusSeconds(60)));
        DiskHint.shown(stamp(), critical().level(), NOW.plusSeconds(60));
        assertFalse(DiskHint.due(hint(), true, stamp(), NOW.plusSeconds(120)), "a lower level stays quiet");
    }

    @Test
    void aStampThatCannotBeWrittenOrReadShowsAgain() throws Exception {
        // The state directory is a file, so the write fails
        Files.writeString(dir.resolve("state"), "");
        DiskHint.shown(stamp(), hint().level(), NOW);
        assertTrue(DiskHint.due(hint(), true, stamp(), NOW.plusSeconds(60)));
        var other = dir.resolve("garbled");
        Files.writeString(other, "yesterday HINT\n");
        assertTrue(DiskHint.due(hint(), true, other, NOW));
    }

    @Test
    void aStampFromTheFutureDoesNotSilenceIt() {
        DiskHint.shown(stamp(), hint().level(), NOW.plus(Duration.ofDays(3)));
        assertTrue(DiskHint.due(hint(), true, stamp(), NOW));
    }

    @Test
    void neverWhenTurnedOffBelowTheLevelOrUnread() {
        assertFalse(DiskHint.due(hint(), false, stamp(), NOW));
        assertFalse(DiskHint.due(new IncusClient.PoolUsage(50 * GIB, 60 * GIB), true, stamp(), NOW), "83%");
        assertFalse(DiskHint.due(null, true, stamp(), NOW));
        assertFalse(Files.exists(stamp()));
    }

    @Test
    void itStatesTheCountsAndNamesTheOverview() {
        var lines = DiskHint.lines(hint(), 43, 30);
        assertEquals("The storage pool is 87% full (7.5 GiB free), with 43 instances, 30 of them stopped.", lines.get(0));
        assertTrue(lines.get(1).startsWith("'isx overview', or the Work tab in 'isx',"), lines.get(1));
    }

    @Test
    void theCountsReadAsEnglishForOneInstanceOrNoneStopped() {
        assertEquals("The storage pool is 87% full (7.5 GiB free), with 3 instances.", DiskHint.lines(hint(), 3, 0).get(0));
        assertEquals("The storage pool is 87% full (7.5 GiB free), with 1 instance, which is stopped.", DiskHint.lines(hint(), 1, 1).get(0));
        assertEquals("The storage pool is 87% full (7.5 GiB free), with 1 instance.", DiskHint.lines(hint(), 1, 0).get(0));
    }
}
