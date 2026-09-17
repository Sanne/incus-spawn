package dev.incusspawn.command;

import dev.incusspawn.Platform;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.Environment;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.nio.file.Files;
import java.util.List;

@CommandDefinition(
        name = "reset",
        description = "Return to a freshly-installed state: destroys all containers, templates, VM, proxy, routing rules, and cached data",
        generateHelp = true
)
public class ResetCommand extends BaseCommand {

    @Option(name = "skip-confirmation", hasValue = false,
            description = "Skip the confirmation prompt")
    boolean skipConfirmation;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        // --- Survey what exists ---

        // Incus may be unreachable (e.g. macOS VM is stopped), so tolerate failures
        // in the Incus-dependent survey and still proceed with host-side cleanup.
        List<String> clones = List.of();
        List<String> builtTemplates = List.of();
        boolean hasPoolArtifacts = false;
        boolean incusReachable = true;
        try {
            clones = DestroyCommand.listClones(incus);
            builtTemplates = DestroyCommand.listBuiltTemplates(incus);
            var poolScan = CleanCommand.scanPool(incus);
            hasPoolArtifacts = poolScan != null &&
                    (!poolScan.failedBuilds().isEmpty() || !poolScan.unusedImages().isEmpty() || poolScan.dnfCacheExists());
        } catch (Exception e) {
            incusReachable = false;
            System.err.println("Warning: could not reach Incus daemon: " + e.getMessage());
            System.err.println("Incus-managed resources (containers, templates, pool) will be skipped.");
        }

        boolean proxyInstalled = ProxyService.isInstalled();

        boolean vmRunning = Platform.isMacOS() && VmManager.isRunning();
        boolean vmDisksExist = Platform.isMacOS() && (
                Files.exists(Environment.vmDiskImage()) || Files.exists(Environment.vmDataImage()));

        var hostDirs = List.of(
                Environment.cacheDir(),
                Environment.vmStateDir(),
                Environment.dataDir(),
                Environment.configDir());
        var hostDirInfos = CleanCommand.collectInfo(hostDirs);

        boolean hasAnything = !incusReachable || !clones.isEmpty() || !builtTemplates.isEmpty()
                || hasPoolArtifacts || proxyInstalled || vmRunning || vmDisksExist
                || !hostDirInfos.isEmpty();

        if (!hasAnything) {
            System.out.println("Nothing to reset — no incus-spawn data found.");
            return CommandResult.SUCCESS;
        }

        // --- Show the plan ---

        BuildOutput.header("Reset plan");

        if (!incusReachable) {
            System.out.println("  (Incus daemon unreachable — containers, templates, and pool will be skipped)");
        }

        if (!clones.isEmpty()) {
            System.out.println("  Destroy " + clones.size() + " instance(s):");
            for (var n : clones) System.out.println("    " + n);
        }

        if (!builtTemplates.isEmpty()) {
            System.out.println("  Destroy " + builtTemplates.size() + " template(s):");
            for (var n : builtTemplates) System.out.println("    " + n);
        }

        if (hasPoolArtifacts) {
            System.out.println("  Clean storage pool artifacts (failed builds, unused images, DNF cache)");
        }

        if (proxyInstalled) {
            System.out.println("  Uninstall proxy service, DNS overrides, and redirect rules");
        } else {
            System.out.println("  Clear any remaining DNS overrides and redirect rules");
        }

        if (vmRunning) {
            System.out.println("  Stop the running VM appliance");
        }
        if (vmDisksExist) {
            long diskSize = 0;
            try {
                if (Files.exists(Environment.vmDiskImage()))
                    diskSize += Files.size(Environment.vmDiskImage());
                if (Files.exists(Environment.vmDataImage()))
                    diskSize += Files.size(Environment.vmDataImage());
            } catch (Exception ignored) {}
            System.out.println("  DELETE VM disk images (" + CleanCommand.formatSize(diskSize)
                    + ") — the VM will need to be recreated from scratch");
        }

