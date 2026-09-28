package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static dev.incusspawn.proxy.ArtifactCacheTiers.Tier.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactCacheTiersTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private static FileTime ago(Duration age) {
        return FileTime.from(NOW.minus(age));
    }

    private static ArtifactCacheTiers fromYaml(String yaml) throws Exception {
        return ArtifactCacheTiers.from(new ObjectMapper(new YAMLFactory()).readValue(yaml, SpawnConfig.class));
    }

    @Test
    void defaultsAreTwoHoursFreshAndAWeekStale() throws Exception {
        var tiers = fromYaml("{}");
        assertEquals(ArtifactCacheTiers.DEFAULT, tiers);
        assertEquals(Duration.ofHours(2), tiers.fresh());
        assertEquals(Duration.ofDays(7), tiers.maxStale());
    }

    @Test
    void tierBoundaries() {
        var tiers = ArtifactCacheTiers.DEFAULT;
        assertEquals(FRESH, tiers.tierOf(ago(Duration.ZERO), NOW));
        assertEquals(FRESH, tiers.tierOf(ago(Duration.ofMinutes(119)), NOW));
        assertEquals(STALE, tiers.tierOf(ago(Duration.ofHours(2)), NOW));
        assertEquals(STALE, tiers.tierOf(ago(Duration.ofDays(7).minusSeconds(1)), NOW));
        assertEquals(EXPIRED, tiers.tierOf(ago(Duration.ofDays(7)), NOW));
        assertEquals(EXPIRED, tiers.tierOf(ago(Duration.ofDays(400)), NOW));
    }

    @Test
    void unknownOrFutureConfirmationIsNotTrusted() {
        assertEquals(EXPIRED, ArtifactCacheTiers.DEFAULT.tierOf(null, NOW));
        // A clock stepped back must not make a copy look freshly confirmed for days
        assertEquals(EXPIRED, ArtifactCacheTiers.DEFAULT.tierOf(FileTime.from(NOW.plusSeconds(60)), NOW));
    }

    @Test
    void zeroConfirmsEveryHit() throws Exception {
        var tiers = fromYaml("artifact-cache: {fresh: 0, max-stale: 0}");
        assertEquals(ArtifactCacheTiers.CONFIRM_EVERY_HIT, tiers);
        assertEquals(EXPIRED, tiers.tierOf(ago(Duration.ZERO), NOW));
    }

    @Test
    void configuredValuesAndPartialOverrides() throws Exception {
        var tiers = fromYaml("artifact-cache: {fresh: 30m, max-stale: 1d}");
        assertEquals(new ArtifactCacheTiers(Duration.ofMinutes(30), Duration.ofDays(1)), tiers);
        // An unset key keeps its default
        assertEquals(Duration.ofDays(7), fromYaml("artifact-cache: {fresh: 10m}").maxStale());
    }

    @Test
    void unreadableValuesFallBackToDefaults() throws Exception {
        var tiers = fromYaml("artifact-cache: {fresh: soon, max-stale: 2 weeks}");
        assertEquals(ArtifactCacheTiers.DEFAULT, tiers);
    }

    @Test
    void aSectionThatIsNotAMappingFallsBackToDefaults() throws Exception {
        assertEquals(ArtifactCacheTiers.DEFAULT, fromYaml("artifact-cache: 0"));
        assertEquals(ArtifactCacheTiers.CONFIRM_EVERY_HIT, fromYaml("artifact-cache: {fresh: 0, max-stale: 0}"));
    }

    @Test
    void maxStaleBelowFreshLeavesNoBackgroundTier() {
        var tiers = new ArtifactCacheTiers(Duration.ofHours(4), Duration.ofHours(1));
        assertEquals(Duration.ofHours(4), tiers.maxStale());
        assertEquals(FRESH, tiers.tierOf(ago(Duration.ofHours(3)), NOW));
        assertEquals(EXPIRED, tiers.tierOf(ago(Duration.ofHours(4)), NOW));
    }
}
