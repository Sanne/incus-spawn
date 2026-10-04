package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How {@link Tasks#parse} reads a status report, on the host; {@link TaskScriptsTest} runs the
 * scripts that write one, on Linux only.
 */
class TasksParseTest {

    @Test
    void aUnitStateNobodyCouldReadIsUnknownNotLost() {
        assertEquals("unknown", Tasks.parse("run=1\nkind=command\nunit=\n---\n\n---stderr\n").state());
        assertEquals("lost", Tasks.parse("run=1\nkind=command\nunit=inactive\n---\n\n---stderr\n").state());
    }
}
