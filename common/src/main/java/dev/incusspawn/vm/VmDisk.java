package dev.incusspawn.vm;

import dev.incusspawn.util.BuildOutput;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The VM's data disk as its control agent reports it ({@code disk-free}, #2555): bytes free and in
 * all, and the reserve the appliance keeps on it so Incus can start once the rest is full.
 * <p>
 * The disk holds Incus's database beside the pool, so when it is full Incus stops, and isx sees
 * only an Incus that does not answer. The agent answers on a port of its own, so isx can still
 * tell that the disk is the cause, and say what gets Incus back, instead of restarting a
 * forwarder that is not at fault.
 */
public record VmDisk(long freeBytes, long totalBytes, long reserveBytes) {

    private static final Pattern REPLY = Pattern.compile("(\\d{1,19}) (\\d{1,19}) (\\d{1,19})");
    private static final double GIB = 1024.0 * 1024 * 1024;

    /** The agent's reply, or empty for an agent that did not answer or predates the verb. */
    static Optional<VmDisk> parse(Optional<String> reply) {
        if (reply.isEmpty()) return Optional.empty();
        var m = REPLY.matcher(reply.get().trim());
        if (!m.matches()) return Optional.empty();
        try {
            var disk = new VmDisk(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)));
            return disk.totalBytes() > 0 ? Optional.of(disk) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** What the agent of the running VM reports, if anything. */
    public static Optional<VmDisk> read() {
        return parse(VmAgentClient.diskFree());
    }

    /**
     * Below this, an Incus that does not answer is taken to be stopped by the disk. Far below the
     * 2 GiB that refuses a branch: Incus runs well there, and a wedged forwarder on such a disk
     * still deserves its restart. A boot with the reserve released leaves about that much and more.
     */
    static final long FULL_FREE_BYTES = 512L * 1024 * 1024;

    /** Too full for Incus to be expected to run. */
    public boolean isFull() {
        return freeBytes < FULL_FREE_BYTES;
    }

    /**
     * Says that the full disk is why Incus does not answer, and what gets it back, when {@code disk}
     * is full. True when it said so.
     */
    static boolean warnIfFull(Optional<VmDisk> disk) {
        var lines = disk.filter(VmDisk::isFull).map(VmDisk::explanation).orElse(null);
        if (lines == null) return false;
        BuildOutput.warn(lines.getFirst(), lines.subList(1, lines.size()).toArray(String[]::new));
        return true;
    }

    /** Why Incus cannot run on this disk, then what brings it back, one instruction a line. */
    /**
     * The freed space shows only once btrfs commits the delete: about five minutes later on an
     * appliance before the agent's btrfs-settle (#2555, measured 4.5 to 7 min on v0.5.3).
     */
    static final String FREE_SPACE = "Then free space with 'isx destroy <name>' and 'isx clean pool'."
            + " The space can take up to about five minutes to show as free.";

    public java.util.List<String> explanation() {
        var lines = new java.util.ArrayList<String>();
        lines.add("The VM's disk is full, so Incus cannot run: "
                + String.format(Locale.ROOT, "%.1f GiB free of %.1f GiB", freeBytes / GIB, totalBytes / GIB) + ".");
        var grow = "grow the disk with 'isx vm resize <size>', larger than its "
                + String.format(Locale.ROOT, "%.0fG", totalBytes / GIB)
                + ": it restarts the VM, and Incus starts with the room.";
        if (reserveBytes > 0) {
            lines.add("Run 'isx vm restart': the VM releases the "
                    + String.format(Locale.ROOT, "%.1f GiB", reserveBytes / GIB)
                    + " it keeps in reserve as it starts, so Incus can start.");
            lines.add(FREE_SPACE);
            lines.add("Or " + grow);
        } else {
            lines.add(Character.toUpperCase(grow.charAt(0)) + grow.substring(1));
            lines.add(FREE_SPACE);
        }
        return lines;
    }
}
