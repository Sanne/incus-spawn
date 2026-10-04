package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@link TaskWatcher} reports, whatever order readings of a task arrive in. */
class TaskWatcherTest {

    private final List<String> sent = new ArrayList<>();
    private final TaskWatcher watcher = new TaskWatcher(slow -> { });

    TaskWatcherTest() {
        watcher.autoPoll = false;
        watcher.sendTo((method, params) -> sent.add(params.path("state").asText() + " " + params.path("run").asInt()));
        watcher.start();
    }

    @Test
    void aReadingOfAnEarlierRunIsStale() {
        watcher.observe("t1", "i", 1, "finished", 0);
        watcher.observe("t1", "i", 2, "running", null); // send_message launched run 2
        watcher.observe("t1", "i", 1, "finished", 0);   // a poll that read run 1 before the launch
        watcher.observe("t1", "i", 2, "finished", 0);
        assertEquals(List.of("finished 1", "running 2", "finished 2"), sent);
    }

    @Test
    void aRunThatEndedNeverRunsAgain() {
        watcher.observe("t1", "i", 1, "running", null);
        watcher.observe("t1", "i", 1, "finished", 0);   // task_status saw it end
        watcher.observe("t1", "i", 1, "running", null); // a poll that read it just before
        watcher.observe("t1", "i", 1, "attached", 0);
        watcher.observe("t1", "i", 1, "finished", 0);
        assertEquals(List.of("running 1", "finished 1", "attached 1", "finished 1"), sent);
    }

    @Test
    void aReadingThatArrivesAfterTheTaskWasForgottenIsDropped() {
        watcher.observe("t1", "i", 1, "running", null);
        watcher.forget("t1", "i", TaskWatcher.RELEASED);  // another session adopted it
        watcher.observe("t1", "i", 1, "finished", 0);     // a status read taken just before
        watcher.observe("t1", "i", 1, "running", null);
        assertEquals(List.of("running 1", "released 1"), sent, "released is the last word");

        watcher.revive("t1"); // this session adopted it back
        watcher.observe("t1", "i", 1, "finished", 0);
        assertEquals(List.of("running 1", "released 1", "finished 1"), sent);
    }

    @Test
    void aTaskTakenBackStillDropsReadingsOfEarlierRuns() {
        watcher.observe("t1", "i", 2, "running", null);
        watcher.forget("t1", "i", TaskWatcher.RELEASED);
        watcher.revive("t1");                            // adopted back
        watcher.observe("t1", "i", 1, "finished", 0);   // a poll that read run 1 long before
        watcher.observe("t1", "i", 2, "finished", 0);
        assertEquals(List.of("running 2", "released 2", "finished 2"), sent);
    }

    @Test
    void aTaskTakenBackWhileRunningIsReportedRunningAndPolled() {
        watcher.observe("t1", "i", 2, "running", null);
        watcher.forget("t1", "i", TaskWatcher.RELEASED);
        watcher.revive("t1");
        assertEquals(null, watcher.lastState("t1"), "never reported since: the poller asks again");
        watcher.observe("t1", "i", 2, "running", null);
        assertEquals(List.of("running 2", "released 2", "running 2"), sent);
    }

    @Test
    void aRunThatWasLostIsNotATombstone() {
        watcher.observe("t1", "i", 1, "running", null);
        watcher.observe("t1", "i", 1, "lost", null);    // the instance restarted under it
        watcher.observe("t1", "i", 2, "running", null); // send_message started run 2
        assertEquals(List.of("running 1", "lost 1", "running 2"), sent);
    }

    @Test
    void aWatcherNobodyStartedKeepsNothing() {
        var quiet = new TaskWatcher(slow -> { });
        quiet.forget("t1", "i", TaskWatcher.RELEASED);
        assertEquals(null, quiet.lastState("t1"), "no tombstones for a client that did not ask");
    }

    @Test
    void nothingIsReportedBeforeStartOrAfterStop() {
        var quiet = new TaskWatcher(slow -> { });
        quiet.sendTo((method, params) -> sent.add("x"));
        quiet.observe("t1", "i", 1, "running", null);
        watcher.stop();
        watcher.observe("t1", "i", 1, "running", null);
        assertEquals(List.of(), sent);
    }
}
