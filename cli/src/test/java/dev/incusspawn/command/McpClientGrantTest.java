package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #1812: a coordinator's branch says what it may control, and warns of each list that disagrees. */
class McpClientGrantTest {

    private static McpScope scope(List<String> controls, List<String> approved) {
        var def = new ImageDef();
        def.setName("tpl-x");
        if (controls != null) {
            var mcpClient = new ImageDef.Coordinator();
            mcpClient.setControlOver(controls);
            def.setCoordinator(mcpClient);
        }
        return McpScope.coordinator("tpl-x", null, Map.of("tpl-x", def), approved);
    }

    @Test
    void aPartlyUnapprovedScopeIsShownAndEachMissingApprovalWarned() {
        var recorded = RecordingOutput.during(() -> McpClientGrant.reportScope(scope(List.of("tpl-a", "tpl-b"), List.of("tpl-a"))));
        assertEquals(List.of("MCP control: tpl-a; tpl-b not approved in mcp.yaml"),
                recorded.events(RecordingOutput.Kind.STEP).stream().map(RecordingOutput.Event::msg).toList());
        assertEquals(List.of("tpl-b is in coordinator.control-over of tpl-x but not approved for agents. Approve it (isx mcp"
                + " setup, or templates in ~/.config/incus-spawn/mcp.yaml), or remove it from coordinator.control-over there."), recorded.warnings());
    }

    @Test
    void anEmptyScopeIsWarnedOfWithWhereToListTemplates() {
        var recorded = RecordingOutput.during(() -> McpClientGrant.reportScope(scope(null, List.of("tpl-a"))));
        assertEquals(List.of("tpl-x declares no coordinator.control-over, so its coordinators control nothing."
                + " List the templates they may control under coordinator.control-over in tpl-x."
                + " A ready coordinator template: https://isx.run/docs/mcp#from-a-fresh-install"), recorded.warnings());
    }
}
