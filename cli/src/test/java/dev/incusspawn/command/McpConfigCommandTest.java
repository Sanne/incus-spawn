package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx mcp config} (#2078): the stdio server entry for {@code isx mcp}, as the
 * {@code mcpServers} JSON most MCP clients take in their settings, so any client can be set up
 * by pasting it.
 */
class McpConfigCommandTest {

    @Test
    void itPrintsTheStdioEntryAsJsonOnTheResultsStream() {
        var exit = new int[1];
        var recorded = RecordingOutput.during(() -> exit[0] = McpCommand.Config.print("/home/u/.local/bin/isx"));
        assertEquals(0, exit[0]);
        var json = String.join("\n", recorded.resultLines());
        assertEquals("""
                {
                  "mcpServers" : {
                    "isx" : {
                      "command" : "/home/u/.local/bin/isx",
                      "args" : [ "mcp" ]
                    }
                  }
                }""", json);
        // Nothing but the entry on stdout: it is meant to be piped or pasted whole.
        assertEquals(List.of(), recorded.events(RecordingOutput.Kind.STEP));
    }

    @Test
    void aPathThatNeedsEscapingStaysValidJson() {
        var recorded = RecordingOutput.during(() -> McpCommand.Config.print("/opt/my \"isx\"\\bin/isx"));
        var parsed = JqValues.parseStrict(String.join("\n", recorded.resultLines()));
        assertEquals("/opt/my \"isx\"\\bin/isx", parsed.getField("mcpServers").getField("isx").pathText("command", null), parsed::toJsonString);
    }

    @Test
    void withoutAnInstalledIsxItFailsAndPrintsNoEntry() {
        var exit = new int[1];
        var recorded = RecordingOutput.during(() -> exit[0] = McpCommand.Config.print(null));
        assertEquals(1, exit[0]);
        assertEquals(List.of(), recorded.resultLines());
        assertEquals(1, recorded.events(RecordingOutput.Kind.ERROR).size());
    }
}
