package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                VmCommand.Restart.class,
                VmCommand.Status.class,
                VmCommand.Resize.class,
                VmCommand.Reset.class,
                VmCommand.Console.class,
                VmCommand.CheckVersion.class
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
            name = "restart",
            description = "Stop and restart the VM (applies pending appliance updates)",
            generateHelp = true
    )
    public static class Restart extends BaseCommand {
        @Option(name = "yes", shortName = 'y', hasValue = false,
                description = "Skip the confirmation prompt")
        boolean yes;

        @Override
        protected CommandResult doExecute() throws Exception {
            if (!VmManager.isRunning()) {
                System.out.println("VM is not running. Use 'isx vm start' to start it.");
                return CommandResult.SUCCESS;
            }
            var skew = VmManager.applianceSkew();
            if (skew != null) {
                System.out.println("Appliance update pending (" + skew.running() + " → " + skew.installed() + ").");
            }
            System.out.println("Running containers will be stopped.");
            if (!CleanCommand.confirm("Restart the VM?", yes)) {
                return CommandResult.SUCCESS;
            }
            BuildOutput.header("Restarting VM");
            return VmManager.restart() ? CommandResult.SUCCESS : CommandResult.valueOf(1);
        }
    }

    @CommandDefinition(
            name = "status",
            description = "Show VM status and system diagnostics",
            generateHelp = true
    )
    public static class Status extends BaseCommand {

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            if (outputFormat != OutputFormat.TABLE) {
                var state = VmManager.state();
                var connError = RuntimeServices.incus().checkConnectivity();
                outputFormat.printOne(System.out, record(state, connError));
                return CommandResult.valueOf(exitCode(connError));
            }
            return CommandResult.valueOf(report(VmManager.status(), RuntimeServices.incus(), System.out, System.err));
        }

        /** Print the VM's status and Incus's diagnostics; 1, with the reason on {@code err}, when Incus is unreachable. */
        static int report(String vmStatus, IncusClient incus, PrintStream out, PrintStream err) {
            out.println(vmStatus);
            var connError = incus.checkConnectivity();
            if (connError != null) {
                err.println("\nIncus not reachable: " + connError);
                return exitCode(connError);
            }
            var pool = incus.findCowPool();
            out.println();
            out.println(incus.getSystemDiagnostics(pool));
            out.println("  (full VM log at " + Environment.vmLogFile() + ")");
            return exitCode(connError);
        }

        /** The exit code of every format: 1 when Incus did not answer ({@code incusError} says why), else 0. */
        static int exitCode(String incusError) {
            return incusError == null ? 0 : 1;
        }

        /**
         * The fields of {@code isx vm status --format=plain|json}, in order: add to the end, never
         * rename. What the VM does not report is {@code null}; {@code appliance_pending} is the
         * installed appliance a restart would apply, {@code null} when it is the one running;
         * {@code incus_error} says why Incus did not answer, which also makes the command exit 1;
         * {@code vsock_connections_high} is the table's warning that the in-VM forwarder may be
         * leaking streams ({@code isx vm restart} clears them).
         */
        static Map<String, Object> record(VmManager.State state, String incusError) {
            var record = new LinkedHashMap<String, Object>();
            record.put("running", state.running());
            record.put("pid", state.pid() < 0 ? null : state.pid());
            record.put("rest_api", state.restApi());
            record.put("log", state.log());
            record.put("appliance", state.appliance());
            record.put("appliance_pending", state.applianceInstalled());
            record.put("vsock_connections", state.vsockConnections() < 0 ? null : state.vsockConnections());
            record.put("incus_reachable", incusError == null);
            record.put("incus_error", incusError);
            record.put("vsock_connections_high", state.vsockConnections() < 0 ? null
                    : state.vsockConnections() > VmManager.VSOCK_CONN_WARN_THRESHOLD);
            return record;
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

            // start() prints its own steps (root-disk replacement, launch, readiness); the boot is
            // only needed so the guest can expand btrfs and we can verify. Wrap in try/finally so a
            // previously-stopped VM is restored even when start fails after launching (readiness timeout).
            try {
                if (!VmManager.start()) {
                    System.err.println("Failed to start VM after resize.");
                    return CommandResult.valueOf(1);
                }
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
            name = "reset",
            description = "Wipe the VM data disk: deletes every instance, template and cached image",
            generateHelp = true
    )
    public static class Reset extends BaseCommand {
        @Option(name = "yes", shortName = 'y', hasValue = false,
                description = "Skip the confirmation prompt")
        boolean yes;

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            // Only Incus can say which instances are on the disk, and the host keeps state for
            // each (SSH config, git remotes) that the reset must remove: a stopped VM is booted to
            // ask. Booting it is also the reset's own last step, so this costs a boot, not a risk.
            if (!VmManager.isRunning()) {
                BuildOutput.note("The VM is not running: starting it to list what the reset deletes.");
                VmManager.start();
            }
            var lost = survey(incus);

            System.out.println("This deletes the VM's data disk (" + Environment.vmDataImage() + "),");
            System.out.println("which holds everything Incus knows: its database, the storage pool and all it contains.");
            System.out.println("The appliance itself is kept, so nothing needs to be downloaded again.");
            System.out.println();
            if (lost == null) {
                System.out.println("Incus is not reachable, so what is on the disk cannot be listed.");
            } else {
                lost.print();
            }
            System.out.println();
            if (!confirmDestructive("Wipe the data disk?", yes, "--yes")) {
                return CommandResult.SUCCESS;
            }

            BuildOutput.header("Resetting VM data disk");
            var result = VmManager.resetDataDisk();
            if (result == VmManager.ResetResult.NOT_RESET) {
                System.err.println("The data disk was not reset; nothing was deleted.");
                return CommandResult.valueOf(1);
            }
            // The instances are gone with the disk, whether or not the VM came back: drop what the
            // host keeps for them and let the proxy forget their addresses.
            var gone = lost == null ? List.<String>of() : lost.all();
            for (var name : gone) {
                try {
                    InstanceLifecycle.removeHostIntegration(name);
                } catch (Exception e) {
                    System.err.println("Note: could not remove host integration for " + name + ": " + e.getMessage());
                }
            }
            if (!gone.isEmpty()) InstanceDestroyer.refreshProxy();
            if (lost == null) {
                System.err.println("Note: the instances on the old disk could not be listed, so the host still"
                        + " holds their SSH config entries and git remotes. Remove those by hand if they get in the way.");
            }
            if (result == VmManager.ResetResult.VM_DOWN) {
                System.err.println("The data disk was reset, but the VM did not come back. Check 'isx vm console'.");
                return CommandResult.valueOf(1);
            }

            var problem = verifyEmpty(incus);
            if (problem != null) {
                System.err.println("The VM restarted, but " + problem + ". Check 'isx vm status'.");
                return CommandResult.valueOf(1);
            }
            BuildOutput.success("Data disk reset: Incus is up and its storage pool is empty.");
            System.out.println("Recreate your templates with: isx build --all");
            return CommandResult.SUCCESS;
        }

        record Survey(List<String> instances, List<String> templates, List<String> others,
                      int images, String poolUsage) {
            List<String> all() {
                var all = new ArrayList<String>(instances);
                all.addAll(templates);
                all.addAll(others);
                return all;
            }

            void print() {
                printGroup("instance(s), which cannot be rebuilt", instances);
                printGroup("template(s), which 'isx build' can recreate", templates);
                printGroup("other instance(s), such as failed builds", others);
                System.out.println("  " + images + " cached image(s)");
                if (poolUsage != null) System.out.println("  Storage pool: " + poolUsage);
            }

            private static void printGroup(String what, List<String> names) {
                if (names.isEmpty()) return;
                System.out.println("  " + names.size() + " " + what + ":");
                names.forEach(n -> System.out.println("    " + n));
            }
        }

        /** What the reset will delete, or null when Incus cannot be asked. */
        static Survey survey(IncusClient incus) {
            if (!VmManager.isRunning() || incus.checkConnectivity() != null) return null;
            try {
                // The same notion of instance and template as 'isx reset' and 'isx destroy'.
                var instances = DestroyCommand.listClones(incus);
                var templates = DestroyCommand.listBuiltTemplates(incus);
                var others = new ArrayList<String>();
                for (var inst : incus.list()) {
                    var name = inst.get("name");
                    if (!instances.contains(name) && !templates.contains(name)) others.add(name);
                }
                String usage = null;
                var pool = incus.findCowPool();
                var bytes = pool == null ? null : incus.getPoolUsageBytes(pool);
                if (bytes != null && bytes.totalBytes() > 0) {
                    usage = VmManager.humanSize(bytes.usedBytes()) + " used of "
                            + VmManager.humanSize(bytes.totalBytes());
                }
                return new Survey(instances, templates, others, incus.listImages().size(), usage);
            } catch (Exception e) {
                return null;
            }
        }

        /** Null when Incus answers with a CoW pool and nothing in it, else what is wrong. */
        static String verifyEmpty(IncusClient incus) {
            try {
                var connError = incus.checkConnectivity();
                if (connError != null) return "Incus is not reachable (" + connError + ")";
                if (incus.findCowPool() == null) return "Incus has no copy-on-write storage pool";
                if (!incus.list().isEmpty()) return "Incus still lists instances";
                if (!incus.listImages().isEmpty()) return "Incus still lists images";
                return null;
            } catch (Exception e) {
                return "Incus could not be checked (" + e.getMessage() + ")";
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

    @CommandDefinition(
            name = "check-version",
            description = "Check whether the running appliance matches the installed version",
            generateHelp = true
    )
    public static class CheckVersion extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            if (!VmManager.isRunning()) return CommandResult.SUCCESS;
            var skew = VmManager.applianceSkew();
            if (skew == null) return CommandResult.SUCCESS;
            System.out.println(skew.message());
            return CommandResult.SUCCESS;
        }
    }
}
