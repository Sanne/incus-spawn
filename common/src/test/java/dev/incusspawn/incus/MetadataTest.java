package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class MetadataTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 8, 0, 0);

    private static String stamp(LocalDateTime t) {
        return t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Test
    void nowReturnsIsoDateTime() {
        var result = Metadata.now();
        // Should parse as ISO_LOCAL_DATE_TIME without exception
        var parsed = LocalDateTime.parse(result, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertNotNull(parsed);
    }

    @Test
    void ageDescriptionJustNow() {
        assertEquals("just now", Metadata.ageDescription(stamp(NOW.plusSeconds(5)), NOW.plusSeconds(40)));
    }

    @Test
    void ageDescriptionTicksOverAtTheMinuteBoundary() {
        // Created at 07:59:50: one calendar minute later it's "1 min ago", even though only 10s
        // passed -- the text must change exactly when the view re-renders it, not mid-minute.
        var created = stamp(NOW.minusSeconds(10));
        assertEquals("just now", Metadata.ageDescription(created, NOW.minusSeconds(1)));
        assertEquals("1 min ago", Metadata.ageDescription(created, NOW));
    }

    @Test
    void ageDescriptionStampAheadOfClockReadsJustNow() {
        assertEquals("just now", Metadata.ageDescription(stamp(NOW.plusMinutes(2)), NOW));
    }

    @Test
    void ageDescriptionMinutes() {
        assertEquals("1 min ago", Metadata.ageDescription(stamp(NOW.minusMinutes(1)), NOW));
        assertEquals("59 min ago", Metadata.ageDescription(stamp(NOW.minusMinutes(59)), NOW));
    }

    @Test
    void ageDescriptionHours() {
        assertEquals("1h ago", Metadata.ageDescription(stamp(NOW.minusMinutes(60)), NOW));
        assertEquals("23h ago", Metadata.ageDescription(stamp(NOW.minusHours(24).plusMinutes(1)), NOW));
    }

    @Test
    void ageDescriptionLastNightIsHoursNotAWallClockTime() {
        // The motivating case: built at 21:20 yesterday, viewed at 08:00. The old wording read
        // "today 21:20:00" if rendered before midnight and never updated.
        assertEquals("10h ago", Metadata.ageDescription(stamp(NOW.minusDays(1).withHour(21).withMinute(20)), NOW));
    }

    @Test
    void ageDescriptionDaysAgo() {
        assertEquals("1 day ago", Metadata.ageDescription(stamp(NOW.minusDays(1)), NOW));
        assertEquals("4 days ago", Metadata.ageDescription(stamp(NOW.minusDays(4)), NOW));
    }

    @Test
    void ageDescriptionWeeksAgo() {
        assertEquals("1 week ago", Metadata.ageDescription(stamp(NOW.minusDays(7)), NOW));
        assertEquals("2 weeks ago", Metadata.ageDescription(stamp(NOW.minusDays(14)), NOW));
    }

    @Test
    void ageDescriptionMonthsAgo() {
        assertEquals("1 month ago", Metadata.ageDescription(stamp(NOW.minusDays(30)), NOW));
        assertEquals("3 months ago", Metadata.ageDescription(stamp(NOW.minusDays(90)), NOW));
    }

    @Test
    void ageDescriptionLegacyDateOnlyUsesCalendarDays() {
        // A date-only stamp has no time of day, so it must not be rendered as "8h ago".
        assertEquals("today", Metadata.ageDescription("2026-09-25", NOW));
        assertEquals("yesterday", Metadata.ageDescription("2026-09-24", NOW));
        assertEquals("3 days ago", Metadata.ageDescription("2026-09-22", NOW));
    }

    @Test
    void ageDescriptionInvalid() {
        assertEquals("unknown", Metadata.ageDescription("not-a-date", NOW));
    }

    @Test
    void ageDescriptionOnlyChangesWhenRefreshKeyDoes() {
        var created = stamp(NOW.minusHours(3).minusSeconds(17));
        var t = NOW.withSecond(1);
        var key = Metadata.ageRefreshKey(t);
        for (int s = 1; s < 60; s++) {
            var later = t.withSecond(s);
            assertEquals(key, Metadata.ageRefreshKey(later));
            assertEquals(Metadata.ageDescription(created, t), Metadata.ageDescription(created, later));
        }
        assertNotEquals(key, Metadata.ageRefreshKey(t.plusMinutes(1)));
    }
}
