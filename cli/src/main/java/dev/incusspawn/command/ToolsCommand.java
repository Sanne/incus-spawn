package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.util.TreeMap;

@CommandDefinition(
        name = "tools",
        description = "List and inspect available tool definitions",
        generateHelp = true,
        groupCommands = {
                ToolsCommand.ListSub.class,
                ToolsCommand.Show.class
        }
)
public class ToolsCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        return new ListSub().doExecute();
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "list", description = "List available tools",
            generateHelp = true)
    public static class ListSub extends BaseCommand {

        @Option(shortName = 'v', name = "verbose", description = "Show source and description",
                hasValue = false)
        boolean verbose;

        @Override
        protected CommandResult doExecute() throws Exception {
            var loader = RuntimeServices.toolDefLoader();
            var tools = new TreeMap<>(loader.allToolSetups());
            if (!verbose) {
                tools.keySet().forEach(System.out::println);
                return CommandResult.SUCCESS;
            }
            int maxName = tools.keySet().stream().mapToInt(String::length).max().orElse(10);
            int maxSource = tools.values().stream()
                    .mapToInt(t -> loader.getSource(t.name()).length())
                    .max().orElse(7);
            var fmt = "%-" + maxName + "s  %-" + maxSource + "s  %s%n";
            System.out.printf(fmt, "NAME", "SOURCE", "DESCRIPTION");
            for (var entry : tools.entrySet()) {
                System.out.printf(fmt, entry.getKey(),
                        loader.getSource(entry.getKey()), entry.getValue().description());
            }
            return CommandResult.SUCCESS;
        }
    }

    // ── show ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "show", description = "Show details of a tool definition",
            generateHelp = true)
    public static class Show extends BaseCommand {

        @Argument(required = true, description = "Tool name")
        String name;

        @Override
        protected CommandResult doExecute() throws Exception {
            var loader = RuntimeServices.toolDefLoader();
            var tools = loader.allToolSetups();
            var tool = tools.get(name);
            if (tool == null) {
                System.err.println("Tool '" + name + "' not found.");
                System.err.println("Available tools: " + String.join(", ",
                        new TreeMap<>(tools).keySet()));
                return CommandResult.valueOf(1);
            }

            System.out.println(tool.name());
            System.out.println("  Description:  " + tool.description());
            System.out.println("  Source:        " + loader.getSource(name));
            if (tool.feature() != null) {
                System.out.println("  Feature gate:  " + tool.feature());
            }

            printRequires(tool);
            printPackages(tool);
            printParameters(tool);
            printActions(tool);
            printDownloads(tool);
            printProxy(tool);

            return CommandResult.SUCCESS;
        }

        private static void printRequires(ToolSetup tool) {
            var requires = tool.requires();
            if (!requires.isEmpty()) {
                System.out.println("  Requires:      " + String.join(", ", requires));
            }
        }

        private static void printPackages(ToolSetup tool) {
            var packages = tool.packages();
            if (!packages.isEmpty()) {
                System.out.println("  Packages:      " + String.join(", ", packages));
            }
        }

        private static void printParameters(ToolSetup tool) {
            var params = tool.parameters();
            if (params.isEmpty()) return;
            System.out.println("  Parameters:");
            for (var entry : params.entrySet()) {
                var p = entry.getValue();
                var sb = new StringBuilder("    ").append(entry.getKey());
                if (p.getType() != null) sb.append(" (").append(p.getType()).append(')');
                if (p.getDefault() != null) sb.append(" default=").append(p.getDefault());
                System.out.println(sb);
                if (p.getDescription() != null && !p.getDescription().isBlank()) {
                    System.out.println("      " + p.getDescription());
                }
                if (p.getOptions() != null && !p.getOptions().isEmpty()) {
                    System.out.println("      options: " + String.join(", ", p.getOptions()));
                }
            }
        }

        private static void printActions(ToolSetup tool) {
            var actions = tool.actions();
            if (actions.isEmpty()) return;
            System.out.println("  Actions:");
            for (var a : actions) {
                var sb = new StringBuilder("    ").append(a.getLabel());
                if (a.getType() != null) sb.append(" (").append(a.getType()).append(')');
                System.out.println(sb);
            }
        }

        private static void printDownloads(ToolSetup tool) {
            if (!(tool instanceof YamlToolSetup yaml)) return;
            var downloads = yaml.toolDef().getDownloads();
            if (downloads.isEmpty()) return;
            System.out.println("  Downloads:");
            for (var dl : downloads) {
                var sb = new StringBuilder("    ").append(dl.getUrl());
                if (dl.getArch() != null) sb.append(" [").append(dl.getArch()).append(']');
                System.out.println(sb);
            }
        }

        private static void printProxy(ToolSetup tool) {
            var proxy = tool.proxy();
            if (proxy == null) return;
            var auth = proxy.getAuth();
            if (auth == null || auth.isEmpty()) return;
            System.out.println("  Proxy domains:");
            for (var a : auth) {
                if (a.getDomains() != null) {
                    for (var domain : a.getDomains()) {
                        System.out.println("    " + domain + " (" + a.getType() + ")");
                    }
                }
            }
        }
    }
}
