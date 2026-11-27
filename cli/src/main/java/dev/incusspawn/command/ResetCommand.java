package dev.incusspawn.command;

import dev.incusspawn.FileTrees;
import dev.incusspawn.Platform;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.Environment;
import dev.incusspawn.lifecycle.ProxyOnlyAcl;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

@CommandDefinition(
        name = "reset",
        description = "Return to a freshly-installed state: destroys all containers, templates, VM, proxy, routing rules, cached data and configuration; keeps Git checkouts and search paths",
        generateHelp = true
)
public class ResetCommand extends BaseCommand {

    @Option(name = "skip-confirmation", hasValue = false,
            description = "Skip the confirmation prompt")
    boolean skipConfirmation;

    @Option(name = "no-backup", hasValue = false,
            description = "Do not write a backup of the deleted configuration to the current directory")
    boolean noBackup;

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
                    (!poolScan.failedBuilds().isEmpty() || !poolScan.unusedImages().isEmpty()
                            || !poolScan.baseImages().isEmpty() || poolScan.dnfCacheExists());
        } catch (Exception e) {
            incusReachable = false;
            BuildOutput.warn("Could not reach Incus daemon: " + e.getMessage(),
                    "Incus-managed resources (containers, templates, pool) will be skipped.");
        }

        boolean proxyInstalled = ProxyService.isInstalled();

        boolean vmRunning = Platform.isMacOS() && VmManager.runningOrAdopted();
        boolean vmDisksExist = Platform.isMacOS() && (
                Files.exists(Environment.vmDiskImage()) || Files.exists(Environment.vmDataImage()));

        var hostDirs = List.of(
                Environment.cacheDir(),
                Environment.vmStateDir(),
                Environment.dataDir(),
                Environment.configDir());
        // What a person wrote in them stays, and the rest of the config directory is archived first (#2666)
        var kept = KeptPaths.in(hostDirs);
        var keep = KeptPaths.paths(kept);
        var hostDirInfos = CleanCommand.collectInfo(hostDirs, keep);
        // Planned before the plan is shown, so a directory that cannot take it stops the reset unasked
        var archive = noBackup || hostDirInfos.isEmpty() ? null : ConfigBackup.plan(hostDirs, keep);

        boolean hasAnything = !incusReachable || !clones.isEmpty() || !builtTemplates.isEmpty()
                || hasPoolArtifacts || proxyInstalled || vmRunning || vmDisksExist
                || !hostDirInfos.isEmpty();

        if (!hasAnything) {
            BuildOutput.step("Nothing to reset — no incus-spawn data found.");
            return CommandResult.SUCCESS;
        }

        // --- Show the plan ---

        BuildOutput.header("Reset plan");

        // On macOS whatever Incus holds, the overrides included, lives in the VM's state, on its
        // data disk if anywhere, which the reset deletes
        var overridesLiveInTheVm = Platform.isMacOS();
        if (!incusReachable && overridesLiveInTheVm) {
            BuildOutput.step("(Incus daemon unreachable — its containers, templates, pool and bridge DNS overrides"
                    + " go with the VM's data disk, which the reset deletes)");
        } else if (!incusReachable) {
            BuildOutput.step("(Incus daemon unreachable — containers, templates, and pool will be skipped;"
                    + " its bridge DNS overrides cannot be cleared, so the reset fails for them)");
        }

        groupOfSteps("Destroy " + clones.size() + " instance(s)", clones);
        groupOfSteps("Destroy " + builtTemplates.size() + " built template(s)", builtTemplates);

        if (hasPoolArtifacts) {
            BuildOutput.step("Clean storage pool artifacts (failed builds, unused and cached base images, DNF cache)");
        }

        if (proxyInstalled) {
            BuildOutput.step("Uninstall proxy service, DNS overrides, and redirect rules");
        } else {
            BuildOutput.step("Clear any remaining DNS overrides and redirect rules");
        }

        if (vmRunning) {
            BuildOutput.step("Stop the running VM appliance");
        }
        if (vmDisksExist) {
            long diskSize = 0;
            try {
                if (Files.exists(Environment.vmDiskImage()))
                    diskSize += Files.size(Environment.vmDiskImage());
                if (Files.exists(Environment.vmDataImage()))
                    diskSize += Files.size(Environment.vmDataImage());
            } catch (Exception ignored) {}
            BuildOutput.step("DELETE VM disk images (" + CleanCommand.formatSize(diskSize)
                    + ") — the VM will need to be recreated from scratch");
        }

        groupOfSteps("Remove host data", hostDirInfos.stream()
                .map(info -> String.format("%-50s %s", info.path(), CleanCommand.formatSize(info.size())))
                .toList());
        CleanCommand.sayKept(kept);
        if (archive != null) archive.announce();

        BuildOutput.section("This returns incus-spawn to a freshly-installed state. Run 'isx init' afterwards to set up again.");

        if (!confirmDestructive("This will destroy all containers, built templates, VM disk images and configuration listed above. Proceed?",
                skipConfirmation, "--skip-confirmation")) {
            return CommandResult.SUCCESS;
        }

        // --- Execute ---

        // Before anything is destroyed: a backup that could not be written whole stops the reset here
        if (archive != null) archive.write();

        boolean failed = false;

        // 1. Instances first (they depend on templates)
        if (!clones.isEmpty()) {
            BuildOutput.header("Destroying instances");
            var dr = DestroyCommand.destroyNames(incus, clones);
            if (dr.failed() > 0 || dr.skipped() > 0) failed = true;
        }

        // 2. Templates (reverse order, derived first)
        if (!builtTemplates.isEmpty()) {
            BuildOutput.header("Destroying templates");
            var dr = DestroyCommand.destroyNames(incus, builtTemplates);
            if (dr.failed() > 0 || dr.skipped() > 0) failed = true;
        }

        // 3. Pool artifacts
        if (hasPoolArtifacts) {
            BuildOutput.header("Cleaning storage pool");
            var result = CleanCommand.cleanPool(incus);
            if (result != null) {
                if (result.failedBuildsDeleted() > 0)
                    BuildOutput.step("Removed " + result.failedBuildsDeleted() + " failed build(s)");
                if (result.unusedImagesDeleted() > 0)
                    BuildOutput.step("Removed " + result.unusedImagesDeleted() + " unused image(s)");
                if (result.baseImagesDeleted() > 0)
                    BuildOutput.step("Removed " + result.baseImagesDeleted() + " cached base image(s)");
                if (result.dnfCacheDeleted())
                    BuildOutput.step("Removed DNF cache volume");
                if (!result.warnings().isEmpty()) {
                    BuildOutput.warn("Some storage pool artifacts were not removed:", result.warnings().toArray(String[]::new));
                    failed = true;
                }
            }
        }

        // 4. Proxy service, DNS overrides, and redirect rules
        if (proxyInstalled) {
            BuildOutput.header("Uninstalling proxy service");
            try {
                if (!ProxyService.uninstallService()) failed = true;
            } catch (Exception e) {
                BuildOutput.warn("Proxy uninstall failed: " + e.getMessage());
                failed = true;
            }
        } else {
            BuildOutput.header("Clearing DNS overrides and redirect rules");
        }
        // Whatever became of the service: `isx proxy uninstall` keeps them for one that stays,
        // but reset goes on to delete the CA and config it runs on, and nothing later would
        // remove them.
        if (!clearProxyRouting(incusReachable, overridesLiveInTheVm,
                () -> ProxyConfig.clearBridgeDns(incus), ProxyConfig::clearRedirectRules)) {
            failed = true;
        }
        // The proxy-only ACL (#1137); Incus keeps it while an instance that was not reset uses it
        if (incusReachable && !incus.deleteNetworkAcl(ProxyOnlyAcl.NAME)) {
            BuildOutput.note("Network ACL " + ProxyOnlyAcl.NAME + " could not be removed;"
                    + " Incus keeps it while a proxy-only instance that was not reset uses it.");
        }

        // 5. Stop the VM (must happen before host dir deletion removes disk images)
        // VmManager.stop() swallows VmException internally, so check isRunning() afterward
        boolean vmStillRunning = false;
        if (vmRunning) {
            BuildOutput.header("Stopping VM appliance");
            VmManager.stopToDelete();
            if (VmManager.isRunning()) {
                BuildOutput.warn("VM is still running after stop attempt.");
                vmStillRunning = true;
                failed = true;
            }
        }

        // 6. Host directories (skip vmStateDir if the VM is still running)
        if (!hostDirInfos.isEmpty()) {
            BuildOutput.header("Removing host data");
            for (var info : hostDirInfos) {
                if (vmStillRunning && info.path().equals(Environment.vmStateDir())) {
                    BuildOutput.warn("Skipping " + info.path()
                            + " — VM is still running, disk images cannot be safely deleted.");
                    continue;
                }
                BuildOutput.stepStart("Deleting " + info.path());
                try {
                    FileTrees.delete(info.path(), keep);
                    BuildOutput.stepDone();
                } catch (Exception e) {
                    BuildOutput.stepFail(e.getMessage());
                    failed = true;
                }
            }
        }

        // Their disk deleted, the overrides an unreachable Incus could not clear are gone too
        // A VM that would not stop already failed the reset, its state skipped with it
        if (!incusReachable && overridesLiveInTheVm && !vmStillRunning && overridesStayOn(Environment.vmDataImage())) {
            failed = true;
        }

        if (failed) {
            BuildOutput.error("Reset did not remove everything.",
                    "See the warnings above: some items may not have been fully removed.");
        } else {
            BuildOutput.success("Reset complete: incus-spawn is back to a freshly installed state");
            BuildOutput.note("Run 'isx init' to set up again.");
        }

        return failed ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }

    /**
     * Clears what routes instances to the proxy: the bridge DNS overrides and the 443 redirect,
     * each tried whatever became of the other; false when either stays. An Incus that is not
     * reachable cannot clear its overrides, so they may stay, and the reset fails for it as
     * {@code isx proxy uninstall} does, saying so, rather than skipping them unseen; unless
     * {@code overridesLiveInTheVm}: on macOS they can only be on the VM's data disk, which the
     * reset deletes, so they count as cleared here and the caller asks {@link #overridesStayOn}
     * once the disk should be gone.
     */
    static boolean clearProxyRouting(boolean incusReachable, boolean overridesLiveInTheVm,
                                     BooleanSupplier bridgeDns, BooleanSupplier redirectRules) {
        var dnsCleared = incusReachable ? bridgeDns.getAsBoolean() : overridesLiveInTheVm;
        if (!incusReachable && !overridesLiveInTheVm) {
            BuildOutput.warn("Could not clear the bridge DNS overrides: Incus is not reachable.", OVERRIDES_STAY);
        }
        return redirectRules.getAsBoolean() && dnsCleared;
    }

    /**
     * Whether the VM's data disk, which holds the bridge DNS overrides an unreachable Incus could
     * not clear, is still there after the reset deleted the VM's state; says so when it is.
     */
    static boolean overridesStayOn(Path dataDisk) {
        if (!Files.exists(dataDisk)) return false;
        BuildOutput.warn("Could not clear the bridge DNS overrides: Incus is not reachable, and the VM's data disk"
                + " that holds them was not deleted (" + dataDisk + ").", OVERRIDES_STAY);
        return true;
    }

    private static final String OVERRIDES_STAY = "They stay until a reset or 'isx proxy uninstall' runs with Incus up.";
}
