package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.EventLogReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx overview} (#2241): the instances beside what the proxy's event log says they did.
 * {@code plain} and {@code json} are a contract with scripts, so these are golden outputs.
 */
class OverviewCommandTest {

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(2);
    private static final String DEV1 = "2026-11-01T08:00:00.123456789Z";

    @TempDir
    Path dir;

    private final FakeIncusDaemon daemon = new FakeIncusDaemon()
            .container("tpl-java", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
            .instance("dev-1", "container", "Running", Map.of(
                    Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java",
                    Metadata.PURPOSE, "fix #12"))
            .instance("dev-2", "container", "Stopped", Map.of(
                    Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java"))
            .createdAt("dev-1", DEV1)
            .createdAt("dev-2", "2026-11-02T08:00:00Z")
            .lastUsedAt("dev-1", "2026-11-03T12:00:00Z")
            .lastUsedAt("dev-2", "1970-01-01T00:00:00Z")
            .execOutput(WorkState.HEADER, WORK);

    /** What dev-1's guest answers: one clone with work in it, one clean, and one Claude Code session, and none of another agent. */
    private static final String WORK = WorkState.HEADER + "\nrepo 3 1 0 app\nrepo 0 0 0 lib\nend\n"
            + UnreadSessions.ALL.stream().map(u -> u.header() + "\nend\n").reduce("", String::concat)
            + ClaudeSessions.HEADER + "\ntotal 1\nsession 1793700000 cC9zLmpzb25s\n"
            + "close eyJ0eXBlIjoiYXNzaXN0YW50IiwiaXNTaWRlY2hhaW4iOmZhbHNlLCJtZXNzYWdlIjp7ImNvbnRlbnQiOlt7InR5cGUiOiJ0ZXh0IiwidGV4dCI6IkZpeGVkIHRoZSBwYXJzZXIuXG5QUiAjMTIgaXMgb3Blbi4ifV19fQ==\nend\n"
            + CodexSessions.HEADER + "\ntotal 0\nend\n" + PiSessions.HEADER + "\ntotal 0\nend\n" + AntigravitySessions.HEADER + "\ntotal 0\nend\n"
            + GeminiSessions.HEADER + "\ntotal 0\nend\n" + CopilotSessions.HEADER + "\ntotal 0\nend\n" + BobSessions.HEADER + "\ntotal 0\nend\n"
            + OpencodeSessions.HEADER + "\ntotal 0\nend\n";

    /** The tokens GitHub was asked with, and what it was asked about. */
    private final java.util.List<String> asked = new java.util.ArrayList<>();

    private EventLogReader.Log log() throws Exception {
        var file = dir.resolve("events.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"t\":\"2026-11-01T09:00:00Z\",\"kind\":\"model\",\"instance\":\"dev-1\",\"incarnation\":\"" + DEV1 + "\","
                        + "\"domain\":\"api.anthropic.com\",\"model\":\"m\",\"status\":200,\"ms\":1,\"input_tokens\":1200,"
                        + "\"output_tokens\":300,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}",
                "{\"t\":\"2026-11-03T10:00:00Z\",\"kind\":\"github\",\"op\":\"push\",\"instance\":\"dev-1\",\"incarnation\":\"" + DEV1 + "\","
                        + "\"repo\":\"o/r\",\"status\":200,\"ms\":1,\"refs\":[\"refs/heads/fix\\u001b[31m\"],\"deleted_refs\":[]}",
                "{\"t\":\"2026-11-03T10:01:00Z\",\"kind\":\"github\",\"op\":\"pull_request\",\"instance\":\"dev-1\",\"incarnation\":\"" + DEV1 + "\","
                        + "\"repo\":\"o/r\",\"number\":12,\"status\":201,\"ms\":1}",
                "{\"t\":\"2026-11-01T07:00:00Z\",\"kind\":\"model\",\"instance\":\"dev-2\",\"incarnation\":\"2026-10-01T00:00:00Z\","
                        + "\"input_tokens\":99}") + "\n");
        return EventLogReader.read(file);
    }

    private String overview(String format) throws Exception {
        var cmd = new OverviewCommand();
        cmd.format = format;
        var read = log();
        cmd.eventLog = () -> read;
        cmd.config = () -> {
            var config = new dev.incusspawn.config.SpawnConfig();
            config.getGithub().setToken("host-token");
            return config;
        };
        cmd.github = (token, pulls) -> {
            asked.add(token + " " + pulls);
            return Map.of(new EventLogReader.PullRequest("o/r", 12), PullRequestStates.State.MERGED);
        };
        cmd.model = (config, target, system, question) -> fail("one session needs no model call");
        cmd.cache = () -> new SessionMerge.Cache(dir.resolve("summaries"));
        cmd.kept = () -> new KeptReads(dir.resolve("reads"));
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            cmd.print(daemon.client(), out, ZONE);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plainIsOneRecordPerInstanceWithWhatTheLogSaw() throws Exception {
        assertEquals("""
                dev-1\trunning\tfix #12\t2026-11-03T14:00:00+02:00\t1\t1500\t1200\t300\t0\t0\t2026-11-01T11:00:00+02:00\t2026-11-03T12:01:00+02:00\to/r:fix [31m\to/r#12\tmerged\tunsaved\t3\t1\t0\tapp,lib\tapp\t1\tFixed the parser.\tsession\t2026-11-03T12:00:00+02:00\t-\tFixed the parser. PR #12 is open.\t-\t-\t-\t-\t-\t-\t-
                dev-2\tstopped\t-\t-\t0\t0\t0\t0\t0\t0\t-\t-\t-\t-\t-\tnot_read\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-
                """, overview("plain"), "dev-2's line is an earlier dev-2's, it never started, and a stopped instance is not read; dev-1's one session says what it did");
        assertEquals(java.util.List.of("host-token [o/r#12]"), asked, "one request, with the instance's account");
        assertEquals(1, daemon.execs().size(), "only the running instance is read");
    }

    @Test
    void aStoppedInstanceShowsItsLastMergedSummaryWithItsAge() throws Exception {
        new SessionMerge.Cache(dir.resolve("summaries")).put("dev-2", "2026-11-02T08:00:00Z", "fp", SessionMerge.questionHash("q"),
                new SessionMerge.Merged("Wrote the docs", "Docs are written.", "All pushed.", "nothing reported", 2, 3, 1,
                        java.time.Instant.parse("2026-11-02T10:00:00Z")), f -> fail(f));
        var plain = overview("plain").lines().toList();
        assertTrue(plain.get(1).endsWith("\t-\tWrote the docs\tmerged\t-\t-\t-\tDocs are written.\tAll pushed.\tnothing reported\t2\t2026-11-02T12:00:00+02:00\t-\t-"),
                plain.get(1));
        var table = overview(null);
        assertTrue(table.contains("merged from 2 of 3 sessions ") && table.contains(": Wrote the docs"), table);
    }

    @Test
    void aStoppedInstanceShowsTheReadingKeptWhenItLastRan() throws Exception {
        new KeptReads(dir.resolve("reads")).put("dev-2", "2026-11-02T08:00:00Z",
                new GuestRead.Answer(0, WORK, false, java.time.Instant.parse("2026-11-02T10:00:00Z")));

        var plain = overview("plain").lines().toList();
        assertTrue(plain.get(1).contains("\tunsaved\t3\t1\t0\tapp,lib\tapp\t1\tFixed the parser.\t"), plain.get(1));
        assertTrue(plain.get(1).endsWith("\t2026-11-02T12:00:00+02:00\tfalse"), "when it was read, and not before a later start: " + plain.get(1));
        assertEquals(1, daemon.execs().size(), "the stopped instance is not started or read");
        var table = overview(null);
        assertTrue(table.contains("3 uncommitted, 1 unpushed, read "), table);
    }

    @Test
    void aKeptReadingOlderThanTheLastStartIsSaidToBe() throws Exception {
        daemon.lastUsedAt("dev-2", "2026-11-03T08:00:00Z");
        new KeptReads(dir.resolve("reads")).put("dev-2", "2026-11-02T08:00:00Z",
                new GuestRead.Answer(0, WORK, false, java.time.Instant.parse("2026-11-02T10:00:00Z")));

        assertTrue(overview("plain").lines().toList().get(1).endsWith("\ttrue"));
        assertTrue(overview(null).contains(" before its last start"));
    }

    @Test
    void aKeptReadingFromBeforeTheLastStartIsNeverUntouched() throws Exception {
        var clean = WORK.replace("repo 3 1 0 app\nrepo 0 0 0 lib\n", "repo 0 0 0 app\n")
                .replaceAll("(?s)" + java.util.regex.Pattern.quote(ClaudeSessions.HEADER) + "\ntotal 1\n.*?\nend\n",
                        java.util.regex.Matcher.quoteReplacement(ClaudeSessions.HEADER + "\ntotal 0\nend\n"));
        var reads = new KeptReads(dir.resolve("reads"));
        reads.put("dev-2", "2026-11-02T08:00:00Z", new GuestRead.Answer(0, clean, false, java.time.Instant.parse("2026-11-02T10:00:00Z")));
        assertTrue(overview("plain").lines().toList().get(1).contains("\tclean\t0\t0\t0\tapp\t-\t0\tuntouched\t"),
                "read after its last start, nothing says it was used");

        daemon.lastUsedAt("dev-2", "2026-11-03T08:00:00Z");
        var stale = overview("plain").lines().toList().get(1);
        assertTrue(stale.contains("\tclean\t") && !stale.contains("untouched"),
                "its last run may have done work the reading never saw: " + stale);
    }

    @Test
    void aRunningInstancesReadIsKeptAndADestroyedOnesIsDropped() throws Exception {
        var reads = new KeptReads(dir.resolve("reads"));
        reads.put("gone", "2026-10-01T00:00:00Z", new GuestRead.Answer(0, WORK, false, java.time.Instant.parse("2026-10-02T00:00:00Z")));

        overview("plain");

        assertEquals(WorkState.State.UNSAVED, reads.get("dev-1", DEV1).work().state(), "kept for when it is stopped");
        assertNull(reads.get("gone", "2026-10-01T00:00:00Z"), "an instance the listing no longer holds was destroyed");
    }

    @Test
    void aReadThatFailsKeepsTheEarlierReading() throws Exception {
        var reads = new KeptReads(dir.resolve("reads"));
        var before = java.time.Instant.parse("2026-11-03T13:00:00Z");
        reads.put("dev-1", DEV1, new GuestRead.Answer(0, WorkState.HEADER + "\nrepo 0 0 0 app\nend\n", false, before));
        daemon.execExit(WorkState.HEADER, 137);

        var plain = overview("plain").lines().toList();

        assertTrue(plain.get(0).contains("\tunknown\t"), "the failed read is shown as it is: " + plain.get(0));
        var kept = reads.get("dev-1", DEV1);
        assertEquals(WorkState.State.CLEAN, kept.work().state(), "a read killed at its limit never replaces a good one");
        assertEquals(before, kept.at());
    }

    @Test
    void jsonSaysWhatEachGuestHoldsAndWhatBecameOfItsPullRequests() throws Exception {
        var json = overview("json");
        assertTrue(json.contains("\"pull_request_states\" : [ \"merged\" ]"), json);
        assertTrue(json.contains("\"work_state\" : \"unsaved\""), json);
        assertTrue(json.contains("\"uncommitted_changes\" : 3"), json);
        assertTrue(json.contains("\"work_state\" : \"not_read\""), json);
        assertTrue(json.contains("\"unpushed_commits\" : null"), json);
    }

    @Test
    void anInstancePinnedToAMissingAccountIsNotAskedAbout() throws Exception {
        daemon.instance("dev-3", "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.PARENT, "tpl-java", Metadata.accountKey("github"), "gone"));
        var instance = InstanceListing.collectEntries(daemon.client().listJsonConfig()).stream()
                .filter(i -> i.name().equals("dev-3")).findFirst().orElseThrow();
        var config = new dev.incusspawn.config.SpawnConfig();
        config.getGithub().setToken("host-token");
        assertNull(PullRequestStates.token(config, instance), "fails closed: never the default account");
    }

    @Test
    void jsonKeepsTheGuestsTextExactAndEscaped() throws Exception {
        var json = overview("json");
        assertTrue(json.contains("\"pushed_branches\" : [ \"o/r:fix\\u001B[31m\" ]"), json);
        assertTrue(json.contains("\"pull_requests\" : [ \"o/r#12\" ]"), json);
        assertFalse(json.contains("\u001b"), "no control character raw");
    }

    @Test
    void theTableShowsTokensActiveDaysAndGitHubOnOneLineEach() throws Exception {
        var table = overview(null);
        var dev1 = table.lines().filter(l -> l.contains("dev-1")).findFirst().orElseThrow();
        assertTrue(dev1.contains("1.5k"), dev1);
        assertTrue(dev1.contains("Nov 1–Nov 3"), dev1);
        assertTrue(dev1.contains("o/r#12 (merged), pushed fix [31m"), dev1);
        assertTrue(dev1.contains("3 uncommitted, 1 unpushed"), dev1);
        assertFalse(table.contains("\u001b"));
        var dev2 = table.lines().filter(l -> l.contains("dev-2")).findFirst().orElseThrow();
        assertTrue(dev2.contains("never") && dev2.contains("not read"), dev2);
        assertFalse(table.contains("tpl-java"), "templates are not listed");
        assertTrue(table.contains("Counted from the proxy's event log since Nov 1."), table);
    }

    @Test
    void cellsAreCompactAndBounded() {
        var effort = new EventLogReader.Effort(1, 2_500_000, 0, 0, 0, null, null, java.util.List.of(), java.util.List.of());
        assertEquals("2.5M", OverviewCommand.tokensCell(effort));
        assertEquals("-", OverviewCommand.tokensCell(EventLogReader.Effort.NONE));
        var nearMillion = new EventLogReader.Effort(1, 999_960, 0, 0, 0, null, null, java.util.List.of(), java.util.List.of());
        assertEquals("1.0M", OverviewCommand.tokensCell(nearMillion), "never 1000.0k");
        var many = new EventLogReader.Effort(0, 0, 0, 0, 0, null, null,
                java.util.stream.IntStream.range(0, 20).mapToObj(i -> new EventLogReader.Branch("o/r", "branch-" + i)).toList(),
                java.util.List.of());
        var cell = OverviewCommand.githubCell(many, java.util.List.of(), 40);
        assertEquals("pushed branch-0 branch-1 branch-2 +17", cell, "each branch whole, the rest counted");
        var pulls = new EventLogReader.Effort(0, 0, 0, 0, 0, null, null, java.util.List.of(),
                java.util.List.of(new EventLogReader.PullRequest("Sanne/incus-spawn", 2287),
                        new EventLogReader.PullRequest("Sanne/incus-spawn", 2289)));
        var merged = java.util.List.of(PullRequestStates.State.MERGED, PullRequestStates.State.OPEN);
        assertEquals("Sanne/incus-spawn#2287 (merged) +1", OverviewCommand.githubCell(pulls, merged, 40));
        assertEquals("Sanne/incus-spawn#2287 +1", OverviewCommand.githubCell(pulls, merged, 30),
                "the state gives way before the repository and number");
        assertEquals("Sanne/incus-spawn… +1", OverviewCommand.githubCell(pulls, merged, 21),
                "only a first reference wider than the whole cell is cut");
        var wide = new EventLogReader.Effort(0, 0, 0, 0, 0, null, null,
                java.util.List.of(new EventLogReader.Branch("o/r", "修复修复修复修复"), new EventLogReader.Branch("o/r", "x")),
                java.util.List.of());
        assertEquals("pushed 修复修复修复修复 x", OverviewCommand.githubCell(wide, java.util.List.of(), 26));
        assertEquals("pushed 修复修复修复… x", OverviewCommand.githubCell(wide, java.util.List.of(), 24),
                "measured in terminal cells, as the table draws it");
        assertEquals("Sanne/incus-spawn#2287 (merged) Sanne/incus-spawn#2289 (open)",
                OverviewCommand.githubCell(pulls, merged, 80));
    }

    @Test
    void anEmptyLogSaysSo() {
        assertEquals("The proxy's event log has no lines yet, so no tokens, pushes or pull requests are shown.",
                OverviewCommand.coverage(EventLogReader.Log.EMPTY, ZONE));
    }
}
