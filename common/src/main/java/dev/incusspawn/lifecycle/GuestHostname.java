package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.proxy.InstanceSecret;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.regex.Pattern;

/**
 * Gives a VM's guest its instance name as its hostname (#2334), in the exec that delivers each
 * boot's secret.
 *
 * <p>Every name the guest shows reads the hostname: {@code ISX_CONTAINER} (which expands
 * {@code ${HOSTNAME}} at login, so a branch shows its own name), the terminal title, the agents'
 * status lines and any {@code hostname()} call. Incus sets a container's hostname to the instance
 * name. Nothing sets a VM's: it keeps the base image's {@code fedora}, unless a DHCP answer
 * happened to name it, and a VM that boots its static {@code .network} file asks DHCP nothing.
 *
 * <p>The name goes to the kernel only, never to {@code /etc/hostname}: a copy would carry that
 * file under its source's name until its own first start. So it lasts one boot, and every path
 * that gives a boot its secret sets it again: a start, a restart, the VM agent's recovery and the
 * repair of a reboot isx did not do. A VM booted outside isx shows {@code fedora} until one of
 * them runs, which the first {@code isx shell}, {@code ssh} or MCP call does.
 *
 * <p>Its line goes after {@link InstanceSecret#GUEST_SCRIPT}, which unsets
 * the variables carrying the secret, so nothing it may ever run inherits them.
 */
final class GuestHostname {

    /** What a hostname label allows, which every Incus instance name already is. */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private GuestHostname() {}

    /**
     * Shell that sets the guest's hostname to {@code name} when {@code instance} is a VM, followed
     * by a newline; empty for a container, whose hostname Incus sets, or a name that is no
     * hostname. Best-effort, so it never fails the exec it rides in.
     *
     * @param instance the instance as read, for its type; null gives empty
     */
    static String script(String name, JqValue instance) {
        if (instance == null || IncusClient.machineType(instance) != MachineType.VM) return "";
        if (name == null || !LABEL.matcher(name).matches()) return "";
        // Braced, so a failed redirection's own message goes to /dev/null too
        return "{ printf '%s' '" + name + "' > /proc/sys/kernel/hostname; } 2>/dev/null || true\n";
    }

    /**
     * {@link InstanceSecret#GUEST_SCRIPT}, then {@link #script}: what every
     * exec that gives a boot its secret runs, so a new one cannot leave a VM's name out.
     */
    static String afterSecret(String name, JqValue instance) {
        return InstanceSecret.GUEST_SCRIPT + "\n" + script(name, instance);
    }
}
