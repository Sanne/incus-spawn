package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceNames;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.util.BuildOutput;

import java.util.Map;

/**
 * Updating a template as a build replaces one (#1760): {@code isx update-all} and
 * {@code isx project update} run in a copy of the template, made under the build's temporary name
 * and untrusted from its copy request on, and put it in place of the template only once every
 * step succeeded. A step that fails, or anything thrown, deletes the copy and leaves the template
 * as it was, so a branch never copies a half-updated template, and one made meanwhile copies the
 * consistent one the update started from.
 */
final class TemplateUpdate {

    private TemplateUpdate() {}

    /** The update's steps, run in {@code copy}, a stopped copy of the template. */
    interface Steps {
        /** Whether every step succeeded and {@code copy} is stopped again: only then is it swapped in. */
        boolean run(String copy);
    }

    /** How an update ended. */
    enum Result {
        /** The copy replaced the template. */
        UPDATED,
        /** A step failed, or the swap found the template or the copy changed: a re-run may succeed. */
        DISCARDED,
        /** Refused before anything was copied, for a reason a re-run meets again until it is dealt with. */
        REFUSED
    }

    /** What an update's copy is named: the build's temporary name, which listings already hide. */
    static String copyName(String template) {
        return template + BuildCommand.REBUILDING_SUFFIX;
    }

    /**
     * Copy {@code template}, which {@code read} describes and must be a stopped template, run
     * {@code steps} in the copy and swap it in when they succeed.
     *
     * <p>No other isx process builds or updates the same template meanwhile: they share the copy's
     * name, and each deletes what it finds there ({@link TemplateLock#building}). As a backstop the
     * copy is known by its {@code created_at} as well: the update deletes only the copy it made, and
     * swaps it in only while both the template and the copy are the instances it started with.
     *
     * @return how the update ended; when not {@link Result#UPDATED}, each failure was reported, a
     * template that is not a stopped template's included
     * @throws RuntimeException whatever a step threw, once the copy is deleted
     */
    static Result run(IncusClient incus, String template, IncusClient.InstanceRead read, Steps steps) {
        // Refused, not thrown: update-all goes on to its other templates
        try {
            InstanceLifecycle.requireStoppedTemplate(template, read);
        } catch (RuntimeException e) {
            BuildOutput.stepFail(e.getMessage());
            return Result.REFUSED;
        }
        var copy = copyName(template);
        if (!InstanceNames.isValid(copy)) {
            BuildOutput.stepFail("'" + template + "' is too long to update: it is updated as '" + copy
                    + "', and Incus allows " + InstanceNames.MAX_LENGTH + " characters. Rebuild it under a shorter name.");
            return Result.REFUSED;
        }
        if (BuildCommand.reportStrandedStorage(incus, template, copy)) return Result.REFUSED;
        var building = TemplateLock.building(template);
        if (building.isEmpty()) {
            BuildOutput.stepFail("Another isx is building or updating " + template + "; update it once that is done.");
            return Result.REFUSED;
        }
        try (var held = building.get()) {
            return runHeld(incus, template, copy, read, steps);
        }
    }

    /** {@link #run} once this process alone works under {@code copy}'s name ({@link TemplateLock#building}). */
    private static Result runHeld(IncusClient incus, String template, String copy, IncusClient.InstanceRead read,
                                  Steps steps) {
        var config = read.metadata().path("config");
        var lineage = config.pathText(Metadata.UNTRUSTED_LINEAGE, "");
        // A project template is untrusted for good (#936), one made before that stamp included:
        // what its setup ran is never its parent template, so its update never trusts it
        if (lineage.isEmpty() && Metadata.TYPE_PROJECT.equals(config.pathText(Metadata.TYPE, ""))) {
            lineage = Metadata.TYPE_PROJECT;
        }
        boolean trustOnSwap = lineage.isEmpty();
        // Which instance the update started from: a rebuild swapped in meanwhile is not replaced
        var copiedFrom = read.metadata().pathText("created_at", "");

        // What a build or update that died before its swap left behind, as a build deletes it
        incus.deleteIfExists(copy);
        // Null until this update's copy request returned. With the hold degraded, or an isx from
        // before it building, another may work under the name: what it made is not deleted or swapped in.
        String copyCreated = null;
        boolean updated;
        try {
            // Held until the copy is made, so a rebuild cannot swap the template away meanwhile (#1212)
            try (var held = TemplateLock.reading(template, BuildOutput.bound())) {
                // Untrusted from the copy on, as a build is, until it is in place; one that already
                // was keeps its reason. Its size is measured again once it is updated.
                incus.copy(template, copy, incus.planCopy(read.metadata()), Map.of(
                        Metadata.UNTRUSTED_LINEAGE, trustOnSwap ? Metadata.UNTRUSTED_BUILDING : lineage,
                        Metadata.DISK_REFERENCED, ""));
            }
            copyCreated = createdAt(incus, copy);
            updated = steps.run(copy);
        } catch (RuntimeException e) {
            try {
                if (copyCreated != null && copyCreated.equals(createdAt(incus, copy))) incus.delete(copy, true);
            } catch (RuntimeException deleteFailed) {
                e.addSuppressed(deleteFailed);
            }
            throw e;
        }
        if (!updated) {
            discard(incus, copy, copyCreated, template + " is unchanged");
            return Result.DISCARDED;
        }
        var made = copyCreated;
        // Measured before the swap deletes the template's subvolume, as a build's is
        var referenced = BuildCommand.probeReferencedSize(incus, copy);
        boolean swapped = TemplateLock.replaceIf(incus, copy, template, BuildOutput.bound(),
                () -> isStill(incus, template, copiedFrom) && isStill(incus, copy, made),
                () -> {
                    if (trustOnSwap) BuildCommand.trustBuilt(incus, template);
                });
        if (!swapped) {
            BuildOutput.stepFail(template + " or its update's copy was rebuilt, updated or removed by another isx while this update ran.");
            discard(incus, copy, copyCreated, "what is in its place is kept");
            return Result.DISCARDED;
        }
        BuildCommand.stampReferencedSize(incus, template, referenced);
        return Result.UPDATED;
    }

    /** When the instance named {@code name} was created; null when there is none. */
    private static String createdAt(IncusClient incus, String name) {
        var instance = incus.instanceMetadataOrThrow(name);
        return instance == null ? null : instance.pathText("created_at", "");
    }

    /**
     * Whether the instance now named {@code name} is the one created at {@code createdAt}; a read
     * that fails says no, and the update is discarded rather than swapped in blind.
     */
    private static boolean isStill(IncusClient incus, String name, String createdAt) {
        try {
            return createdAt.equals(createdAt(incus, name));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Delete the update's copy, created at {@code copyCreated}, saying what was kept; one left
     * behind is deleted by the next update, and one another isx made in its place is left to it.
     */
    private static void discard(IncusClient incus, String copy, String copyCreated, String kept) {
        BuildOutput.stepStart("Discarding the update; " + kept);
        try {
            // A failed read is reported as one, not taken for another isx's copy and left in silence
            if (copyCreated != null && copyCreated.equals(createdAt(incus, copy))) incus.delete(copy, true);
            BuildOutput.stepDone();
        } catch (RuntimeException e) {
            BuildOutput.stepFail("Could not delete " + copy + ": " + e.getMessage());
            BuildOutput.warn(copy + " is left behind.", "The next update deletes it, or run 'incus delete --force " + copy + "'.");
        }
    }
}
