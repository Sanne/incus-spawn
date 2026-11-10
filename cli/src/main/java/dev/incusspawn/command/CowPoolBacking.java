package dev.incusspawn.command;

import dev.incusspawn.incus.BtrfsUsage;
import dev.incusspawn.incus.IncusClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * How the host's CoW pool is backed (#1656).
 *
 * <p>On a host whose Incus directory is on btrfs, the pool is a subvolume of that filesystem:
 * Incus creates it itself when {@code source} is the pool's own mount path. A loop file there
 * nests btrfs inside a file on btrfs, so every instance write is copy-on-write twice, with two
 * sets of metadata and commits; on a compressed host the file cannot be {@code nodatacow}.
 * Elsewhere (ext4, xfs) a loop file is the only option, mounted as {@link #loopCommand} says.
 */
final class CowPoolBacking {

    private CowPoolBacking() {}

    static final String POOL_NAME = "cow";
    /** Where Incus mounts its pools, and the only source path it accepts under its own directory. */
    static final Path POOLS_DIR = Path.of(BtrfsUsage.POOL_MOUNT_PREFIX);
    static final String LOOP_SIZE = "100GiB";
    static final String DISKS_DIR = "/var/lib/incus/disks";
    /** The published host migration notes, where a section's heading gives its anchor. */
    static final String MIGRATIONS = "https://github.com/Sanne/incus-spawn/blob/main/docs/dev/MIGRATIONS.md";
    /** The maintainer notes' migration off a loop file, which also covers the host's own tuning. */
    static final String MIGRATION_NOTES = MIGRATIONS + "#the-cow-pool-is-a-loop-file-on-a-btrfs-host";
    private static final String INCUS_DEFAULT_MOUNT_OPTIONS = "user_subvol_rm_allowed";
    /** The options that choose how file atimes are written; {@code nodiratime} covers directories only. */
    private static final Set<String> ATIME_OPTIONS = Set.of("noatime", "relatime", "strictatime", "atime");

    /** The pool's source when it is a subvolume of the host filesystem. */
    static String subvolumeSource() {
        return POOLS_DIR.resolve(POOL_NAME).toString();
    }

    /**
     * The pool this host should get: a subvolume on btrfs, a loop file elsewhere.
     *
     * @param hostFs   the Incus pools directory's filesystem ({@link #filesystemOf} of {@link #POOLS_DIR}), null if unknown
     * @param skipTrim pass {@code -K} to mkfs.btrfs, skipping the whole-device TRIM that takes
     *                 minutes on a loop device backed by a sparse file on ext4. Only when the
     *                 daemon supports {@code btrfs.create_options}; a subvolume runs no mkfs.
     */
    static String[] createCommand(String hostFs, boolean skipTrim) {
        return "btrfs".equals(hostFs) ? subvolumeCommand() : loopCommand(compressesLoop(hostFs), skipTrim);
    }

    static String[] subvolumeCommand() {
        return new String[] {"sudo", "incus", "storage", "create", POOL_NAME, "btrfs", "source=" + subvolumeSource()};
    }

    /**
     * A loop file. Incus replaces its own default mount options ({@code user_subvol_rm_allowed})
     * with whatever is set, so that stays. Instances are scratch pads, so a 300 s commit interval is
     * the right trade for the writes it saves; fsync is still honoured. With {@code compress} the
     * pool compresses, as the macOS appliance and {@code docs/dev/MIGRATIONS.md} do.
     */
    static String[] loopCommand(boolean compress, boolean skipTrim) {
        var cmd = new ArrayList<>(List.of("sudo", "incus", "storage", "create", POOL_NAME, "btrfs",
                "size=" + LOOP_SIZE, "btrfs.mount_options=" + withLoopMountOptions("", compress)));
        if (skipTrim) cmd.add("btrfs.create_options=-K");
        return cmd.toArray(String[]::new);
    }

    /**
     * Whether a loop file on {@code hostFs} should compress inside: only where the host's own
     * filesystem is known and is not btrfs, which may compress the file again.
     */
    static boolean compressesLoop(String hostFs) {
        return hostFs != null && !"btrfs".equals(hostFs);
    }

    /** Run a host command with stdout shown, returning stderr so the caller can word the failure. */
    static Attempt runCapturingStderr(String... command) {
        dev.incusspawn.util.Privilege.check(command);
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            var process = pb.start();
            var stderr = new String(process.getErrorStream().readAllBytes());
            return new Attempt(process.waitFor(), stderr);
        } catch (IOException | InterruptedException e) {
            return new Attempt(1, "Failed to run: " + String.join(" ", command) + ": " + e.getMessage());
        }
    }
    /**
     * Why creating the CoW pool failed, worded after Incus's own error rather than a guess:
     * missing loop device support is only one cause, and naming it for any other sends the user
     * after the wrong fix.
     */
    static List<String> failureExplanation(String incusError) {
        var lines = new ArrayList<String>();
        var error = incusError == null ? "" : incusError.strip();
        error.lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(lines::add);
        var lower = error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("loop")) {
            lines.add("This is expected inside containers or VMs without loop device");
            lines.add("support. On bare metal, ensure the 'loop' kernel module is");
            lines.add("loaded (sudo modprobe loop) and try again.");
        } else if (lower.contains("mkfs.btrfs") || lower.contains("btrfs-progs")) {
            lines.add("Install btrfs-progs on the host and try again.");
        } else if (lower.contains("storage-pools/cow") && lower.contains("already exists")) {
            lines.add("A leftover /var/lib/incus/storage-pools/cow is in the way: if no pool");
            lines.add("uses it, remove it (sudo rm -r) and try again.");
        }
        return lines;
    }

    /** What a host command returned: its exit code and its stderr, for wording a failure. */
    record Attempt(int exitCode, String stderr) {}

    @FunctionalInterface
    interface Runner {
        Attempt run(String... command);
    }

    /** The pool create that decided, and whether it made a subvolume. */
    record Created(boolean subvolume, Attempt result) {}

    /**
     * Create the pool the way this host wants it, as {@code isx init} and doctor's remediation both
     * do. On btrfs a failed subvolume create falls back to a loop file: that writes twice, but no
     * CoW pool at all makes every branch a full copy. {@code warn} says why it fell back, worded
     * after Incus's error; {@code makeDisksDir} makes {@link #DISKS_DIR} for the loop file.
     */
    static Created create(Function<Path, String> fsOf, BooleanSupplier skipTrim, Runner run,
                          Runnable makeDisksDir, BiConsumer<String, List<String>> warn) {
        if ("btrfs".equals(fsOf.apply(POOLS_DIR))) {
            var subvolume = run.run(subvolumeCommand());
            if (subvolume.exitCode() == 0) return new Created(true, subvolume);
            // One warning, before the loop file is made: why, then what doctor offers later
            var details = new ArrayList<>(failureExplanation(subvolume.stderr()));
            details.add("'isx doctor' will say how to move it to a subvolume later.");
            warn.accept("Could not create the pool as a subvolume; falling back to a loop file.", details);
        }
        makeDisksDir.run();
        // Whether to compress depends on where the file lives, as doctor judges it
        var compress = compressesLoop(fsOf.apply(Path.of(DISKS_DIR)));
        return new Created(false, run.run(loopCommand(compress, skipTrim.getAsBoolean())));
    }

    /**
     * The filesystem holding {@code path}, or null when it cannot be read. Asks of its nearest
     * existing ancestor, so a directory not made yet does not pass for another filesystem.
     */
    static String filesystemOf(Path path) {
        var dir = path;
        while (dir != null && !Files.exists(dir)) dir = dir.getParent();
        return dir == null ? null : filesystemType(dir);
    }

    /** The filesystem type holding {@code path} ("btrfs", "ext4", ...), or null when it cannot be read. */
    static String filesystemType(Path path) {
        try {
            return Files.getFileStore(path).type();
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether a pool with this config is a loop file Incus manages: its source is {@code disks/<pool>.img}. */
    static boolean isLoopFile(Map<String, String> config) {
        // Incus names it disks/<pool>.img for every driver; a subvolume or device source never ends so
        var source = config.getOrDefault("source", "");
        return source.startsWith("/") && source.endsWith(".img");
    }

    /**
     * {@code current} mount options with {@code user_subvol_rm_allowed} kept, and
     * {@code noatime} and {@code commit=300} added where the
     * user set no atime or commit option of their own, and {@code compress=zstd:1} where
     * {@code compress} is true and they chose no compression and no {@code nodatacow} or
     * {@code nodatasum}, which compression would turn off. Empty means Incus's default.
     */
    static String withLoopMountOptions(String current, boolean compress) {
        var options = new ArrayList<>(Arrays.asList(effectiveMountOptions(current).split(",")));
        // Incus's own default, lost whenever a value is set without it
        if (!options.contains(INCUS_DEFAULT_MOUNT_OPTIONS)) options.add(0, INCUS_DEFAULT_MOUNT_OPTIONS);
        if (options.stream().noneMatch(ATIME_OPTIONS::contains)) options.add("noatime");
        if (options.stream().noneMatch(o -> o.startsWith("commit="))) options.add("commit=300");
        if (compress && options.stream().noneMatch(o -> o.startsWith("compress")
                || o.equals("nodatacow") || o.equals("nodatasum"))) options.add("compress=zstd:1");
        return String.join(",", options);
    }

    /** The mount options Incus uses for {@code btrfs.mount_options} set to {@code current}. */
    private static String effectiveMountOptions(String current) {
        var options = current == null ? List.<String>of()
                : Arrays.stream(current.split(",")).map(String::strip).filter(o -> !o.isEmpty()).toList();
        return options.isEmpty() ? INCUS_DEFAULT_MOUNT_OPTIONS : String.join(",", options);
    }

    /**
     * What {@code isx doctor} says about how a btrfs CoW pool is backed: a loop file on btrfs is
     * worth moving off, and a loop file anywhere should be mounted with the options {@link #loopCommand} sets.
     * Nothing for a subvolume pool. {@code setConfig} applies one pool config key.
     */
    static List<DoctorCommand.Finding> doctorFindings(String pool, IncusClient.PoolInfo info,
                                                      Function<Path, String> fsType,
                                                      ConfigSetter setConfig) {
        var config = info.config();
        if (!"btrfs".equals(info.driver()) || !isLoopFile(config)) return List.of();
        var findings = new ArrayList<DoctorCommand.Finding>();
        var file = Path.of(config.get("source"));
        var type = fsType.apply(file.getParent());
        if ("btrfs".equals(type)) {
            findings.add(DoctorCommand.Finding.warn("CoW pool '" + pool + "' is a loop file on btrfs",
                    file + " nests btrfs inside btrfs, so every instance write is copy-on-write twice."
                            + " Move to a subvolume pool: " + MIGRATION_NOTES,
                    null));
        }
        var current = effectiveMountOptions(config.get("btrfs.mount_options"));
        // On btrfs, or a filesystem that cannot be read, the host may compress the file again
        var wanted = withLoopMountOptions(current, compressesLoop(type));
        if (!wanted.equals(current)) {
            findings.add(DoctorCommand.Finding.warn("CoW pool '" + pool + "' is not mounted for scratch space",
                    "its loop file is mounted with '" + current + "', so idle instances write more than they need to",
                    new DoctorCommand.Remediation("Mount it with " + wanted, false,
                            () -> setConfig.set(pool, "btrfs.mount_options", wanted))));
        }
        return findings;
    }

    @FunctionalInterface
    interface ConfigSetter {
        void set(String pool, String key, String value) throws Exception;
    }
}
