package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.tool.ShellMenu;
import dev.incusspawn.tool.ToolAction;
import dev.incusspawn.tool.YamlToolAction;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalLink;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@CommandDefinition(
        name = "run",
        description = "Run the default action or a specific action on an instance",
        generateHelp = true
)
public class RunCommand extends BaseCommand {

    @Argument(completer = Completers.Instance.class, description = "Name of the instance", required = true)
    String name;

    @Option(name = "action", hasValue = true, description = "Action to run, as tool-name or tool-name:action-id (default: the template's default action, or a shell when it does not have one)")
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

        // One read for the tools, the default action's stamp, the action's context and the shell
        // (#1159); the context is built only where it is used, since it may ask for /state.
        var instance = resolver.readInstance(name);
        var installedTools = resolver.collectInstalledTools(instance, parent);
        var repos = resolver.collectRepos(instance, parent);
        var allActions = resolver.resolveActionsForInstance(name, parent, installedTools, repos);

        // Find the action to execute
        ToolAction toolAction;
        if (action == null || action.isBlank()) {
            // Execute default action
            var ref = resolver.defaultActionRef(instance, parent);
            var noDefault = noDefaultAction(name, ref, allActions);
            if (noDefault != null) {
                // Fall back to shell; a broken reference is a warning
                if (ref == null) BuildOutput.step(noDefault + ", opening a shell");
                else BuildOutput.warn(noDefault + ".", "Opening a shell instead.");
                var prep = dev.incusspawn.incus.IncusClient.ShellPrep.fromInstance(incus, instance);
                incus.interactiveShell(name, "agentuser", prep,
                        resolver.shellMenu(name, instance, parent, prep.workdir()));
                ShellFarewell.say(name);
                return CommandResult.SUCCESS;
            }
            toolAction = ActionResolver.findActionByRef(ref, allActions).orElseThrow();
            BuildOutput.step("Running default action: " + toolAction.label());
        } else {
            // Execute named action
            var namedAction = ActionResolver.findActionByRef(action, allActions);
            if (namedAction.isEmpty()) {
                var available = new ArrayList<String>();
                var actionsByTool = new LinkedHashMap<String, List<ToolAction>>();
                for (var a : allActions) {
                    actionsByTool.computeIfAbsent(a.toolName(), k -> new ArrayList<>()).add(a);
                }

                for (var entry : actionsByTool.entrySet()) {
                    var toolName = entry.getKey();
                    var actions = entry.getValue();
                    if (actions.size() == 1 && actions.get(0).id().isEmpty()) {
                        available.add(toolName + " - " + actions.get(0).label());
                    } else {
                        for (var a : actions) {
                            var ref = a.id().map(id -> toolName + ":" + id).orElse(toolName);
                            available.add(ref + " - " + a.label());
                        }
                    }
                }
                BuildOutput.error("action '" + action + "' not found for instance '" + name + "'."
                        + (available.isEmpty() ? "" : " Available actions:"),
                        available.toArray(String[]::new));
                return CommandResult.valueOf(1);
            }
            toolAction = namedAction.get();
            BuildOutput.step("Running action: " + toolAction.label());
        }

        var context = resolver.buildActionContext(name, instance, parent);

        // Execute the action
        var cmd = toolAction.shellCommand(context);
        if (cmd.isPresent()) {
            // Action wants to run a shell command
            BuildOutput.step("Connecting to " + name);
            var prep = dev.incusspawn.incus.IncusClient.ShellPrep.fromInstance(incus, instance);
            var shellCmd = cmd.get();
            var updatedPrep = new dev.incusspawn.incus.IncusClient.ShellPrep(
                    prep.workdir(), shellCmd, prep.autoAttachTmux(), prep.autoAttachZmx(),
                    prep.subnetDiagnostic(), prep.terminfoHandled(), prep.templateName());
            var menu = ShellMenu.enabled()
                    ? ShellMenu.of(allActions, prep.workdir(), context)
                    : ShellMenu.NONE;
            incus.interactiveShell(name, "agentuser", updatedPrep, menu);
            ShellFarewell.say(name);
            return CommandResult.SUCCESS;
        }

        // Action executes directly (e.g., opens URL, copies to clipboard); it may ask only a terminal
        var prompts = Prompts.terminal();
        var result = toolAction.execute(context, prompts == null ? null : prompts::ask);
        var succeeded = report(result);

        // For YAML actions with auto_return: false, wait for keypress (only in interactive terminals)
        if (toolAction instanceof YamlToolAction yamlAction && !yamlAction.shouldAutoReturn()) {
            if (prompts != null) prompts.ask("Press Enter to continue...");
        }

        return succeeded ? CommandResult.SUCCESS : CommandResult.valueOf(1);
    }

    /**
     * What an action that ran directly did: a step, or why it failed as an error. Returns whether it
     * succeeded. A success may quote the action's own text (a copy-to-clipboard action without a
     * clipboard shows it), which a tool's YAML chooses, so nothing in it may drive the terminal
     * ({@link TerminalLink#plainText}); its tabs and line breaks stay, since it is there to be copied.
     */
    public static boolean report(ActionResult result) {
        var message = result.message();
        if (result.success()) {
            if (message != null && !message.isBlank()) BuildOutput.step(TerminalLink.plainText(message));
        }
        else BuildOutput.error(message == null || message.isBlank() ? "the action failed." : message);
        return result.success();
    }

    /**
     * Why {@code isx run} opens a shell instead of the default action {@code ref}, or null when
     * {@code ref} resolves among {@code actions}: none configured, or what is wrong with it (#1444).
     */
    static String noDefaultAction(String name, String ref, List<ToolAction> actions) {
        return ref == null ? "No default action configured for " + name
                : ActionResolver.unresolvedReason(ref, actions);
    }
}
