package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code mcp:} section: what {@code isx mcp} lets an agent do, read but never written by it. */
class McpConfigTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static SpawnConfig parse(String yaml) throws Exception {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    @Test
    void aConfigWithoutMcpApprovesNothing() throws Exception {
        var mcp = parse("github:\n  token: ghp_x\n").mcp();
        assertEquals(List.of(), mcp.templates());
        assertEquals(McpConfig.DEFAULT_MAX_INSTANCES, mcp.maxInstances());
        assertEquals(McpConfig.DEFAULT_MAX_CONCURRENT_TASKS, mcp.maxConcurrentTasks());
        assertNull(mcp.delegateMaxTurns());
    }

    @Test
    void aConfigWithoutMcpGainsNoMcpOnSave() throws Exception {
        var out = YAML.writeValueAsString(parse("github:\n  token: ghp_x\n"));
        assertFalse(out.contains("mcp"), "saving must not pin an mcp: section:\n" + out);
    }

    @Test
    void theMcpSectionLoadsAndSurvivesASave() throws Exception {
        var yaml = """
                mcp:
                  templates: [tpl-java, tpl-dev]
                  max-instances: 5
                  max-concurrent-tasks: 1
                  delegate-max-turns: 40
                """;
        var mcp = parse(yaml).mcp();
        assertEquals(List.of("tpl-java", "tpl-dev"), mcp.templates());
        assertEquals(5, mcp.maxInstances());
        assertEquals(1, mcp.maxConcurrentTasks());
        assertEquals(40, mcp.delegateMaxTurns());

        var again = parse(YAML.writeValueAsString(parse(yaml))).mcp();
        assertEquals(List.of("tpl-java", "tpl-dev"), again.templates());
        assertEquals(5, again.maxInstances());
        assertEquals(40, again.delegateMaxTurns());
    }

    @Test
    void onlyTheLimitsSetAreWrittenBack() throws Exception {
        var out = YAML.writeValueAsString(parse("mcp:\n  templates: [tpl-dev]\n"));
        assertTrue(out.contains("tpl-dev"));
        assertFalse(out.contains("max-instances"), "an unset limit must stay unset:\n" + out);
    }

    @Test
    void removingAnUnrelatedKeyKeepsTheMcpSection() throws Exception {
        var config = parse("""
                github:
                  token: ghp_x
                mcp:
                  templates: [tpl-java]
                """);
        config.removeConfigPath("github.token");
        assertEquals(List.of("tpl-java"), config.mcp().templates());
    }

    @Test
    void aNegativeLimitIsZeroNotUnlimited() throws Exception {
        var mcp = parse("mcp:\n  max-instances: -1\n").mcp();
        assertEquals(0, mcp.maxInstances());
    }
}
