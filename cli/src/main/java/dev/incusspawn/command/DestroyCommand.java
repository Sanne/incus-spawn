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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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

        if (!incus.exists(target)) {
            System.err.println("Error: no instance named '" + target + "' found.");
            return CommandResult.valueOf(1);
        }

        BuildOutput.header("Destroying " + target);

        var type = Metadata.getType(incus, target);
        if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
            BuildOutput.note("'" + target + "' is a template (type: " + type + ").");
            BuildOutput.note("Destroying it means you won't be able to create new branches from it");
            BuildOutput.note("until you rebuild it. Existing branches are not affected.");
        }

        var lockOpt = tryAcquireLock(target);
        if (lockOpt.isEmpty()) {
            System.err.println("Error: '" + target + "' is locked by another process.");
            return CommandResult.valueOf(1);
        }
        try (var lock = lockOpt.get()) {
            incus.setPendingOperation(target, Metadata.OP_DELETING);
            try {
                BuildOutput.stepStart("Removing instance...");
                incus.delete(target, true);
                InstanceLifecycle.removeHostIntegration(target);
                BuildOutput.stepDone();
            } finally {
                incus.clearPendingOperation(target);
            }
        }

        BuildOutput.success("Destroyed " + target + ".");
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
        int failures = destroyNames(incus, names);
        int destroyed = names.size() - failures;

        BuildOutput.success("Destroyed " + destroyed + " " + kind + "(s)."
                + (failures > 0 ? " " + failures + " failed." : ""));
        return failures > 0 ? CommandResult.valueOf(1) : CommandResult.SUCCESS;
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

        // Start with YAML-defined names that actually exist as Incus instances.
        var result = new java.util.LinkedHashSet<String>();
        for (var name : definedNames) {
            if (incus.exists(name)) result.add(name);
        }

        // Also discover templates that exist in Incus but have no on-disk definition
        // (e.g. the YAML was deleted or renamed after the template was built).
        for (var inst : incus.list()) {
            var name = inst.get("name");
            if (name.endsWith(BuildCommand.REBUILDING_SUFFIX)) continue;
            if (!result.contains(name)) {
                var type = Metadata.getType(incus, name);
                if (Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type)) {
                    result.add(name);
                }
            }
        }

        return List.copyOf(result);
    }

    static int destroyNames(IncusClient incus, List<String> names) {
        int failures = 0;
        for (var n : names) {
            BuildOutput.stepStart("Destroying " + n + "...");
            var lockOpt = tryAcquireLock(n);
            if (lockOpt.isEmpty()) {
                BuildOutput.stepFail("locked by another process");
                failures++;
                continue;
            }
            try (var lock = lockOpt.get()) {
                incus.setPendingOperation(n, Metadata.OP_DELETING);
                try {
                    incus.delete(n, true);
                    InstanceLifecycle.removeHostIntegration(n);
                    BuildOutput.stepDone();
                } catch (Exception e) {
                    BuildOutput.stepFail(e.getMessage());
                    failures++;
                } finally {
                    incus.clearPendingOperation(n);
                }
            }
        }
        return failures;
    }

    private static Optional<InstanceLockManager.LockHandle> tryAcquireLock(String name) {
        try {
            return RuntimeServices.lockManager().tryAcquire(name, Metadata.OP_DELETING);
        } catch (java.io.UncheckedIOException e) {
            System.err.println("Warning: could not acquire lock for '" + name + "': " + e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
