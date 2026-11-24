package dev.incusspawn.tui;

import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The status line's busy refusals and batch destroy summary, in the words the commands use (#2542). */
class TuiStatusWordingTest {

    @Test
    void aBusyRefusalSaysWhatIsBeingDoneWhenTheMarkSays() {
        assertEquals("x is being stopped. Try again when it is done.", Tui.busy("x", Metadata.OP_STOPPING));
        assertEquals("x is being destroyed. Try again when it is done.", Tui.busy("x", Metadata.OP_DELETING));
        assertEquals("x is busy with another change. Try again when it is done.", Tui.busy("x"));
    }

    @Test
    void aBatchSummaryTellsThisTuisOwnTasksApartFromAnotherProcesssLocks() {
        assertEquals("Destroyed 2 instances", Tui.destroyBatchSummary("instance", 2, 0, 0));
        assertEquals("Destroyed 1 instance; skipped 1 instance busy with another change",
                Tui.destroyBatchSummary("instance", 1, 1, 0));
        assertEquals("Destroyed 3 templates; skipped 2 templates in use by another isx process",
                Tui.destroyBatchSummary("template", 3, 0, 2));
        assertNull(Tui.destroyBatchSummary("instance", 0, 0, 0));
    }

    @Test
    void aBatchThatDestroyedNothingLeadsWithTheSkip() {
        assertEquals("Skipped 1 instance busy with another change and 2 instances in use by another isx process",
                Tui.destroyBatchSummary("instance", 0, 1, 2));
    }
}
