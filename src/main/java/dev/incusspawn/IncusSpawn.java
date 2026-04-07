package dev.incusspawn;

import dev.incusspawn.command.*;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@TopCommand
@Command(
        name = "incus-spawn",
        description = "Manage isolated Incus development environments",
        mixinStandardHelpOptions = true,
        versionProvider = IncusSpawn.VersionProvider.class,
        subcommands = {
                InitCommand.class,
                BuildCommand.class,
                ProjectCommand.class,
                BranchCommand.class,
                ShellCommand.class,
                ListCommand.class,
                DestroyCommand.class,
                UpdateAllCommand.class,
                ProxyCommand.class
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

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            var version = ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.application.version", String.class)
                    .orElse("dev");
            return new String[]{"incus-spawn " + version};
        }
    }
}
