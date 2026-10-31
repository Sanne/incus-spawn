package dev.incusspawn.lifecycle;

import dev.incusspawn.util.Output;
import dev.incusspawn.Environment;
import dev.incusspawn.Warnings;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceNames;
import dev.incusspawn.util.HostLock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Replacing a template while other isx processes copy from it (#1212). A rebuild is made under a
 * temporary name and put in place by {@link #replace}: Incus cannot rename onto an existing name,
 * so the swap is a delete and a rename, and between the two the template does not exist. The swap
 * holds the template's lock exclusively, and whatever copies from a template holds it shared from
 * looking the template up until the copy is made ({@link #reading}), so no copy finds it missing
 * and none is cut off by the delete. Readers never hold each other off.
 *
 * <p>Only isx processes on this host take the lock: a raw {@code incus copy}, or isx on another
 * host using the same Incus, can still meet the gap.
 */
public final class TemplateLock {

    /**
     * Bounds the swap's wait for the copies under way, which can include starting the copy. A VM's
     * full copy can take minutes, and timing out fails a build whose work is done.
     */
    static final Duration SWAP_TIMEOUT = Duration.ofMinutes(10);

    private TemplateLock() {}

    /** A {@link #reading} hold, released by closing it. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** One file per template name, kept: deleting it would let two processes lock different files. */
    static Path lockFile(String template) {
        if (!InstanceNames.isValid(template)) {
            throw new IllegalArgumentException("'" + template + "' is not an instance name");
        }
        return Environment.lockDir().resolve("templates").resolve(template + ".lock");
    }

    /**
     * Held for as long as one isx process builds or updates {@code template} under its temporary
     * build name (#1760), so that no two use that name at once: each deletes what it finds there
     * first, which would be the other's work. Never waits: a build or update takes minutes.
     * A lock file that cannot be used at all degrades to none, warning.
     *
     * @return the hold, released by closing it; empty when another isx process or thread holds it
     */
    public static Optional<Held> building(String template) {
        // As for reading: no instance has such a name, so no other build can be under way on it
        if (!InstanceNames.isValid(template)) return Optional.of(() -> {});
        var file = Environment.lockDir().resolve("template-builds").resolve(template + ".lock");
        try {
            Held held = HostLock.acquireOrDegrade(file, "building or updating template '" + template + "'",
                    message -> {}, Warnings::warn, Duration.ZERO)::close;
            return Optional.of(held);
        } catch (HostLock.HostLockException e) {
            return Optional.empty();
        }
    }

    /**
     * Hold {@code template} in place until closed: a {@link #replace} waits for it. Take it before
     * looking the template up, and keep it until the copy from it is made. A name no instance can
     * have is held by nothing: the lookup will find nothing to copy.
     *
     * @param out where to note that a swap is in progress and this waits for it
     * @throws HostLock.HostLockException if a swap holds it past {@link HostLock#TIMEOUT}
     */
    public static Held reading(String template, Output out) {
        if (!InstanceNames.isValid(template)) return () -> {};
        return HostLock.acquireShared(lockFile(template), "replacing template '" + template + "'", out::note, Warnings::warn)::close;
    }

    /**
     * Put {@code built} in place as {@code template}, replacing the one there, once no isx process
     * is copying from it. A lock file that cannot be used at all degrades to the swap without it.
     *
     * @param out where to note that this waits for copies under way
     */
    public static void replace(IncusClient incus, String built, String template, Output out) {
        replace(incus, built, template, out, () -> {});
    }

    /**
     * As above, then {@code afterSwap} while the lock is still held: whoever waited to copy the
     * template copies it only once that has run too, so it sees the template as that step leaves it.
     * It must not throw: the swap is done by then, and a caller would take an exception for a
     * failed swap.
     */
    public static void replace(IncusClient incus, String built, String template, Output out,
                               Runnable afterSwap) {
        replaceIf(incus, built, template, out, () -> true, afterSwap);
    }

    /**
     * As above, once {@code stillCurrent} says, under the lock, that the template is still the
     * one {@code built} was made from: an update copies the template and runs in the copy (#1760),
     * and a rebuild swapped in meanwhile must not be replaced by an update of what it replaced.
     *
     * @return whether it was replaced; when not, {@code built} is left as it was
     */
    public static boolean replaceIf(IncusClient incus, String built, String template, Output out,
                                    BooleanSupplier stillCurrent, Runnable afterSwap) {
        try (var lock = HostLock.acquireOrDegrade(lockFile(template), "copying from template '" + template + "'",
                out::note, Warnings::warn, SWAP_TIMEOUT)) {
            if (!stillCurrent.getAsBoolean()) return false;
            incus.deleteIfExists(template);
            incus.rename(built, template);
            afterSwap.run();
            return true;
        }
    }
}
