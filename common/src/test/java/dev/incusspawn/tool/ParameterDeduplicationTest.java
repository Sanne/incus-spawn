package dev.incusspawn.tool;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify parameter handling in tool deduplication scenarios.
 * Tool references are parsed from YAML, but deduplication and validation
 * happens in BuildCommand.resolveWithDeps() during the build process.
 */

class ParameterDeduplicationTest {

    @Test
    void testDuplicateToolRefsWithDifferentParamsParseThenFailAtBuild() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - example-tool:
                  memory: "2g"
              - example-tool:
                  memory: "8g"
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        // Verify both tool references are in the YAML
        // Note: During build, BuildCommand.resolveWithDeps() will throw
        // IllegalArgumentException when it detects the parameter conflict
        assertEquals(2, imageDef.getTools().size());
        assertEquals("example-tool", imageDef.getTools().get(0).getName());
        assertEquals("2g", imageDef.getTools().get(0).getParams().get("memory"));
        assertEquals("example-tool", imageDef.getTools().get(1).getName());
        assertEquals("8g", imageDef.getTools().get(1).getParams().get("memory"));
    }

    @Test
    void testSameToolWithSameParametersIsOk() throws Exception {
        var yaml = """
            name: test-image
            tools:
              - example-tool:
                  memory: "8g"
              - example-tool:
                  memory: "8g"
            """;

        var imageDef = ImageDef.parseYaml(yaml);

        assertEquals(2, imageDef.getTools().size());
        // Both have same parameters - this is redundant but not an error
        assertEquals("8g", imageDef.getTools().get(0).getParams().get("memory"));
        assertEquals("8g", imageDef.getTools().get(1).getParams().get("memory"));
    }

    @Test
    void testRequiresWithDifferentParameters() throws Exception {
        var yaml = """
            name: test-tool
            requires:
              - sshd
              - java-runtime:
                  memory: "4g"
            """;

        var toolDef = ToolDef.loadFromStream(
            new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );

        assertEquals(2, toolDef.getRequires().size());
        assertEquals("sshd", toolDef.getRequires().get(0).getName());
        assertTrue(toolDef.getRequires().get(0).getParams().isEmpty());

        assertEquals("java-runtime", toolDef.getRequires().get(1).getName());
        assertEquals("4g", toolDef.getRequires().get(1).getParams().get("memory"));
    }
}
