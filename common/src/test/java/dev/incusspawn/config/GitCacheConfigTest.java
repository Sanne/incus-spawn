package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitCacheConfigTest {
    @Test void absentAndMalformedSectionsKeepDefaults() {
        assertEquals(GitCacheConfig.DEFAULT, GitCacheConfig.of(null));
        assertEquals(GitCacheConfig.DEFAULT, GitCacheConfig.of("invalid"));
        assertEquals(GitCacheConfig.DEFAULT, GitCacheConfig.of(Map.of("snapshot-max-mib", "9223372036854775807", "max-age-days", "bad")));
    }

    @Test void retentionCanBeDisabled() {
        var config = GitCacheConfig.of(Map.of("snapshot-max-mib", 0, "max-age-days", 2));
        assertEquals(0, config.snapshotBytes());
        assertEquals(Duration.ofDays(2), config.maxAge());
        assertEquals(Duration.ZERO, GitCacheConfig.of(Map.of("max-age-days", 0)).maxAge());
    }

    @Test void yamlRoundTripKeepsTheExplicitBudgets() {
        var config = new SpawnConfig();
        config.setConfigByPath("git-cache.snapshot-max-mib", "0");
        config.setConfigByPath("git-cache.max-age-days", "2");
        assertEquals(0, config.gitCache().snapshotBytes());
        assertEquals(Duration.ofDays(2), config.gitCache().maxAge());
    }
}
