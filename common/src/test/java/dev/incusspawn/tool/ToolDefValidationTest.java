package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates built-in tool definitions for common mistakes.
 */
class ToolDefValidationTest {

    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private static final String[] BUILTIN_TOOLS = {
            "tools/maven-3.yaml",
            "tools/idea-backend.yaml",
            "tools/starship.yaml",
            "tools/podman.yaml",
            "tools/sshd.yaml",
            "tools/tmux.yaml"
    };

    @Test
    void allBuiltInToolsHaveValidSha256() throws IOException {
        var failures = new java.util.ArrayList<String>();

        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            if (tool == null) {
                failures.add("Failed to load built-in tool: " + resourcePath);
                continue;
            }

            for (var download : tool.getDownloads()) {
                if (download.getSha256() == null) {
                    continue; // Optional field
                }

                var sha = download.getSha256();
                if (!SHA256_PATTERN.matcher(sha).matches()) {
                    failures.add(String.format(
                            "Tool '%s' has invalid SHA256 for %s: '%s' (must be 64 hex chars)",
                            tool.getName(), download.getUrl(), sha));
                }
            }
        }

        if (!failures.isEmpty()) {
            fail("Tool definition validation failures:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    void allBuiltInToolsLoadWithoutError() throws IOException {
        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            assertNotNull(tool, "Failed to load: " + resourcePath);
            assertNotNull(tool.getName(), resourcePath + " missing name");
            assertFalse(tool.getName().isBlank(), resourcePath + " has blank name");
        }
    }

    private ToolDef loadBuiltinTool(String resourcePath) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return ToolDef.loadFromStream(is);
        }
    }
}
