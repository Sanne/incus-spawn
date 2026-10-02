package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
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
        var incus = RuntimeServices.incus();

        var parent = InstancePrep.prepareInstance(incus, name);
        if (parent == null) {
            return CommandResult.valueOf(1);
        }

        System.out.println("Connecting to " + name + "...\n");
        var prep = IncusClient.ShellPrep.from(incus, name);
        var menu = ShellMenu.NONE;
        if (ShellMenu.enabled()) {
            // PROFILE is the leaf template; PARENT may be a clone when branched from one.
            var stamps = incus.configByPrefix(name, Metadata.PREFIX);
            var templateName = stamps.getOrDefault("profile", "");
            if (templateName.isBlank()) templateName = stamps.getOrDefault("parent", "");
            menu = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                    RuntimeServices.toolSetups(), ImageDef.loadAll(w -> {}))
                    .shellMenu(name, templateName, prep.workdir());
        }
        incus.interactiveShell(name, "agentuser", prep, menu);
        return CommandResult.SUCCESS;
    }

}
