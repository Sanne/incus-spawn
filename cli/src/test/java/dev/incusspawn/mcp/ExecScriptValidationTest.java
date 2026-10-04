package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@link ExecScript#build} refuses or leaves out, decided on the host before anything runs;
 * {@link ExecScriptTest} runs the scripts themselves, on Linux only.
 */
class ExecScriptValidationTest {

    private static String build(String cwd, Map<String, String> env, String command, Integer timeout) {
        return ExecScript.build("exec-1-1", cwd, env, command, timeout);
    }

    @Test
    void anInvalidEnvironmentNameIsRefused() {
        for (var key : new String[] {"A-B", "1X", "X;Y", "", "A B"}) {
            assertThrows(ToolError.class, () -> build("x", Map.of(key, "v"), "true", null), key);
        }
    }

    @Test
    void thereIsNoTimeoutUnlessOneIsAsked() {
        assertFalse(build("x", Map.of(), "true", null).contains("timeout"));
        assertThrows(ToolError.class, () -> build("x", Map.of(), "true", 0));
    }
}
