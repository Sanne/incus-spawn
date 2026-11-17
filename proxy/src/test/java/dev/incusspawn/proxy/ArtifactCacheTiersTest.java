package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import dev.incusspawn.config.Jjq;
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
        return java.util.Objects.requireNonNullElse(ArtifactCacheTiers.configured(Jjq.spawnConfig(yaml)), ArtifactCacheTiers.DEFAULT);
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

    private static ArtifactCacheTiers centralUnder(String yaml) throws Exception {
        return CachedRepository.CENTRAL.revalidation().tiersOr(ArtifactCacheTiers.configured(Jjq.spawnConfig(yaml)));
    }

    @Test
    void mavenCentralConfirmsLessOftenWhileArtifactCacheIsUnset() throws Exception {
        assertNull(ArtifactCacheTiers.configured(Jjq.spawnConfig("{}")));
        assertEquals(ArtifactCacheTiers.CENTRAL_DEFAULT, centralUnder("{}"));
        assertEquals(ArtifactCacheTiers.CENTRAL_DEFAULT, CachedRepository.CENTRAL_REPO1.revalidation().tiersOr(null));
        assertEquals(Duration.ofHours(12), ArtifactCacheTiers.CENTRAL_DEFAULT.fresh());
        // The others keep theirs: the Portal is confirmed every hit, Gradle distributions get the default
        assertEquals(ArtifactCacheTiers.CONFIRM_EVERY_HIT, CachedRepository.PLUGIN_PORTAL.revalidation().tiersOr(null));
        assertEquals(ArtifactCacheTiers.DEFAULT, CachedRepository.GRADLE_DISTRIBUTIONS.revalidation().tiersOr(null));
    }

    @Test
    void anyArtifactCacheSectionReplacesCentralsDefaultWhole() throws Exception {
        assertEquals(ArtifactCacheTiers.CONFIRM_EVERY_HIT, centralUnder("artifact-cache: {fresh: 0, max-stale: 0}"));
        // One key alone takes the other from DEFAULT, never from Central's longer fresh, which would raise this max-stale
        assertEquals(new ArtifactCacheTiers(Duration.ofHours(2), Duration.ofHours(2)),
                centralUnder("artifact-cache: {max-stale: 1h}"));
        // A malformed section is read as the defaults, not as absent
        assertEquals(ArtifactCacheTiers.DEFAULT, centralUnder("artifact-cache: 0"));
    }
}