        if (!hostDirInfos.isEmpty()) {
            System.out.println("  Remove host data:");
            for (var info : hostDirInfos) {
                System.out.printf("    %-50s %s%n", info.path(), CleanCommand.formatSize(info.size()));
            }
        }

        System.out.println();
        System.out.println("This returns incus-spawn to a freshly-installed state.");
        System.out.println("Run 'isx init' afterwards to set up again.");
        System.out.println();

        if (!confirm("This will destroy all containers, templates, and VM disk images listed above. Proceed?",
                skipConfirmation)) {
            return CommandResult.SUCCESS;
        }

        // --- Execute ---

        boolean failed = false;

        // 1. Instances first (they depend on templates)
        if (!clones.isEmpty()) {
            BuildOutput.header("Destroying instances");
            if (DestroyCommand.destroyNames(incus, clones) > 0) failed = true;
        }

        // 2. Templates (reverse order, derived first)
        if (!builtTemplates.isEmpty()) {
            BuildOutput.header("Destroying templates");
            if (DestroyCommand.destroyNames(incus, builtTemplates) > 0) failed = true;
        }

        // 3. Pool artifacts
        if (hasPoolArtifacts) {
            BuildOutput.header("Cleaning storage pool");
            var result = CleanCommand.cleanPool(incus);
            if (result != null) {
                if (result.failedBuildsDeleted() > 0)
                    System.out.println("  Removed " + result.failedBuildsDeleted() + " failed build(s)");
                if (result.unusedImagesDeleted() > 0)
                    System.out.println("  Removed " + result.unusedImagesDeleted() + " unused image(s)");
                if (result.dnfCacheDeleted())
                    System.out.println("  Removed DNF cache volume");
                for (var w : result.warnings()) System.err.println("  Warning: " + w);
                if (!result.warnings().isEmpty()) failed = true;
            }
        }

        // 4. Proxy service, DNS overrides, and redirect rules
        if (proxyInstalled) {
            BuildOutput.header("Uninstalling proxy service");
            try {
                ProxyService.uninstall(incus);
            } catch (Exception e) {
                System.err.println("Warning: proxy uninstall failed: " + e.getMessage());
                failed = true;
            }
        } else {
            BuildOutput.header("Clearing DNS overrides and redirect rules");
            if (incusReachable) {
                ProxyConfig.clearBridgeDns(incus);
            }
            if (!ProxyConfig.clearRedirectRules()) failed = true;
        }

        // 5. Stop the VM (must happen before host dir deletion removes disk images)
        // VmManager.stop() swallows VmException internally, so check isRunning() afterward
        boolean vmStillRunning = false;
        if (vmRunning) {
            BuildOutput.header("Stopping VM appliance");
            VmManager.stop();
            if (VmManager.isRunning()) {
                System.err.println("Warning: VM is still running after stop attempt.");
                vmStillRunning = true;
                failed = true;
            }
        }

        // 6. Host directories (skip vmStateDir if the VM is still running)
        if (!hostDirInfos.isEmpty()) {
            BuildOutput.header("Removing host data");
            for (var info : hostDirInfos) {
                if (vmStillRunning && info.path().equals(Environment.vmStateDir())) {
                    System.err.println("  Skipping " + info.path()
                            + " — VM is still running, disk images cannot be safely deleted.");
                    continue;
                }
                BuildOutput.stepStart("Deleting " + info.path() + "...");
                try {
                    CleanCommand.deleteDir(info.path());
                    BuildOutput.stepDone();
                } catch (Exception e) {
                    BuildOutput.stepFail(e.getMessage());
                    failed = true;
                }
            }
        }

        System.out.println();
        if (failed) {
            System.err.println("Reset completed with warnings. Some items may not have been fully removed.");
        } else {
            BuildOutput.success("Reset complete — incus-spawn is back to a freshly-installed state. Run 'isx init' to set up again.");
        }

        return failed ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }
}
