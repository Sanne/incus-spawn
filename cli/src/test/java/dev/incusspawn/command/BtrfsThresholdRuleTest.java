package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The btrfs sudoers rule's entries for settling deletes (#2061): {@code tee} to the one sysfs file
 * of the pool's filesystem, written out, and {@code subvolume sync} on the pool's mount.
 */
class BtrfsThresholdRuleTest {

    private static final String FSID = "4e47dfd0-7cd8-4069-893a-3410114af64f";

    @Test
    void theThresholdEntryNamesTheFileItWritesInFull() {
        assertEquals(", /usr/bin/tee /sys/fs/btrfs/" + FSID + "/qgroups/drop_subtree_threshold",
                HostSetup.thresholdEntry(FSID));
    }

    /** sudo's {@code *} also matches spaces, so a pattern there would let tee write any file. */
    @Test
    void noFilesystemOrAnythingButAUuidGivesNoEntry() {
        for (var bad : new String[]{null, "", "*", FSID + " /etc/shadow"}) {
            assertEquals("", HostSetup.thresholdEntry(bad), String.valueOf(bad));
        }
    }

    @Test
    void theRuleLetsIsxWaitForTheCleanerAndARunningRescanOnThePoolOnly() {
        var commands = HostSetup.btrfsUsageCommands("cow", "/usr/bin/btrfs");
        assertTrue(commands.contains("/usr/bin/btrfs subvolume sync /var/lib/incus/storage-pools/cow"), commands.toString());
        assertTrue(commands.contains("/usr/bin/btrfs quota rescan -W /var/lib/incus/storage-pools/cow"), commands.toString());
    }
}
