package dev.incusspawn.command;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.TemplateValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** The template isx writes for a preferred agent (#2409), for every agent isx supports. */
@ExtendWith(IsolatedHome.class)
class ExampleTemplateTest {

    @Test
    void everyAgentsTemplateLoadsAndValidates() throws Exception {
        var agents = AgentCommand.agents(RuntimeConstants.CDI_TOOLS);
        assertFalse(agents.isEmpty());
        var known = ImageDef.loadBuiltins();
        for (var agent : agents) {
            var text = ExampleTemplate.text(agent);
            assertTrue(text.startsWith("# yaml-language-server: $schema=https://isx.run/schema/template.json\n"), text);
            var def = ImageDef.parseYaml(text);
            assertEquals("tpl-agent", def.getName(), agent.name());
            assertEquals("tpl-minimal", def.getParent(), agent.name());
            assertEquals(java.util.List.of(agent.name()), def.getTools().stream().map(t -> t.getName()).toList(), agent.name());
            // Its default-action names the agent's own action, or is left out for an agent with none
            assertEquals(agent.actions().isEmpty() ? null : agent.name(), def.getDefaultAction(), agent.name());
            var result = TemplateValidator.validate(text, ExampleTemplate.file(), known);
            assertFalse(result.hasErrors(), agent.name() + ": " + result.errors());
            assertFalse(result.hasWarnings(), agent.name() + ": " + result.warnings());
        }
    }

    @Test
    void anExistingFileIsLeftUntouched() throws Exception {
        var claude = AgentCommand.agents(RuntimeConstants.CDI_TOOLS).stream().filter(a -> a.name().equals("claude")).findFirst().orElseThrow();
        var file = ExampleTemplate.file();
        assertTrue(ExampleTemplate.write(file, claude));
        assertEquals(ExampleTemplate.text(claude), Files.readString(file));
        Files.writeString(file, "name: tpl-agent\n# mine\n");
        assertFalse(ExampleTemplate.write(file, claude));
        assertEquals("name: tpl-agent\n# mine\n", Files.readString(file));
    }
}
