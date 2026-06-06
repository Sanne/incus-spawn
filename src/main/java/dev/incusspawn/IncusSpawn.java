package dev.incusspawn;

import dev.incusspawn.command.*;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

@QuarkusMain
public class IncusSpawn implements QuarkusApplication {
    @Override
    public int run(String... args) {
        if (args.length == 0) {
            return launchTui() ? 0 : 1;
        }
        try {
            var result = AeshRuntimeRunner.builder()
                    .command(IncusSpawnCommand.class)
                    .args(args)
                    .execute();
            return result != null ? result.getResultValue() : 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static boolean launchTui() {
        if (!InitCommand.requireInit()) return false;
        new ListCommand().executeDirect();
        return true;
    }

    @CommandDefinition(name = "incus-spawn",
            description = "Manage isolated Incus development environments",
            groupCommands = {
                InitCommand.class, BuildCommand.class, ProjectCommand.class,
                BranchCommand.class, ShellCommand.class, ListCommand.class,
                DestroyCommand.class, UpdateAllCommand.class, ProxyCommand.class,
                CompletionCommand.class, TemplatesCommand.class, InstancesCommand.class,
                GitRemoteHelperCommand.class, SshProxyCommand.class, VmCommand.class
            }, generateHelp = true)
    public static class IncusSpawnCommand extends BaseCommand {
        @Option(shortName = 'V', name = "version", hasValue = false, description = "Display version info")
        boolean versionRequested;
        @Override
        protected CommandResult doExecute() {
            if (versionRequested) {
                var info = BuildInfo.instance();
                System.out.println("incus-spawn " + info.version() + " (" + info.gitSha() + ")");
                System.out.println("incus client " + info.incusClient() + ", server " + info.incusServer());
                System.out.println(info.runtime());
                return CommandResult.SUCCESS;
            }
            return launchTui() ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }
    }
}
