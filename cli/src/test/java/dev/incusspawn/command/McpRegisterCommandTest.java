package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx mcp register} (#2189): one command registers {@code isx mcp} with Claude Code,
 * Codex or opencode, under {@code sg incus-admin} where that helps, and plain where {@code sg} is missing.
 */
class McpRegisterCommandTest {

    static final String ISX = "/home/u/.local/bin/isx";

    /** The host stubbed: a fixed form, which commands exist, what each run answers. */
    static final class Host extends McpCommand.Registrar {
        final List<List<String>> ran = new ArrayList<>();
        final List<File> directories = new ArrayList<>();
        int removeExit = 1;
        int addExit = 0;
        boolean installed = true;
        boolean socketDenied = false;

        Host(McpLaunch.Form form) {
            launch = new McpLaunch() {
                @Override
                Form form() {
                    return form;
                }

                @Override
                boolean sessionRefused(Form form) {
                    return form == Form.PLAIN_WITHOUT_SG && socketDenied;
                }
            };
        }

        @Override
        boolean hasCommand(String command) {
            return installed;
        }

        @Override
        Ran run(List<String> command, File directory) {
            ran.add(command);
            directories.add(directory);
            return command.contains("remove") ? new Ran(removeExit, "") : new Ran(addExit, addExit == 0 ? "" : "boom\n");
        }
    }

    @Test
    void claudeIsRegisteredUnderSgWhenTheHostHasIt() {
        var host = new Host(McpLaunch.Form.SG);
        var recorded = RecordingOutput.during(() -> assertEquals(0, host.register("claude", ISX)));
        assertEquals(List.of(
                List.of("claude", "mcp", "remove", "--scope", "user", "isx"),
                List.of("claude", "mcp", "remove", "--scope", "local", "isx"),
                List.of("claude", "mcp", "add", "--scope", "user", "isx", "--", "sg", "incus-admin", "-c", "exec " + ISX + " mcp")),
                host.ran);
        assertTrue(recorded.events(RecordingOutput.Kind.WARN).isEmpty(), recorded::toString);
    }

    @Test
    void codexGetsTheSameLaunchThroughItsOwnCli() {
        var host = new Host(McpLaunch.Form.SG);
        RecordingOutput.during(() -> assertEquals(0, host.register("codex", ISX)));
        assertEquals(List.of(
                List.of("codex", "mcp", "remove", "isx"),
                List.of("codex", "mcp", "add", "isx", "--", "sg", "incus-admin", "-c", "exec " + ISX + " mcp")),
                host.ran);
    }

    /**
     * opencode's add replaces an existing entry, so nothing is removed first (#2214), and it runs
     * from the home directory, since it rewrites the opencode.json of the directory it runs in.
     */
    @Test
    void opencodeGetsTheSameLaunchThroughItsOwnCli() {
        var host = new Host(McpLaunch.Form.SG);
        RecordingOutput.during(() -> assertEquals(0, host.register("opencode", ISX)));
        assertEquals(List.of(
                List.of("opencode", "mcp", "add", "isx", "--", "sg", "incus-admin", "-c", "exec " + ISX + " mcp")),
                host.ran);
        assertEquals(List.of(dev.incusspawn.Environment.home().toFile()), host.directories);
    }

    /** Sanne on #2189: sg is a safeguard, never a requirement. */
    @Test
    void withoutSgTheRegistrationIsPlainAndStillSucceeds() {
        var host = new Host(McpLaunch.Form.PLAIN_WITHOUT_SG);
        var recorded = RecordingOutput.during(() -> assertEquals(0, host.register("claude", ISX)));
        assertEquals(List.of("claude", "mcp", "add", "--scope", "user", "isx", "--", ISX, "mcp"), host.ran.getLast());
        assertTrue(recorded.events(RecordingOutput.Kind.WARN).isEmpty(), "a session that has the group needs no new login");
    }

    @Test
    void withoutSgASessionTheSocketRefusesIsToldToLogInAgain() {
        var host = new Host(McpLaunch.Form.PLAIN_WITHOUT_SG);
        host.socketDenied = true;
        var recorded = RecordingOutput.during(() -> assertEquals(0, host.register("claude", ISX)));
        assertEquals(1, recorded.events(RecordingOutput.Kind.WARN).size(), recorded::toString);
    }

    @Test
    void aFailedAddFailsAndNamesTheCommandToRunByHand() {
        var host = new Host(McpLaunch.Form.SG);
        host.addExit = 1;
        var recorded = RecordingOutput.during(() -> assertEquals(1, host.register("claude", ISX)));
        var error = recorded.events(RecordingOutput.Kind.ERROR);
        assertEquals(1, error.size());
        assertTrue(error.getFirst().toString().contains("claude mcp add --scope user isx -- sg incus-admin -c 'exec " + ISX + " mcp'"),
                error.toString());
    }

    @Test
    void anAgentIsxCannotRegisterWithRunsNothingAndPointsAtTheEntry() {
        for (var agent : new String[] {null, "gemini-cli"}) {
            var host = new Host(McpLaunch.Form.SG);
            var recorded = RecordingOutput.during(() -> assertEquals(1, host.register(agent, ISX)));
            assertEquals(List.of(), host.ran);
            assertTrue(recorded.events(RecordingOutput.Kind.ERROR).toString().contains("isx mcp config"), recorded::toString);
        }
    }

    @Test
    void aMissingClientOrIsxRunsNothing() {
        var host = new Host(McpLaunch.Form.SG);
        host.installed = false;
        RecordingOutput.during(() -> assertEquals(1, host.register("codex", ISX)));
        var noIsx = new Host(McpLaunch.Form.SG);
        RecordingOutput.during(() -> assertEquals(1, noIsx.register("claude", null)));
        assertEquals(List.of(), host.ran);
        assertEquals(List.of(), noIsx.ran);
    }

    /** Init's registration, its advice and its plan name the command register runs (#2189). */
    @Test
    void initNamesTheSameClaudeCommand() {
        var launch = McpLaunch.command(ISX, McpLaunch.Form.SG);
        assertEquals(McpCommand.Client.CLAUDE.addCommand(launch), InitCommand.claudeMcpAddCommand(launch));
        assertEquals("claude mcp add --scope user isx -- sg incus-admin -c 'exec " + ISX + " mcp'",
                McpLaunch.display(InitCommand.claudeMcpAddCommand(launch)));
    }

    @Test
    void aPathTheShellWouldSplitIsQuotedInsideSgsCommand() {
        assertEquals(List.of("sg", "incus-admin", "-c", "exec '/opt/my isx/isx' mcp"),
                McpLaunch.command("/opt/my isx/isx", McpLaunch.Form.SG));
        assertEquals("sg incus-admin -c 'exec <path to isx> mcp'",
                McpLaunch.display(McpLaunch.command(McpLaunch.PLACEHOLDER, McpLaunch.Form.SG)));
    }

    @Test
    void theFormFollowsTheGroupAndSg() {
        if (!dev.incusspawn.Platform.isLinux()) {
            assertEquals(McpLaunch.Form.PLAIN, launch(true, true).form());
            return;
        }
        assertEquals(McpLaunch.Form.SG, launch(true, true).form());
        assertEquals(McpLaunch.Form.PLAIN_WITHOUT_SG, launch(true, false).form());
        assertEquals(McpLaunch.Form.PLAIN, launch(false, true).form());
    }

    private static McpLaunch launch(boolean inGroup, boolean hasSg) {
        return new McpLaunch() {
            @Override
            boolean inGroup() {
                return inGroup;
            }

            @Override
            boolean hasSg() {
                return hasSg;
            }
        };
    }
}
