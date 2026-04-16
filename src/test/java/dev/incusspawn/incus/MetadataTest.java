package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class MetadataTest {

    @Test
    void nowReturnsIsoDateTime() {
        var result = Metadata.now();
        // Should parse as ISO_LOCAL_DATE_TIME without exception
        var parsed = LocalDateTime.parse(result, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertNotNull(parsed);
    }

    @Test
    void ageDescriptionToday() {
        var timestamp = LocalDateTime.now().withHour(14).withMinute(32).withSecond(7)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var result = Metadata.ageDescription(timestamp);
        assertTrue(result.startsWith("today "), "expected 'today HH:mm:ss', got: " + result);
        assertTrue(result.contains("14:32:07"), "expected time in result, got: " + result);
    }

    @Test
    void ageDescriptionYesterday() {
        var timestamp = LocalDateTime.now().minusDays(1).withHour(9).withMinute(15).withSecond(30)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var result = Metadata.ageDescription(timestamp);
        assertTrue(result.startsWith("yesterday "), "expected 'yesterday HH:mm:ss', got: " + result);
        assertTrue(result.contains("09:15:30"), "expected time in result, got: " + result);
    }

    @Test
    void ageDescriptionDaysAgo() {
        var timestamp = LocalDateTime.now().minusDays(4)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertEquals("4 days ago", Metadata.ageDescription(timestamp));
    }

    @Test
    void ageDescriptionWeeksAgo() {
        var timestamp = LocalDateTime.now().minusDays(14)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertEquals("2 weeks ago", Metadata.ageDescription(timestamp));
    }

    @Test
    void ageDescriptionMonthsAgo() {
        var timestamp = LocalDateTime.now().minusDays(90)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertEquals("3 months ago", Metadata.ageDescription(timestamp));
    }

    @Test
    void ageDescriptionLegacyDateOnly() {
        // Legacy format (date-only) should still work, without time
        var date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        var result = Metadata.ageDescription(date);
        assertTrue(result.startsWith("today "), "legacy date-only for today should work, got: " + result);
    }

    @Test
    void ageDescriptionInvalid() {
        assertEquals("unknown", Metadata.ageDescription("not-a-date"));
    }
}
