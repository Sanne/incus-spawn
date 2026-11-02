package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.util.UUID;

/**
 * Every instance's own {@code /etc/machine-id} (#1305). A copy carries its source's, so without
 * this every branch of a template would be one host to the journal, a DHCP server's DUID and
 * anything derived with {@code systemd-id128}.
 *
 * <p>Never {@code uninitialized}: that makes the next boot a first boot, and Fedora's systemd
 * then runs a full {@code preset-all}, which disables every unit the image and the template
 * build enabled against the presets -- {@code systemd-networkd} and the network watchdog among
 * them. A fresh ID, or an empty file that systemd fills with one, boots as any other boot.
 */
public final class MachineId {

    static final String PATH = "/etc/machine-id";

    /**
     * Run in a finished template's guest before its last stop, so each copy's first start finds no
     * ID and systemd generates its own. Still needed by a container branch whose {@link #giveOwn}
     * push failed, and by a VM on an image that commits its ID (older than #1483); the current VM
     * image never does, and takes each boot's from the SMBIOS UUID (design/instances.md "Branching").
     * Unmounted first: a transient ID systemd never committed is mounted over the file on disk.
     * Best effort, like {@link #giveOwn}: a template whose ID stays is still a working template.
     */
    public static final String CLEAR_SCRIPT = "{ umount " + PATH + " 2>/dev/null; : > " + PATH + " || true; }";

    private MachineId() {}

    /**
     * Give a stopped container branch a fresh ID, before its first start. Only warns on failure:
     * the branch works, and shares its source's ID unless that was cleared.
     */
    static void giveOwn(IncusClient incus, String name) {
        try {
            // The format systemd itself writes: a random v4 UUID, lowercase, without dashes
            incus.filePushContent(UUID.randomUUID().toString().replace("-", "") + "\n", name, PATH, "0", "0", "0444");
        } catch (IOException | RuntimeException e) {
            BuildOutput.warn("failed to give " + name + " a machine-id of its own, it may share its source's: "
                    + e.getMessage());
        }
    }
}
