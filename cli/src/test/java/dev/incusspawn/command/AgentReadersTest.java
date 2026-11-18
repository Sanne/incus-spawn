package dev.incusspawn.command;

import dev.incusspawn.command.AgentSessions.Session;
import dev.incusspawn.command.AgentSessions.Summary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codex's, Pi's and opencode's sessions read as the overview reads Claude Code's (#2241). The
 * fixtures are what the agents themselves wrote, Codex 0.169.0, Pi 1.5.2 and opencode 1.19.5,
 * each run against a stand-in model that answered "## Done\nFixed the flaky test; PR #12 is
 * open."; only Codex's 21 KB base instructions are cut from its first line.
 *
 * <p>The scripts run here as the guest runs them, so the tests are Linux-only, as
 * {@link AgentSessionsTest}'s are; opencode's also needs {@code sqlite3}, which CI installs.
 */
@EnabledOnOs(value = OS.LINUX, disabledReason = "the script is a Linux guest's, with GNU find, tac and base64")
class AgentReadersTest {

    private static final String DONE = "## Done\nFixed the flaky test; PR #12 is open.";

    private static String fixture(String name) {
        try (var in = AgentReadersTest.class.getResourceAsStream("/agent-sessions/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void codexsRolloutGivesThePersonsAskNotItsOwnContext(@TempDir Path home) throws Exception {
        var day = Files.createDirectories(home.resolve(".codex/sessions/2026/11/16"));
        Files.writeString(day.resolve("rollout-2026-11-16T14-29-20-01a1e4c3.jsonl"), fixture("codex-rollout.jsonl"));
        // A repository's AGENTS.md longer than a candidate's bound, in a rollout that names no kinds
        var older = day.resolve("rollout-2026-11-16T10-00-00-01a1e4c0.jsonl");
        Files.writeString(older, String.join("\n",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\","
                        + "\"text\":\"# AGENTS.md instructions for /app\\n\\n<INSTRUCTIONS>" + "x".repeat(JsonlSessions.ASK_BYTES) + "\"}]}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Bump the parent\"}],"
                        + "\"internal_chat_message_metadata_passthrough\":{\"content_item_kinds\":[\"user.text\",\"user.image\"]}}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Bumped.\"}]}}",
                // What a turn interrupted after this one leaves behind: no newer message, an older last_agent_message
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"last_agent_message\":\"An older report\"}}") + "\n");
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-11-16T10:00:00Z")));
        Files.writeString(home.resolve(".codex/sessions/stray.jsonl"), "");

        var sessions = AgentSessionsTest.run(home).sessions();

        assertEquals(AgentSessions.State.READ, sessions.state(), sessions.why());
        assertEquals(2, sessions.total(), "only the dated rollouts are sessions");
        var newest = sessions.sessions().getFirst();
        assertEquals("codex", newest.agent());
        assertEquals("Port the parser", newest.ask(), "not the AGENTS.md or environment context Codex sends as the user");
        assertEquals(DONE, newest.closing());
        var bumped = sessions.sessions().get(1);
        assertEquals("Bump the parent", bumped.ask(), "a long AGENTS.md is not taken for the ask, nor hides it, and a screenshot beside the words is the person's");
        assertEquals("Bumped.", bumped.closing());
        assertTrue(sessions.otherAgents().isEmpty());
    }

    @Test
    void pisSessionGivesItsAskClosingAndDirectory(@TempDir Path home) throws Exception {
        var dir = Files.createDirectories(home.resolve(".pi/agent/sessions/--tmp-h-pi-app--"));
        Files.writeString(dir.resolve("2026-11-16T14-25-26-142Z_01a1e4bf.jsonl"), fixture("pi-session.jsonl")
                // A tool call with no text after the report is not the closing message
                + "{\"type\":\"message\",\"id\":\"x\",\"parentId\":\"ececd061\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"toolCall\",\"name\":\"bash\"}],\"stopReason\":\"toolUse\"}}\n");

        var sessions = AgentSessionsTest.run(home).sessions();

        assertEquals(AgentSessions.State.READ, sessions.state(), sessions.why());
        assertEquals(1, sessions.total());
        var session = sessions.sessions().getFirst();
        assertEquals("pi", session.agent());
        assertEquals("Fix the flaky test", session.ask(), "the system prompt is not the person's");
        assertEquals(DONE, session.closing());
        assertEquals("/tmp/h-pi/app", session.cwd(), "from the session's header");
    }

    @Test
    void opencodesDatabaseGivesItsTopLevelSessions(@TempDir Path home) throws Exception {
        var db = opencodeDb(home, fixture("opencode.sql") + """
                -- A subagent's session, newer than both: not a session of the person's
                INSERT INTO session VALUES('ses_child','global',NULL,'ses_e1b3cf965ffeecSPgR1UnAMh9b','s','/tmp/h-oc/app','','t','1.19.5',NULL,0,0,0,NULL,NULL,0.0,0,0,0,0,0,NULL,NULL,'build',NULL,1794839400000,1794839400000,NULL,NULL);
                INSERT INTO message VALUES('msg_c','ses_child',1794839400000,1794839400000,'{"role":"assistant"}');
                INSERT INTO part VALUES('prt_c','msg_c','ses_child',1794839400000,1794839400000,'{"type":"text","text":"a subagent''s report"}');
                -- A compaction's summary after the newest report, and a part opencode made up before the first ask
                INSERT INTO message VALUES('msg_zsum','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839399000,1794839399000,'{"role":"assistant","summary":true}');
                INSERT INTO part VALUES('prt_zsum','msg_zsum','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839399000,1794839399000,'{"type":"text","text":"Summary of the conversation"}');
                INSERT INTO message VALUES('msg_0syn','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839300000,1794839300000,'{"role":"user"}');
                INSERT INTO part VALUES('prt_0syn','msg_0syn','ses_e1b3cf965ffeecSPgR1UnAMh9b',1794839300000,1794839300000,'{"type":"text","text":"Called the read tool","synthetic":true}');
                """);
        assertTrue(Files.exists(db));

        var sessions = AgentSessionsTest.run(home).sessions();

        assertEquals(AgentSessions.State.READ, sessions.state(), sessions.why());
        assertEquals(2, sessions.total(), "a subagent's session is not counted");
        assertEquals(List.of("Port the parser", "\"Fix the flaky test\""), sessions.sessions().stream().map(Session::ask).toList(),
                "newest first; a made-up part is not the ask");
        assertEquals(List.of(DONE, DONE), sessions.sessions().stream().map(Session::closing).toList(),
                "a compaction's summary is not the closing message");
        var newest = sessions.sessions().getFirst();
        assertEquals("opencode", newest.agent());
        assertEquals("/tmp/h-oc/app", newest.cwd());
        assertEquals(Instant.ofEpochSecond(1794839349), newest.modified());
        assertTrue(sessions.otherAgents().isEmpty());
    }

    @Test
    void opencodesSessionsThatCannotBeReadAreNamedAndTheOthersStillShow(@TempDir Path home) throws Exception {
        var share = Files.createDirectories(home.resolve(".local/share/opencode"));
        Files.writeString(share.resolve("opencode.db"), "not a database");
        var dir = Files.createDirectories(home.resolve(".pi/agent/sessions/--app--"));
        Files.writeString(dir.resolve("s.jsonl"), fixture("pi-session.jsonl"));

        var read = AgentSessionsTest.run(home);

        assertEquals(AgentSessions.State.READ, read.sessions().state(), read.sessions().why());
        assertEquals(List.of("opencode"), read.sessions().otherAgents());
        assertEquals(1, read.sessions().sessions().size(), "Pi's session still shows");
        assertTrue(Summary.of(read.sessions(), read.work(), false).cell().endsWith(" (opencode not read)"));
    }

    @Test
    void filesNoReaderKnowsAreSessionsNotReadNeverNone(@TempDir Path home) throws Exception {
        // Where a Pi with another setting, or another Codex, kept its sessions: not the layout read here
        Files.writeString(Files.createDirectories(home.resolve(".pi/agent/sessions")).resolve("s.jsonl"), fixture("pi-session.jsonl"));
        Files.writeString(Files.createDirectories(home.resolve(".codex/sessions/2026")).resolve("rollout-x.jsonl"), fixture("codex-rollout.jsonl"));
        // Claude Code's directory holds more than sessions, so what it does not know there says nothing
        Files.writeString(Files.createDirectories(home.resolve(".claude/projects/p/memory")).resolve("MEMORY.md"), "notes");

        var read = AgentSessionsTest.run(home);

        assertEquals(AgentSessions.State.READ, read.sessions().state(), read.sessions().why());
        assertEquals(0, read.sessions().total());
        assertEquals(List.of("codex", "pi"), read.sessions().otherAgents());
        assertNull(Summary.of(read.sessions(), read.work(), false).from(), "never untouched");
        Files.delete(home.resolve(".pi/agent/sessions/s.jsonl"));
        Files.delete(home.resolve(".codex/sessions/2026/rollout-x.jsonl"));
        assertEquals("untouched", AgentSessionsTest.run(home).summary(false).from(), "empty directories say nothing");
    }

    /**
     * Gemini CLI, Copilot and Bob are coding agents no reader reads (#2290): their session files
     * name them as not read, so an instance only they worked in is never {@code untouched}. What
     * isx itself writes into their directories at build time says nothing.
     */
    @Test
    void agentsNoReaderReadsAreNamedWhenTheirSessionsAreThere(@TempDir Path home) throws Exception {
        // What a build writes: settings, an extension, a policy, an MCP configuration
        Files.writeString(Files.createDirectories(home.resolve(".gemini/extensions/isx")).resolve("GEMINI.md"), "notes");
        Files.writeString(Files.createDirectories(home.resolve(".gemini/policies")).resolve("isx-auto-approve.toml"), "");
        Files.writeString(home.resolve(".gemini/settings.json"), "{}");
        Files.writeString(Files.createDirectories(home.resolve(".copilot")).resolve("mcp-config.json"), "{}");
        Files.createDirectories(home.resolve(".copilot/session-state"));
        Files.writeString(Files.createDirectories(home.resolve(".bob/settings")).resolve("settings.json"), "{}");
        assertEquals("untouched", AgentSessionsTest.run(home).summary(false).from(), "what isx wrote at build is no session");

        Files.writeString(Files.createDirectories(home.resolve(".gemini/tmp/app/chats")).resolve("session-2026-11-18T10-00-1a2b3c4d.jsonl"), "{}\n");
        Files.writeString(home.resolve(".copilot/session-state/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jsonl"), "{}\n");
        Files.writeString(Files.createDirectories(home.resolve(".bob/db")).resolve("bob.db"), "not a database");

        var read = AgentSessionsTest.run(home);

        assertEquals(AgentSessions.State.READ, read.sessions().state(), read.sessions().why());
        assertEquals(List.of("gemini-cli", "copilot", "bob"), read.sessions().otherAgents());
        var summary = Summary.of(read.sessions(), read.work(), false);
        assertNull(summary.from(), "never untouched");
        assertEquals("sessions of gemini-cli, copilot, bob not read", summary.cell());
    }

    /** Bob's database read by its own tasks: none is no session, and only then. */
    @Test
    void bobsDatabaseWithoutTasksIsNoSession(@TempDir Path home) throws Exception {
        var db = Files.createDirectories(home.resolve(".bob/db")).resolve("bob.db");
        sqlite(db, "CREATE TABLE tasks (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, parent_id TEXT REFERENCES tasks(id));");
        assertEquals("untouched", AgentSessionsTest.run(home).summary(false).from());

        sqlite(db, "INSERT INTO tasks VALUES ('t1', 'file:/app', NULL);");
        assertEquals(List.of("bob"), AgentSessionsTest.run(home).sessions().otherAgents());
    }

    @Test
    void aReaderThatFailedIsNamedAndNeverBlanksTheOthers() {
        var work = WorkState.HEADER + "\nend\n";
        // The sections of the agents found but not read come first, and found nothing here
        var unread = UnreadSessions.ALL.stream().map(u -> u.header() + "\nend\n").reduce("", String::concat);
        var claude = ClaudeSessions.HEADER + "\ntotal 0\nend\n";
        var codex = CodexSessions.HEADER + "\ntotal x\nend\n";
        var pi = PiSessions.HEADER + "\ntotal 0\nend\n";
        var opencode = OpencodeSessions.HEADER + "\ntotal 0\nend\n";

        var oneFailed = GuestRead.parse(0, work + unread + claude + codex + pi + opencode, false).sessions();
        assertEquals(AgentSessions.State.READ, oneFailed.state());
        assertEquals(List.of("codex"), oneFailed.otherAgents());
        assertNull(Summary.of(oneFailed, WorkState.parse(0, work, false), false).from(), "never untouched");

        var cut = GuestRead.parse(0, work + unread + claude + codex.replace("total x", "total 0") + pi + OpencodeSessions.HEADER + "\ntotal 1\n", true);
        assertEquals(List.of("opencode"), cut.sessions().otherAgents(), "the bound cut opencode's section alone");

        var all = GuestRead.parse(0, work + unread + claude.replace("total 0", "total x"), false).sessions();
        assertEquals(AgentSessions.State.UNKNOWN, all.state(), "no reader answered: the agents found but not read are no reader");

        var untouched = GuestRead.parse(0, work + unread + claude + codex.replace("total x", "total 0") + pi + opencode, false);
        assertEquals("untouched", Summary.of(untouched.sessions(), untouched.work(), false).from());
    }

    @Test
    void theNewestSessionsOfAllAgentsAreKeptAndAllCounted() {
        var reads = new ArrayList<AgentSessions>();
        for (var agent : List.of("claude", "codex")) {
            var sessions = new ArrayList<Session>();
            for (int i = 0; i < JsonlSessions.MAX_SESSIONS; i++) {
                sessions.add(new Session(agent, agent + i, Instant.ofEpochSecond(agent.equals("codex") ? 2 * i + 1 : 2 * i), null, null, "r", true));
            }
            reads.add(new AgentSessions(AgentSessions.State.READ, 10, sessions, List.of(), null));
        }

        var merged = AgentSessions.merge(List.of("claude", "codex"), reads);

        assertEquals(20, merged.total());
        assertEquals(JsonlSessions.MAX_SESSIONS, merged.sessions().size(), "what a merge is given stays bounded");
        assertEquals("codex7", merged.sessions().getFirst().file());
    }

    @Test
    void opencodesAnswerIsReadOnlyInItsShape() {
        var reader = new OpencodeSessions();
        for (var bad : List.of("total 1\nsession 1 41 41\nask 41", "total 1\nsession 1 41 41\nclose 41\nask 41",
                "total 1\nsession 1 41 41\nask 4\nclose 41", "total 0\nsession 1 41 41\nask \nclose ", "total 1\nhello\nask \nclose ")) {
            var section = (OpencodeSessions.HEADER + "\n" + bad + "\nend").lines().toList();
            assertEquals(AgentSessions.State.UNKNOWN, reader.parse(section).state(), bad);
        }
        var none = reader.parse(List.of(OpencodeSessions.HEADER, "total 1", "session 1 41 ", "ask ", "close ", "end"));
        assertEquals(AgentSessions.State.READ, none.state());
        assertNull(none.sessions().getFirst().closing(), "a session with no report yet");
    }

    /** opencode's database as 1.19.5 made it, plus {@code sql}, in {@code home}. */
    private static Path opencodeDb(Path home, String sql) throws Exception {
        var share = Files.createDirectories(home.resolve(".local/share/opencode"));
        return sqlite(share.resolve("opencode.db"), sql);
    }

    /** {@code sql} run on the database {@code db}, which it creates when there is none. */
    private static Path sqlite(Path db, String sql) throws Exception {
        Process p;
        try {
            p = new ProcessBuilder("sqlite3", db.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort("sqlite3 is not installed");
            throw e;
        }
        try (var in = p.getOutputStream()) {
            in.write(sql.getBytes(StandardCharsets.UTF_8));
        }
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        return db;
    }
}
