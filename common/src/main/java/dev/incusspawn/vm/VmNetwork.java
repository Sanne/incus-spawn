package dev.incusspawn.vm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * VM network utilities — MAC address management and IP discovery.
 * On macOS with vfkit NAT networking, the VM gets a DHCP lease.
 * We assign a fixed MAC and look up the IP from the macOS DHCP leases file.
 */
public final class VmNetwork {

    private VmNetwork() {}

    /**
     * Fixed MAC address for the incus-spawn VM.
     * Locally-administered (bit 1 of first octet set), avoids OUI conflicts.
     */
    public static final String ISX_VM_MAC = "4a:53:58:00:00:01";

    private static final Path DHCP_LEASES_FILE = Path.of("/var/db/dhcpd_leases");

    // Lease record format (macOS dhcpd_leases):
    //   {
    //     name=...
    //     ip_address=192.168.64.3
    //     hw_address=1,4a:53:58:0:0:1
    //     ...
    //   }
    private static final Pattern IP_PATTERN = Pattern.compile("ip_address=([\\d.]+)");
    private static final Pattern MAC_PATTERN = Pattern.compile("hw_address=\\d+,([0-9a-f:]+)");

    /**
     * Discover the VM's IP address by parsing the macOS DHCP leases file.
     * Returns the IP or null if no matching lease is found.
     */
    public static String discoverVmIp() {
        if (!Files.exists(DHCP_LEASES_FILE)) return null;
        try {
            var content = Files.readString(DHCP_LEASES_FILE);
            var normalizedMac = normalizeMac(ISX_VM_MAC);

            // Split into lease records (brace-delimited blocks)
            var records = content.split("\\}");
            for (var record : records) {
                var macMatcher = MAC_PATTERN.matcher(record);
                if (!macMatcher.find()) continue;
                if (!normalizeMac(macMatcher.group(1)).equals(normalizedMac)) continue;

                var ipMatcher = IP_PATTERN.matcher(record);
                if (ipMatcher.find()) {
                    return ipMatcher.group(1);
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Wait for the VM to obtain a DHCP lease and return its IP.
     * Polls the leases file every second up to maxWaitSeconds.
     */
    public static String waitForVmIp(int maxWaitSeconds) {
        for (int i = 0; i < maxWaitSeconds; i++) {
            var ip = discoverVmIp();
            if (ip != null) return ip;
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Normalize a MAC address: lowercase, strip leading zeros from each octet.
     * macOS dhcpd_leases may store "4a:53:58:0:0:1" instead of "4a:53:58:00:00:01".
     */
    static String normalizeMac(String mac) {
        var parts = mac.toLowerCase().split(":");
        var sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(Integer.toHexString(Integer.parseInt(parts[i], 16)));
        }
        return sb.toString();
    }

    /**
     * Discover the macOS host IP on the VM-facing bridge interface.
     * Finds the network interface on the same /24 subnet as the VM's IP
     * by parsing ifconfig output.
     */
    public static String discoverHostBridgeIp() {
        var vmIp = discoverVmIp();
        if (vmIp == null) return null;
        var vmPrefix = vmIp.substring(0, vmIp.lastIndexOf('.') + 1);
        try {
            var pb = new ProcessBuilder("ifconfig");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            for (var line : output.split("\n")) {
                line = line.strip();
                if (line.startsWith("inet ") && line.contains(vmPrefix)) {
                    var parts = line.split("\\s+");
                    if (parts.length >= 2) return parts[1];
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
