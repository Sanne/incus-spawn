package dev.incusspawn.command;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx agent}: the preferred agent, shown and set (#2409). */
@ExtendWith(IsolatedHome.class)
class AgentCommandTest {

    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("\\d+\\.\\d+");

    private static int run(String agent) {
        var command = new AgentCommand();
        command.agent = agent;
        int[] code = new int[1];
        RecordingOutput.during(() -> code[0] = command.run(RuntimeConstants.CDI_TOOLS, List.of()));
        return code[0];
    }

    @Test
    void itIsSetAndUnset() {
        assertNull(SpawnConfig.load().getPreferredAgent());
        assertEquals(0, run("codex"));
        assertEquals("codex", SpawnConfig.load().getPreferredAgent());
        assertEquals(0, run("none"));
        assertNull(SpawnConfig.load().getPreferredAgent());
    }

    @Test
    void anAgentIsxDoesNotSupportIsRefused() {
        assertEquals(1, run("maven-3"));
        assertNull(SpawnConfig.load().getPreferredAgent());
    }

    @Test
    void aConfigThatCannotBeReadIsNotWrittenOver() throws Exception {
        var file = SpawnConfig.file();
        java.nio.file.Files.createDirectories(file.getParent());
        var broken = "claude: [unclosed\n";
        java.nio.file.Files.writeString(file, broken);
        assertThrows(IllegalStateException.class, () -> new AgentCommand() {{ agent = "codex"; }}.run(RuntimeConstants.CDI_TOOLS, List.of()));
        assertEquals(broken, java.nio.file.Files.readString(file));
    }

    @Test
    void anAgentsTitleCarriesNoVersion() {
        for (var agent : AgentCommand.agents(RuntimeConstants.CDI_TOOLS)) {
            assertFalse(VERSION.matcher(ExampleTemplate.title(agent)).find(), agent.name() + ": " + ExampleTemplate.title(agent));
        }
    }

    @Test
    void theListSaysWhatIsPreferredAndWhatTheHostHas() {
        var found = new HostAgentProbe.HostAgent("claude", true, HostAgentProbe.Kind.SUBSCRIPTION, null, null, null, null, "~/.claude.json");
        var claude = AgentCommand.agents(RuntimeConstants.CDI_TOOLS).stream().filter(a -> a.name().equals("claude")).findFirst().orElseThrow();
        var record = AgentCommand.record(claude, "claude", List.of(found));
        assertEquals("claude", record.get("agent"));
        assertEquals("Claude Code", record.get("title"));
        assertEquals(true, record.get("preferred"));
        assertEquals("signed in with a subscription (~/.claude.json)", record.get("on_host"));
    }
}
