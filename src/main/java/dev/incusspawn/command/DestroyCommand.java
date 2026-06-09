package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.ssh.SshKeyManager;
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

        // Informational message for templates
        var type = Metadata.getType(incus, name);
        if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
            System.out.println("Note: '" + name + "' is a template (type: " + type + ").");
            System.out.println("Destroying it means you won't be able to create new branches from it");
            System.out.println("until you rebuild it. Existing branches are not affected.");
        }

        System.out.println("Destroying " + name + "...");
        incus.delete(name, true);
        AutoRemoteService.removeRemotes(name);
        SshKeyManager.cleanupInstance(name);
        System.out.println("Destroyed " + name + ".");
        return CommandResult.SUCCESS;
    }
}
