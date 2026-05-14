package dev.incusspawn.incus;

public final class CidrUtils {

    public record Cidr(long network, int prefixLen) {}

    private CidrUtils() {}

    public static long ipToLong(String ip) {
        var parts = ip.split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IP: " + ip);
        long result = 0;
        for (var part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) throw new IllegalArgumentException("Invalid IP: " + ip);
            result = (result << 8) | octet;
        }
        return result;
    }

    public static String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    public static long networkMask(int prefixLen) {
        if (prefixLen == 0) return 0L;
        return 0xFFFFFFFFL << (32 - prefixLen) & 0xFFFFFFFFL;
    }

    public static Cidr parseCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return new Cidr(ipToLong(cidr), 32);
        }
        var ip = cidr.substring(0, slash);
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        long network = ipToLong(ip) & networkMask(prefix);
        return new Cidr(network, prefix);
    }

    public static boolean overlaps(Cidr a, Cidr b) {
        int shorter = Math.min(a.prefixLen(), b.prefixLen());
        long mask = networkMask(shorter);
        return (a.network() & mask) == (b.network() & mask);
    }
}
