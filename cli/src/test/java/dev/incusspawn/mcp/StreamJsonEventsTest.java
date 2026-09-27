package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamJsonEventsTest {

    @Test
    void aTailCutMidLineAndAnInProgressLastLineAreSkipped() {
        var tail = """
                "text":"cut off by tail -c"}]}}
                {"type":"assistant","message":{"content":[{"type":"text","text":"one"}]}}
                {"type":"future_event","payload":1}
                not json at all
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/a"}}]}}
                {"type":"assistant","message":{"content":[{"type":"te""";
        var s = StreamJsonEvents.summarize(tail, 10);
        assertEquals(List.of("said: one", "ran Read: /a"), s.recent());
        assertEquals(2, s.assistantMessages());
        assertFalse(s.finished());
    }

    @Test
    void onlyTheMostRecentActivityIsKept() {
        var sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"step ")
                    .append(i).append("\"}]}}\n");
        }
        assertEquals(List.of("said: step 18", "said: step 19"), StreamJsonEvents.summarize(sb.toString(), 2).recent());
    }

    @Test
    void anErrorResultIsReportedAsOne() {
        var s = StreamJsonEvents.summarize(
                "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"result\":\"\"}\n", 5);
        assertTrue(s.finished());
        assertTrue(s.isError());
    }

    @Test
    void longTextIsShortenedToOneLine() {
        var s = StreamJsonEvents.summarize("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
                + "a\\n".repeat(400) + "\"}]}}\n", 1);
        var said = s.recent().getFirst();
        assertFalse(said.contains("\n"));
        assertTrue(said.endsWith("..."));
    }
}
