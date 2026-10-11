package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How {@link Tasks#parse} reads a status report, on the host; {@link TaskScriptsTest} runs the
 * scripts that write one, on Linux only.
 */
class TasksParseTest {

    @Test
    void aUnitStateNobodyCouldReadIsUnknownNotLost() {
        assertEquals("unknown", Tasks.parse("run=1\nkind=command\nstate=unknown\n---\n\n---stderr\n").state());
        assertEquals("lost", Tasks.parse("run=1\nkind=command\nstate=lost\n---\n\n---stderr\n").state());
    }

    @Test
    void timesAreReadOnlyWhenTheGuestGaveSome() {
        var status = Tasks.parse("run=1\nkind=command\nstate=running\nstarted=1790000000\nactivity=\n---\n\n---stderr\n");
        assertEquals(java.time.Instant.ofEpochSecond(1790000000), status.startedAt());
        assertNull(status.lastActivity(), "no output yet");
        assertNull(Tasks.parse("run=1\nkind=command\nstate=running\nstarted=$(evil)\n---\n").startedAt());
    }

    @Test
    void legacyClaudeAndVersionedCodexSessionsDetectHumanTakeover() {
        var legacy = "run=1\nkind=agent\nstate=finished\nexit=0\nsession_id=saved\ncwd=/work\n"
                + "presence=claude 12 /elsewhere\npresence=resume 12 saved\n---\n";
        assertEquals("attached", Tasks.parse(legacy).state());
        assertEquals("attached", Tasks.parse(legacy.replace("presence=claude", "identity=1 codex\npresence=codex")).state());
        assertEquals("finished", Tasks.parse(legacy.replace("presence=claude", "identity=1 codex\npresence=claude")).state());
        assertEquals(DelegateAgent.UNKNOWN, Tasks.recordedAgent("2 codex"));
        assertEquals(DelegateAgent.UNKNOWN, Tasks.recordedAgent("1 malicious"));
        assertEquals(DelegateAgent.CLAUDE, Tasks.recordedAgent(null));
    }

    @Test
    void codexProgressIncludesRunningItemsAndCountsOnlyCompletedMessages() {
        var summary = CodexEvents.summarize("""
                {"type":"item.started","item":{"type":"command_execution","command":"make test"}}
                {"type":"item.updated","item":{"type":"agent_message","text":"working"}}
                {"type":"item.completed","item":{"type":"agent_message","text":"done"}}
                {"type":"isx.codex.result","success":true,"report_bytes":4}
                """, 3);
        assertEquals(java.util.List.of("command_execution: make test", "agent_message: working", "agent_message: done"), summary.recent());
        assertEquals(1, summary.assistantMessages());
        assertEquals("success", summary.result().path("subtype").asText());
    }
}
