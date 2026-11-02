package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.util.Output;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What enforces {@code --proxy-only} (#1137): an Incus network ACL on the instance's bridge NIC
 * that lets its egress reach the gateway's proxy, health check and DNS, and nothing else. Incus
 * installs it in the host's nftables, out of reach of root in the guest, and installs it again on
 * every start of the instance, whoever starts it (an {@code incus restart}, autostart after a host
 * reboot), or refuses the start. Rules inside the guest held against neither.
 *
 * <p>Ingress stays open: the host reaches the instance over SSH, and replies to the instance's own
 * connections pass as established either way.
 */
public final class ProxyOnlyAcl {

    private ProxyOnlyAcl() {}

    /** The ACL, one per Incus, shared by every proxy-only instance. */
    public static final String NAME = "isx-proxy-only";

    static final String DESCRIPTION = "isx --proxy-only: egress to the isx proxy and DNS on the bridge gateway only";

    /** The NIC settings that put the ACL in force. */
    static final Map<String, String> NIC_KEYS = nicKeys();

    private static Map<String, String> nicKeys() {
        var keys = new LinkedHashMap<String, String>();
        keys.put("security.acls", NAME);
        keys.put("security.acls.default.egress.action", "drop");
        keys.put("security.acls.default.ingress.action", "allow");
        return Collections.unmodifiableMap(keys);
    }

    /** Proxy-only enforcement is not possible on this host, so nothing proxy-only may run on it. */
    public static final class NotEnforceableException extends IncusException {
        NotEnforceableException(String message) {
            super(message);
        }
    }

    /**
     * What an instance's egress may reach. DNS, over UDP and TCP, is also among the rules Incus adds
     * ahead of any ACL, so no ACL can narrow it; it is named here so that the ACL says what is in
     * force, and a change there does not cut it. ICMP echo is for the connectivity watchdog's
     * {@code ping} of the gateway.
     *
     * <p>The proxy's listening port is allowed too: with bridge netfilter on (Docker, Kubernetes),
     * the host's redirect of 443 to it runs before the ACL's chains (#1503, design/network.md).
     */
    static List<Map<String, String>> egressRules(String gateway) {
        var ports = ProxyConfig.CONTAINER_FACING_PORT + "," + ProxyConfig.DEFAULT_MITM_PORT + ","
                + ProxyConfig.DEFAULT_HEALTH_PORT;
        return List.of(
                allow(gateway, "tcp", "destination_port", ports, "the isx proxy and its health check"),
                allow(gateway, "udp", "destination_port", "53", "DNS"),
                allow(gateway, "tcp", "destination_port", "53", "DNS"),
                allow(gateway, "icmp4", "icmp_type", "8", "ping of the gateway"));
    }

    /** A rule allowing {@code protocol} to {@code gateway}, narrowed by one more field. */
    private static Map<String, String> allow(String gateway, String protocol, String key, String value, String description) {
        return Map.of("action", "allow", "state", "enabled", "destination", gateway,
                "protocol", protocol, key, value, "description", description);
    }

    /**
     * Check that this Incus can enforce the ACL, and write it for {@code gateway}. Incus accepts
     * NIC ACLs only from its nftables firewall driver.
     *
     * @throws NotEnforceableException when it cannot
     */
    public static void ensure(IncusClient incus, String gateway) {
        var driver = incus.firewallDriver();
        if (!"nftables".equals(driver)) throw new NotEnforceableException(refusal(driver));
        write(incus, gateway);
    }

    /** Write the ACL for {@code gateway}, as after the bridge moved to another subnet. */
    static void write(IncusClient incus, String gateway) {
        incus.putNetworkAcl(NAME, DESCRIPTION, egressRules(gateway));
    }

    static String refusal(String driver) {
        return "--proxy-only is enforced by Incus on the host, which needs its nftables firewall driver,"
                + " and this Incus uses " + (driver.isEmpty() ? "another one" : driver) + ".\n"
                + "Rules inside the instance would not hold against its root user, so isx does not fall back to them.\n"
                + "Incus picks xtables when legacy iptables or ebtables rules are in use on the host: remove them"
                + " (or switch the host's firewall to nftables), restart Incus, and check 'incus info | grep firewall'.\n"
                + "Until then, use full network or --airgap.";
    }

    /**
     * The network devices Incus would attach to {@code instance}, from its own devices or a
     * profile's ({@link IncusClient#isNetworkDevice}): NICs, and the devices that reach a network
     * without any NIC.
     */
    private static List<String> networkDevices(JqValue instance) {
        return InstanceNetwork.expandedDevices(instance, IncusClient::isNetworkDevice);
    }

    /**
     * The {@linkplain #networkDevices network devices} of {@code instance} besides {@code restricted}.
     * Only the one bridge NIC carries the ACL, so any other has full egress.
     */
    static List<String> otherNetworkDevices(JqValue instance, String restricted) {
        return networkDevices(instance).stream().filter(device -> !device.equals(restricted)).toList();
    }

    /**
     * Refuse proxy-only for {@code instance} when Incus would attach a network device besides the
     * bridge NIC isx restricts. One with no bridge NIC is left to the callers, which refuse it themselves.
     *
     * @param subject how the refusal names it: the instance, or the branch to be made from it
     * @param remedy what the user can do about it
     * @throws NotEnforceableException when it would
     */
    static void requireOneNic(String subject, JqValue instance, String remedy) {
        var nic = IncusClient.nicDeviceName(instance, BridgeAddress.BRIDGE);
        if (nic == null) return;
        var others = otherNetworkDevices(instance, nic);
        if (others.isEmpty()) return;
        throw new NotEnforceableException(subject + " has a network device besides the one on " + BridgeAddress.BRIDGE
                + " that --proxy-only restricts (" + String.join(", ", others) + "), which would reach the network"
                + " directly. " + remedy);
    }

    /** Whether a NIC's config has the ACL in force, exactly as isx sets it. */
    static boolean inForce(Map<String, String> nicConfig) {
        return NIC_KEYS.entrySet().stream().allMatch(e -> e.getValue().equals(nicConfig.get(e.getKey())));
    }

    /** Whether a NIC's config names any ACL setting, which a branch that is not proxy-only drops. */
    static boolean anyKey(Map<String, String> nicConfig) {
        return NIC_KEYS.keySet().stream().anyMatch(nicConfig::containsKey);
    }

    /**
     * Put the ACL in force on a proxy-only instance that lacks it: one branched before #1137, whose
     * rules were inside the guest. Every start for use goes through here (and {@code isx shell} on a
     * running instance), so such an instance is restricted on the host from the first time isx sees
     * it. An instance that has it costs no request: only the instance the caller read is looked at.
     *
     * @throws NotEnforceableException when the host cannot enforce it: the instance must not be
     *         started, or used, as if it were restricted
     */
    static void enforce(IncusClient incus, String name, JqValue instance, Output out) {
        if (!NetworkMode.isProxyOnly(instance)) return;
        var passthrough = InstanceNetwork.passthroughRefusal(name, "proxy-only", instance);
        if (passthrough != null) throw new NotEnforceableException(passthrough);
        var nic = IncusClient.nic(instance, BridgeAddress.BRIDGE);
        if (nic == null) {
            // No network device at all is no network, which is within the mode; a network device
            // elsewhere is unrestricted
            var devices = networkDevices(instance);
            if (!devices.isEmpty()) {
                var one = devices.size() == 1;
                throw new NotEnforceableException("'" + name + "' is proxy-only, but it has "
                        + (one ? "a network device that is" : "network devices that are") + " not on "
                        + BridgeAddress.BRIDGE + " (" + String.join(", ", devices) + "), where isx restricts its network;"
                        + " isx will not use it until " + (one ? "it is" : "they are")
                        + " removed from the instance or its profile."
                        + " 'isx destroy " + name + "' removes it.");
            }
            return;
        }
        requireOneNic("'" + name + "' is proxy-only, but it", instance, "isx will not use it until the device is"
                + " removed from it or its profile; 'isx destroy " + name + "' removes it.");
        if (inForce(nic.config())) return;
        try {
            // The bridge's gateway, not the one the instance recorded: the ACL is every proxy-only
            // instance's, and a stale record would take the proxy away from all of them
            ensure(incus, BridgeAddress.require(incus).gateway());
            incus.deviceConfigSetAll(name, nic.name(), NIC_KEYS);
        } catch (RuntimeException e) {
            throw notEnforceable(name, e.getMessage());
        }
        // A warning, not a step: the TUI's log keeps it, and isolation never changes unsaid
        out.warn("The proxy-only restriction of " + name + " is now enforced on the host.");
    }

    /**
     * Run {@code start}, a write to or start of {@code name}, and when it fails because this Incus
     * cannot enforce the ACL the instance already has, throw the refusal instead. An Incus that
     * moved to xtables since the branch refuses every write to such an instance and every start
     * of it, which isx would otherwise report only as a failed write. A restriction in force costs
     * {@link #enforce} no request, so the driver is asked here, once Incus has refused.
     */
    static <T> T explaining(IncusClient incus, String name, JqValue instance, Supplier<T> start) {
        try {
            return start.get();
        } catch (RuntimeException e) {
            if (e instanceof NotEnforceableException || !NetworkMode.isProxyOnly(instance)) throw e;
            String driver;
            try {
                driver = incus.firewallDriver();
            } catch (RuntimeException unread) {
                throw e;
            }
            throw "nftables".equals(driver) ? e : notEnforceable(name, refusal(driver));
        }
    }

    private static NotEnforceableException notEnforceable(String name, String reason) {
        return new NotEnforceableException("'" + name + "' is proxy-only, but its restriction cannot be enforced on the host: "
                + reason + "\nisx will not use it until it can; 'isx destroy " + name + "' removes it.");
    }
}
