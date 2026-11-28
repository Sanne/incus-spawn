package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which task {@code isx shell --task} joins, from what {@link TaskScripts#joinable} printed. */
class TaskJoinTest {

    private static String finished(String id, String sid, String cwd) {
        return "task " + id + " finished 1700000000\nsid " + id + " " + sid + "\ncwd " + id + " " + cwd
                + "\nprompt " + id + " Fix the flaky test\n";
    }

    @Test
    void noTaskJoinsNothing() {
        assertInstanceOf(TaskJoin.Outcome.None.class, TaskJoin.resolve(TaskJoin.parse(""), null));
        assertInstanceOf(TaskJoin.Outcome.NotFound.class, TaskJoin.resolve(TaskJoin.parse(""), "t1-abcd"));
        assertNull(TaskJoin.hint("box", List.of()));
    }

    @Test
    void theOneTaskIsJoinedWithItsAgentsResumeInItsDirectory() {
        var entries = TaskJoin.parse(finished("t1-abcd", "sess-1", "/home/agentuser/incus-spawn"));
        var join = assertInstanceOf(TaskJoin.Outcome.Join.class, TaskJoin.resolve(entries, null));
        assertEquals("t1-abcd", join.entry().id());
        var command = join.entry().guestCommand();
        assertTrue(command.startsWith(TaskScripts.joinGuard("t1-abcd", DelegateAgent.CLAUDE, "sess-1", "/home/agentuser/incus-spawn", null)), command);
        assertTrue(command.contains("cd '/home/agentuser/incus-spawn' || isx_refuse 'its directory is gone'; "), command);
        assertTrue(command.contains(TaskScripts.CONVERSATION_LOCK + "claude --resume sess-1; rc=$?"), command);
        assertEquals("Fix the flaky test", join.entry().instruction());
        assertEquals(join, TaskJoin.resolve(entries, " t1-abcd "));
        assertEquals("A delegated task's conversation is here; to join it: isx shell box --task t1-abcd", TaskJoin.hint("box", entries));
    }

    @Test
    void aCodexTaskResumesWithCodex() {
        var entries = TaskJoin.parse("identity t2-abcd 1 codex\n" + finished("t2-abcd", "019a-thread", "/w o'k"));
        var join = assertInstanceOf(TaskJoin.Outcome.Join.class, TaskJoin.resolve(entries, null));
        assertEquals("codex", join.entry().agent());
        var command = join.entry().guestCommand();
        assertTrue(command.contains("cd '/w o'\"'\"'k' || isx_refuse"), command);
        assertTrue(command.contains(TaskScripts.CONVERSATION_LOCK + "codex resume 019a-thread; rc=$?"), command);
    }

    @Test
    void severalAreListedNeverGuessed() {
        var entries = TaskJoin.parse(finished("t1-abcd", "sess-1", "/a") + "task t2-abcd running 1700000300\nsid t2-abcd sess-2\ncwd t2-abcd /b\n");
        var several = assertInstanceOf(TaskJoin.Outcome.Several.class, TaskJoin.resolve(entries, null));
        assertEquals(List.of("t1-abcd", "t2-abcd"), several.entries().stream().map(TaskJoin.Entry::id).toList());
        assertEquals("t1-abcd  claude  finished 5m ago  Fix the flaky test",
                TaskJoin.describe(several.entries().getFirst(), Instant.ofEpochSecond(1700000300)));
        assertEquals("2 delegated tasks' conversations are here; to pick one: isx shell box --task", TaskJoin.hint("box", entries));
        assertInstanceOf(TaskJoin.Outcome.Join.class, TaskJoin.resolve(entries, "t1-abcd"));
    }

    @Test
    void aTaskMidTurnOrOfUnknownStateIsRefused() {
        var running = TaskJoin.parse("task t1-abcd running 1700000000\nsid t1-abcd sess-1\ncwd t1-abcd /a\n");
        var busy = assertInstanceOf(TaskJoin.Outcome.Busy.class, TaskJoin.resolve(running, null));
        assertEquals("running", busy.entry().state());
        var unknown = TaskJoin.parse("task t1-abcd whatever\nsid t1-abcd sess-1\ncwd t1-abcd /a\n");
        assertEquals("unknown", assertInstanceOf(TaskJoin.Outcome.Busy.class, TaskJoin.resolve(unknown, null)).entry().state());
        var lost = TaskJoin.parse("task t1-abcd lost\nsid t1-abcd sess-1\ncwd t1-abcd /a\n");
        assertInstanceOf(TaskJoin.Outcome.Join.class, TaskJoin.resolve(lost, null), "a run cut off is over");
    }

    @Test
    void aConversationSomeoneIsInIsRefused() {
        var entries = TaskJoin.parse(finished("t1-abcd", "sess-1", "/a") + "presence claude 42 /elsewhere\npresence resume 42 sess-1\n");
        var busy = assertInstanceOf(TaskJoin.Outcome.Busy.class, TaskJoin.resolve(entries, null));
        assertEquals(42L, busy.entry().attachedPid());
        assertEquals("A person is already in t1-abcd's conversation (agent pid 42, without a terminal); a second would write to it "
                + "at the same time. To end their agent and join instead: isx shell box --task t1-abcd --take",
                TaskJoin.refusal(busy, "box"));
        var onTerminal = TaskJoin.parse(finished("t1-abcd", "sess-1", "/a")
                + "presence tty 42\npresence claude 42 /elsewhere\npresence input 42 1700000000\npresence resume 42 sess-1\n").getFirst();
        assertTrue(onTerminal.attachedTty());
        assertEquals(java.time.Instant.ofEpochSecond(1700000000), onTerminal.attachedLastInputAt());
        var elsewhere = TaskJoin.parse(finished("t1-abcd", "sess-1", "/a") + "presence claude 42 /b\n");
        assertInstanceOf(TaskJoin.Outcome.Join.class, TaskJoin.resolve(elsewhere, null), "an agent on another conversation");
    }

    @Test
    void aFirstTurnWithNoSessionYetIsMidTurnNotMissing() {
        var entries = TaskJoin.parse("task t1-abcd running 1700000000\nsid t1-abcd \ncwd t1-abcd /a\n");
        assertInstanceOf(TaskJoin.Outcome.Busy.class, TaskJoin.resolve(entries, null));
        assertInstanceOf(TaskJoin.Outcome.Busy.class, TaskJoin.resolve(entries, "t1-abcd"));
    }

    @Test
    void onlyResumableConversationsCount() {
        var entries = TaskJoin.parse(finished("t1-abcd", "", "/a")                      // no session saved yet
                + finished("t2-abcd", "x; rm -rf ~", "/a")                                // not a session id
                + "identity t3-abcd 2 claude\n" + finished("t3-abcd", "sess-3", "/a")     // a version this isx cannot read
                + finished("t4-abcd", "sess-4", "")                                       // nowhere to resume in
                + finished("t5-abcd", "sess-5", "/a"));
        var join = assertInstanceOf(TaskJoin.Outcome.Join.class, TaskJoin.resolve(entries, null));
        assertEquals("t5-abcd", join.entry().id());
        assertInstanceOf(TaskJoin.Outcome.NotFound.class, TaskJoin.resolve(entries, "t2-abcd"));
        assertThrows(IllegalArgumentException.class, () -> TaskJoin.guestCommand("t1-abcd", DelegateAgent.CLAUDE, "/a", "x; rm -rf ~", null));
    }

    @Test
    void anInstructionIsOneShortLineSafeToPrint() {
        var entries = TaskJoin.parse("task t1-abcd finished\nsid t1-abcd s\ncwd t1-abcd /a\nprompt t1-abcd \u001b]0;owned\u0007 " + "x".repeat(80) + "\n");
        var instruction = entries.getFirst().instruction();
        assertTrue(instruction.codePoints().noneMatch(Character::isISOControl), instruction);
        assertEquals(60, instruction.codePointCount(0, instruction.length()));
        assertTrue(instruction.endsWith("…"));
    }

    @Test
    void aReclaimIsReadFromTheLastLineAndAnUnreadableOneSaysNothingOfASignal() {
        assertEquals(new Reclaim(Reclaim.Outcome.RECLAIMED, 77L), Reclaim.parse("Welcome back\nreclaimed 77\n"));
        var unreadable = Reclaim.parse("Welcome back\n");
        assertEquals(Reclaim.Outcome.UNREADABLE, unreadable.outcome());
        assertTrue(unreadable.refusal("t1-abcd").contains("task_status"), unreadable.refusal("t1-abcd"));
    }

    @Test
    void aPersonIsGivenCommandsOfTheirOwnWhenATakeIsRefused() {
        assertTrue(new Reclaim(Reclaim.Outcome.RUNNING, null).personRefusal("t1-abcd", "box").endsWith("isx shell box --task t1-abcd"));
        assertTrue(new Reclaim(Reclaim.Outcome.OTHER, 9L).personRefusal("t1-abcd", "box").endsWith("isx shell box --task t1-abcd --take"));
        var unreadable = new Reclaim(Reclaim.Outcome.UNREADABLE, null).personRefusal("t1-abcd", "box");
        assertTrue(unreadable.endsWith("isx shell box --task t1-abcd") && !unreadable.contains("task_status"), unreadable);
        assertTrue(unreadable.contains("may or may not have been ended"), "never that nothing happened: " + unreadable);
        assertTrue(!new Reclaim(Reclaim.Outcome.RUNNING, null).personRefusal("t1-abcd", "box").contains("cancel_task"));
    }

    @Test
    void onlyAFinishedResumableConversationIsTakeable() {
        var held = new TaskJoin.Entry("t1-abcd", "claude", "finished", null, "", "s1", "/a", 42L, true, null);
        assertTrue(held.takeable());
        var noDirectory = new TaskJoin.Entry("t1-abcd", "claude", "finished", null, "", "s1", "", 42L, true, null);
        assertTrue(!noDirectory.takeable(), "ending the holder could not be followed by a join");
        assertTrue(!TaskJoin.refusal(new TaskJoin.Outcome.Busy(noDirectory), "box").contains("--take"));
        var unknown = new TaskJoin.Entry("t1-abcd", "claude", "unknown", null, "", "s1", "/a", 42L, true, null);
        assertTrue(!unknown.takeable());
    }
}
