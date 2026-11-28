package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.Drivers;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@CommandDefinition(
        name = "destroy",
        description = "Destroy an instance or a template",
        generateHelp = true
)
public class DestroyCommand extends BaseCommand {

    @Argument(completer = Completers.Source.class, description = "Name of the instance or template to destroy")
    String name;

    @Option(name = "all-templates", hasValue = false,
            description = "Destroy every built template, each before the template it was built from")
    boolean allTemplates;

    @Option(name = "all-instances", hasValue = false,
            description = "Destroy every instance, but not the templates")
    boolean allInstances;

    @Option(name = "skip-confirmation", hasValue = false,
            description = "Destroy without asking first")
    boolean skipConfirmation;

    @Option(name = "owner", description = "Destroy the instance only if this external tool owns it (set by isx branch"
            + " --owner) and the 'drivers:' section of config.yaml lets that tool destroy. Succeeds if the instance is already gone.")
    String owner;

    @Option(name = "owner-id", description = "With --owner: destroy it only if the owner's handle for it is this one")
    String ownerId;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (allTemplates && allInstances) {
            BuildOutput.error("give --all-templates or --all-instances, not both.");
            return CommandResult.valueOf(1);
        }
        if ((allTemplates || allInstances) && name != null && !name.isBlank()) {
            BuildOutput.error("give a name or --all-templates / --all-instances, not both.");
            return CommandResult.valueOf(1);
        }
        if ((owner != null || ownerId != null) && (allTemplates || allInstances)) {
            BuildOutput.error("--owner and --owner-id destroy one named instance, not --all-templates / --all-instances.");
            return CommandResult.valueOf(1);
        }
        if (allTemplates) return destroyAllTemplates();
        if (allInstances) return destroyAllInstances();

