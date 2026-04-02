package dev.incusspawn;

import dev.incusspawn.command.*;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@TopCommand
@Command(
        name = "incus-spawn",
        description = "Manage isolated Incus development environments",
        mixinStandardHelpOptions = true,
        version = "incus-spawn 0.1.0",
        subcommands = {
                InitCommand.class,
                BuildCommand.class,
                ProjectCommand.class,
                CreateCommand.class,
                ShellCommand.class,
                ListCommand.class,
                DestroyCommand.class,
                BranchCommand.class,
                UpdateAllCommand.class
        }
)
public class IncusSpawn implements Runnable {

    @Inject
    CommandLine.IFactory factory;

    @Override
    public void run() {
        // Default action when no subcommand is given: show the list
        new CommandLine(ListCommand.class, factory).execute();
    }
}
