package dev.incusspawn.command;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ListCommandBuildStatusTest {

    /**
     * A build can exit non-zero without writing a report (conflicting definitions, a missing
     * parent). A report left by an earlier, unrelated failure must not be named as the reason.
     */
    @Test
    void reportFromAnEarlierFailureIsNotNamed(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var report = writeReport("tpl-minimal");
            var buildStart = Instant.now();
            setModified(report, buildStart.minus(Duration.ofHours(23)));

            assertNull(ListCommand.freshFailureReport("tpl-minimal", buildStart));
        });
    }

    @Test
    void reportWrittenByThisBuildIsNamed(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var buildStart = Instant.now();
            var report = writeReport("tpl-minimal");
            setModified(report, buildStart.plusSeconds(5));

            assertEquals(report, ListCommand.freshFailureReport("tpl-minimal", buildStart));
        });
    }

    /** Coarse-mtime filesystems round down; a report from the build's first second still counts. */
    @Test
    void reportInTheSameSecondAsTheStartIsNamed(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var buildStart = Instant.parse("2026-09-15T10:00:00.900Z");
            var report = writeReport("tpl-minimal");
            setModified(report, Instant.parse("2026-09-15T10:00:00Z"));

            assertEquals(report, ListCommand.freshFailureReport("tpl-minimal", buildStart));
        });
    }

    @Test
    void pathTraversalInTemplateNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Environment.buildFailureLogFile("../../../etc/evil"));
    }

    /** When the stale-report delete failed, buildStart is null and no report should be shown. */
    @Test
    void nullBuildStartSuppressesReport(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            writeReport("tpl-minimal");
            assertNull(ListCommand.freshFailureReport("tpl-minimal", null));
        });
    }

    @Test
    void missingReportIsNotNamed(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () ->
                assertNull(ListCommand.freshFailureReport("tpl-minimal", Instant.now())));
    }

    private static Path writeReport(String template) {
        var report = Environment.buildFailureLogFile(template);
        assertDoesNotThrow(() -> {
            Files.createDirectories(report.getParent());
            Files.writeString(report, "Build failed for " + template);
        });
        return report;
    }

    private static void setModified(Path file, Instant when) {
        assertDoesNotThrow(() -> Files.setLastModifiedTime(file, FileTime.from(when)));
    }
}
