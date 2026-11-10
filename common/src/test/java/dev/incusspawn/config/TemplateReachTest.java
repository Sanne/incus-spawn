package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a definition reaches is told as it is where the definition lives (#2076). */
class TemplateReachTest {

    private static final String GUI_AND_COORDINATOR = """
            name: tpl-p
            parent: tpl-minimal
            gui: true
            coordinator:
              control-over:
                - tpl-minimal
            """;

    private static List<String> reach(String yaml, boolean projectLocal) throws Exception {
        return TemplateReach.of(ImageDef.parseYaml(yaml), projectLocal).stream()
                .map(r -> r.field() + ": " + r.detail()).toList();
    }

    @Test
    void aUserTemplatesGuiAndCoordinatorAreDefaults() throws Exception {
        assertEquals(List.of(
                "gui: branches get the host's GPU, Wayland display and audio by default",
                "coordinator.control-over: its branches may create instances of tpl-minimal (as far as mcp.yaml allows),"
                        + " and are isx MCP clients by default"), reach(GUI_AND_COORDINATOR, false));
    }

    @Test
    void aProjectTemplatesGuiIsNoDefaultAndItsCoordinatorIsIgnored() throws Exception {
        // Not the opposite of the check's own warning that the coordinator is ignored
        assertEquals(List.of(
                "gui: branches get the host's GPU, Wayland display and audio only with --gui (never by default in a project)",
                "coordinator: ignored: a project-local template makes no coordinators"), reach(GUI_AND_COORDINATOR, true));
    }

    @Test
    void aFileBaseImageIsAHostFileAndANetworkOneIsNot() throws Exception {
        assertEquals(List.of("vm_image_url: imports host file FILE:///srv/vm.tar.xz as the base image"), reach("""
                name: tpl-r
                image_url: https://example.com/r.tar.xz
                vm_image_url: FILE:///srv/vm.tar.xz
                """, false));
        // A child changing machine type is built from scratch, from its own base image
        assertEquals(List.of("image_url: imports host file file:///srv/c.tar.xz as the base image when a build"
                        + " starts it from scratch (a change of machine type)"),
                reach("name: tpl-c\nparent: tpl-minimal\nimage_url: file:///srv/c.tar.xz\nvm_image_url: file:///srv/c-vm.tar.xz\n",
                        false), "a child's vm_image_url is never read: a build from scratch takes the root's");
    }
}
