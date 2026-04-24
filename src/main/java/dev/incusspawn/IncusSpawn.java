package dev.incusspawn;

import dev.incusspawn.command.*;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
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
                ProxyCommand.class,
                CompletionCommand.class,
                TemplatesCommand.class,
                InstancesCommand.class,
                GitRemoteHelperCommand.class
        }
)
public class IncusSpawn implements Runnable {

    @Inject
    CommandLine.IFactory factory;

    @Override
    public void run() {
        if (!InitCommand.requireInit(factory)) return;
        // Default action when no subcommand is given: show the TUI
        new CommandLine(ListCommand.class, factory).execute();
    }

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            var info = BuildInfo.instance();
            return new String[]{
                    "incus-spawn " + info.version() + " (" + info.gitSha() + ")",
                    "incus client " + info.incusClient() + ", server " + info.incusServer(),
                    info.runtime()
            };
        }
    }
}
