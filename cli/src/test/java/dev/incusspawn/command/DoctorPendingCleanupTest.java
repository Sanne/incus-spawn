package dev.incusspawn.command;

import dev.incusspawn.incus.BtrfsUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doctor names a btrfs cleaner backlog, which makes every branch wait while full qgroup
 * accounting frees deleted instances (#2061).
 */
class DoctorPendingCleanupTest {

    private static BtrfsUsage.QgroupStatus status(boolean inconsistent, String mode) {
        return new BtrfsUsage.QgroupStatus(true, true, inconsistent, mode, 3);
    }

    @Test
    void aBacklogUnderFullAccountingIsANoteThatSaysWhatItCostsAndHowToWait() {
        var finding = DoctorCommand.pendingCleanupFinding("cow",
                new BtrfsUsage.PendingCleanup(20, 20 * 2_916_741_120L), false).orElseThrow();

        assertEquals(DoctorCommand.Status.NOTE, finding.status());
        assertFalse(finding.status().isProblem(), "it clears on its own, so it must not fail doctor");
        assertTrue(finding.detail().contains("20 deleted subvolume(s)"), finding.detail());
        assertTrue(finding.detail().contains("a new branch can take seconds"), finding.detail());
        assertTrue(finding.detail().contains("sudo btrfs subvolume sync /var/lib/incus/storage-pools/cow"), finding.detail());
    }

    @Test
    void onMacOsItNamesNoHostCommand() {
        var finding = DoctorCommand.pendingCleanupFinding("cow", new BtrfsUsage.PendingCleanup(1, 1L << 30), true)
                .orElseThrow();
        assertFalse(finding.detail().contains("sudo"), finding.detail());
    }

    @Test
    void nothingPendingSaysNothing() {
        assertTrue(DoctorCommand.pendingCleanupFinding("cow", BtrfsUsage.PendingCleanup.NONE, false).isEmpty());
    }

    @Test
    void onlyConsistentFullAccountingIsReadForABacklog() {
        assertTrue(DoctorCommand.slowsCleanup(status(false, "qgroup")));
        // Simple quotas measured no slowdown; frozen counters could report a backlog long gone
        assertFalse(DoctorCommand.slowsCleanup(status(false, "squota")));
        assertFalse(DoctorCommand.slowsCleanup(status(true, "qgroup")));
        // A kernel without the mode attribute (before simple quotas existed) only has full accounting
        assertTrue(DoctorCommand.slowsCleanup(status(false, "")));
        assertFalse(DoctorCommand.slowsCleanup(BtrfsUsage.QgroupStatus.UNAVAILABLE));
        assertFalse(DoctorCommand.slowsCleanup(new BtrfsUsage.QgroupStatus(true, false, false, "", -1)));
    }
}
