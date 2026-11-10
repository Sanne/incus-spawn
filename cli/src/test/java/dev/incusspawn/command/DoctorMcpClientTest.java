package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #1812: {@code isx doctor} says where a coordinator template's {@code coordinator.control-over} and
 * {@code mcp.yaml} disagree, with the remedy {@code isx branch} and {@code isx mcp} give.
 */
@ExtendWith(IsolatedHome.class)
class DoctorMcpClientTest {

    private static ImageDef coordinator(String name, List<String> controls) throws Exception {
        var def = ImageDef.parseYaml("name: " + name + "\ncoordinator:\n  isx-control-capability: true\n"
                + (controls == null ? "" : "  control-over: " + controls + "\n"));
        def.setSource("/home/u/.config/incus-spawn/images/" + name + ".yaml");
        return def;
    }

    @Test
    void eachMismatchIsAFindingWithItsRemedy() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("mcp.yaml"), "templates: [tpl-a]\n");
        var defs = Map.of("tpl-x", coordinator("tpl-x", List.of("tpl-a", "tpl-b")), "tpl-y", coordinator("tpl-y", null),
                "tpl-ok", coordinator("tpl-ok", List.of("tpl-a")));
        var findings = DoctorCommand.mcpClientFindings(defs, null);
        assertEquals(List.of(
                "Coordinator template tpl-x|tpl-b is in coordinator.control-over of tpl-x (/home/u/.config/incus-spawn/images/tpl-x.yaml)"
                        + " but not approved for agents. Approve it (isx mcp setup, or templates in ~/.config/incus-spawn/mcp.yaml), or remove it"
                        + " from coordinator.control-over there.",
                "Coordinator template tpl-y|tpl-y declares no coordinator.control-over, so its coordinators control nothing."
                        + " List the templates they may control under coordinator.control-over in tpl-y"
                        + " (/home/u/.config/incus-spawn/images/tpl-y.yaml). A ready coordinator template: https://isx.run/docs/mcp#from-a-fresh-install"),
                findings.stream().map(f -> f.label() + "|" + f.detail()).toList());
        assertEquals(List.of(), DoctorCommand.mcpClientFindings(Map.of("tpl-plain", ImageDef.parseYaml("name: tpl-plain\n")), null));
    }

    @Test
    void eachChainIsReportedByTheLayerThatDecidesIt() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("mcp.yaml"), "templates: [tpl-a]\n");
        // A child with no coordinator: of its own takes its parent's and repeats nothing; one whose
        // coordinator: says only the switch decides alone, and lists nothing
        var quiet = ImageDef.parseYaml("name: tpl-x-quiet\nparent: tpl-x\n");
        var switchOnly = ImageDef.parseYaml("name: tpl-x-switch\nparent: tpl-x\ncoordinator:\n  isx-control-capability: true\n");
        var defs = Map.of("tpl-x", coordinator("tpl-x", List.of("tpl-a", "tpl-b")), "tpl-x-quiet", quiet,
                "tpl-x-switch", switchOnly);
        var findings = DoctorCommand.mcpClientFindings(defs, null);
        assertEquals(List.of("Coordinator template tpl-x", "Coordinator template tpl-x-switch"),
                findings.stream().map(DoctorCommand.Finding::label).toList());
        org.junit.jupiter.api.Assertions.assertTrue(findings.get(1).detail().startsWith("tpl-x-switch declares no coordinator.control-over"),
                findings.get(1).detail());
    }

    @Test
    void aCoordinatorBranchedFromATemplateThatSaysNothingIsReported() throws Exception {
        // The common upgrade case: isx branch --mcp-client from a template with no coordinator: block
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("mcp.yaml"), "templates: [tpl-a]\n");
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon()
                .container("tpl-dev", Map.of(dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_BASE))
                .container("coord", Map.of(dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_CLONE,
                        dev.incusspawn.incus.Metadata.PROFILE, "tpl-dev", dev.incusspawn.incus.Metadata.PARENT, "tpl-dev",
                        dev.incusspawn.incus.Metadata.MCP_CALLER, dev.incusspawn.incus.Metadata.newMcpCallerGrant()))
                .container("plain", Map.of(dev.incusspawn.incus.Metadata.TYPE, dev.incusspawn.incus.Metadata.TYPE_CLONE,
                        dev.incusspawn.incus.Metadata.PARENT, "tpl-dev"));
        var findings = DoctorCommand.mcpClientFindings(Map.of("tpl-dev", ImageDef.parseYaml("name: tpl-dev\n")),
                daemon.client().listJsonConfig());
        assertEquals(1, findings.size(), findings.toString());
        assertEquals("Coordinator coord", findings.getFirst().label());
        org.junit.jupiter.api.Assertions.assertTrue(findings.getFirst().detail().contains("tpl-dev declares no coordinator.control-over"),
                findings.getFirst().detail());
    }
}
