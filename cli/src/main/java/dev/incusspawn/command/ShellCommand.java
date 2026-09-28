package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ActionResolver;
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

        var templateName = incus.configGet(name, Metadata.PROFILE);
        if (templateName == null || templateName.isBlank()) {
            templateName = incus.configGet(name, Metadata.PARENT);
        }

        if (templateName != null && !templateName.isBlank()) {
            var imageDefs = ImageDef.loadAll(w -> {});
            var toolDefLoader = RuntimeServices.toolDefLoader();
            var resolver = new ActionResolver(incus, toolDefLoader, RuntimeServices.toolSetups(), imageDefs);
            var menuActions = resolver.resolveShellMenuActions(name, templateName);
            var context = resolver.buildActionContext(name, templateName);
            incus.interactiveShell(name, "agentuser", prep, menuActions, context);
        } else {
            incus.interactiveShell(name, "agentuser", prep);
        }
        return CommandResult.SUCCESS;
    }

}
