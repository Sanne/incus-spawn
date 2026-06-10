package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

import java.io.IOException;
import java.nio.file.Files;

@CommandDefinition(
        name = "vm",
        description = "Manage the incus-spawn VM appliance (macOS: vfkit, Linux: QEMU)",
        generateHelp = true,
        groupCommands = {
                VmCommand.Start.class,
                VmCommand.Stop.class,
                VmCommand.Status.class,
                VmCommand.Console.class
        }
)
public class VmCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        if (Environment.isLinux()) {
            printLinuxMessage();
            return CommandResult.SUCCESS;
        }
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    @CommandDefinition(
            name = "start",
            description = "Start the VM (creates disk image on first run)",
            generateHelp = true
    )
    public static class Start extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (Environment.isLinux()) {
                printLinuxMessage();
                return CommandResult.SUCCESS;
            }
            return VmManager.start() ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }
    }

    @CommandDefinition(
            name = "stop",
            description = "Stop the VM (graceful shutdown)",
            generateHelp = true
    )
    public static class Stop extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (Environment.isLinux()) {
                printLinuxMessage();
                return CommandResult.SUCCESS;
            }
            VmManager.stop();
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "status",
            description = "Show VM status and system diagnostics",
            generateHelp = true
    )
    public static class Status extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (Environment.isLinux()) {
                var incus = RuntimeServices.incus();
                var pool = incus.findCowPool();
                System.out.println(incus.getSystemDiagnostics(pool));
                return CommandResult.SUCCESS;
            }
            System.out.println(VmManager.status());
            var incus = RuntimeServices.incus();
            var connError = incus.checkConnectivity();
            if (connError != null) {
                System.out.println("\nIncus not reachable: " + connError);
            } else {
                var pool = incus.findCowPool();
                System.out.println();
                System.out.println(incus.getSystemDiagnostics(pool));
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "console",
            description = "Follow VM serial console output",
            generateHelp = true
    )
    public static class Console extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (Environment.isLinux()) {
                printLinuxMessage();
                return CommandResult.SUCCESS;
            }
            var logFile = Environment.vmLogFile();
            if (!Files.exists(logFile)) {
                System.err.println("No VM log file found at " + logFile);
                System.err.println("Start the VM first: isx vm start");
                return CommandResult.valueOf(1);
            }
            try {
                var pb = new ProcessBuilder("tail", "-f", logFile.toString());
                pb.inheritIO();
                pb.start().waitFor();
            } catch (IOException | InterruptedException e) {
                System.err.println("Failed to tail log file: " + e.getMessage());
            }
            return CommandResult.SUCCESS;
        }
    }

    private static void printLinuxMessage() {
        System.out.println("VM management is not needed — Incus runs natively on Linux.");
        System.out.println("The 'isx vm' commands are for macOS, where a lightweight Linux VM");
        System.out.println("hosts the Incus daemon.");
    }
}
