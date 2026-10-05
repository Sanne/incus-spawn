package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.NoTerminalForShellException;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ShellMenu;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

@CommandDefinition(
        name = "shell",
        description = "Open a shell in an existing clone",
        generateHelp = true
)
public class ShellCommand extends BaseCommand {

    @Argument(description = "Name of the clone to connect to", required = true)
    String name;

    @Override
    protected CommandResult doExecute() throws Exception {
        // Refuse before starting the instance: without a terminal there is no shell to open.
        if (!IncusClient.hasTerminal()) throw new NoTerminalForShellException(name);
        var incus = RuntimeServices.incus();

        var parent = InstancePrep.prepareInstance(incus, name);
        if (parent == null) {
            return CommandResult.valueOf(1);
        }

        System.out.println("Connecting to " + name + "...\n");
        var prep = IncusClient.ShellPrep.from(incus, name);
        var menu = ShellMenu.NONE;
        if (ShellMenu.enabled()) {
            menu = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                    RuntimeServices.toolSetups(), ImageDef.loadAll(w -> {}))
                    .shellMenu(name, prep.templateName(), prep.workdir());
        }
        incus.interactiveShell(name, "agentuser", prep, menu);
        return CommandResult.SUCCESS;
    }

}
