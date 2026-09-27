package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class StaticIpAllocator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int LOCK_TIMEOUT_SECONDS = 30;

    /**
     * Serializes claims within this JVM. A second {@link FileChannel#tryLock} on the same file
     * from one process throws rather than waits, and closing any channel on the file drops every
     * {@code fcntl} lock the process holds on it, so only one thread may touch the file at a time.
     */
    private static final ReentrantLock IN_PROCESS = new ReentrantLock();

    private StaticIpAllocator() {}

    /**
     * Allocate the lowest free address on the incusbr0 subnet (.2-.254, skipping every NIC's
     * {@code ipv4.address}) and hand it to {@code write}, which must stamp it on the instance's
     * NIC before returning. Nothing reserves an address between the listing that finds it free
     * and that write, so two concurrent branches could otherwise both be given the same one
     * (#815). A host-wide lock is held across both, which serializes every isx process on this
     * host; Incus's own conflict check (409 "IP address ... already defined on another NIC")
     * refuses a duplicate from writers outside it.
     *
     * @return the address written
     */
    public static String claim(IncusClient incus, Consumer<String> write) {
        return claim(incus, Environment.lockDir().resolve("static-ip.lock"), write);
    }

    static String claim(IncusClient incus, Path lockFile, Consumer<String> write) {
        // The subnet does not depend on what is claimed, so it is read before the lock
        var bridgeAddr = incus.networkConfigGet("incusbr0", "ipv4.address");
        if (bridgeAddr.isEmpty()) {
            throw new IncusException("Bridge incusbr0 has no ipv4.address configured");
        }
        var cidr = CidrUtils.parseCidr(bridgeAddr);
        var gateway = CidrUtils.ipToLong(bridgeAddr.contains("/")
                ? bridgeAddr.substring(0, bridgeAddr.indexOf('/')) : bridgeAddr);

        if (IN_PROCESS.isHeldByCurrentThread()) {
            // The outer claim's address is not written yet, so this one would get the same
            throw new IllegalStateException("Nested static IP claim");
        }
        IN_PROCESS.lock();
        try (var channel = openLocked(lockFile)) {
            var claimed = getClaimedIps(incus);
            claimed.add(gateway);
            var ip = pickFreeIp(cidr, claimed);
            write.accept(ip);
            return ip;
        } catch (IOException e) {
            throw new IncusException("Failed to lock " + lockFile + ": " + e.getMessage(), e);
        } finally {
            IN_PROCESS.unlock();
        }
    }

    /** Opens the lock file with its lock held; closing the channel releases it. */
    private static FileChannel openLocked(Path lockFile) throws IOException {
        Files.createDirectories(lockFile.getParent());
        var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() != null) return channel;
            System.err.println("Another isx process is assigning a static IP -- waiting...");
            long deadline = System.nanoTime() + LOCK_TIMEOUT_SECONDS * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                // Short: the lock is held for a listing, a push and a write
                Thread.sleep(10);
                if (channel.tryLock() != null) return channel;
            }
            throw new IncusException("Timed out waiting for another isx process to assign a static IP"
                    + " (lock: " + lockFile + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.close();
            throw new IncusException("Interrupted waiting for the static IP lock", e);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    static String pickFreeIp(CidrUtils.Cidr cidr, Set<Long> claimed) {
        long networkBase = cidr.network();
        int prefixLen = cidr.prefixLen();
        long hostCount = 1L << (32 - prefixLen);
        for (long offset = 2; offset < hostCount - 1; offset++) {
            long candidate = networkBase + offset;
            if (!claimed.contains(candidate)) {
                return CidrUtils.longToIp(candidate);
            }
        }
        throw new IncusException("No free IP addresses on the bridge subnet "
                + CidrUtils.longToIp(networkBase) + "/" + prefixLen);
    }

    /**
     * Find the NIC device name attached to incusbr0 on an instance.
     * The NIC is typically inherited from the default Incus profile,
     * so we check expanded_devices rather than instance devices.
     */
    public static String findNicDevice(IncusClient incus, String instanceName) {
        var name = incus.findNicDeviceName(instanceName, "incusbr0");
        if (name == null) {
            throw new IncusException("No NIC device for incusbr0 found on " + instanceName);
        }
        return name;
    }

    /**
     * Every address a NIC claims. Fails rather than returning what it could read: an address
     * missing from this set is handed out.
     */
    static Set<Long> getClaimedIps(IncusClient incus) {
        var claimed = new HashSet<Long>();
        JsonNode instances;
        try {
            instances = JSON.readTree(incus.listJsonConfig());
        } catch (IOException e) {
            throw new IncusException("Failed to parse the instance listing: " + e.getMessage(), e);
        }
        for (var instance : instances) {
            collectClaimedIps(instance.path("expanded_devices"), claimed);
            collectClaimedIps(instance.path("devices"), claimed);
        }
        return claimed;
    }

    static void collectClaimedIps(JsonNode devices, Set<Long> claimed) {
        if (devices.isMissingNode()) return;
        for (var it = devices.fields(); it.hasNext(); ) {
            var dev = it.next().getValue();
            if (!"nic".equals(dev.path("type").asText())) continue;
            var ipStr = dev.path("ipv4.address").asText("");
            if (!ipStr.isEmpty()) {
                try {
                    claimed.add(CidrUtils.ipToLong(ipStr));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

}
