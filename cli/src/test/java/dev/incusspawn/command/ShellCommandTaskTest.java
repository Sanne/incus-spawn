package dev.incusspawn.command;

import dev.incusspawn.mcp.TaskJoin;
import dev.incusspawn.util.RecordingOutput;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@code isx shell} reads {@code --task} (with an id, without one, not at all), and what it prints when it joins nothing. */
class ShellCommandTaskTest {

    @CommandDefinition(name = "shell", description = "")
    public static class Parsed extends ShellCommand {
        static String name;
        static boolean task;
        static String taskId;

        @Override
        protected CommandResult doExecute() {
            Parsed.name = this.name;
            Parsed.task = this.task;
            Parsed.taskId = this.taskId;
            return CommandResult.SUCCESS;
        }
    }

    private static void parse(String... args) {
        Parsed.name = null;
        Parsed.task = false;
        Parsed.taskId = null;
        AeshRuntimeRunner.builder().command(Parsed.class).args(args).execute();
    }

    @Test
    void anIdAfterTaskNamesIt() {
        parse("box", "--task", "t3-abcd");
        assertEquals("box", Parsed.name);
        assertTrue(Parsed.task);
        assertEquals("t3-abcd", Parsed.taskId);
        parse("--task", "box", "t3-abcd");
        assertEquals("box", Parsed.name);
        assertEquals("t3-abcd", Parsed.taskId);
    }

    @Test
    void taskAloneAsksForTheOnlyOne() {
        parse("box", "--task");
        assertEquals("box", Parsed.name);
        assertTrue(Parsed.task);
        assertNull(Parsed.taskId);
    }

    @Test
    void withoutTaskItIsAShell() {
        parse("box");
        assertEquals("box", Parsed.name);
        assertFalse(Parsed.task);
        assertNull(Parsed.taskId);
    }

    @Test
    void severalTasksAreAnErrorEachWithTheCommandThatJoinsIt() {
        var entries = List.of(
                new TaskJoin.Entry("t1-abcd", "claude", "finished", Instant.ofEpochSecond(0), "Fix the flaky test", "s1", "/a"),
                new TaskJoin.Entry("t2-abcd", "codex", "running", null, "", "s2", "/b"));
        var out = new RecordingOutput();
        ShellCommand.explain(new TaskJoin.Outcome.Several(entries), "box", Instant.ofEpochSecond(7200), List.of(), out);
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "2 delegated tasks in box; join one:",
                "t1-abcd  claude  finished 2h ago  Fix the flaky test: isx shell box --task t1-abcd",
                "t2-abcd  codex  running: isx shell box --task t2-abcd")), out.events());
    }

    /** A busy task's refusal is one error, with what it did lately under it, never loose lines on stderr. */
    @Test
    void aBusyTaskIsAnErrorWithWhatItDidLately() {
        var busy = new TaskJoin.Outcome.Busy(
                new TaskJoin.Entry("t2-abcd", "codex", "running", null, "", "s2", "/b"));
        var out = new RecordingOutput();
        ShellCommand.explain(busy, "box", Instant.ofEpochSecond(7200), List.of("ran mvn test", "edited Foo.java"), out);
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                TaskJoin.refusal(busy, "box") + " Lately it:",
                "ran mvn test", "edited Foo.java")), out.events());
    }

    private static final TaskJoin.Entry HELD = new TaskJoin.Entry("t1-abcd", "claude", "finished", null, "", "s1", "/a",
            4242L, true, Instant.ofEpochSecond(0));

    @Test
    void takeShowsTheHolderAndAsksBeforeEndingIt() {
        var asked = new java.util.ArrayList<TaskJoin.Entry>();
        var out = new RecordingOutput();
        assertFalse(ShellCommand.take(HELD, "box", Instant.ofEpochSecond(3 * 3600), ScriptedPrompts.lines("n"),
                e -> { asked.add(e); return null; }, out));
        assertTrue(asked.isEmpty(), "declined: nothing is signalled");
        assertTrue(out.events().getFirst().msg().contains("agent pid 4242, last input 3h ago"), out.events().toString());

        assertTrue(ShellCommand.take(HELD, "box", Instant.ofEpochSecond(3 * 3600), ScriptedPrompts.lines("y"),
                e -> { asked.add(e); return null; }, new RecordingOutput()));
        assertEquals(List.of(HELD), asked);

        var refused = new RecordingOutput();
        assertFalse(ShellCommand.take(HELD, "box", Instant.ofEpochSecond(3 * 3600), ScriptedPrompts.lines("y"),
                e -> "Did not take it back: it did not exit.", refused));
        assertEquals(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "Did not take it back: it did not exit."), refused.events().getLast());
    }

    @Test
    void anAttachedTaskSRefusalNamesTake() {
        var why = TaskJoin.refusal(new TaskJoin.Outcome.Busy(HELD), "box");
        assertTrue(why.contains("isx shell box --task t1-abcd --take"), why);
        var noTerminal = new TaskJoin.Entry("t1-abcd", "claude", "finished", null, "", "s1", "/a", 4242L, false, null);
        assertTrue(TaskJoin.holder(noTerminal, Instant.now()).equals("agent pid 4242, with no terminal"));
    }
}
