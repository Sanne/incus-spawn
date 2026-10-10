package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static dev.incusspawn.proxy.ArtifactCacheStats.Hit.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactCacheStatsTest {

    @Test
    void nothingToSayWithoutHits() {
        assertNull(new ArtifactCacheStats().drain());
    }

    @Test
    void summarizesAndResets() {
        var stats = new ArtifactCacheStats();
        stats.record(TRUSTED, 1024);
        stats.record(TRUSTED, 1024);
        stats.record(TRUSTED_CHECKING, 2048);
        stats.record(CONFIRMED, 0);
        assertEquals("Maven/Gradle cache: 4 hits, " + ArtifactCacheHandler.formatSize(4096) + " (2 served on a fresh confirmation, "
                + "1 served while confirming again, 1 confirmed first)", stats.drain());
        assertNull(stats.drain(), "counts start over after each summary");

        stats.record(UNCONFIRMED, 10);
        assertEquals("Maven/Gradle cache: 1 hit, 10 B (1 unconfirmed, upstream unreachable)", stats.drain());
    }
}
