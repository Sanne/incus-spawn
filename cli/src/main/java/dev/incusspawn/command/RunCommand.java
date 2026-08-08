package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.YamlToolAction;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.util.ArrayList;

@CommandDefinition(
        name = "run",
        description = "Run the default action or a specific action on an instance",
        generateHelp = true
)
public class RunCommand extends BaseCommand {

    @Argument(description = "Name of the instance", required = true)
    String name;

    @Option(name = "action", hasValue = true, description = "Action to run (tool-name or tool-name:action-id). If not specified, runs the default action.")
    String action;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        var parent = InstancePrep.prepareInstance(incus, name);
        if (parent == null) {
            return CommandResult.valueOf(1);
        }

        // Build the action resolver
        var toolDefLoader = RuntimeServices.toolDefLoader();
        var cdiTools = RuntimeServices.toolSetups();
        var imageDefs = ImageDef.loadAll(w -> {});
        var resolver = new ActionResolver(incus, toolDefLoader, cdiTools, imageDefs);

        var installedTools = resolver.collectInstalledTools(parent);
        var repos = resolver.collectRepos(parent);

        // Find the action to execute
        ToolAction toolAction;
        if (action == null || action.isBlank()) {
            // Execute default action
            var defaultAction = resolver.findDefaultAction(name, parent, installedTools, repos);
            if (defaultAction.isEmpty()) {
                // No default action configured, fall back to shell
                System.out.println("No default action configured for " + name + ", opening shell...\n");
                incus.interactiveShell(name, "agentuser");
                return CommandResult.SUCCESS;
            }
            toolAction = defaultAction.get();
            System.out.println("Running default action: " + toolAction.label());
        } else {
            // Execute named action
            var allActions = resolver.resolveActionsForInstance(name, parent, installedTools, repos);
            var namedAction = resolver.findActionByRef(action, allActions);
            if (namedAction.isEmpty()) {
                System.err.println("Error: action '" + action + "' not found for instance '" + name + "'.");
                System.err.println("Available actions:");

                var actionsByTool = new java.util.LinkedHashMap<String, java.util.List<ToolAction>>();
                for (var a : allActions) {
                    actionsByTool.computeIfAbsent(a.toolName(), k -> new ArrayList<>()).add(a);
                }

                for (var entry : actionsByTool.entrySet()) {
                    var toolName = entry.getKey();
                    var actions = entry.getValue();
                    if (actions.size() == 1 && actions.get(0).id().isEmpty()) {
                        System.err.println("  " + toolName + " - " + actions.get(0).label());
                    } else {
                        for (var a : actions) {
                            var ref = a.id().map(id -> toolName + ":" + id).orElse(toolName);
                            System.err.println("  " + ref + " - " + a.label());
                        }
                    }
                }

                return CommandResult.valueOf(1);
            }
            toolAction = namedAction.get();
            System.out.println("Running action: " + toolAction.label());
        }

        // Build action context
        var context = resolver.buildActionContext(name, parent);

        // Execute the action
        var cmd = toolAction.shellCommand(context);
        if (cmd.isPresent()) {
            // Action wants to run a shell command
            System.out.println("Connecting to " + name + "...\n");
            var prep = dev.incusspawn.incus.IncusClient.ShellPrep.from(incus, name);
            var shellCmd = cmd.get();
            var updatedPrep = new dev.incusspawn.incus.IncusClient.ShellPrep(
                    prep.workdir(), shellCmd, prep.autoAttachTmux(), prep.autoAttachZmx(),
                    prep.subnetDiagnostic(), prep.terminfoHandled());
            incus.interactiveShell(name, "agentuser", updatedPrep);
            return CommandResult.SUCCESS;
        }

        // Action executes directly (e.g., opens URL, copies to clipboard)
        var result = toolAction.execute(context);
        System.out.println(result.message());

        // For YAML actions with auto_return: false, wait for keypress (only in interactive terminals)
        if (toolAction instanceof YamlToolAction yamlAction && !yamlAction.shouldAutoReturn()) {
            if (System.console() != null) {
                System.out.println("\nPress any key to continue...");
                try {
                    System.in.read();
                } catch (java.io.IOException ignored) {}
            }
        }

        return CommandResult.SUCCESS;
    }
}
