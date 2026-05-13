package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRefDeserializerTest {

    @Test
    void testDeserializeStringForm() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - maven-3
              - sshd
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(2, imageDef.getTools().size());
        assertEquals("maven-3", imageDef.getTools().get(0).getName());
        assertTrue(imageDef.getTools().get(0).getParams().isEmpty());
        assertEquals("sshd", imageDef.getTools().get(1).getName());
        assertTrue(imageDef.getTools().get(1).getParams().isEmpty());
    }

    @Test
    void testDeserializeObjectForm() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - idea-backend:
                  memory: "8g"
                  debug: "true"
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(1, imageDef.getTools().size());
        var tool = imageDef.getTools().get(0);
        assertEquals("idea-backend", tool.getName());
        assertEquals("8g", tool.getParams().get("memory"));
        assertEquals("true", tool.getParams().get("debug"));
    }

    @Test
    void testDeserializeMixedForm() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - maven-3
              - idea-backend:
                  memory: "8g"
              - sshd
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(3, imageDef.getTools().size());
        assertEquals("maven-3", imageDef.getTools().get(0).getName());
        assertTrue(imageDef.getTools().get(0).getParams().isEmpty());

        assertEquals("idea-backend", imageDef.getTools().get(1).getName());
        assertEquals("8g", imageDef.getTools().get(1).getParams().get("memory"));

        assertEquals("sshd", imageDef.getTools().get(2).getName());
        assertTrue(imageDef.getTools().get(2).getParams().isEmpty());
    }

    @Test
    void testDeserializeMultipleParameters() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - custom-tool:
                  memory: "4g"
                  port: "8080"
                  debug: "false"
                  mode: "production"
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(1, imageDef.getTools().size());
        var tool = imageDef.getTools().get(0);
        assertEquals("custom-tool", tool.getName());
        assertEquals(4, tool.getParams().size());
        assertEquals("4g", tool.getParams().get("memory"));
        assertEquals("8080", tool.getParams().get("port"));
        assertEquals("false", tool.getParams().get("debug"));
        assertEquals("production", tool.getParams().get("mode"));
    }

    @Test
    void testDeserializeToolWithNullParams() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - idea-backend:
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(1, imageDef.getTools().size());
        assertEquals("idea-backend", imageDef.getTools().get(0).getName());
        assertTrue(imageDef.getTools().get(0).getParams().isEmpty());
    }

    @Test
    void testDeserializeMultipleKeysRejected() {
        var yaml = """
            name: test-image
            tools:
              - idea-backend:
                  memory: "8g"
                extra-key:
                  port: "9090"
            """;

        assertThrows(Exception.class, () -> ImageDef.parseYaml(yaml));
    }

    @Test
    void testBackwardCompatibility() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - maven-3
              - sshd
              - podman
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(3, imageDef.getTools().size());
        for (var tool : imageDef.getTools()) {
            assertNotNull(tool.getName());
            assertTrue(tool.getParams().isEmpty());
        }
    }
}
