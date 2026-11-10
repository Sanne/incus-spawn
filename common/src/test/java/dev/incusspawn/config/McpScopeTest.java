package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1812: a coordinator in an instance may control only the templates in both {@code mcp.yaml}'s
 * {@code templates:} and its own template's {@code coordinator.control-over}, and nothing when its
 * template lists none; every mismatch says which list to change, in which file.
 */
class McpScopeTest {

    private static final List<String> APPROVED = List.of("tpl-a", "tpl-b");

    private static ImageDef def(String name, String parent, List<String> controls) {
        var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        def.setSource("/home/u/.config/incus-spawn/images/" + name + ".yaml");
        if (controls != null) {
            var mcpClient = new ImageDef.Coordinator();
            mcpClient.setControlOver(controls);
            def.setCoordinator(mcpClient);
        }
        return def;
    }

    private static Map<String, ImageDef> defs(ImageDef... defs) {
        var byName = new HashMap<String, ImageDef>();
        for (var def : defs) byName.put(def.getName(), def);
        return byName;
    }

    /** {@code tpl-x} lists tpl-a and tpl-c, {@code tpl-y} lists nothing. */
    private static final Map<String, ImageDef> DEFS = defs(def("tpl-x", null, List.of("tpl-a", "tpl-c")), def("tpl-y", null, null));

    @Test
    void aCoordinatorGetsOnlyWhatBothListsHold() {
        var scope = McpScope.coordinator("tpl-x", null, DEFS, APPROVED);
        assertEquals(List.of("tpl-a"), scope.templates());
        assertNull(scope.explain("tpl-a"));

        var notListedHere = scope.explain("tpl-b");
        assertTrue(notListedHere.contains("not in coordinator.control-over of tpl-x (/home/u/.config/incus-spawn/images/tpl-x.yaml)"),
                notListedHere);
        var notApproved = scope.explain("tpl-c");
        assertTrue(notApproved.contains("add tpl-c to templates in ~/.config/incus-spawn/mcp.yaml"), notApproved);

        assertEquals(List.of("tpl-c is in coordinator.control-over of tpl-x (/home/u/.config/incus-spawn/images/tpl-x.yaml) but not"
                + " approved for agents. Add it to templates in ~/.config/incus-spawn/mcp.yaml, or remove it from"
                + " coordinator.control-over there."), scope.mismatches());
        assertEquals("tpl-a; tpl-c not approved in mcp.yaml", scope.summary());
    }

    @Test
    void aCoordinatorWhoseTemplateListsNothingControlsNothing() {
        var scope = McpScope.coordinator("tpl-y", null, DEFS, APPROVED);
        assertEquals(List.of(), scope.templates());
        assertTrue(scope.explain("tpl-a").contains("declares no coordinator.control-over"), scope.explain("tpl-a"));
        assertEquals(1, scope.mismatches().size());
        assertTrue(scope.mismatches().getFirst().contains("under coordinator.control-over in tpl-y (/home/u/"), scope.mismatches().toString());
        assertEquals("nothing", scope.summary());
    }

    @Test
    void anUntrustedLineageAMissingDefinitionOrAProjectLocalOneControlNothing() {
        assertEquals(List.of(), McpScope.coordinator("tpl-x", "built from a project-local definition", DEFS, APPROVED).templates());
        assertEquals(List.of(), McpScope.coordinator("tpl-gone", null, DEFS, APPROVED).templates());
        var local = def("tpl-x", null, List.of("tpl-a"));
        local.setProjectRoot(Path.of("/home/u/cloned"));
        var scope = McpScope.coordinator("tpl-x", null, defs(local), APPROVED);
        assertEquals(List.of(), scope.templates());
        assertTrue(scope.explain("tpl-a").contains("project-local"), scope.explain("tpl-a"));
    }

    @Test
    void aCoordinatorOfNoKnownTemplateNeverGetsTheHostsList() {
        for (var untrusted : new String[] {null, "a failed build"}) {
            var scope = McpScope.coordinator(null, untrusted, DEFS, APPROVED);
            assertTrue(scope.isCoordinator());
            assertEquals(List.of(), scope.templates());
        }
    }

    @Test
    void anUntrustedLineageIsDeniedWhateverItsReasonSays() {
        var unexplained = new dev.incusspawn.proxy.InstanceRegistry.Lineage("tpl-x", true, "");
        var scope = McpScope.coordinator(unexplained, DEFS, APPROVED);
        assertEquals(List.of(), scope.templates());
        assertTrue(scope.explain("tpl-a").contains("of no known lineage"), scope.explain("tpl-a"));
    }

    @Test
    void theHostKeepsTheGlobalList() {
        var scope = McpScope.host(APPROVED);
        assertEquals(APPROVED, scope.templates());
        assertEquals(List.of(), scope.mismatches());
        assertEquals("template 'tpl-c' is not approved for agents; approved: tpl-a, tpl-b. Only the user can approve"
                + " templates, on the host: by running isx mcp setup at a terminal, or by listing them under templates in"
                + " ~/.config/incus-spawn/mcp.yaml.", scope.explain("tpl-c"));
    }

    @Test
    void aChildsListReplacesItsParentsRatherThanAddingToIt() {
        var defs = defs(def("tpl-x", null, List.of("tpl-a", "tpl-b")), def("tpl-x-child", "tpl-x", List.of("tpl-b")),
                def("tpl-x-quiet", "tpl-x", null));
        assertEquals(List.of("tpl-b"), McpScope.coordinator("tpl-x-child", null, defs, APPROVED).templates());
        assertEquals(List.of("tpl-a", "tpl-b"), McpScope.coordinator("tpl-x-quiet", null, defs, APPROVED).templates(),
                "a child that says nothing keeps its parent's list");
        var switchOnly = def("tpl-x-switch", "tpl-x", null);
        var coordinator = new ImageDef.Coordinator();
        coordinator.setIsxControlCapability(true);
        switchOnly.setCoordinator(coordinator);
        defs.put("tpl-x-switch", switchOnly);
        assertEquals(List.of(), McpScope.coordinator("tpl-x-switch", null, defs, APPROVED).templates(),
                "a child whose coordinator: says only the switch decides alone, and lists nothing");
    }
}
