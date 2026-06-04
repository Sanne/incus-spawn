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

    @Option(name = "force", description = "Force destruction, even for templates", hasValue = false)
    boolean force;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            return CommandResult.valueOf(1);
        }

        // Safety check: refuse to destroy templates without --force
        var type = Metadata.getType(incus, name);
        if ((Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) && !force) {
            System.err.println("Error: '" + name + "' is a template (type: " + type + ").");
            System.err.println("Destroying templates affects all branches derived from them.");
            System.err.println("Use --force if you really want to destroy it.");
            return CommandResult.valueOf(1);
        }

        System.out.println("Destroying " + name + "...");
        incus.delete(name, true);
        AutoRemoteService.removeRemotes(name);
        SshKeyManager.cleanupInstance(name);
        System.out.println("Destroyed " + name + ".");
        return CommandResult.SUCCESS;
    }
}
