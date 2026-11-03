package dev.incusspawn.tui;

import dev.incusspawn.lifecycle.TemplateLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Which unstamped templates the TUI's backfill measures with a commit, and which it saves as stamps. */
class ReferencedBackfillTest {

    /** A pool whose answers a test sets, recording what the backfill asked of it. */
    static final class ScriptedPool implements ReferencedBackfill.Pool {
        boolean inconsistentBefore;
        boolean inconsistentAfter;
        boolean stampFails;
        Map<String, Long> sizes = Map.of("tpl-a", 100L, "tpl-b", 200L);
        final Set<String> swapping = new HashSet<>();
        final List<String> events = new ArrayList<>();
        final Map<String, Long> stamped = new HashMap<>();
        int heldNow;

        @Override
        public boolean repairIfInconsistent() {
            events.add("status before");
            return inconsistentBefore;
        }

        @Override
        public boolean untrusted() {
            events.add("status after");
            return inconsistentAfter;
        }

        @Override
        public Map<String, Long> probe(boolean sync) {
            events.add(sync ? "probe sync" : "probe");
            return sizes;
        }

        @Override
        public Optional<TemplateLock.Held> tryHold(String template) {
            if (swapping.contains(template)) return Optional.empty();
            heldNow++;
            return Optional.of(() -> heldNow--);
        }

        @Override
        public boolean stamp(String template, long referenced) {
            events.add("stamp " + template);
            assertTrue(heldNow > 0, "stamped " + template + " without holding it in place");
            if (stampFails || referenced <= 0) return false;
            stamped.put(template, referenced);
            return true;
        }
    }

    private final ReferencedBackfill backfill = new ReferencedBackfill();
    private final ScriptedPool pool = new ScriptedPool();

    @Test
    void consistentAccountingIsMeasuredWithACommitAndStamped() {
        var live = backfill.fill(List.of("tpl-a"), pool);
        assertEquals(List.of("status before", "probe sync", "status after", "stamp tpl-a"), pool.events);
        assertEquals(Map.of("tpl-a", 100L), pool.stamped);
        assertEquals(100L, live.get("tpl-a"));
        assertEquals(0, pool.heldNow, "a hold outlived the backfill");
    }

    @Test
    void inconsistentAccountingBeforeTheReadIsNeitherReadNorStamped() {
        pool.inconsistentBefore = true;
        assertTrue(backfill.fill(List.of("tpl-a"), pool).isEmpty());
        assertEquals(List.of("status before"), pool.events);
        assertTrue(pool.stamped.isEmpty());
        // Measured again, with a commit, once the accounting is repaired
        pool.inconsistentBefore = false;
        pool.events.clear();
        backfill.fill(List.of("tpl-a"), pool);
        assertTrue(pool.events.contains("probe sync"));
        assertEquals(Map.of("tpl-a", 100L), pool.stamped);
    }

    @Test
    void accountingFlaggedDuringTheReadIsNotStamped() {
        pool.inconsistentAfter = true;
        assertTrue(backfill.fill(List.of("tpl-a"), pool).isEmpty());
        assertEquals(List.of("status before", "probe sync", "status after"), pool.events);
        assertTrue(pool.stamped.isEmpty());
        pool.inconsistentAfter = false;
        pool.events.clear();
        backfill.fill(List.of("tpl-a"), pool);
        assertTrue(pool.events.contains("probe sync"), "not measured again after the accounting cleared");
    }

    @Test
    void aGapTheMeasureCannotFillIsReadWithACommitOnlyOnce() {
        pool.sizes = Map.of("tpl-b", 200L);    // no figure for tpl-a
        backfill.fill(List.of("tpl-a"), pool);
        assertEquals(List.of("status before", "probe sync", "status after", "stamp tpl-a"), pool.events);
        pool.events.clear();
        backfill.fill(List.of("tpl-a"), pool);
        backfill.fill(List.of("tpl-a"), pool);
        assertEquals(List.of("probe", "probe"), pool.events);
    }

    @Test
    void anEmptyReadIsNotRetriedWithACommit() {
        pool.sizes = Map.of();
        backfill.fill(List.of("tpl-a"), pool);
        pool.events.clear();
        backfill.fill(List.of("tpl-a"), pool);
        assertEquals(List.of("probe"), pool.events);
    }

    @Test
    void aStampThatCannotBeWrittenIsNotMeasuredWithACommitAgain() {
        pool.stampFails = true;
        backfill.fill(List.of("tpl-a"), pool);
        pool.events.clear();
        backfill.fill(List.of("tpl-a"), pool);
        assertEquals(List.of("probe"), pool.events);
    }

    @Test
    void aRebuildAfterAStampLeavesAGapThatIsMeasuredAfresh() {
        backfill.fill(List.of("tpl-a"), pool);
        backfill.fill(List.of(), pool);        // stamped: no gap
        pool.events.clear();
        pool.stamped.clear();
        backfill.fill(List.of("tpl-a"), pool); // rebuilt, unstamped again
        assertTrue(pool.events.contains("probe sync"));
        assertEquals(Map.of("tpl-a", 100L), pool.stamped);
    }

    @Test
    void aTemplateBeingSwappedIsLeftForALaterReload() {
        pool.swapping.add("tpl-a");
        var live = backfill.fill(List.of("tpl-a", "tpl-b"), pool);
        assertEquals(Map.of("tpl-b", 200L), pool.stamped, "only what was held in place is stamped");
        assertFalse(live.containsKey("tpl-a"), "a template being swapped was filled");
        assertEquals(200L, live.get("tpl-b"));
        // The swap done, it is measured and stamped like any other gap
        pool.swapping.clear();
        pool.events.clear();
        backfill.fill(List.of("tpl-a"), pool);
        assertEquals(List.of("status before", "probe sync", "status after", "stamp tpl-a"), pool.events);
        assertEquals(100L, pool.stamped.get("tpl-a"));
    }

    @Test
    void onlyTemplatesBeingSwappedReadNothingAndStampNothing() {
        pool.swapping.add("tpl-a");
        var live = backfill.fill(List.of("tpl-a"), pool);
        assertTrue(pool.events.isEmpty(), "read the pool with every gap held by a swap");
        assertTrue(pool.stamped.isEmpty());
        assertFalse(live.containsKey("tpl-a"));
    }

    @Test
    void noGapReadsNothing() {
        assertTrue(backfill.fill(List.of(), pool).isEmpty());
        assertTrue(pool.events.isEmpty());
    }
}
