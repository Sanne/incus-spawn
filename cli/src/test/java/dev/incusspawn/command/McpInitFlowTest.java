package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The "Agent Access over MCP" step of {@code isx init}: registering {@code isx mcp} with Claude
 * Code, and approving templates. What an agent may branch from is decided here and nowhere
 * else, so what reaches {@code config.yaml} is asserted on disk.
 */
@ExtendWith(IsolatedHome.class)
class McpInitFlowTest {

    /** InitCommand with the host stubbed out: which commands exist, Claude Code's MCP list. */
    static final class Host extends InitCommand {
        boolean hasClaude = true;
        boolean registered;
        boolean registrationWorks = true;
        String isx = "/home/u/.local/bin/isx";
        Set<String> built = Set.of();
        final List<String> registrations = new ArrayList<>();

        @Override
        boolean hostHasCommand(String command) {
            return !command.equals("claude") || hasClaude;
        }

        @Override
        boolean claudeMcpRegistered() {
            return registered;
        }

        @Override
        boolean registerClaudeMcp(String isxPath) {
            registrations.add(isxPath);
            if (registrationWorks) registered = true;
            return registrationWorks;
        }

        @Override
        String isxPath() {
            return isx;
        }

        @Override
        boolean templateBuilt(String name) {
            return built.contains(name);
        }
    }

    private static Map<String, ImageDef> defs(String... yamls) throws Exception {
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var yaml : yamls) {
            var def = ImageDef.parseYaml(yaml);
            defs.put(def.getName(), def);
        }
        return defs;
    }

    /** A root, a plain child, a child that installs Claude, and a grandchild inheriting it. */
    private static Map<String, ImageDef> templates() throws Exception {
        return defs("name: tpl-minimal\n",
                "name: tpl-dev\nparent: tpl-minimal\ntools: [gh]\n",
                "name: tpl-agent\nparent: tpl-dev\ntools: [claude]\n",
                "name: tpl-agent-java\nparent: tpl-agent\ntools: [maven-3]\n");
    }

    private static void run(Host host, SpawnConfig config, ScriptedPrompts prompts) throws Exception {
        host.setupMcp(config, prompts, templates());
        prompts.assertFullyConsumed();
    }

    private static List<String> approved() {
        return SpawnConfig.load().mcp().templates();
    }

    @Test
    void withoutClaudeCodeOnTheHostNothingIsAsked() throws Exception {
        var host = new Host();
        host.hasClaude = false;
        run(host, new SpawnConfig(), ScriptedPrompts.lines());
        assertNothingSaved();
        assertEquals(List.of(), host.registrations);
    }

    @Test
    void itIsOffByDefaultAndANonInteractiveInitLeavesItOff() throws Exception {
        // 'isx init </dev/null': every prompt reads end of input.
        var host = new Host();
        host.setupMcp(new SpawnConfig(), ScriptedPrompts.lines(), templates());
        assertNothingSaved();
        assertEquals(List.of(), host.registrations);

        run(host, new SpawnConfig(), ScriptedPrompts.lines(""));
        assertNothingSaved();
    }

    @Test
    void enablingRegistersItAndAsksOnlyAboutTemplatesThatInstallClaude() throws Exception {
        var host = new Host();
        // Enable; then tpl-agent (asked first, sorted) no, tpl-agent-java yes.
        run(host, new SpawnConfig(), ScriptedPrompts.lines("y", "n", "y"));
        assertEquals(List.of("/home/u/.local/bin/isx"), host.registrations);
        assertEquals(List.of("tpl-agent-java"), approved());
    }

    @Test
    void aTemplateApprovedEarlierIsTheDefaultAndHandApprovedOnesAreKept() throws Exception {
        var host = new Host();
        host.registered = true;
        var config = seed("mcp:\n  templates: [tpl-dev, tpl-agent]\n");
        // Enabled is the default now: Enter keeps it; Enter keeps tpl-agent; Enter declines tpl-agent-java.
        run(host, config, ScriptedPrompts.lines("", "", ""));
        assertEquals(List.of(), host.registrations, "already registered: not again");
        assertEquals(List.of("tpl-dev", "tpl-agent"), approved(),
                "tpl-dev was approved by hand and is not this step's to drop");
    }

    @Test
    void unapprovingATemplateRemovesIt() throws Exception {
        var host = new Host();
        host.registered = true;
        var config = seed("mcp:\n  templates: [tpl-agent, tpl-agent-java]\n  max-instances: 5\n");
        run(host, config, ScriptedPrompts.lines("y", "n", "y"));
        assertEquals(List.of("tpl-agent-java"), approved());
        assertEquals(5, SpawnConfig.load().mcp().maxInstances(), "the other mcp settings survive");
    }

    @Test
    void decliningLeavesAnExistingSetupAlone() throws Exception {
        var host = new Host();
        host.registered = true;
        var seeded = "mcp:\n  templates: [tpl-agent]\n";
        var config = seed(seeded);
        run(host, config, ScriptedPrompts.lines("n"));
        assertUnchanged(seeded);
    }

    @Test
    void aFailedRegistrationStillLetsTemplatesBeApproved() throws Exception {
        var host = new Host();
        host.registrationWorks = false;
        run(host, new SpawnConfig(), ScriptedPrompts.lines("y", "y", "n"));
        assertEquals(List.of("tpl-agent"), approved());
    }

    @Test
    void withNoTemplateInstallingClaudeThereIsNothingToApprove() throws Exception {
        var host = new Host();
        var prompts = ScriptedPrompts.lines("y");
        host.setupMcp(new SpawnConfig(), prompts, defs("name: tpl-minimal\n", "name: tpl-dev\nparent: tpl-minimal\n"));
        prompts.assertFullyConsumed();
        assertEquals(1, host.registrations.size());
        assertNothingSaved();
    }
}
