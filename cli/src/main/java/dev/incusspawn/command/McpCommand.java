package dev.incusspawn.command;

import dev.incusspawn.mcp.McpMain;
import dev.incusspawn.proxy.McpClientRegistration;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostPath;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.Privilege;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bare, {@code isx mcp} is the MCP server an agent starts; {@code isx mcp setup} is the person's
 * step of approving what that server may branch (#2077), {@code isx mcp register} registers it
 * with Claude Code or Codex (#2189), and {@code isx mcp config} prints the entry any MCP client
 * takes to start it (#2078). The approvals are written by {@link InitCommand}, never by the
 * {@code mcp} package.
 */
@CommandDefinition(
        name = "mcp",
        description = "Serve isx to a local AI agent over MCP (stdio), or set up what it may use",
        generateHelp = true,
        groupCommands = { McpCommand.Setup.class, McpCommand.Register.class, McpCommand.Config.class }
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
     * {@code isx mcp register <agent>} (#2189): registers {@code isx mcp} with Claude Code or Codex
     * through the agent's own CLI, in the form {@link McpLaunch} gives this host, so an agent that
     * installed isx runs one command and never chooses the {@code sg} form itself.
     * An existing {@code isx} entry is replaced, since one an agent wrote by hand may lack {@code sg}.
     */
    @CommandDefinition(name = "register",
            description = "Register isx mcp with an agent's MCP client: claude or codex",
            generateHelp = true)
    public static class Register extends BaseCommand {
        @Argument(description = "The agent to register with: claude or codex")
        String agent;

        @Override
        protected CommandResult doExecute() {
            return CommandResult.valueOf(new Registrar().register(agent, ProxyService.resolveIsxPath()));
        }
    }

    /** The agents {@code isx mcp register} knows, by the name of their isx tool. */
    enum Client {
        CLAUDE("claude", "Claude Code", List.of("claude", "mcp", "add", "--scope", "user", McpClientRegistration.SERVER_NAME, "--"),
                // A local-scope entry, the default of a bare 'claude mcp add', overrides the user one in its directory
                List.of(List.of("claude", "mcp", "remove", "--scope", "user", McpClientRegistration.SERVER_NAME),
                        List.of("claude", "mcp", "remove", "--scope", "local", McpClientRegistration.SERVER_NAME))),
        CODEX("codex", "Codex", List.of("codex", "mcp", "add", McpClientRegistration.SERVER_NAME, "--"),
                List.of(List.of("codex", "mcp", "remove", McpClientRegistration.SERVER_NAME)));

        final String tool;
        final String label;
        /** The add command, to which the launch command is appended. */
        final List<String> add;
        /** The commands that remove an existing entry, each in a scope the client reads. */
        final List<List<String>> remove;

        Client(String tool, String label, List<String> add, List<List<String>> remove) {
            this.tool = tool;
            this.label = label;
            this.add = add;
            this.remove = remove;
        }

        static Client named(String name) {
            for (var client : values()) if (client.tool.equals(name)) return client;
            return null;
        }

        /** The command that registers {@code launch} with this client. */
        List<String> addCommand(List<String> launch) {
            return McpLaunch.after(add, launch);
        }
    }

    /** {@code isx mcp register}'s work, with the host behind overridable seams for tests. */
    static class Registrar {
        McpLaunch launch = new McpLaunch();

        record Ran(int exit, String output) {}

        int register(String agent, String isxPath) {
            var client = Client.named(agent);
            if (client == null) {
                BuildOutput.error(agent == null || agent.isBlank() ? "Name the agent to register with: claude or codex."
                                : "isx mcp register knows claude and codex, not '" + OutputFormat.oneLine(agent) + "'.",
                        "For any other agent, add the entry 'isx mcp config' prints to its user-wide MCP settings.");
                return 1;
            }
            if (isxPath == null) {
                BuildOutput.error("Could not find the installed isx binary.", "Install it (see https://isx.run), then run this again.");
                return 1;
            }
            if (!hasCommand(client.tool)) {
                BuildOutput.error(client.label + " is not installed on this host: '" + client.tool + "' is not on the PATH.");
                return 1;
            }
            var form = launch.form();
            var add = client.addCommand(McpLaunch.command(isxPath, form));
            // An entry by that name, from an earlier register or by hand, would make the add fail
            var replaced = false;
            for (var remove : client.remove) replaced |= run(remove).exit() == 0;
            var added = run(add);
            if (added.exit() != 0) {
                var details = new ArrayList<String>();
                if (!added.output().isBlank()) details.add(OutputFormat.oneLine(added.output().strip()));
                if (replaced) details.add("The earlier '" + McpClientRegistration.SERVER_NAME + "' entry was already removed.");
                details.add("Register it yourself: " + McpLaunch.display(add));
                BuildOutput.error(client.label + " could not register isx (exit " + added.exit() + ").", details.toArray(String[]::new));
                return 1;
            }
            BuildOutput.ok((replaced ? "Replaced the registration of '" : "Registered '") + McpClientRegistration.SERVER_NAME
                    + "' with " + client.label + ": " + McpLaunch.display(McpLaunch.command(isxPath, form)));
            if (form == McpLaunch.Form.SG) {
                BuildOutput.stepNote("It runs under sg " + McpLaunch.GROUP + ", so it reaches Incus from a session older than that group.");
            } else if (launch.sessionRefused(form)) {
                BuildOutput.warn("sg is not installed, so isx mcp runs with the groups of " + client.label + "'s session, which lacks "
                        + McpLaunch.GROUP + ".", "Start " + client.label + " again from a new login, or log out and in, before using isx over MCP.");
            }
            BuildOutput.stepNote(client.label + " may load it only in a new session.");
            return 0;
        }

        boolean hasCommand(String command) {
            return HostPath.isOnPath(command);
        }

        /** Runs {@code command} with no input, for its exit code and its output, stderr included. */
        Ran run(List<String> command) {
            Privilege.check(command);
            try {
                var process = new ProcessBuilder(command)
                        .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                        .redirectErrorStream(true)
                        .start();
                var output = new String(process.getInputStream().readAllBytes());
                return new Ran(process.waitFor(), output);
            } catch (IOException e) {
                return new Ran(-1, e.getMessage() == null ? "" : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Ran(-1, "interrupted");
            }
        }
    }

    /**
     * {@code isx mcp config} (#2078): the stdio entry that starts {@code isx mcp}, as the
     * {@code mcpServers} JSON most MCP clients take in their settings, in the form
     * {@link McpLaunch} gives this host (#2189). It only prints: a client whose settings isx does
     * not know is set up by pasting it.
     */
    @CommandDefinition(name = "config",
            description = "Print the MCP client entry that starts isx mcp, to paste into an agent's settings",
            generateHelp = true)
    public static class Config extends BaseCommand {
        @Override
        protected CommandResult doExecute() {
            var isx = ProxyService.resolveIsxPath();
            return CommandResult.valueOf(print(isx, isx == null ? McpLaunch.Form.PLAIN : new McpLaunch().form()));
        }

        /** Print the entry for the {@code isx} at {@code isxPath}; null, when none is installed, fails. */
        static int print(String isxPath, McpLaunch.Form form) {
            if (isxPath == null) {
                BuildOutput.error("Could not find the installed isx binary.",
                        "Install it (see https://isx.run), or write the entry by hand with its path as the command and [\"mcp\"] as the args.");
                return 1;
            }
            var launch = McpLaunch.command(isxPath, form);
            var server = new LinkedHashMap<String, Object>();
            server.put("command", launch.getFirst());
            server.put("args", launch.subList(1, launch.size()));
            OutputFormat.printJson(BuildOutput.results(),
                    Map.of("mcpServers", Map.of(McpClientRegistration.SERVER_NAME, server)));
            return 0;
        }
    }
}
