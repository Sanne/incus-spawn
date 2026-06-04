package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
        name = "instances",
        description = "List connectable instance names (excludes templates)",
        generateHelp = true
)
public class InstancesCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        incus.list().stream()
                .map(m -> m.get("name"))
                .filter(name -> !name.startsWith("tpl-"))
                .forEach(System.out::println);
        return CommandResult.SUCCESS;
    }
}
