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
}
