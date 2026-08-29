package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;

// Registered only on macOS (see IncusSpawn): the appliance VM hosts the Incus daemon there,
// whereas on Linux Incus runs natively and there is no VM to manage — so `isx vm` does not
// exist on Linux at all.
@CommandDefinition(
        name = "vm",
        description = "Manage the incus-spawn VM appliance (macOS)",
        generateHelp = true,
        groupCommands = {
                VmCommand.Start.class,
                VmCommand.Stop.class,
                VmCommand.Status.class,
                VmCommand.Resize.class,
                VmCommand.Console.class
        }
)
public class VmCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
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
            BuildOutput.header("Starting VM");
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
            BuildOutput.header("Stopping VM");
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
            System.out.println(VmManager.status());
            var incus = RuntimeServices.incus();
            var connError = incus.checkConnectivity();
            if (connError != null) {
                System.out.println("\nIncus not reachable: " + connError);
            } else {
                var pool = incus.findCowPool();
                System.out.println();
                System.out.println(incus.getSystemDiagnostics(pool));
                System.out.println("  (full VM log at " + Environment.vmLogFile() + ")");
            }
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(
            name = "resize",
            description = "Grow the VM data disk that backs the storage pool",
            generateHelp = true
    )
    public static class Resize extends BaseCommand {

        @Argument(description = "New size, e.g. 100G (must be larger than current; grow-only)",
                required = true)
        String size;

        @Option(name = "yes", shortName = 'y', hasValue = false,
                description = "Skip the confirmation prompt")
        boolean yes;

        @Override
        protected CommandResult doExecute() throws Exception {
            // Validate the requested size and read the current disk size before touching anything.
            long target = VmManager.parseDiskSize(size);
            long current = VmManager.dataDiskSizeBytes();
            if (current < 0) {
                System.err.println("No data disk found. Run 'isx vm start' once to create it before resizing.");
                return CommandResult.valueOf(1);
            }
            if (target <= current) {
                System.err.println("New size (" + VmManager.humanSize(target)
                        + ") must be larger than the current size (" + VmManager.humanSize(current)
                        + "). Shrinking is not supported.");
                return CommandResult.valueOf(1);
            }

            // Advisory: the image is sparse, but the host must still be able to hold the extra
            // blocks as the pool fills. Warn (don't block) if the growth exceeds host free space.
            try {
                long usable = Files.getFileStore(Environment.vmDataImage()).getUsableSpace();
                if (target - current > usable) {
                    System.out.println("Warning: growing by " + VmManager.humanSize(target - current)
                            + " exceeds the " + VmManager.humanSize(usable)
                            + " free on the host. The disk is thin-provisioned, but writes will fail");
                    System.out.println("once the host runs out of space.");
                }
            } catch (IOException ignored) {}

            boolean wasRunning = VmManager.isRunning();
            System.out.println("Grow the storage pool's data disk from " + VmManager.humanSize(current)
                    + " to " + VmManager.humanSize(target) + "?");
            if (wasRunning) {
                System.out.println("The VM will be restarted; running instances will be interrupted.");
            }
            if (!CleanCommand.confirm("Continue?", yes)) {
                return CommandResult.SUCCESS;
            }

            var currentH = VmManager.humanSize(current);
            var targetH = VmManager.humanSize(target);
            BuildOutput.header("Resizing VM data disk", currentH + " → " + targetH);

            if (wasRunning) {
                VmManager.stop();
            }

            BuildOutput.stepStart("Growing disk image to " + targetH + " (sparse)...");
            VmManager.resizeDataDisk(size);
            BuildOutput.stepDone();

            // start() prints its own steps (root-disk replacement, launch); the boot is only needed
            // so the guest can expand btrfs and we can verify.
            if (!VmManager.start()) {
                System.err.println("Failed to start VM after resize.");
                return CommandResult.valueOf(1);
            }
            // Once the VM is up, restore its prior state on every exit path (below): a later failure
            // must not leave a previously-stopped VM running.
            try {
                BuildOutput.stepStart("Waiting for Incus daemon...");
                if (!VmManager.waitUntilReady(60)) {
                    BuildOutput.stepFail("VM started but Incus did not become reachable within 60s.");
                    System.err.println("Check 'isx vm console' for boot logs.");
                    return CommandResult.valueOf(1);
                }
                BuildOutput.stepDone();

                // Verify the guest actually grew the pool to fill the larger device.
                var incus = RuntimeServices.incus();
                var pool = incus.findUsablePool();
                var usage = pool == null ? null : incus.getPoolUsageBytes(pool);
                if (usage != null && usage.totalBytes() > 0) {
                    // btrfs reports a total somewhat below the raw device size (fs overhead), so
                    // don't compare against the requested `target` directly. A pool that grew sits
                    // near `target`; one that didn't sits near `current`. The midpoint separates the
                    // two cleanly and is immune to that overhead.
                    if (usage.totalBytes() >= (current + target) / 2) {
                        BuildOutput.success("Storage pool is now " + VmManager.humanSize(usage.totalBytes())
                                + " (" + usage.percent() + "% used).");
                    } else {
                        System.out.println();
                        BuildOutput.note("The data disk image was grown, but the storage pool did not expand.");
                        BuildOutput.note("Your appliance may predate automatic data-disk expansion. Upgrade isx");
                        BuildOutput.note("(so it re-downloads the appliance) to pick up the change.");
                    }
                }
                return CommandResult.SUCCESS;
            } finally {
                // Best-effort: the resize already succeeded, so a stop failure must not mask it.
                if (!wasRunning) {
                    BuildOutput.note("VM was stopped before the resize — stopping it again.");
                    try {
                        VmManager.stop();
                    } catch (Exception e) {
                        System.err.println("Note: could not stop the VM again (" + e.getMessage()
                                + "). The resize succeeded; stop it manually with 'isx vm stop'.");
                    }
                }
            }
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
}
