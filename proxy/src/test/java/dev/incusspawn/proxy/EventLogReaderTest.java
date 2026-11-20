package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The overview's reader against the lines the proxy's own {@link EventLog} writes (#2241). */
class EventLogReaderTest {

    @TempDir
    Path dir;

    private static final String FIRST = "2026-11-01T10:00:00.123456789Z";
    private static final String AGAIN = "2026-11-03T09:00:00.5Z";

    private static Instant day(int d, int hour) {
        return Instant.parse(String.format("2026-11-%02dT%02d:00:00Z", d, hour));
    }

    private static EventLog.ModelCall call(Instant at, String name, String incarnation, long in, long out) {
        return new EventLog.ModelCall(at, name, incarnation, "api.anthropic.com", "claude-x", 200, 10, in, out, 3, 2);
    }

    @Test
    void readsTheTokensActiveDaysBranchesAndPullRequestsTheProxyWrote() throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file)) {
            log.modelCall(call(day(1, 10), "w", FIRST, 100, 20));
            log.push(new EventLog.Push(day(1, 11), "w", FIRST, "o/r", 200, 5,
                    List.of("refs/heads/main", "refs/heads/fix", "refs/tags/v1"), List.of()));
            log.push(new EventLog.Push(day(1, 12), "w", FIRST, "o/r", 200, 5, List.of(), List.of("refs/heads/fix")));
            log.pullRequest(new EventLog.PullRequest(day(2, 9), "w", FIRST, "o/r", 12, 201, 5));
            log.modelCall(call(day(2, 10), "w", FIRST, 50, 10));
            log.flush();
        }
        var effort = EventLogReader.read(file).of("w", FIRST);
        assertEquals(2, effort.modelCalls());
        assertEquals(150, effort.inputTokens());
        assertEquals(30, effort.outputTokens());
        assertEquals(150 + 30 + 2 * (3 + 2), effort.tokens());
        assertEquals(day(1, 10), effort.first());
        assertEquals(day(2, 10), effort.last());
        assertEquals(List.of("o/r:main", "o/r:refs/tags/v1"), effort.branches().stream().map(Object::toString).toList(),
                "a branch a later push deleted is not listed");
        assertEquals(List.of("o/r#12"), effort.pullRequests().stream().map(Object::toString).toList());
    }

    @Test
    void anInstanceIsItsIncarnationSoARenameKeepsItsLinesAndANameBranchedAgainStartsAfresh() throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file)) {
            log.modelCall(call(day(1, 10), "w", FIRST, 100, 0));
            log.modelCall(call(day(3, 10), "w", AGAIN, 7, 0));
            log.flush();
        }
        var read = EventLogReader.read(file);
        assertEquals(100, read.of("renamed", FIRST).inputTokens(), "a rename keeps created_at");
        assertEquals(7, read.of("w", AGAIN).inputTokens(), "the new w is not the old one");
        assertFalse(read.of("w", "2026-12-01T00:00:00Z").any());
    }

    @Test
    void aLineWithNoIncarnationIsMatchedByName() throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file)) {
            log.modelCall(call(day(1, 10), "w", "", 4, 0));
            log.modelCall(call(day(2, 10), "w", FIRST, 6, 0));
            log.flush();
        }
        var effort = EventLogReader.read(file).of("w", FIRST);
        assertEquals(10, effort.inputTokens());
        assertEquals(day(1, 10), effort.first());
    }

    @Test
    void olderFilesAndSumsAreReadTooAndTheLogSaysSinceWhenAndWhatItLost() throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file, 600)) {
            for (int i = 0; i < 12; i++) log.modelCall(call(day(1 + i, 1), "w", FIRST, 1, 0));
            log.flush();
        }
        assertTrue(Files.exists(dir.resolve("events.jsonl.1")), "rolled");
        Files.writeString(file, "{\"t\":\"2026-11-20T00:00:00Z\",\"kind\":\"dropped\",\"lines\":3}\n{\"t\":\"2026-11-2",
                StandardOpenOption.APPEND);
        var read = EventLogReader.read(file);
        var effort = read.of("w", FIRST);
        assertEquals(12, effort.modelCalls(), "what rolled out is read from the sums (#2429)");
        assertEquals(day(1, 1), effort.first());
        assertEquals(effort.first(), read.since());
        assertEquals(3, read.droppedLines());
        assertEquals(1, read.unreadableLines(), "the torn line is skipped");
    }

    @Test
    void noLogIsNoEffortAndNoFiles() {
        var read = EventLogReader.read(dir.resolve("events.jsonl"));
        assertFalse(read.files());
        assertNull(read.since());
        assertSame(EventLogReader.Effort.NONE, read.of("w", FIRST));
    }

    @Test
    void aLineCutInsideACharacterSpoilsItselfAloneAndTheLinesAfterItCount() throws Exception {
        var file = dir.resolve("events.jsonl");
        var good = "{\"t\":\"2026-11-01T10:00:00Z\",\"kind\":\"model\",\"instance\":\"w\",\"incarnation\":\"" + FIRST
                + "\",\"input_tokens\":1}\n";
        var torn = "{\"t\":\"2026-11-01T10:00:00Z\",\"kind\":\"github\",\"repo\":\"o/caf".getBytes(StandardCharsets.UTF_8);
        try (var out = Files.newOutputStream(file)) {
            out.write(good.getBytes(StandardCharsets.UTF_8));
            out.write(torn);
            out.write(new byte[]{(byte) 0xC3, '\n'}); // the first byte of an é, then the line the next write began
            out.write(good.getBytes(StandardCharsets.UTF_8));
        }
        var read = EventLogReader.read(file);
        assertEquals(2, read.of("w", FIRST).modelCalls());
        assertEquals(1, read.unreadableLines());
    }

    @Test
    void aKindOfALaterIsxIsSkippedAndNoInstanceIsMadeOfIt() throws Exception {
        var file = dir.resolve("events.jsonl");
        Files.writeString(file, "{\"t\":\"2026-11-01T10:00:00Z\",\"kind\":\"session\",\"instance\":\"w\",\"incarnation\":\""
                + FIRST + "\"}\n{\"t\":\"2026-11-02T10:00:00Z\",\"kind\":\"model\",\"instance\":\"w\",\"input_tokens\":3}\n");
        var effort = EventLogReader.read(file).of("w", FIRST);
        assertEquals(3, effort.inputTokens());
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), effort.first());
    }

    @Test
    void theNewestBranchesAndPullRequestsAreKept() throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file)) {
            for (int i = 0; i <= EventLogReader.MAX_KEPT; i++) {
                log.push(new EventLog.Push(day(1, 1), "w", FIRST, "o/r", 200, 1, List.of("refs/heads/b" + i), List.of()));
                log.pullRequest(new EventLog.PullRequest(day(1, 1), "w", FIRST, "o/r", i, 201, 1));
            }
            log.flush();
        }
        var effort = EventLogReader.read(file).of("w", FIRST);
        assertEquals(EventLogReader.MAX_KEPT, effort.branches().size());
        assertEquals("b" + EventLogReader.MAX_KEPT, effort.branches().getLast().name(), "the latest push is shown");
        assertEquals(EventLogReader.MAX_KEPT, effort.pullRequests().getLast().number());
        assertEquals("b1", effort.branches().getFirst().name(), "the oldest went");
    }

    @Test
    void onlyAPullRequestOfGitHubsShapeLinksToItsPage() {
        assertEquals("https://github.com/Sanne/incus-spawn/pull/2287",
                new EventLogReader.PullRequest("Sanne/incus-spawn", 2287).url());
        for (var repo : List.of("o/r/../x", "o/..", "o/.", "o r/x", "o/r?x", "\u001b]8;;x/r", "o", "o.x/r")) {
            assertNull(new EventLogReader.PullRequest(repo, 1).url(), repo);
        }
        assertNull(new EventLogReader.PullRequest("o/r", 0).url(), "no number");
        assertNull(new EventLogReader.PullRequest("o/r", 1L << 40).url(), "beyond GitHub's numbers");
    }

    /** The instance line (#2427): once per incarnation before its first model line, again for a new incarnation or a changed purpose. */
    @Test
    void anInstanceLineComesOnceBeforeItsFirstCallAndTheLastOneWins() throws Exception {
        var file = dir.resolve("events.jsonl");
        var now = new java.util.concurrent.atomic.AtomicLong(day(1, 10).toEpochMilli());
        var purpose = new java.util.concurrent.atomic.AtomicReference<>("#1 implement");
        var incarnation = new java.util.concurrent.atomic.AtomicReference<>(FIRST);
        var activity = new ApiActivity(now::get);
        activity.describeWith(name -> new InstanceRegistry.Description(incarnation.get(), "tpl", purpose.get(), "42-7"));
        try (var log = new EventLog(file)) {
            activity.recordTo(log);
            for (int i = 0; i < 3; i++) activity.begin("w", FIRST, 0).to("api.anthropic.com").end();
            purpose.set("#1 review");
            activity.begin("w", FIRST, 0).to("api.anthropic.com").end();
            // The name branched again: its listing now describes another incarnation than the caller's
            incarnation.set(AGAIN);
            activity.begin("w", FIRST, 0).to("api.anthropic.com").end();
            activity.begin("w", AGAIN, 0).to("api.anthropic.com").end();
            log.flush();
        }
        var kinds = Files.readAllLines(file).stream().map(l -> l.contains("\"kind\":\"instance\"") ? "i" : "m")
                .collect(java.util.stream.Collectors.joining());
        assertEquals("immmimmim", kinds);
        var read = EventLogReader.read(file);
        var first = read.described("w", FIRST);
        assertEquals("#1 review", first.purpose(), "the last line wins");
        assertEquals("tpl", first.template());
        assertEquals("42-7", first.session());
        assertEquals(5, read.of("w", FIRST).modelCalls(), "an instance line is no model call");
        assertNotNull(read.described("w", AGAIN));
        assertNull(read.described("gone", "2020-01-01T00:00:00Z"));
        assertEquals(day(1, 10), read.of("w", FIRST).first());
    }
}
