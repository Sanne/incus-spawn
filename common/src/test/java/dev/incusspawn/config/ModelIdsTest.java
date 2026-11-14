package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIdsTest {

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9.]*(-[a-z0-9.]+)+$");

    @Test
    void everyDeclaredIdLooksLikeAModelId() {
        assertFalse(ModelIds.all().isEmpty());
        ModelIds.all().forEach((key, id) -> {
            assertTrue(ID.matcher(id).matches(), key + " is not a model ID: " + id);
            assertTrue(McpConfig.isModelName(id), key + " is refused by McpConfig.isModelName: " + id);
        });
    }

    @Test
    void theDefaultsTheIssueNamesAreDeclared() {
        for (var key : new String[] {"ai-help-anthropic", "ai-help-vertex", "ai-help-openai",
                "vertex-rewriter-default", "pi-default", "opencode-default"}) {
            assertFalse(ModelIds.get(key).isBlank(), key);
        }
    }

    @Test
    void anUndeclaredKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ModelIds.get("no-such-key"));
    }

    @Test
    void parseRefusesARepeatedKeyAndAMalformedLine() {
        assertThrows(IllegalStateException.class, () -> ModelIds.parse("a: x-1\na: y-2\n"));
        assertThrows(IllegalStateException.class, () -> ModelIds.parse("no colon here\n"));
        assertThrows(IllegalStateException.class, () -> ModelIds.parse("a: \"x-1\"\n"));
        assertThrows(IllegalStateException.class, () -> ModelIds.parse("a: x-1 # note\n"));
        assertEquals("x-1", ModelIds.parse("# c\n\na: x-1\n").get("a"));
    }
}
