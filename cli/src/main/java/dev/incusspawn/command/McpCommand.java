package dev.incusspawn.command;

import dev.incusspawn.mcp.McpMain;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
        name = "mcp",
        description = "Serve isx to a local AI agent over MCP (stdio)",
        generateHelp = true
)
public class McpCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        return CommandResult.valueOf(McpMain.run(InitCommand::hasBeenInitialized));
    }
}
