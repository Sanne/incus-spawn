package dev.incusspawn.command;

import dev.incusspawn.mcp.McpMain;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

@CommandDefinition(
        name = "mcp",
        description = "Serve isx to a local AI agent over MCP (stdio)",
        generateHelp = true
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
}
