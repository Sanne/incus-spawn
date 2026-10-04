package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelCheck#refusal}: what decides whether an unverified model reaches a task. Only a
 * result Claude Code reported as a success passes; anything else refuses, exit 0 included.
 */
class ModelCheckTest {

    private static final String OK = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"OK\"}";

    /** The refusal for this outcome, failing the test if it passed the check. */
    private static String refused(int exit, String out, String err, String what) {
        var why = ModelCheck.refusal(exit, out, err);
        assertNotNull(why, "must refuse: " + what);
        return why;
    }

    @Test
    void onlyAReportedSuccessPasses() {
        assertNull(ModelCheck.refusal(0, OK + "\n", ""));
        assertNull(ModelCheck.refusal(0, "a warning first\n" + OK + "\n", "a warning on stderr"), "the result is the last line");
    }

    @Test
    void anExitZeroWithoutAResultRefuses() {
        assertTrue(refused(0, "", "", "nothing at all").contains("no successful result"));
        assertTrue(refused(0, "  \n", "", "only blank lines").contains("no successful result"));
        assertTrue(refused(0, "", "Update available: 9.9", "a notice on stderr").startsWith("Claude Code gave no successful result"),
                "what happened comes first, whatever stderr said");
        assertTrue(refused(0, "not json", "", "not a result").contains("not json"), "says what it got");
        refused(0, "[\"OK\"]", "", "JSON, but not a result object");
        refused(0, "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false}", "", "a result without an answer");
        refused(0, "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":false,\"result\":\"\"}", "",
                "an error subtype not flagged is_error");
        refused(0, "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":false,\"result\":\"x\"}", "",
                "an error subtype with text");
        refused(0, "{\"result\":\"OK\"}", "", "an object that is not a result event");
    }

    @Test
    void aFailureSaysWhatClaudeCodeSaid() {
        assertTrue(refused(0, "{\"is_error\":true,\"result\":\"API Error: 404\"}", "", "an error result")
                .contains("API Error: 404"));
        assertTrue(refused(1, "", "Not logged in", "a failed exit").contains("Not logged in"));
        refused(1, OK, "", "a non-zero exit, even with a result");
    }
}
