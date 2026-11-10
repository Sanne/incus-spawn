package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
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
 * else, so what reaches {@code mcp.yaml} is asserted on disk.
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

    private static void run(Host host, ScriptedPrompts prompts) throws Exception {
        host.setupMcp(prompts, templates());
        prompts.assertFullyConsumed();
    }

    /** Everything the step reported, steps and warnings alike, a line each. */
    private static String reported(RecordingOutput.Work body) {
        return String.join("\n", RecordingOutput.during(body).lines());
    }

    private static List<String> approved() {
        return McpConfig.load().templates();
    }

    /** Writes {@code yaml} as the mcp.yaml the step reads. */
    private static void seedMcp(String yaml) throws Exception {
        Files.createDirectories(McpConfig.file().getParent());
        Files.writeString(McpConfig.file(), yaml);
    }

    private static void assertMcpUnchanged(String seeded) throws Exception {
        assertEquals(seeded, Files.readString(McpConfig.file()), "mcp.yaml should not have been rewritten");
    }

    /** Neither file was written: the step saves only mcp.yaml, and only on a change. */
    private static void assertNothingSavedAnywhere() {
        assertFalse(Files.exists(McpConfig.file()), "mcp.yaml was written");
        assertNothingSaved();
    }

    @Test
    void withoutClaudeCodeOnTheHostNothingIsAsked() throws Exception {
        var host = new Host();
        host.hasClaude = false;
        run(host, ScriptedPrompts.lines());
        assertNothingSavedAnywhere();
        assertEquals(List.of(), host.registrations);
    }

    @Test
    void itIsOffByDefaultAndANonInteractiveInitLeavesItOff() throws Exception {
        // 'isx init </dev/null': every prompt reads end of input.
        var host = new Host();
        host.setupMcp(ScriptedPrompts.lines(), templates());
        assertNothingSavedAnywhere();
        assertEquals(List.of(), host.registrations);

        run(host, ScriptedPrompts.lines(""));
        assertNothingSavedAnywhere();
    }

    /**
     * The questions are shown through the prompts that read their answers (#1703): what the step
     * asks is what a test sees, the template questions under the group that names them.
     */
    @Test
    void theStepAsksThroughItsPrompts() throws Exception {
        var host = new Host();
        var prompts = ScriptedPrompts.lines("y", "n", "y");
        var recorded = RecordingOutput.during(() -> run(host, prompts));

        assertEquals(List.of("Enable the isx MCP server? (y/N): ",
                "tpl-agent (not built yet: isx build tpl-agent)? (y/N): ",
                "tpl-agent-java (not built yet: isx build tpl-agent-java)? (y/N): "),
                prompts.questions().stream().map(BuildOutput::stripAnsi).toList());
        assertEquals(List.of("Approve the templates agents may create instances from"),
                recorded.events(RecordingOutput.Kind.GROUP).stream().map(RecordingOutput.Event::msg).toList());
    }

    @Test
    void enablingRegistersItAndAsksOnlyAboutTemplatesThatInstallClaude() throws Exception {
        var host = new Host();
        // Enable; then tpl-agent (asked first, sorted) no, tpl-agent-java yes.
        run(host, ScriptedPrompts.lines("y", "n", "y"));
        assertEquals(List.of("/home/u/.local/bin/isx"), host.registrations);
        assertEquals(List.of("tpl-agent-java"), approved());
    }

    @Test
    void aTemplateApprovedEarlierIsTheDefaultAndHandApprovedOnesAreKept() throws Exception {
        var host = new Host();
        host.registered = true;
        seedMcp("templates: [tpl-dev, tpl-agent]\n");
        // Already set up: 't' to change; Enter keeps tpl-agent; Enter declines tpl-agent-java.
        run(host, ScriptedPrompts.lines("t", "", ""));
        assertEquals(List.of(), host.registrations, "already registered: not again");
        assertEquals(List.of("tpl-dev", "tpl-agent"), approved(),
                "tpl-dev was approved by hand and is not this step's to drop");
    }

    @Test
    void unapprovingATemplateRemovesIt() throws Exception {
        var host = new Host();
        host.registered = true;
        seedMcp("templates: [tpl-agent, tpl-agent-java]\nmax-instances: 5\n");
        run(host, ScriptedPrompts.lines("t", "n", "y"));
        assertEquals(List.of("tpl-agent-java"), approved());
        assertEquals(5, McpConfig.load().maxInstances(), "the other settings survive");
    }

    @Test
    void endOfInputAtATemplateQuestionKeepsItsApproval() throws Exception {
        var host = new Host();
        host.registered = true;
        var seeded = "templates: [tpl-agent, tpl-agent-java]\n";
        // 't', then Ctrl-D at the first template question (and the script is exhausted for the rest).
        seedMcp(seeded);
        host.setupMcp(ScriptedPrompts.lines("t"), templates());
        assertMcpUnchanged(seeded);
    }

    @Test
    void turningItOffOnlyShowsHow() throws Exception {
        var host = new Host();
        host.registered = true;
        var seeded = "templates: [tpl-agent]\n";
        seedMcp(seeded);
        run(host, ScriptedPrompts.lines("n"));
        assertMcpUnchanged(seeded);
    }

    @Test
    void registeredWithNothingApprovedStillAsksAboutEachTemplate() throws Exception {
        var host = new Host();
        host.registered = true;
        // Enabled is the default: Enter keeps it; then tpl-agent yes, tpl-agent-java no.
        run(host, ScriptedPrompts.lines("", "y", "n"));
        assertEquals(List.of(), host.registrations);
        assertEquals(List.of("tpl-agent"), approved());
    }

    @Test
    void aReRunKeepsAnExistingSetupWithOneEnter() throws Exception {
        // #1292: re-running init for something else must not walk through every template again.
        var host = new Host();
        host.registered = true;
        var seeded = "templates: [tpl-agent]\n";
        seedMcp(seeded);
        var out = reported(() -> run(host, ScriptedPrompts.lines("")));
        assertMcpUnchanged(seeded);
        assertTrue(out.contains("Not approved: tpl-agent-java."), "a template not yet approved is named: " + out);

        // 'isx init </dev/null': the one question reads end of input, which keeps it too.
        host.setupMcp(ScriptedPrompts.lines(), templates());
        assertMcpUnchanged(seeded);
        assertEquals(List.of(), host.registrations);
    }

    /** Delegate policies removed in #1857 load, are named, and are gone once approvals are saved. */
    @Test
    void retiredDelegatePoliciesAreNamedAndDroppedOnSave() throws Exception {
        var host = new Host();
        host.registered = true;
        var seeded = "templates: [tpl-agent]\ndelegate-max-turns: 40\ndelegate-permission-mode: plan\n";
        seedMcp(seeded);
        var out = reported(() -> run(host, ScriptedPrompts.lines("")));
        assertMcpUnchanged(seeded);
        assertTrue(out.contains("delegate-max-turns, delegate-permission-mode, which no longer have any effect"), out);

        run(host, ScriptedPrompts.lines("t", "y", "y"));
        assertEquals(List.of("tpl-agent", "tpl-agent-java"), approved());
        assertFalse(Files.readString(McpConfig.file()).contains("delegate"), Files.readString(McpConfig.file()));
    }

    @Test
    void anUnknownChoiceAsksAgain() throws Exception {
        var host = new Host();
        host.registered = true;
        seedMcp("templates: [tpl-agent]\n");
        run(host, ScriptedPrompts.lines("y", "t", "n", "y"));
        assertEquals(List.of("tpl-agent-java"), approved());
    }

    @Test
    void halfASetupStillGetsTheWholeStep() throws Exception {
        // Templates approved by hand but never registered: there is something to finish.
        var host = new Host();
        seedMcp("templates: [tpl-agent]\n");
        run(host, ScriptedPrompts.lines("", "", "y"));
        assertEquals(List.of("/home/u/.local/bin/isx"), host.registrations);
        assertEquals(List.of("tpl-agent", "tpl-agent-java"), approved());
    }

    @Test
    void aFailedRegistrationStillLetsTemplatesBeApproved() throws Exception {
        var host = new Host();
        host.registrationWorks = false;
        run(host, ScriptedPrompts.lines("y", "y", "n"));
        assertEquals(List.of("tpl-agent"), approved());
    }

    @Test
    void withNoTemplateInstallingClaudeThereIsNothingToApprove() throws Exception {
        var host = new Host();
        var prompts = ScriptedPrompts.lines("y");
        host.setupMcp(prompts, defs("name: tpl-minimal\n", "name: tpl-dev\nparent: tpl-minimal\n"));
        prompts.assertFullyConsumed();
        assertEquals(1, host.registrations.size());
        assertNothingSavedAnywhere();
    }

    @Test
    void codexOnlyAndInheritedCodexTemplatesCanBeApproved() throws Exception {
        var host = new Host();
        var prompts = ScriptedPrompts.lines("y", "y", "y");
        host.setupMcp(prompts, defs("name: tpl-minimal\n",
                "name: tpl-codex\nparent: tpl-minimal\ntools: [codex]\n",
                "name: tpl-codex-java\nparent: tpl-codex\ntools: [maven-3]\n"));
        prompts.assertFullyConsumed();
        assertEquals(List.of("tpl-codex", "tpl-codex-java"), approved());
    }

    @Test
    void piOnlyTemplatesCanBeApproved() throws Exception {
        var host = new Host();
        var prompts = ScriptedPrompts.lines("y", "y");
        host.setupMcp(prompts, defs("name: tpl-minimal\n", "name: tpl-isx-pi\nparent: tpl-minimal\ntools: [pi]\n"));
        prompts.assertFullyConsumed();
        assertEquals(List.of("tpl-isx-pi"), approved());
    }

    @Test
    void approvalsGoToAnOwnerOnlyMcpYamlAndNeverToConfigYaml() throws Exception {
        var seeded = "github:\n  token: ghp_x\n";
        seed(seeded);
        run(new Host(), ScriptedPrompts.lines("y", "y", "n"));
        assertEquals(List.of("tpl-agent"), approved());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(McpConfig.file())));
        assertUnchanged(seeded);
    }

    /**
     * #1331: with only the old section, nothing is asked or saved, since saving approvals alone
     * would put the defaults in place of the section's limits; once mcp.yaml exists the section
     * is only named, never read.
     */
    @Test
    void aLegacySectionInConfigYamlIsNotReadButNamed() throws Exception {
        var seeded = "mcp:\n  templates: [tpl-agent]\n  max-instances: 2\n";
        seed(seeded);
        var host = new Host();
        host.registered = true;
        var out = reported(() -> run(host, ScriptedPrompts.lines()));
        assertTrue(out.contains("mcp.yaml does not exist, and the mcp: section of config.yaml"), out);
        assertFalse(Files.exists(McpConfig.file()));
        assertUnchanged(seeded);

        seedMcp("templates: [tpl-agent-java]\n");
        // Already set up: Enter keeps it.
        out = reported(() -> run(host, ScriptedPrompts.lines("")));
        assertTrue(out.contains("The mcp: section of config.yaml is no longer read; move its contents to"), out);
        assertTrue(out.contains("approved templates: tpl-agent-java."), out);
        assertUnchanged(seeded);
    }

    @Test
    void anInvalidMcpYamlIsReportedAndLeftAlone() throws Exception {
        var seeded = "templates: [tpl-agent\n";
        seedMcp(seeded);
        var host = new Host();
        var out = reported(() -> run(host, ScriptedPrompts.lines()));
        assertTrue(out.contains("mcp.yaml"), out);
        assertTrue(out.contains("Fix it, then run 'isx mcp setup'."), out);
        assertMcpUnchanged(seeded);
        assertEquals(List.of(), host.registrations);
    }

    /** #2077: 'isx mcp setup' asks what init's step asks and writes the same mcp.yaml. */
    @Test
    void theStepAloneAsksWhatInitAsksAndWritesTheSameFile() throws Exception {
        var host = new Host();
        var prompts = ScriptedPrompts.lines("y", "n", "y");
        var exit = new int[1];
        var recorded = RecordingOutput.during(() -> exit[0] = host.setupMcpAlone(prompts, () -> {
            try {
                return templates();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        prompts.assertFullyConsumed();
        assertEquals(0, exit[0]);
        assertEquals(List.of("Enable the isx MCP server? (y/N): ",
                "tpl-agent (not built yet: isx build tpl-agent)? (y/N): ",
                "tpl-agent-java (not built yet: isx build tpl-agent-java)? (y/N): "),
                prompts.questions().stream().map(BuildOutput::stripAnsi).toList());
        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("Agent Access over MCP") && l.contains("[1/1]")),
                () -> String.join("\n", recorded.lines()));
        assertEquals(List.of("/home/u/.local/bin/isx"), host.registrations);
        assertEquals(List.of("tpl-agent-java"), approved());
        // This home was never initialized, so the server just registered would refuse: say so.
        assertEquals(List.of("isx mcp serves agents once 'isx init' has run."),
                recorded.events(RecordingOutput.Kind.NOTE).stream().map(RecordingOutput.Event::msg).toList());
    }

    /** #2077: without a terminal it changes nothing, loads no definition, and says a person must run it. */
    @Test
    void theStepAloneRefusesWithoutATerminal() throws Exception {
        var seeded = "templates: [tpl-agent]\n";
        seedMcp(seeded);
        var host = new Host();
        var exit = new int[1];
        var recorded = RecordingOutput.during(() -> exit[0] = host.setupMcpAlone(null, () -> {
            throw new AssertionError("no definition is loaded for a refusal");
        }));
        assertEquals(1, exit[0]);
        var errors = recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList();
        assertEquals(1, errors.size(), errors::toString);
        assertTrue(errors.getFirst().contains("a person must run it at a terminal"), errors::toString);
        assertMcpUnchanged(seeded);
        assertNothingSaved();
        assertEquals(List.of(), host.registrations);
    }
}