        if (name == null || name.isBlank()) {
            BuildOutput.error("give the name of what to destroy, or --all-templates / --all-instances.");
            return CommandResult.valueOf(1);
        }
        if (owner != null || ownerId != null) {
            // The arguments before config.yaml, whose own problems would otherwise hide theirs
            var problem = Metadata.ownerProblem(owner, ownerId);
            if (problem != null) {
                BuildOutput.error(problem);
                return CommandResult.valueOf(1);
            }
            return destroyOwned(RuntimeServices.incus(), RuntimeServices.lockManager(), SpawnConfig.load().drivers(), name);
        }
        return destroySingle(RuntimeServices.incus(), RuntimeServices.lockManager(), name);
    }

    /**
     * {@code isx destroy <name> --owner <tool>} (#1720): the delete an orchestrator runs when it is
     * done with an instance it made. Fails closed: only for a tool {@code drivers:} lets destroy, and
     * only while the instance still carries that owner (and {@code --owner-id}'s handle). One read
     * decides, and the delete checks again under the deleting mark ({@link InstanceDestroyer#deleteHeldIf}),
     * so an instance the user branched from it, or took over meanwhile, is never the tool's to delete.
     * One already gone is success, since a tool repeats a destroy that failed after the delete.
     * Never asks: the grant in config.yaml is the user's answer.
     */
    CommandResult destroyOwned(IncusClient incus, InstanceLockManager lockManager, Drivers drivers, String target) {
        var problem = Metadata.ownerProblem(owner, ownerId);
        if (problem != null) {
            BuildOutput.error(problem);
            return CommandResult.valueOf(1);
        }
        // The grant first, before anything is asked of Incus
        var driver = drivers.find(owner, BuildOutput::warn);
        if (driver == null || !driver.destroy()) {
            BuildOutput.error("config.yaml does not let " + owner + " destroy its instances.",
                    "To let it, add to " + SpawnConfig.file() + ":",
                    "drivers:", "  " + owner + ":", "    destroy: true");
            return CommandResult.valueOf(1);
        }
        var shown = OutputFormat.oneLine(target);
        // One read: gone, not the tool's, or the tool's. One that cannot be read fails, never "gone"
        var instance = incus.instanceMetadataOrThrow(target);
        if (instance == null) {
            BuildOutput.success(shown + " is already gone");
            return CommandResult.SUCCESS;
        }
        if (!ownedByTheTool(instance)) return notTheTools(shown);
        var lockOpt = tryAcquireLock(lockManager, target, true);
        if (lockOpt == null) return CommandResult.valueOf(1);
        if (lockOpt.isEmpty()) {
            reportBusy(shown);
            return CommandResult.valueOf(1);
        }
        try (var lock = lockOpt.get()) {
            // Read again under the mark: whether it was there to judge, and whether it still is the tool's
            var judged = new boolean[1];
            Optional<List<String>> deleted;
            try {
                deleted = InstanceDestroyer.deleteHeldIf(incus, target, current -> {
                    judged[0] = true;
                    return ownedByTheTool(current);
                });
            } catch (RuntimeException e) {
                // Gone meanwhile (another destroy, a retry of this one), or deleted with its host
                // cleanup failing: either way the instance is not there, and its address is freed
                if (incus.instanceMetadataOrThrow(target) != null) throw e;
                InstanceDestroyer.refreshProxy();
                if (judged[0]) BuildOutput.warn("Destroyed " + shown + ", but: " + failure(e));
                BuildOutput.success(judged[0] ? "Destroyed " + shown : shown + " is already gone");
                return CommandResult.SUCCESS;
            }
            if (deleted.isPresent()) {
                InstanceDestroyer.refreshProxy();
                InstanceDestroyer.saidRemoved(target, deleted.get(), BuildOutput.bound());
                BuildOutput.success("Destroyed " + shown);
                return CommandResult.SUCCESS;
            }
            if (!judged[0]) {
                BuildOutput.success(shown + " is already gone");
                return CommandResult.SUCCESS;
            }
            return notTheTools(shown);
        }
    }

    private boolean ownedByTheTool(JqValue instance) {
        var config = instance.path("config");
        return owner.equals(config.pathText(Metadata.OWNER, ""))
                && (ownerId == null || ownerId.equals(config.pathText(Metadata.OWNER_ID, "")));
    }

    private CommandResult notTheTools(String shown) {
        BuildOutput.error("did not destroy '" + shown + "': " + owner + " does not own it"
                + (ownerId == null ? "" : " under the id " + ownerId) + ".");
        return CommandResult.valueOf(1);
    }

    CommandResult destroySingle(IncusClient incus, InstanceLockManager lockManager, String target) {

        // One read for whether it exists, its type and its owner
        var instance = incus.instanceMetadataOrThrow(target);
        if (instance == null) {
            BuildOutput.error("no instance or template named '" + OutputFormat.oneLine(target) + "' found.",
                    "Run 'isx list' to see available instances and templates.");
            return CommandResult.valueOf(1);
        }
        // An orchestrator's instance (#1720): deleting it under the tool would break the tool's
        // view of it, so a person is asked, and a script must say --skip-confirmation
        var ownedBy = instance.path("config").pathText(Metadata.OWNER, "");
        if (!ownedBy.isEmpty() && !confirmDestructive(OutputFormat.oneLine(ownedBy) + " controls '" + target
                + "' and still expects it. Destroy it anyway?", skipConfirmation, "--skip-confirmation")) {
            return CommandResult.SUCCESS;
        }

        var lockOpt = tryAcquireLock(lockManager, target, true);
        if (lockOpt == null) return CommandResult.valueOf(1);
        if (lockOpt.isEmpty()) {
            reportBusy(OutputFormat.oneLine(target));
            return CommandResult.valueOf(1);
        }

        try (var lock = lockOpt.get()) {
            var type = instance.path("config").pathText(Metadata.TYPE, "");
            if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
                BuildOutput.note("'" + target + "' is a template: 'isx branch' cannot copy it again until it is rebuilt.");
                BuildOutput.note("The instances already branched from it keep working.");
            }

            BuildOutput.stepStart("Destroying " + target);
            var removedFrom = InstanceDestroyer.deleteHeld(incus, target);
            InstanceDestroyer.refreshProxy();
            BuildOutput.stepDone();
            InstanceDestroyer.saidRemoved(target, removedFrom, BuildOutput.bound());

            BuildOutput.success("Destroyed " + target);
        }
        return CommandResult.SUCCESS;
    }

    private CommandResult destroyAllTemplates() throws Exception {
        var incus = RuntimeServices.incus();
        var built = listBuiltTemplates(incus);
        return destroyAll(built, "template");
    }

    private CommandResult destroyAllInstances() throws Exception {
        var instances = clones(RuntimeServices.incus());
        // Say which belong to a tool (#1720): deleting them under it breaks the tool's view of them
        var shown = instances.stream().map(i -> OutputFormat.oneLine(i.name())
                + (i.owner() == null ? "" : " (owned by " + OutputFormat.oneLine(i.owner()) + ")")).toList();
        return destroyAll(instances.stream().map(InstanceListing.InstanceInfo::name).toList(), shown, "instance");
    }

    private CommandResult destroyAll(List<String> names, String kind) throws Exception {
        return destroyAll(names, names, kind);
    }

    private CommandResult destroyAll(List<String> names, List<String> shown, String kind) throws Exception {
        if (names.isEmpty()) {
            BuildOutput.step("There are no " + kind + "s to destroy.");
            return CommandResult.SUCCESS;
        }

        groupOfSteps("Will destroy " + count(names.size(), kind), shown);

        if (!confirmDestructive("Destroy all listed " + kind + "s?", skipConfirmation, "--skip-confirmation")) return CommandResult.SUCCESS;

        var incus = RuntimeServices.incus();
        return summarize(destroyNames(incus, names), kind);
    }

    /**
     * How a bulk destroy ended: a success only when everything went, else an error, since one
     * left behind, locked or failed, fails the command.
     */
    static CommandResult summarize(DestroyResult result, String kind) {
        if (result.skipped == 0 && result.failed == 0) {
            BuildOutput.success("Destroyed " + count(result.destroyed, kind));
            return CommandResult.SUCCESS;
        }
        var total = result.destroyed + result.skipped + result.failed;
        var why = new ArrayList<String>();
        if (result.skipped > 0) why.add(result.skipped + " in use by another isx process");
        if (result.failed > 0) why.add(result.failed + " failed, as shown above");
        BuildOutput.error("could not destroy " + (result.skipped + result.failed) + " of " + count(total, kind)
                + ": " + String.join(", ", why) + ".",
                result.skipped > 0 ? new String[] {InstanceStart.BUSY_ADVICE} : new String[0]);
        return CommandResult.valueOf(1);
    }

    /** {@code 1 instance}, {@code 2 instances}. */
    static String count(int n, String kind) {
        return n + " " + kind + (n == 1 ? "" : "s");
    }

    /** That another isx process holds {@code shown}'s lock, and what to do. */
    private static void reportBusy(String shown) {
        BuildOutput.error("'" + shown + "' is in use by another isx process.", InstanceStart.BUSY_ADVICE);
    }

    static List<String> listClones(IncusClient incus) {
        return clones(incus).stream().map(InstanceListing.InstanceInfo::name).toList();
    }

    /** The instances (not templates), from one listing rather than a read of each one's type. */
    static List<InstanceListing.InstanceInfo> clones(IncusClient incus) {
        return InstanceListing.collectEntries(incus.listJsonConfig()).stream()
                .filter(i -> Metadata.TYPE_CLONE.equals(i.type()))
                .toList();
    }

    static List<String> listBuiltTemplates(IncusClient incus) {
        var definedNames = new ArrayList<>(ImageDef.loadAll().keySet());
        Collections.reverse(definedNames);

        var allInstances = incus.list();
        var existingNames = new HashSet<String>();
        for (var inst : allInstances) {
            existingNames.add(inst.get("name"));
        }

        var result = new ArrayList<String>();
        for (var name : definedNames) {
            if (existingNames.contains(name)) {
                result.add(name);
            }
        }

        // Include templates that still exist in Incus but whose definitions were removed.
        // Exclude -rebuilding temporaries (BuildCommand creates these during rebuilds).
        // Sort orphans derived-first using parent metadata so destroyNames deletes children
        // before parents.
        var definedSet = new HashSet<>(definedNames);
        var orphans = new ArrayList<String>();
        for (var inst : allInstances) {
            var name = inst.get("name");
            if (definedSet.contains(name)) continue;
            if (name.endsWith(BuildCommand.REBUILDING_SUFFIX)) continue;
            var type = inst.get("isx-type"); // the listing carries the type: no request per instance
            if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
                orphans.add(name);
            }
        }
        if (orphans.size() > 1) {
            var parentOf = new java.util.HashMap<String, String>();
            for (var name : orphans) {
                try {
                    var parent = incus.configGet(name, Metadata.PARENT);
                    if (!parent.isEmpty()) parentOf.put(name, parent);
                } catch (Exception ignored) {}
            }
            orphans.sort((a, b) -> {
                if (a.equals(parentOf.get(b))) return 1;  // a is parent of b → b first
                if (b.equals(parentOf.get(a))) return -1;  // b is parent of a → a first
                return a.compareTo(b);
            });
        }
        result.addAll(orphans);

        return result;
    }

    record DestroyResult(int destroyed, int skipped, int failed) {}

    static DestroyResult destroyNames(IncusClient incus, List<String> names) {
        var lockManager = RuntimeServices.lockManager();
        int destroyed = 0;
        int skipped = 0;
        int failures = 0;
        // One settle of the pool's accounting at the end, not one per instance (#2061)
        try (var batch = incus.deleteBatch()) {
            for (var n : names) {
                var lockOpt = tryAcquireLock(lockManager, n, false);
                if (lockOpt == null) {
                    failures++;
                    continue;
                }
                if (lockOpt.isEmpty()) {
                    BuildOutput.stepStart("Destroying " + n);
                    BuildOutput.stepFail("in use by another isx process");
                    skipped++;
                    continue;
                }
                try (var lock = lockOpt.get()) {
                    BuildOutput.stepStart("Destroying " + n);
                    try {
                        var removedFrom = InstanceDestroyer.deleteHeld(incus, n);
                        BuildOutput.stepDone();
                        InstanceDestroyer.saidRemoved(n, removedFrom, BuildOutput.bound());
                        destroyed++;
                    } catch (Exception e) {
                        BuildOutput.stepFail(e.getMessage());
                        failures++;
                    }
                }
            }
        }
        if (destroyed > 0) InstanceDestroyer.refreshProxy();
        return new DestroyResult(destroyed, skipped, failures);
    }

    /**
     * The lock on {@code name}, empty when another process holds it, or null after reporting why
     * it could not be taken: an error when that fails the command, else a warning.
     */
    private static java.util.Optional<InstanceLockManager.LockHandle> tryAcquireLock(
            InstanceLockManager lockManager, String name, boolean fails) {
        try {
            return lockManager.tryAcquire(name, Metadata.OP_DELETING);
        } catch (UncheckedIOException e) {
            var why = "could not lock " + name + ": " + failure(e.getCause());
            if (fails) BuildOutput.error(why);
            else BuildOutput.warn(why);
            return null;
        }
    }
}
