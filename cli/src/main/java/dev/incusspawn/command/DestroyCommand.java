package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

@CommandDefinition(
        name = "destroy",
        description = "Destroy a clone environment",
        generateHelp = true
)
public class DestroyCommand extends BaseCommand {

    @Argument(description = "Name of the environment to destroy")
    String name;

    @Option(name = "all-templates", hasValue = false,
            description = "Destroy all built templates (reverse order, derived first)")
    boolean allTemplates;

    @Option(name = "all-instances", hasValue = false,
            description = "Destroy all instances")
    boolean allInstances;

    @Option(name = "skip-confirmation", hasValue = false,
            description = "Skip the confirmation prompt")
    boolean skipConfirmation;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (allTemplates && allInstances) {
            System.err.println("Error: specify --all-templates or --all-instances, not both.");
            return CommandResult.valueOf(1);
        }
        if ((allTemplates || allInstances) && name != null && !name.isBlank()) {
            System.err.println("Error: cannot combine a named target with --all-templates / --all-instances.");
            return CommandResult.valueOf(1);
        }
        if (allTemplates) return destroyAllTemplates();
        if (allInstances) return destroyAllInstances();

        if (name == null || name.isBlank()) {
            System.err.println("Error: specify a name, or use --all-templates / --all-instances.");
            return CommandResult.valueOf(1);
        }
        return destroySingle(name);
    }

    private CommandResult destroySingle(String target) throws Exception {
        var incus = RuntimeServices.incus();
        var lockManager = RuntimeServices.lockManager();

        if (!incus.exists(target)) {
            System.err.println("Error: no instance named '" + target + "' found.");
            return CommandResult.valueOf(1);
        }

        var lockOpt = tryAcquireLock(lockManager, target);
        if (lockOpt == null) return CommandResult.valueOf(1);
        if (lockOpt.isEmpty()) {
            System.err.println("Error: '" + target + "' is locked by another process.");
            return CommandResult.valueOf(1);
        }

        try (var lock = lockOpt.get()) {
            BuildOutput.header("Destroying " + target);

            var type = Metadata.getType(incus, target);
            if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
                BuildOutput.note("'" + target + "' is a template (type: " + type + ").");
                BuildOutput.note("Destroying it means you won't be able to create new branches from it");
                BuildOutput.note("until you rebuild it. Existing branches are not affected.");
            }

            BuildOutput.stepStart("Removing instance...");
            incus.setPendingOperation(target, Metadata.OP_DELETING);
            try {
                incus.delete(target, true);
                InstanceLifecycle.removeHostIntegration(target);
            } catch (Exception e) {
                incus.clearPendingOperation(target);
                throw e;
            }
            BuildOutput.stepDone();

            BuildOutput.success("Destroyed " + target + ".");
        }
        return CommandResult.SUCCESS;
    }

    private CommandResult destroyAllTemplates() throws Exception {
        var incus = RuntimeServices.incus();
        var built = listBuiltTemplates(incus);
        return destroyAll(built, "template");
    }

    private CommandResult destroyAllInstances() throws Exception {
        var incus = RuntimeServices.incus();
        var instances = listClones(incus);
        return destroyAll(instances, "instance");
    }

    private CommandResult destroyAll(List<String> names, String kind) throws Exception {
        if (names.isEmpty()) {
            System.out.println("No " + kind + "s found.");
            return CommandResult.SUCCESS;
        }

        System.out.println("Will destroy " + names.size() + " " + kind + "(s):");
        for (var n : names) {
            System.out.println("  " + n);
        }

        if (!confirm("Destroy all listed " + kind + "s?", skipConfirmation)) return CommandResult.SUCCESS;

        var incus = RuntimeServices.incus();
        var result = destroyNames(incus, names);

        var msg = new StringBuilder("Destroyed " + result.destroyed + " " + kind + "(s).");
        if (result.skipped > 0) msg.append(" ").append(result.skipped).append(" skipped (locked).");
        if (result.failed > 0) msg.append(" ").append(result.failed).append(" failed.");
        BuildOutput.success(msg.toString());
        return (result.failed > 0 || result.skipped > 0) ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
    }

    static List<String> listClones(IncusClient incus) {
        return incus.list().stream()
                .filter(m -> Metadata.TYPE_CLONE.equals(Metadata.getType(incus, m.get("name"))))
                .map(m -> m.get("name"))
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
            var type = Metadata.getType(incus, name);
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
        for (var n : names) {
            var lockOpt = tryAcquireLock(lockManager, n);
            if (lockOpt == null) {
                failures++;
                continue;
            }
            if (lockOpt.isEmpty()) {
                BuildOutput.stepStart("Destroying " + n + "...");
                BuildOutput.stepFail("locked by another process");
                skipped++;
                continue;
            }
            try (var lock = lockOpt.get()) {
                BuildOutput.stepStart("Destroying " + n + "...");
                incus.setPendingOperation(n, Metadata.OP_DELETING);
                try {
                    incus.delete(n, true);
                    InstanceLifecycle.removeHostIntegration(n);
                    BuildOutput.stepDone();
                    destroyed++;
                } catch (Exception e) {
                    incus.clearPendingOperation(n);
                    BuildOutput.stepFail(e.getMessage());
                    failures++;
                }
            }
        }
        return new DestroyResult(destroyed, skipped, failures);
    }

    private static java.util.Optional<InstanceLockManager.LockHandle> tryAcquireLock(
            InstanceLockManager lockManager, String name) {
        try {
            return lockManager.tryAcquire(name, Metadata.OP_DELETING);
        } catch (UncheckedIOException e) {
            System.err.println("Warning: lock error for " + name + ": " + e.getCause().getMessage());
            return null;
        }
    }
}
