package dev.incusspawn.command;

import dev.incusspawn.command.AgentSessions.Summary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the overview says was done in an instance (#2241), from its agents' own closing messages.
 * The text is the guest's: it is scrubbed, bounded and checked field by field, and it never makes
 * an instance look {@code untouched} when the read could not say so.
 *
 * <p>The tests that run the script over sessions are Linux-only, where the script runs: it is a
 * guest's, and every isx guest is Fedora. It uses GNU {@code find -printf}, {@code tac} and
 * {@code base64 -w0}, which macOS's BSD tools lack: on a Mac host BSD {@code find} refuses
 * {@code -printf}, so the script answers {@code total x} and the sessions read as unknown. The
 * parsing and the summary are tested on every host.
 */
class AgentSessionsTest {

    private static final String GNU_GUEST = "the script is a Linux guest's, with GNU find, tac and base64";

    private static final String USER = """
            {"parentUuid":null,"isSidechain":false,"type":"user","message":{"role":"user","content":"%s"},"cwd":"/home/agentuser/app"}""";
    private static final String TOOL_RESULT = """
            {"isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":[{"type":"text","text":"tool said"}]}]}}""";
    private static final String ASSISTANT = """
            {"isSidechain":%s,"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"%s"}]},"cwd":"/home/agentuser/app"}""";
    private static final String TOOL_USE = """
            {"isSidechain":false,"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","name":"Bash"}]}}""";
    /** Not a message at all, yet it holds both strings the script selects on. */
    private static final String NOISE = """
            {"type":"api-request-blob","note":"\\"type\\":\\"assistant\\"","content":[{"type":"text","text":"a request, not the agent"}]}""";

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = GNU_GUEST)
    void theScriptReadsTheNewestSessionsClosingMessagesAndWhatWasAsked(@TempDir Path home) throws Exception {
        var projects = Files.createDirectories(home.resolve(".claude/projects/-home-agentuser-app"));
        var older = projects.resolve("a.jsonl");
        Files.writeString(older, String.join("\n",
                "{\"type\":\"queue-operation\"}",
                USER.formatted("<command-name>/clear</command-name>"),
                "{\"type\":\"user\",\"isCompactSummary\":true,\"message\":{\"content\":\"This session is being continued\"}}",
                USER.formatted("Fix the flaky test"),
                TOOL_USE,
                ASSISTANT.formatted("false", "## Done\\n**Fixed** the flaky test; PR #12 is open.\\nPending: review."),
                TOOL_RESULT,
                ASSISTANT.formatted("true", "a subagent's report"),
                NOISE) + "\n");
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-11-10T10:00:00Z")));
        var newer = projects.resolve("b.jsonl");
        // A closing message that leaks a token, with an escape sequence: scrubbed, and kept on one line when shown
        // Put together here, so no secret scanner takes the fixture for a real token
        var token = "ghp_" + "0123456789abcdefghij" + "ABCDEFGHIJ012345";
        Files.writeString(newer, String.join("\n",
                USER.formatted("Port the parser"),
                ASSISTANT.formatted("false", "\\u001b[31mPorted\\u001b[0m, token " + token + " used")) + "\n");
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-11-12T10:00:00Z")));
        // A file where Codex keeps no rollout is not a session, and Codex may keep them another way: never untouched
        Files.createDirectories(home.resolve(".codex/sessions")).resolve("x.jsonl").toFile().createNewFile();
        // opencode's sessions that cannot be read: there may be some, so never untouched
        Files.createDirectories(home.resolve(".local/share/opencode/storage")).resolve("s").toFile().createNewFile();

        var read = run(home);
        var sessions = read.sessions();

        assertEquals(AgentSessions.State.READ, sessions.state(), sessions.why());
        assertEquals(2, sessions.total());
        assertEquals(List.of("codex", "opencode"), sessions.otherAgents());
        var newest = sessions.sessions().getFirst();
        assertEquals(Instant.parse("2026-11-12T10:00:00Z"), newest.modified());
        assertEquals("Port the parser", newest.ask());
        assertFalse(newest.closing().contains(token), newest.closing());
        var oldest = sessions.sessions().get(1);
        assertEquals("Fix the flaky test", oldest.ask(), "a slash command is not what the person asked");
        assertEquals("## Done\n**Fixed** the flaky test; PR #12 is open.\nPending: review.", oldest.closing(),
                "the request blob and the subagent's report are not the closing message");
        assertEquals("/home/agentuser/app", oldest.cwd());
        assertEquals("no_repos", read.work().state().field, "the work section is read beside the sessions");

        var summary = Summary.of(sessions, read.work(), false);
        assertEquals("newest_session", summary.from());
        assertTrue(summary.cell().startsWith("newest of 2: "), summary.cell());
        assertTrue(summary.cell().endsWith(" (codex, opencode not read)"), "Codex may have worked since: " + summary.cell());
        assertFalse(OverviewCommand.doneCell(summary, 200).chars().anyMatch(c -> c < 0x20), "shown on one line");
        var one = Summary.of(new AgentSessions(AgentSessions.State.READ, 1, List.of(oldest), List.of(), null), read.work(), false);
        assertEquals("Done", one.headline(), "the first line that says something, without its Markdown");
        assertEquals("session", one.from());
        assertEquals("`isx overview` reads --format", AgentSessions.firstLine("\n- **`isx overview`** reads --format\n"),
                "only Markdown's markers are taken off");
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = GNU_GUEST)
    void aClosingMessageCutAtTheBoundIsUnreadableNeverAnOlderOne(@TempDir Path home) throws Exception {
        var projects = Files.createDirectories(home.resolve(".claude/projects/p"));
        var huge = "x".repeat(ClaudeSessions.CLOSE_BYTES);
        Files.writeString(projects.resolve("s.jsonl"), String.join("\n",
                USER.formatted("Go"),
                ASSISTANT.formatted("false", "An earlier report"),
                ASSISTANT.formatted("false", huge)) + "\n");

        var sessions = run(home).sessions();

        var session = sessions.sessions().getFirst();
        assertFalse(session.closingReadable());
        assertNull(session.closing());
        assertEquals("(closing message not readable)", Summary.of(sessions, WorkState.parse(0, WorkState.HEADER + "\nend\n", false), false).cell());
    }

    @Test
    void untouchedOnlyWhenNothingSaysOtherwise(@TempDir Path home) throws Exception {
        var none = new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of(), null);
        var noRepos = WorkState.parse(0, WorkState.HEADER + "\nend\n", false);
        var unsaved = WorkState.parse(0, WorkState.HEADER + "\nrepo 1 0 0 app\nend\n", false);
        var unknown = WorkState.parse(0, WorkState.HEADER + "\n", false);
        assertEquals("untouched", Summary.of(none, noRepos, false).from());
        assertEquals("untouched", run(home).summary(false).from(), "an empty home, read for real");
        assertNull(Summary.of(none, unsaved, false).from(), "uncommitted work");
        assertNull(Summary.of(none, unknown, false).from(), "clones that could not be read");
        assertNull(Summary.of(none, noRepos, true).from(), "the event log saw it at work");
        assertNull(Summary.of(new AgentSessions(AgentSessions.State.READ, 0, List.of(), List.of("pi"), null), noRepos, false).from(),
                "another agent's sessions");
        assertNull(Summary.of(AgentSessions.unknown("x"), noRepos, false).from());
        assertNull(Summary.of(AgentSessions.NOT_READ, WorkState.NOT_READ, false).from());
        assertEquals("not read", Summary.of(AgentSessions.NOT_READ, WorkState.NOT_READ, false).cell());
    }

    @Test
    void eachSectionIsParsedOnItsOwn() {
        var work = WorkState.HEADER + "\nrepo 0 0 0 app\nend\n";
        var sessions = ClaudeSessions.HEADER + "\ntotal 0\nend\n";
        var both = GuestRead.parse(0, work + sessions, false);
        assertEquals(WorkState.State.CLEAN, both.work().state());
        assertEquals(AgentSessions.State.READ, both.sessions().state());

        var cut = GuestRead.parse(0, work + ClaudeSessions.HEADER + "\ntotal 3\nsession 1 YQ==\nclose eyJ", true);
        assertEquals(WorkState.State.CLEAN, cut.work().state(), "the bound cut the sessions, not the clones");
        assertEquals(AgentSessions.State.UNKNOWN, cut.sessions().state());

        assertEquals(AgentSessions.State.UNKNOWN, GuestRead.parse(0, work, false).sessions().state(), "no sessions section");
        assertEquals(WorkState.State.UNKNOWN, GuestRead.parse(0, WorkState.HEADER + "\nrepo 0 0 0 app\n", true).work().state());
        for (var bad : List.of("total x", "total 1\nsession 1 YQ==\nask !!", "total 1\nhello", "total 0\nsession 1 YQ==",
                "total 1\nother /etc")) {
            var read = GuestRead.parse(0, work + ClaudeSessions.HEADER + "\n" + bad + "\nend\n", false);
            assertEquals(AgentSessions.State.UNKNOWN, read.sessions().state(), bad);
            assertEquals(WorkState.State.CLEAN, read.work().state(), bad);
        }
        var b64 = Base64.getEncoder().encodeToString("not json".getBytes(StandardCharsets.UTF_8));
        var notJson = GuestRead.parse(0, work + ClaudeSessions.HEADER + "\ntotal 1\nsession 1 YQ==\nask " + b64 + "\nend\n", false);
        assertNull(notJson.sessions().sessions().getFirst().ask(), "a line that is not JSON says nothing");
    }

    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = GNU_GUEST)
    void onlyTheNewestSessionsAreReadButAllAreCounted(@TempDir Path home) throws Exception {
        var projects = Files.createDirectories(home.resolve(".claude/projects/p"));
        for (int i = 0; i < ClaudeSessions.MAX_SESSIONS + 3; i++) {
            var file = projects.resolve("s" + i + ".jsonl");
            Files.writeString(file, ASSISTANT.formatted("false", "report " + i) + "\n");
            Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(1_700_000_000L + i * 60L)));
        }
        Files.createSymbolicLink(projects.resolve("link.jsonl"), Path.of("/dev/zero"));

        var sessions = run(home).sessions();

        assertEquals(ClaudeSessions.MAX_SESSIONS + 3, sessions.total(), "a symbolic link is not a session");
        assertEquals(ClaudeSessions.MAX_SESSIONS, sessions.sessions().size());
        assertEquals("report " + (ClaudeSessions.MAX_SESSIONS + 2), sessions.sessions().getFirst().closing());
    }

    /**
     * The clones and the sessions are read at once (#2627) and still answer in their order: the
     * work section first, each reader's after it. The readers' file is gone once the read ends,
     * and with no place for it they are read one after another, to the same answer.
     */
    @Test
    @EnabledOnOs(value = OS.LINUX, disabledReason = GNU_GUEST)
    void theClonesAndTheSessionsAreReadAtOnceInTheirOrder(@TempDir Path home, @TempDir Path tmp) throws Exception {
        var git = new ProcessBuilder("git", "init", "-q", home.resolve("app").toString()).start();
        assertTrue(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0);
        var projects = Files.createDirectories(home.resolve(".claude/projects/p"));
        Files.writeString(projects.resolve("s.jsonl"), USER.formatted("Fix it") + "\n" + ASSISTANT.formatted("false", "Fixed") + "\n");

        for (var dir : List.of(tmp.toString(), tmp.resolve("absent").toString())) {
            var out = output(home, dir);
            var lines = out.lines().toList();
            assertEquals(WorkState.HEADER, lines.getFirst(), dir);
            var headers = GuestRead.READERS.stream().map(r -> lines.indexOf(r.header())).toList();
            assertEquals(headers.stream().sorted().toList(), headers, "each reader's section in its place: " + dir);
            assertTrue(lines.indexOf("end") < headers.getFirst(), "the work section comes whole before them");
            var read = GuestRead.parse(0, out, false);
            assertEquals(List.of(new WorkState.Repo("app", 0, 0, 0)), read.work().repos(), dir);
            assertEquals("Fixed", read.sessions().sessions().getFirst().closing(), dir);
            try (var left = Files.list(tmp)) {
                assertEquals(List.of(), left.toList(), "the readers' file is gone");
            }
        }
    }

    private static String output(Path home, String tmpdir) throws Exception {
        var out = new ByteArrayOutputStream();
        var process = new ProcessBuilder("sh", "-c", GuestRead.SCRIPT).directory(home.toFile());
        process.environment().put("HOME", home.toString());
        process.environment().put("TMPDIR", tmpdir);
        for (var override : List.of("CLAUDE_CONFIG_DIR", "CODEX_HOME", "PI_CODING_AGENT_DIR", "XDG_DATA_HOME", "GEMINI_CLI_HOME", "COPILOT_HOME")) {
            process.environment().remove(override);
        }
        var p = process.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        p.getInputStream().transferTo(out);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue());
        return out.toString(StandardCharsets.UTF_8);
    }

    record Read(WorkState work, AgentSessions sessions) {
        Summary summary(boolean active) {
            return Summary.of(sessions, work, active);
        }
    }

    /** The overview's whole script, run here as the guest runs it. */
    static Read run(Path home) throws Exception {
        var out = new ByteArrayOutputStream();
        var process = new ProcessBuilder("sh", "-c", GuestRead.SCRIPT).directory(home.toFile());
        process.environment().put("HOME", home.toString());
        for (var override : List.of("CLAUDE_CONFIG_DIR", "CODEX_HOME", "PI_CODING_AGENT_DIR", "XDG_DATA_HOME", "GEMINI_CLI_HOME", "COPILOT_HOME")) {
            process.environment().remove(override);
        }
        var p = process.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        p.getInputStream().transferTo(out);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        var read = GuestRead.parse(p.exitValue(), out.toString(StandardCharsets.UTF_8), false);
        return new Read(read.work(), read.sessions());
    }
}
