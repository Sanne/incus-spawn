package dev.incusspawn.command;

import dev.incusspawn.mcp.McpMain;
import dev.incusspawn.proxy.McpClientRegistration;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bare, {@code isx mcp} is the MCP server an agent starts; {@code isx mcp setup} is the person's
 * step of approving what that server may branch (#2077), and {@code isx mcp config} prints the
 * entry any MCP client takes to start it (#2078). The approvals are written by
 * {@link InitCommand}, never by the {@code mcp} package.
 */
@CommandDefinition(
        name = "mcp",
        description = "Serve isx to a local AI agent over MCP (stdio), or set up what it may use",
        generateHelp = true,
        groupCommands = { McpCommand.Setup.class, McpCommand.Config.class }
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
            description = "Approve the templates agents may use, and register isx mcp with Claude Code if installed (needs a terminal)",
            generateHelp = true)
    public static class Setup extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            return CommandResult.valueOf(new InitCommand().setupMcpAlone());
        }
    }

    /**
     * {@code isx mcp config} (#2078): the stdio entry that starts {@code isx mcp}, as the
     * {@code mcpServers} JSON most MCP clients take in their settings. It only prints: registering
     * stays the person's step, in the client's own settings.
     */
    @CommandDefinition(name = "config",
            description = "Print the MCP client entry that starts isx mcp, to paste into an agent's settings",
            generateHelp = true)
    public static class Config extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            return CommandResult.valueOf(print(ProxyService.resolveIsxPath()));
        }

        /** Print the entry for the {@code isx} at {@code isxPath}; null, when none is installed, fails. */
        static int print(String isxPath) {
            if (isxPath == null) {
                BuildOutput.error("Could not find the installed isx binary.",
                        "Install it (see https://isx.run), or write the entry by hand with its path as the command and [\"mcp\"] as the args.");
                return 1;
            }
            var server = new LinkedHashMap<String, Object>();
            server.put("command", isxPath);
            server.put("args", List.of("mcp"));
            OutputFormat.printJson(BuildOutput.results(),
                    Map.of("mcpServers", Map.of(McpClientRegistration.SERVER_NAME, server)));
            return 0;
        }
    }
}
