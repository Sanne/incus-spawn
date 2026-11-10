package dev.incusspawn.command;

import dev.incusspawn.mcp.McpMain;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

/**
 * Bare, {@code isx mcp} is the MCP server an agent starts; {@code isx mcp setup} is the person's
 * step of approving what that server may branch (#2077). The approvals are written by
 * {@link InitCommand}, never by the {@code mcp} package.
 */
@CommandDefinition(
        name = "mcp",
        description = "Serve isx to a local AI agent over MCP (stdio), or set up what it may use",
        generateHelp = true,
        groupCommands = { McpCommand.Setup.class }
)
public class McpCommand extends BaseCommand {

    @Option(name = "caller-instance", description = "Serve this isx instance, branched with --mcp-client, "
            + "instead of the process that started isx mcp. The proxy runs this for each MCP "
            + "connection to mcp.isx.internal.")
    String callerInstance;

    @Override
    protected CommandResult doExecute() throws Exception {
        return CommandResult.valueOf(McpMain.run(InitCommand::hasBeenInitialized, callerInstance));
    }

    @CommandDefinition(name = "setup",
            description = "Register isx mcp with Claude Code and approve the templates agents may use (needs a terminal)",
            generateHelp = true)
    public static class Setup extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            return CommandResult.valueOf(new InitCommand().setupMcpAlone());
        }
    }
}
