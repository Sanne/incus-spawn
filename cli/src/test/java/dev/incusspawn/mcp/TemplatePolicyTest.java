package dev.incusspawn.mcp;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.McpScope;
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
        assertTrue(e.getMessage().contains("templates in ~/.config/incus-spawn/mcp.yaml"), e.getMessage());
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

    @Test
    void aForksLineageNeedNotBeBuiltButMustStillBeApprovedAndTrusted() {
        config.setTemplates(List.of("tpl-unbuilt", "tpl-repo"));
        assertEquals("tpl-unbuilt", policy.requireLineage("tpl-unbuilt").name());
        assertThrows(ToolError.class, () -> policy.require("tpl-unbuilt"));
        assertThrows(ToolError.class, () -> policy.requireLineage("tpl-repo"));
        assertThrows(ToolError.class, () -> policy.requireLineage("tpl-java"));
    }

    /** A trusted definition of {@code name} whose coordinators may control {@code controls}, or say nothing. */
    private static ImageDef coordinatorTemplate(String name, List<String> controls) {
        var def = new ImageDef();
        def.setName(name);
        if (controls != null) {
            var mcpClient = new ImageDef.Coordinator();
            mcpClient.setControlOver(controls);
            def.setCoordinator(mcpClient);
        }
        return def;
    }

    private TemplatePolicy coordinator(String template, String untrusted) {
        var defs = java.util.Map.of("tpl-x", coordinatorTemplate("tpl-x", List.of("tpl-java", "tpl-unbuilt")),
                "tpl-y", coordinatorTemplate("tpl-y", null));
        return new TemplatePolicy(backend, () -> config, c -> McpScope.coordinator(template, untrusted, defs, c.templates()));
    }

    @Test
    void aCoordinatorGetsWhatBothItsTemplateAndMcpYamlList() {
        // #1812: mcp.yaml approves tpl-java and tpl-dev; tpl-x's coordinators may control tpl-java
        // and tpl-unbuilt, and tpl-y's are given no list
        config.setTemplates(List.of("tpl-java", "tpl-dev"));
        var x = coordinator("tpl-x", null);
        assertEquals(List.of("tpl-java"), x.approved().stream().map(InstanceBackend.TemplateInfo::name).toList());
        assertEquals("tpl-java", x.require("tpl-java").name());
        var notInItsList = assertThrows(ToolError.class, () -> x.require("tpl-dev"));
        assertEquals(ToolError.Code.NOT_APPROVED, notInItsList.code);
        assertTrue(notInItsList.getMessage().contains("not in coordinator.control-over of tpl-x"), notInItsList.getMessage());
        var notInMcpYaml = assertThrows(ToolError.class, () -> x.requireLineage("tpl-unbuilt"));
        assertTrue(notInMcpYaml.getMessage().contains("Ask the user to approve it")
                        && notInMcpYaml.getMessage().contains(dev.incusspawn.config.McpScope.SETUP_COMMAND),
                notInMcpYaml.getMessage());

        var y = coordinator("tpl-y", null);
        assertEquals(List.of(), y.approved());
        var e = assertThrows(ToolError.class, () -> y.require("tpl-java"));
        assertTrue(e.getMessage().contains("declares no coordinator.control-over"), e.getMessage());

        assertEquals(List.of(), coordinator("tpl-x", "built from a project-local definition").approved(),
                "an untrusted copy of a coordinator controls nothing");
        assertEquals(2, policy.approved().size(), "the host's own session keeps mcp.yaml's list");
    }
}
