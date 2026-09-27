package dev.incusspawn.incus;

import java.util.Optional;

/**
 * The incusbr0 bridge's own address, which is the gateway, and the subnet it serves: everything
 * a branch's static network config needs, from one read of the bridge. A flow that needs more
 * than one of these reads the bridge once and passes this along.
 */
public record BridgeAddress(String gateway, CidrUtils.Cidr subnet) {

    public static final String BRIDGE = "incusbr0";

    /** The bridge's address, or empty if it has no IPv4 address configured. */
    public static Optional<BridgeAddress> read(IncusClient incus) {
        return parse(incus.networkConfigGet(BRIDGE, "ipv4.address"));
    }

    /** The bridge's address; throws if it has none, since nothing can be addressed without it. */
    public static BridgeAddress require(IncusClient incus) {
        return read(incus).orElseThrow(() ->
                new IncusException("Bridge " + BRIDGE + " has no ipv4.address configured"));
    }

    /** {@code 10.166.11.1/24}; an address without a prefix length is taken as a /24. */
    static Optional<BridgeAddress> parse(String cidr) {
        if (cidr.isEmpty()) return Optional.empty();
        var slash = cidr.indexOf('/');
        var gateway = slash < 0 ? cidr : cidr.substring(0, slash);
        return Optional.of(new BridgeAddress(gateway,
                CidrUtils.parseCidr(slash < 0 ? gateway + "/24" : cidr)));
    }

    public int prefixLen() {
        return subnet.prefixLen();
    }
}
