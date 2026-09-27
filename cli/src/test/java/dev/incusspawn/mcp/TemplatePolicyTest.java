package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePolicyTest {

    private final FakeBackend backend = new FakeBackend()
            .template("tpl-java", true, "maven-3", "claude")
            .template("tpl-dev", true)
            .template("tpl-unbuilt", false)
            .projectLocalTemplate("tpl-repo");
    private final McpConfig config = new McpConfig();
    private final TemplatePolicy policy = new TemplatePolicy(backend, () -> config);

    @Test
    void nothingIsApprovedByDefault() {
        assertEquals(List.of(), policy.approved());
        var e = assertThrows(ToolError.class, () -> policy.require("tpl-java"));
        assertTrue(e.getMessage().contains("mcp.templates"), e.getMessage());
    }

    @Test
    void onlyListedTemplatesAreOffered() {
        config.setTemplates(List.of("tpl-java", "tpl-unbuilt", "tpl-repo"));
        assertEquals(List.of("tpl-java", "tpl-unbuilt"),
                policy.approved().stream().map(InstanceBackend.TemplateInfo::name).toList());
        assertThrows(ToolError.class, () -> policy.require("tpl-dev"));
    }

    @Test
    void aProjectLocalTemplateIsRefusedEvenWhenListed() {
        config.setTemplates(List.of("tpl-repo"));
        var e = assertThrows(ToolError.class, () -> policy.require("tpl-repo"));
        assertTrue(e.getMessage().contains("project-local"), e.getMessage());
    }

    @Test
    void anUnbuiltTemplateIsRefusedWithTheBuildCommand() {
        config.setTemplates(List.of("tpl-unbuilt"));
        var e = assertThrows(ToolError.class, () -> policy.require("tpl-unbuilt"));
        assertTrue(e.getMessage().contains("isx build tpl-unbuilt"), e.getMessage());
    }

    @Test
    void aListedTemplateWithoutADefinitionIsRefused() {
        config.setTemplates(List.of("tpl-gone"));
        assertThrows(ToolError.class, () -> policy.require("tpl-gone"));
    }

    @Test
    void theConfigIsReadOnEveryCall() {
        config.setTemplates(List.of("tpl-java"));
        assertEquals("tpl-java", policy.require("tpl-java").name());
        config.setTemplates(List.of());
        assertThrows(ToolError.class, () -> policy.require("tpl-java"));
    }

    @Test
    void delegationNeedsClaudeInTheTemplate() {
        config.setTemplates(List.of("tpl-java", "tpl-dev"));
        assertTrue(policy.require("tpl-java").supportsDelegate());
        assertEquals(false, policy.require("tpl-dev").supportsDelegate());
    }
}
