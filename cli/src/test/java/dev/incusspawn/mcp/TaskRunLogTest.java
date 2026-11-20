package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The task lines isx mcp writes to the event log (#2428), whatever order the reads come in. */
class TaskRunLogTest {

    @TempDir
    Path home;
    private String realHome;

    @BeforeEach
    void setUp() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", realHome);
    }

    private List<JqValue> lines() throws Exception {
        return Files.readAllLines(home.resolve(".local/state/incus-spawn/events/events.jsonl")).stream()
                .map(JqValues::parseStrict).toList();
    }

    private static final Tasks.Task TASK = new Tasks.Task("t1-ab", "box", Tasks.AGENT, "/", Tasks.Profile.NONE, 2,
            TaskWatcher.RUNNING, false, null);

    @Test
    void aRunSeenRunningBeforeItsLaunchReturnedStillGetsItsStartAndActivity() throws Exception {
        var log = new TaskRunLog();
        log.seen(TASK, 2, TaskWatcher.RUNNING, null);
        log.started(TASK, "review-fixes", "2026-11-20T08:00:00Z");
        log.seen(TASK, 2, "finished", 0);
        log.seen(TASK, 2, "finished", 0);
        var lines = lines();
        assertEquals(List.of("start", "end"), lines.stream().map(l -> l.pathText("op", "")).toList());
        assertEquals("review-fixes", lines.get(0).pathText("activity", ""));
        assertEquals("review-fixes", lines.get(1).pathText("activity", ""), "the end repeats the start's");
        assertEquals("2026-11-20T08:00:00Z", lines.get(1).pathText("incarnation", ""));
        assertEquals(0, lines.get(1).path("exit_code").asLong(-1));
    }

    @Test
    void anEndSeenBeforeTheLaunchReturnedIsWrittenAfterTheStart() throws Exception {
        var log = new TaskRunLog();
        log.launching(TASK);
        log.seen(TASK, 2, TaskWatcher.RUNNING, null);
        log.seen(TASK, 2, "finished", 3);
        log.started(TASK, "ci-failure", null);
        var lines = lines();
        assertEquals(List.of("start", "end"), lines.stream().map(l -> l.pathText("op", "")).toList());
        assertEquals("ci-failure", lines.get(1).pathText("activity", ""));
        assertEquals(3, lines.get(1).path("exit_code").asLong(-1));
    }

    @Test
    void theStartLineCarriesTheMomentTheLaunchExecWasSent() throws Exception {
        var log = new TaskRunLog();
        var before = java.time.Instant.now();
        log.launching(TASK);
        var sent = java.time.Instant.now();
        Thread.sleep(20); // the launch round trip
        log.started(TASK, "implement", null);
        var at = java.time.Instant.parse(lines().getFirst().pathText("t", ""));
        assertTrue(!at.isBefore(before) && !at.isAfter(sent), "stamped when the exec went out, not when it returned: " + at);
    }

    @Test
    void aRunThisSessionNeverSawRunningGetsNoEnd() throws Exception {
        var log = new TaskRunLog();
        log.seen(TASK, 1, "finished", 0);
        log.seen(TASK, 2, TaskWatcher.RUNNING, null);
        log.seen(TASK, 2, TaskWatcher.LOST, null);
        var lines = lines();
        assertEquals(1, lines.size(), "an adopted run's end, nothing for the finished one: " + lines);
        assertEquals("lost", lines.getFirst().pathText("state", ""));
        assertEquals(2, lines.getFirst().path("run").asLong(0));
    }
}
