package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.Report;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code isx agent [<agent>]} (#2409): show or set the coding agent the person prefers, which
 * the example template is written for once init offers it (#2412). The preference limits nothing:
 * a template installs whichever agent it names, and changing it changes no template.
 */
@CommandDefinition(
        name = "agent",
        description = "Show or set your preferred coding agent",
        generateHelp = true
)
public class AgentCommand extends BaseCommand {

    @Argument(description = "The agent to prefer, by its tool's name, or 'none' to unset it")
    String agent;

    @Option(name = "format", completer = Completers.Format.class,
            description = "The output format of the list: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() {
        // Only the list shows what the host has, so setting the agent reads none of its files
        return CommandResult.valueOf(run(RuntimeServices.toolSetups(), agent == null ? HostAgentProbe.probe() : List.of()));
    }

    /** The coding agents isx supports, in the order isx lists its tools. */
    static List<ToolSetup> agents(List<ToolSetup> tools) {
        return tools.stream().filter(ToolSetup::codingAgent).toList();
    }

    int run(List<ToolSetup> tools, List<HostAgentProbe.HostAgent> found) {
        var agents = agents(tools);
        var names = agents.stream().map(ToolSetup::name).toList();
        if (agent != null) {
            if (format != null) throw new IllegalArgumentException("--format is for the list; setting the agent prints nothing on stdout");
            var config = SpawnConfig.loadToChange("set the agent again");
            if (agent.equals("none")) {
                config.setPreferredAgent(null);
                config.save();
                BuildOutput.step("No preferred agent");
                return 0;
            }
            if (!names.contains(agent)) {
                BuildOutput.error("no coding agent named '" + OutputFormat.oneLine(agent) + "'.",
                        "Agents: " + String.join(", ", names) + ", or none.");
                return 1;
            }
            config.setPreferredAgent(agent);
            config.save();
            BuildOutput.step("Preferred agent: " + agent);
            BuildOutput.note("It changes no template: a template installs the agent its tools: list names.");
            return 0;
        }
        var preferred = SpawnConfig.load().getPreferredAgent();
        if (preferred != null && !names.contains(preferred)) {
            BuildOutput.warn("preferred-agent in config.yaml names '" + OutputFormat.oneLine(preferred) + "', which is no coding agent isx supports.",
                    "Set one with 'isx agent <agent>', or remove it with 'isx agent none'.");
        }
        var records = agents.stream().map(a -> record(a, preferred, found)).toList();
        var outputFormat = OutputFormat.parse(format);
        if (outputFormat != OutputFormat.TABLE) {
            outputFormat.print(BuildOutput.results(), records);
            return 0;
        }
        var out = BuildOutput.results();
        out.println(preferred == null ? "No preferred agent." : "Preferred agent: " + OutputFormat.oneLine(preferred));
        var table = Report.table();
        for (var r : records) table.row(Boolean.TRUE.equals(r.get("preferred")) ? "*" : " ", r.get("agent"), r.get("on_host"));
        table.print(out);
        return 0;
    }

    /** One agent's record: {@code agent}, {@code title}, {@code preferred}, and what the probe found of it on this host. */
    static Map<String, Object> record(ToolSetup agent, String preferred, List<HostAgentProbe.HostAgent> found) {
        var record = new LinkedHashMap<String, Object>();
        record.put("agent", agent.name());
        record.put("title", ExampleTemplate.title(agent));
        record.put("preferred", agent.name().equals(preferred));
        record.put("on_host", found.stream().filter(f -> f.agent().equals(agent.name())).findFirst()
                .map(HostAgentProbe.HostAgent::summary).orElse("not found"));
        return record;
    }
}
