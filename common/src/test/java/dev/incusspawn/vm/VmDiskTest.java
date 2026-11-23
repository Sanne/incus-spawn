package dev.incusspawn.vm;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #2555: a full VM disk stops Incus. When Incus does not answer, isx asks the agent about the disk
 * and, when it is full, says so and what brings Incus back, rather than restart a forwarder that
 * is not at fault and wait.
 */
class VmDiskTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void theAgentsReplyParses() {
        assertEquals(Optional.of(new VmDisk(GIB, 60 * GIB, GIB)),
                VmDisk.parse(Optional.of(GIB + " " + 60 * GIB + " " + GIB + "\n")));
        assertEquals(Optional.empty(), VmDisk.parse(Optional.of("error: unknown verb")), "an appliance before the verb");
        assertEquals(Optional.empty(), VmDisk.parse(Optional.of("error: no disk")));
        assertEquals(Optional.empty(), VmDisk.parse(Optional.of("1 0 0")), "a disk of no size says nothing");
        assertEquals(Optional.empty(), VmDisk.parse(Optional.of("99999999999999999999 1 0")));
        assertEquals(Optional.empty(), VmDisk.parse(Optional.empty()));
    }

    @Test
    void aFullDiskSkipsTheForwarderRestartAndNamesTheFix() {
        var restarts = new AtomicInteger();
        var waits = new AtomicInteger();
        var recorded = RecordingOutput.during(() -> assertFalse(VmManager.recoverReachability(
                secs -> { waits.incrementAndGet(); return false; },
                () -> { restarts.incrementAndGet(); return true; },
                Optional::empty,
                () -> Optional.of(new VmDisk(GIB / 4, 60 * GIB, GIB)))));
        assertEquals(0, restarts.get(), "a forwarder restart cannot bring Incus back on a full disk");
        assertEquals(1, waits.get(), "only the grace wait: nothing else can clear it");
        var warnings = recorded.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warnings.size(), recorded::toString);
        assertEquals("The VM's disk is full, so Incus cannot run: 0.3 GiB free of 60.0 GiB.", warnings.getFirst().msg());
        var details = String.join("\n", warnings.getFirst().details());
        assertTrue(details.startsWith("Run 'isx vm restart': the VM releases the 1.0 GiB it keeps in reserve"), details);
        assertTrue(details.contains("isx vm resize <size>', larger than its 60G"), details);
    }

    @Test
    void withoutAReserveTheFixIsToGrowTheDisk() {
        var recorded = RecordingOutput.during(() ->
                assertTrue(VmDisk.warnIfFull(Optional.of(new VmDisk(0, 60 * GIB, 0)))));
        var details = recorded.events(RecordingOutput.Kind.WARN).getFirst().details();
        assertTrue(details.getFirst().startsWith("Grow the disk with 'isx vm resize <size>'"), details.toString());
        assertTrue(details.stream().noneMatch(d -> d.contains("isx vm restart")), details.toString());
    }

    @Test
    void aDiskWithRoomLeavesTheForwarderRestartToRun() {
        var restarts = new AtomicInteger();
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true));
        assertTrue(VmManager.recoverReachability(secs -> probes.poll(),
                () -> { restarts.incrementAndGet(); return true; },
                Optional::empty,
                () -> Optional.of(new VmDisk(20 * GIB, 60 * GIB, GIB))));
        assertEquals(1, restarts.get());
    }

    @Test
    void aDiskTooShortToBranchOnIsNotBlamedForAnIncusThatDoesNotAnswer() {
        // 1.5 GiB free refuses a branch, but Incus runs there: a wedged forwarder still gets its restart
        assertFalse(new VmDisk(3 * GIB / 2, 60 * GIB, 0).isFull());
        assertTrue(new VmDisk(VmDisk.FULL_FREE_BYTES - 1, 60 * GIB, 0).isFull());
    }
}
