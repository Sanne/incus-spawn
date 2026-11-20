package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task runs read back from the event log, each booked the model calls it encloses (#2428). */
class TaskRunsTest {

    @TempDir
    Path dir;

    private static final String INC = "2026-11-20T08:00:00Z";

    private static String model(String at, String instance, String incarnation, long input) {
        return "{\"t\":\"" + at + "\",\"kind\":\"model\",\"instance\":\"" + instance + "\",\"incarnation\":\"" + incarnation
                + "\",\"domain\":\"api.anthropic.com\",\"model\":\"m\",\"status\":200,\"ms\":10,\"input_tokens\":" + input
                + ",\"output_tokens\":1,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}";
    }

    private static String start(String at, String instance, String incarnation, String task, int run, String kind, String activity) {
        return "{\"t\":\"" + at + "\",\"kind\":\"task\",\"op\":\"start\",\"instance\":\"" + instance + "\",\"incarnation\":\""
                + incarnation + "\",\"task_id\":\"" + task + "\",\"run\":" + run + ",\"task_kind\":\"" + kind
                + "\",\"activity\":\"" + activity + "\"}";
    }

    private static String end(String at, String instance, String task, int run, String state, int exit) {
        return "{\"t\":\"" + at + "\",\"kind\":\"task\",\"op\":\"end\",\"instance\":\"" + instance + "\",\"incarnation\":\"\","
                + "\"task_id\":\"" + task + "\",\"run\":" + run + ",\"task_kind\":\"agent\",\"activity\":\"\",\"state\":\""
                + state + "\",\"exit_code\":" + exit + "}";
    }

    private EventLogReader.Log read(String... lines) throws Exception {
        var file = dir.resolve("events.jsonl");
        Files.write(file, List.of(lines));
        return EventLogReader.read(file);
    }

    @Test
    void aModelCallIsBookedToTheRunThatEnclosesItAndOneOutsideToTheInstanceAlone() throws Exception {
        var log = read(
                model("2026-11-20T09:00:00Z", "box", INC, 1),
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                model("2026-11-20T10:01:00Z", "box", INC, 10),
                model("2026-11-20T10:02:00Z", "box", INC, 20),
                end("2026-11-20T10:30:00Z", "box", "t1-ab", 1, "finished", 0),
                model("2026-11-20T10:40:00Z", "box", INC, 100),
                start("2026-11-20T11:00:00Z", "box", INC, "t1-ab", 2, "agent", "review-fixes"),
                model("2026-11-20T11:05:00Z", "box", INC, 1000),
                end("2026-11-20T11:10:00Z", "box", "t1-ab", 2, "finished", 1));
        var booked = log.runs("box", INC);
        assertEquals(2, booked.runs().size());
        var first = booked.runs().get(0);
        assertEquals("implement", first.activity());
        assertEquals(2, first.usage().modelCalls());
        assertEquals(30, first.usage().inputTokens());
        assertEquals(Instant.parse("2026-11-20T10:30:00Z"), first.end());
        assertEquals("finished", first.state());
        assertEquals(0, first.exitCode());
        var second = booked.runs().get(1);
        assertEquals("review-fixes", second.activity());
        assertEquals(1000, second.usage().inputTokens());
        assertEquals(1, second.exitCode());
        assertEquals(2, booked.outside().modelCalls(), "before the first run and between the two");
        assertEquals(101, booked.outside().inputTokens());
        assertEquals(0, booked.shared().modelCalls());
        assertEquals(1131, log.of("box", INC).inputTokens(), "the instance's own effort still counts every call");
    }

    @Test
    void aRunNeverSeenToEndIsClosedAtTheNextStartElseAtTheLastCall() throws Exception {
        var log = read(
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                model("2026-11-20T10:01:00Z", "box", INC, 10),
                start("2026-11-20T11:00:00Z", "box", INC, "t2-ab", 1, "agent", "conflict"),
                model("2026-11-20T11:01:00Z", "box", INC, 20),
                model("2026-11-20T11:09:00Z", "box", INC, 40));
        var runs = log.runs("box", INC).runs();
        assertNull(runs.get(0).end());
        assertEquals("", runs.get(0).state());
        assertEquals(10, runs.get(0).usage().inputTokens());
        assertEquals(60, runs.get(1).usage().inputTokens());
    }

    @Test
    void anAgentRunTakesACallBeforeACommandAndTwoAgentsShareIt() throws Exception {
        var log = read(
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                start("2026-11-20T10:00:10Z", "box", INC, "t2-ab", 1, "command", ""),
                model("2026-11-20T10:01:00Z", "box", INC, 10),
                end("2026-11-20T10:02:00Z", "box", "t1-ab", 1, "finished", 0),
                model("2026-11-20T10:03:00Z", "box", INC, 20),
                end("2026-11-20T10:04:00Z", "box", "t2-ab", 1, "finished", 0),
                // Another session's agent in the same box, at the same time
                start("2026-11-20T10:10:00Z", "box", INC, "t1-cd", 1, "agent", ""),
                start("2026-11-20T10:10:00Z", "box", INC, "t1-ef", 1, "agent", ""),
                model("2026-11-20T10:11:00Z", "box", INC, 40),
                end("2026-11-20T10:12:00Z", "box", "t1-cd", 1, "finished", 0),
                end("2026-11-20T10:12:00Z", "box", "t1-ef", 1, "finished", 0));
        var booked = log.runs("box", INC);
        assertEquals(10, booked.runs().get(0).usage().inputTokens());
        assertEquals(20, booked.runs().get(1).usage().inputTokens(), "no agent ran then: the command's");
        assertEquals(40, booked.shared().inputTokens());
        assertEquals(0, booked.runs().get(2).usage().modelCalls());
    }

    @Test
    void aNameBranchedAgainKeepsItsRunsApartAndAnEndOnlyRunIsNotListed() throws Exception {
        var log = read(
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                model("2026-11-20T10:01:00Z", "box", "2026-11-20T09:59:00Z", 10),
                model("2026-11-20T10:02:00Z", "box", INC, 20),
                end("2026-11-20T10:30:00Z", "box", "t1-ab", 1, "finished", 0),
                end("2026-11-20T10:31:00Z", "box", "t9-zz", 4, "finished", 0));
        var booked = log.runs("box", INC);
        assertEquals(1, booked.runs().size());
        assertEquals(20, booked.runs().getFirst().usage().inputTokens());
        assertTrue(log.runs("box", "2026-11-20T09:59:00Z").runs().isEmpty());
        assertEquals(0, booked.outside().modelCalls(), "the other incarnation's call is not this one's");
        assertEquals(10, log.runs("box", "2026-11-20T09:59:00Z").outside().inputTokens());
    }

    @Test
    void anEndLineOfAnotherInstanceDoesNotCloseARun() throws Exception {
        var log = read(
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                // A guest elsewhere planted the same task id, and its session saw that one end
                end("2026-11-20T10:00:30Z", "other", "t1-ab", 1, "finished", 0),
                model("2026-11-20T10:01:00Z", "box", INC, 10),
                end("2026-11-20T10:30:00Z", "box", "t1-ab", 1, "finished", 0));
        var run = log.runs("box", INC).runs().getFirst();
        assertEquals(Instant.parse("2026-11-20T10:30:00Z"), run.end());
        assertEquals(10, run.usage().inputTokens());
    }

    @Test
    void anAgentRunNeverSeenToEndIsNotCutShortByACommandBesideIt() throws Exception {
        var log = read(
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                start("2026-11-20T10:05:00Z", "box", INC, "t2-ab", 1, "command", ""),
                end("2026-11-20T10:06:00Z", "box", "t2-ab", 1, "finished", 0),
                model("2026-11-20T10:10:00Z", "box", INC, 10));
        assertEquals(10, log.runs("box", INC).runs().getFirst().usage().inputTokens());
    }

    @Test
    void aFreshInstancesRunTakesItsCallsIncarnationAndANameMadeAgainDoesNotExtendIt() throws Exception {
        var later = "2026-11-20T12:00:00Z";
        var log = read(
                start("2026-11-20T10:00:00Z", "box", "", "t1-ab", 1, "agent", "implement"),
                model("2026-11-20T10:01:00Z", "box", INC, 10),
                // box destroyed unseen and branched again; its calls are not the old run's
                model("2026-11-20T12:01:00Z", "box", later, 100));
        var run = log.runs("box", INC).runs().getFirst();
        assertEquals(INC, run.incarnation());
        assertEquals(10, run.usage().inputTokens());
        assertTrue(log.runs("box", later).runs().isEmpty());
        assertEquals(100, log.runs("box", later).outside().inputTokens());
    }

    private static String sum(String day, String first, String last, long calls, long input) {
        return "{\"t\":\"" + day + "\",\"kind\":\"model_sum\",\"instance\":\"box\",\"incarnation\":\"" + INC
                + "\",\"model\":\"m\",\"status\":200,\"calls\":" + calls + ",\"input_tokens\":" + input
                + ",\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"ms\":0,"
                + "\"first\":\"" + first + "\",\"last\":\"" + last + "\"}";
    }

    @Test
    void aFoldedSumIsBookedToARunOnlyWhenItLiesWhollyInIt() throws Exception {
        var log = read(
                start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                end("2026-11-20T12:00:00Z", "box", "t1-ab", 1, "finished", 0),
                sum("2026-11-20T00:00:00Z", "2026-11-20T10:30:00Z", "2026-11-20T11:30:00Z", 5, 50),
                sum("2026-11-21T00:00:00Z", "2026-11-21T09:00:00Z", "2026-11-21T09:30:00Z", 2, 7),
                start("2026-11-22T10:00:00Z", "box", INC, "t2-ab", 1, "agent", "pr-review"),
                end("2026-11-22T12:00:00Z", "box", "t2-ab", 1, "finished", 0),
                sum("2026-11-22T00:00:00Z", "2026-11-22T08:00:00Z", "2026-11-22T11:00:00Z", 3, 300));
        var booked = log.runs("box", INC);
        assertEquals(5, booked.runs().get(0).usage().modelCalls());
        assertEquals(50, booked.runs().get(0).usage().inputTokens());
        assertEquals(0, booked.runs().get(1).usage().modelCalls());
        assertEquals(300, booked.folded().inputTokens(), "crosses the second run's start");
        assertEquals(7, booked.outside().inputTokens());
        assertEquals(357, log.of("box", INC).inputTokens());
    }

    @Test
    void aFoldedSumInAnAgentsRunIsItsThoughItCrossesACommandsRun() throws Exception {
        var log = read(
                start("2026-11-20T08:00:00Z", "box", INC, "t1-ab", 1, "agent", "implement"),
                start("2026-11-20T10:00:00Z", "box", INC, "t2-ab", 1, "command", ""),
                end("2026-11-20T10:15:00Z", "box", "t2-ab", 1, "finished", 0),
                sum("2026-11-20T00:00:00Z", "2026-11-20T09:00:00Z", "2026-11-20T11:00:00Z", 4, 40),
                end("2026-11-20T12:00:00Z", "box", "t1-ab", 1, "finished", 0));
        var booked = log.runs("box", INC);
        assertEquals(40, booked.runs().getFirst().usage().inputTokens(), "an agent's run outranks the command it crosses");
        assertEquals(0, booked.folded().inputTokens());
    }

    @Test
    void aLogWithoutTaskLinesHasNoRuns() throws Exception {
        var log = read(model("2026-11-20T10:01:00Z", "box", INC, 10));
        assertTrue(log.runs("box", INC).runs().isEmpty());
        assertEquals(10, log.of("box", INC).inputTokens());
    }

    @Test
    void theAppenderWritesOwnerOnlyLinesTheReaderReads() throws Exception {
        var file = dir.resolve("events/events.jsonl");
        assertTrue(EventLogAppender.append(file, start("2026-11-20T10:00:00Z", "box", INC, "t1-ab", 1, "agent", "x")));
        assertTrue(EventLogAppender.append(file, model("2026-11-20T10:01:00Z", "box", INC, 5)));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        assertEquals(5, EventLogReader.read(file).runs("box", INC).runs().getFirst().usage().inputTokens());
    }
}
