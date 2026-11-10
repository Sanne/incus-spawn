package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import dev.incusspawn.vm.VmNetwork;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Who may connect to an exposed host port (#2117): instances, and nothing beside the host. The
 * relay listens on the bridge's gateway (Linux) or the Mac's end of the VM link (macOS), and a
 * host with no input firewall routes a LAN peer's packets for that address to it (the weak host
 * model), which would hand that peer a loopback caller's access to the service.
 * <p>
 * So a connection is relayed only from the bridge's own subnet on Linux, the subnet of the
 * interface that holds the bind address, and only from the VM's address on macOS, where every
 * container reaches the Mac through the VM's NAT. Anything else, an address that cannot be
 * placed included, is refused: closed before anything is connected to loopback.
 * <p>
 * The rule is found again once it is {@link #REDISCOVER_MILLIS} old, at the next connection,
 * since the bridge's subnet or the VM's lease can change under a running proxy; no caller can
 * make it look more often.
 */
final class RelayCallers {

    /** The least time between two lookups of the rule, so a refused caller cannot make them costly. */
    static final long REDISCOVER_MILLIS = 5_000;

    private final Supplier<Predicate<InetAddress>> discover;
    private final LongSupplier clock;
    private Predicate<InetAddress> rule;
    private long discoveredAt;

    /** Tests: {@code discover} gives the rule, looked up again on a refusal at most every {@link #REDISCOVER_MILLIS} of {@code clock}. */
    RelayCallers(Supplier<Predicate<InetAddress>> discover, LongSupplier clock) {
        this.discover = discover;
        this.clock = clock;
    }

    /** This host's rule for a relay bound to {@code bindAddress}. */
    static RelayCallers forThisHost(String bindAddress) {
        Supplier<Predicate<InetAddress>> discover = Platform.isMacOS()
                ? () -> only(VmNetwork.discoverVmIp())
                : () -> subnetOf(bindAddress);
        return new RelayCallers(discover, System::currentTimeMillis);
    }

    /** Whether a connection from {@code source} may be relayed. */
    synchronized boolean admits(InetAddress source) {
        var address = unmapped(source);
        var now = clock.getAsLong();
        // Again once the rule is older than the interval, whatever the caller: a moved lease or a
        // narrowed bridge must stop admitting an address the old rule held
        if (rule == null || now - discoveredAt >= REDISCOVER_MILLIS) {
            rule = discover.get();
            discoveredAt = now;
        }
        return rule.test(address);
    }

    /** Admits {@code address} alone, or nothing when it is null or not an address. */
    static Predicate<InetAddress> only(String address) {
        if (address == null) return source -> false;
        InetAddress expected;
        try {
            expected = InetAddress.ofLiteral(address);
        } catch (IllegalArgumentException notALiteral) {
            return source -> false;
        }
        return expected::equals;
    }

    /**
     * Admits the IPv4 subnet of the interface that holds {@code bindAddress}, or nothing when no
     * interface holds it, as when the bridge has given the address up; a loopback bind admits
     * loopback callers, the only ones that can reach it.
     */
    static Predicate<InetAddress> subnetOf(String bindAddress) {
        try {
            var bind = InetAddress.ofLiteral(bindAddress);
            // Only the host itself reaches a loopback address, as a proxy bound there in tests
            if (bind.isLoopbackAddress()) return InetAddress::isLoopbackAddress;
            var nic = NetworkInterface.getByInetAddress(bind);
            if (nic != null) {
                for (var assigned : nic.getInterfaceAddresses()) {
                    if (assigned.getAddress().equals(bind)) return subnet(bind, assigned.getNetworkPrefixLength());
                }
            }
        } catch (Exception unplaced) {
            // Refuse everything: an address that cannot be placed is no instance's
        }
        ProxyLog.warn("Exposed host ports: no interface holds " + bindAddress + ", so no caller is admitted");
        return source -> false;
    }

    /** Admits the IPv4 addresses that share {@code prefix} leading bits with {@code network}. */
    static Predicate<InetAddress> subnet(InetAddress network, int prefix) {
        if (!(network instanceof Inet4Address) || prefix < 0 || prefix > 32) return source -> false;
        var mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        var base = toInt(network) & mask;
        return source -> source instanceof Inet4Address && (toInt(source) & mask) == base;
    }

    private static int toInt(InetAddress v4) {
        var b = v4.getAddress();
        return (b[0] & 0xff) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | (b[3] & 0xff);
    }

    /** An IPv4 address as itself, when a dual-stack socket reports it as {@code ::ffff:a.b.c.d}. */
    static InetAddress unmapped(InetAddress address) {
        if (address instanceof Inet6Address v6) {
            var b = v6.getAddress();
            var mapped = true;
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            if (mapped) {
                try {
                    return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
                } catch (java.net.UnknownHostException impossible) {
                    return address;
                }
            }
        }
        return address;
    }
}
