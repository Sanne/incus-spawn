package dev.incusspawn.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonMergeUtilTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void testMergeDisjointObjects() throws Exception {
        var base = JSON.readTree("{\"a\": 1, \"b\": 2}");
        var overlay = JSON.readTree("{\"c\": 3}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertEquals(1, result.get("a").asInt());
        assertEquals(2, result.get("b").asInt());
        assertEquals(3, result.get("c").asInt());
    }

    @Test
    void testOverlayWinsOnConflict() throws Exception {
        var base = JSON.readTree("{\"a\": 1, \"b\": 2}");
        var overlay = JSON.readTree("{\"b\": 99}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertEquals(1, result.get("a").asInt());
        assertEquals(99, result.get("b").asInt());
    }

    @Test
    void testDeepMergeNestedObjects() throws Exception {
        var base = JSON.readTree("{\"settings\": {\"theme\": \"dark\", \"size\": 12}}");
        var overlay = JSON.readTree("{\"settings\": {\"theme\": \"light\"}}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertEquals("light", result.get("settings").get("theme").asText());
        assertEquals(12, result.get("settings").get("size").asInt());
    }

    @Test
    void testArrayReplacement() throws Exception {
        var base = JSON.readTree("{\"permissions\": {\"allow\": [\"Bash\", \"Read\"]}}");
        var overlay = JSON.readTree("{\"permissions\": {\"allow\": [\"Bash\", \"Read\", \"Write\"]}}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        var allowArray = result.get("permissions").get("allow");
        assertEquals(3, allowArray.size());
        assertEquals("Bash", allowArray.get(0).asText());
        assertEquals("Read", allowArray.get(1).asText());
        assertEquals("Write", allowArray.get(2).asText());
    }

    @Test
    void testNullOverlay() throws Exception {
        var base = JSON.readTree("{\"a\": 1}");
        var result = JsonMergeUtil.deepMerge(base, null);

        assertEquals(base, result);
    }

    @Test
    void testNullBase() throws Exception {
        var overlay = JSON.readTree("{\"a\": 1}");
        var result = JsonMergeUtil.deepMerge(null, overlay);

        assertEquals(overlay, result);
    }

    @Test
    void testComplexRealWorldExample() throws Exception {
        // Simulating .claude/settings.json merge
        var base = JSON.readTree("""
                {
                  "permissions": {
                    "defaultMode": "bypassPermissions",
                    "allow": ["Bash(*)", "Read(**)", "Edit(**)"]
                  },
                  "skipDangerousModePermissionPrompt": true
                }
                """);

        var overlay = JSON.readTree("""
                {
                  "permissions": {
                    "allow": ["CustomTool(*)"]
                  },
                  "customField": "value"
                }
                """);

        var result = JsonMergeUtil.deepMerge(base, overlay);

        // Permissions.allow should be replaced (array replacement)
        var allowArray = result.get("permissions").get("allow");
        assertEquals(1, allowArray.size());
        assertEquals("CustomTool(*)", allowArray.get(0).asText());

        // defaultMode should be preserved from base
        assertEquals("bypassPermissions", result.get("permissions").get("defaultMode").asText());

        // skipDangerousModePermissionPrompt should be preserved
        assertTrue(result.get("skipDangerousModePermissionPrompt").asBoolean());

        // customField should be added from overlay
        assertEquals("value", result.get("customField").asText());
    }

    @Test
    void testMixedTypes() throws Exception {
        var base = JSON.readTree("{\"str\": \"hello\", \"num\": 42, \"bool\": true, \"nil\": null}");
        var overlay = JSON.readTree("{\"str\": \"world\", \"num\": 99, \"bool\": false}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertEquals("world", result.get("str").asText());
        assertEquals(99, result.get("num").asInt());
        assertFalse(result.get("bool").asBoolean());
        assertTrue(result.get("nil").isNull());
    }

    @Test
    void testOverlayAddsNewNestedObject() throws Exception {
        var base = JSON.readTree("{\"existing\": {\"field\": 1}}");
        var overlay = JSON.readTree("{\"newSection\": {\"nested\": {\"deep\": \"value\"}}}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertEquals(1, result.get("existing").get("field").asInt());
        assertEquals("value", result.get("newSection").get("nested").get("deep").asText());
    }

    @Test
    void testTypeConflictObjectReplacesArray() throws Exception {
        var base = JSON.readTree("{\"field\": [1, 2, 3]}");
        var overlay = JSON.readTree("{\"field\": {\"newType\": \"object\"}}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertTrue(result.get("field").isObject());
        assertEquals("object", result.get("field").get("newType").asText());
    }

    @Test
    void testTypeConflictArrayReplacesObject() throws Exception {
        var base = JSON.readTree("{\"field\": {\"old\": \"object\"}}");
        var overlay = JSON.readTree("{\"field\": [1, 2, 3]}");
        var result = JsonMergeUtil.deepMerge(base, overlay);

        assertTrue(result.get("field").isArray());
        assertEquals(3, result.get("field").size());
    }
}
