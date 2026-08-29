package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

@CommandDefinition(
        name = "destroy",
        description = "Destroy a clone environment",
        generateHelp = true
)
public class DestroyCommand extends BaseCommand {

    @Argument(description = "Name of the environment to destroy", required = true)
    String name;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            return CommandResult.valueOf(1);
        }

        BuildOutput.header("Destroying " + name);

        // Informational note for templates
        var type = Metadata.getType(incus, name);
        if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
            BuildOutput.note("'" + name + "' is a template (type: " + type + ").");
            BuildOutput.note("Destroying it means you won't be able to create new branches from it");
            BuildOutput.note("until you rebuild it. Existing branches are not affected.");
        }

        BuildOutput.stepStart("Removing instance...");
        incus.delete(name, true);
        InstanceLifecycle.removeHostIntegration(name);
        BuildOutput.stepDone();

        BuildOutput.success("Destroyed " + name + ".");
        return CommandResult.SUCCESS;
    }
}
