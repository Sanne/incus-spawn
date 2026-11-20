package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a coordinator is told of an agy run, from events in the shape agy 1.5.12 printed them. */
class AntigravityEventsTest {

    private static String step(int index, String state, String type, String extra) {
        return "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"c\",\"step_index\":" + index
                + ",\"state\":\"" + state + "\",\"step_type\":\"" + type + "\"" + extra + "}}";
    }

    @Test
    void eachStepIsOneLineWhereItWasFirstSeen() {
        var tail = String.join("\n",
                "ate\":\"DONE\"}}", // the tail starts mid-line
                step(0, "DONE", "user_input", ""),
                step(1, "ACTIVE", "agent_response", ",\"text_delta\":\"Let me \""),
                step(1, "DONE", "agent_response", ",\"text_delta\":\"look.\",\"usage\":{\"input_tokens\":3}"),
                step(2, "ACTIVE", "tool", ",\"tool_name\":\"run_command\",\"tool_info\":{\"parameters\":{\"CommandLine\":\"make test\"}}"),
                step(2, "DONE", "tool", ",\"tool_name\":\"run_command\",\"tool_info\":{\"parameters\":{\"CommandLine\":\"make test\"},\"output\":\"ok\"}"),
                step(3, "DONE", "error_message", ""),
                step(4, "DONE", "tool", ",\"tool_name\":\"read_file\",\"tool_info\":{\"parameters\":{\"Path\":\"/w/a.txt\"}}"),
                "{\"event\":\"step_update\",\"step_upd");
        var summary = AntigravityEvents.summarize(tail, 8);
        assertEquals(List.of("said: Let me look.", "ran run_command: make test", "a model call failed",
                "ran read_file: /w/a.txt"), summary.recent());
        assertEquals(1, summary.assistantMessages());
        assertNull(summary.result(), "still running");
        assertEquals(List.of("ran read_file: /w/a.txt"), AntigravityEvents.summarize(tail, 1).recent());
    }

    @Test
    void theRunnersOutcomeIsTheResult() {
        var summary = AntigravityEvents.summarize("{\"type\":\"isx.antigravity.result\",\"success\":false,"
                + "\"error\":\"Error 429, quota\",\"turns\":0,\"usage\":{}}\n", 0);
        assertEquals("Error 429, quota", summary.resultText());
        assertTrue(summary.isError());
    }
}
