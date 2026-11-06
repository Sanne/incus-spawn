package dev.incusspawn;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A macOS-only command run on Linux says it is macOS only, not that it is unknown (#1441). */
class IncusSpawnMacOsOnlyTest {

    /** {@code isx vm} exists on macOS only: on Linux it says so, rather than "not a subcommand". */
    @Test
    void vmOnLinuxSaysMacOsOnly() {
        var refused = new boolean[1];
        var recorded = RecordingOutput.during(() -> refused[0] = IncusSpawn.refuseMacOsOnly(new String[] {"vm", "status"}, false));
        assertTrue(refused[0]);
        assertEquals("'isx vm' is macOS only.", recorded.events(RecordingOutput.Kind.ERROR).getFirst().msg());
        assertFalse(IncusSpawn.refuseMacOsOnly(new String[] {"vm", "status"}, true), "macOS runs it");
        assertFalse(IncusSpawn.refuseMacOsOnly(new String[] {"list"}, false));
    }
}
