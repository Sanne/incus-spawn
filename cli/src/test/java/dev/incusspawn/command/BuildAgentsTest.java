package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a build says anything of agents' Git identity: only for a template whose chain installs
 * a coding agent. tpl-minimal, built first for tpl-agent, told a person who had just chosen their
 * own GitHub account that its agents had no identity (#2528).
 */
@ExtendWith(IsolatedHome.class)
class BuildAgentsTest {

    private static ImageDef def(String yaml) throws Exception {
        return ImageDef.parseYaml(yaml);
    }

    @Test
    void aChainWithNoCodingAgentInstallsNone() throws Exception {
        var minimal = def("name: tpl-minimal\ntools: [gh]\n");
        assertFalse(BuildCommand.installsCodingAgent(minimal, Map.of("tpl-minimal", minimal), new ToolDefLoader().allToolSetups()));
    }

    @Test
    void anAgentInAParentIsInstalledByTheChild() throws Exception {
        var agent = def("name: tpl-agent\ntools: [claude]\n");
        var child = def("name: tpl-app\nparent: tpl-agent\ntools: [gh]\n");
        assertTrue(BuildCommand.installsCodingAgent(child, Map.of("tpl-agent", agent, "tpl-app", child),
                new ToolDefLoader().allToolSetups()));
    }

    @Test
    void anAgentAnotherToolRequiresIsInstalled() throws Exception {
        var template = def("name: tpl-head\ntools: [headroom]\n");
        assertTrue(BuildCommand.installsCodingAgent(template, Map.of("tpl-head", template), new ToolDefLoader().allToolSetups()));
    }
}
