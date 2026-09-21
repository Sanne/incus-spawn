package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maps a request's source address to the instance that made it, and to the credential
 * accounts that instance is pinned to.
 *
 * <h3>Why source address identifies an instance</h3>
 * Every branch is given a static IP on the bridge by {@code InstanceLifecycle.assignStaticIp},
 * recorded as {@link Metadata#STATIC_IP}, and the iptables REDIRECT that sends :443 to the
 * proxy preserves the source address. So the address a connection arrives from is the
 * instance's own, and one {@code /1.0/instances?recursion=1} call maps every address at once.
 *
 * <p>That identity is only trustworthy because {@code security.ipv4_filtering} is set on the
 * NIC: without it a container could re-address itself as a neighbour and spend that
 * neighbour's credentials. Do not treat the mapping as an authorization decision on hosts
 * where the filtering could not be applied.
 *
 * <h3>Staleness</h3>
 * Lookups never block -- they read a snapshot, and the caller refreshes off the event loop.
 * An address missing from the snapshot resolves to the configured defaults rather than
 * failing: the proxy also serves template build containers and host-side traffic, which have
 * no account pinning and must keep working. The window where a freshly branched instance is
 * not yet in the snapshot is closed by {@code isx branch} signalling the proxy on completion,
 * with {@link #STALE_AFTER_MS} as the backstop if that signal is missed.
 *
 * <p>Note this is deliberately Vert.x-free: {@link IncusClient} calls block, so the proxy
 * drives {@link #refresh} from a worker thread and only ever calls {@link #lookup} on the
 * event loop.
 */
public final class InstanceRegistry {

    /** How long a snapshot is served before a refresh is wanted. */
    public static final long STALE_AFTER_MS = 10_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One instance's identity, as far as credential selection is concerned. */
    public record InstanceAccounts(String instanceName, Map<String, String> accountsByNamespace) {
        public InstanceAccounts {
            accountsByNamespace = accountsByNamespace == null
                    ? Map.of() : Map.copyOf(accountsByNamespace);
        }

        /** The instance pins nothing, so the configured defaults apply. */
        public boolean usesDefaults() { return accountsByNamespace.isEmpty(); }
    }

    private record Snapshot(Map<String, InstanceAccounts> byAddress, long takenAt) {}

    private static final Snapshot EMPTY = new Snapshot(Map.of(), 0L);

    private final IncusClient incus;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Snapshot snapshot = EMPTY;

    public InstanceRegistry(IncusClient incus) {
        this.incus = incus;
    }

    /**
     * The accounts pinned to the instance at this address, or null when the address is not a
     * known instance. Non-blocking: safe to call from the event loop.
     */
    public InstanceAccounts lookup(String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank()) return null;
        return snapshot.byAddress().get(normalize(sourceAddress));
    }

    /** Whether the snapshot is old enough that a refresh is worth doing. */
    public boolean isStale() {
        return System.currentTimeMillis() - snapshot.takenAt() > STALE_AFTER_MS;
    }

    public boolean isEmpty() { return snapshot.byAddress().isEmpty(); }

    /**
     * Rebuild the snapshot from Incus. <strong>Blocks</strong> -- call from a worker thread.
     * Single-flight: a concurrent call returns immediately rather than queueing a second
     * listing behind the first.
     *
     * @return true if this call performed the refresh
     */
    public boolean refresh() {
        if (!refreshing.compareAndSet(false, true)) return false;
        try {
            var parsed = parse(incus.listJsonConfig());
            snapshot = new Snapshot(parsed, System.currentTimeMillis());
            return true;
        } catch (RuntimeException e) {
            // Keep serving the previous snapshot: a transient Incus hiccup should not
            // strip every instance of its pinned account and silently fall back to
            // defaults. Bump the timestamp so a broken socket is not retried per request.
            snapshot = new Snapshot(snapshot.byAddress(), System.currentTimeMillis());
            ProxyLog.warn("Instance registry refresh failed: " + e.getMessage());
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * Build the address map from the JSON of {@code /1.0/instances?recursion=1}.
     * Package-private and static so it can be tested without a running Incus.
     */
    static Map<String, InstanceAccounts> parse(String instancesJson) {
        var byAddress = new LinkedHashMap<String, InstanceAccounts>();
        try {
            var root = JSON.readTree(instancesJson);
            if (!root.isArray()) return byAddress;
            for (var instance : root) {
                var name = instance.path("name").asText("");
                var config = instance.path("config");
                if (name.isEmpty() || !config.isObject()) continue;

                var address = config.path(Metadata.STATIC_IP).asText("").strip();
                if (address.isEmpty()) continue;

                byAddress.put(normalize(address), new InstanceAccounts(name, accountsOf(config)));
            }
        } catch (Exception e) {
            ProxyLog.warn("Could not parse instance list for the account registry: " + e.getMessage());
        }
        return byAddress;
    }

    private static Map<String, String> accountsOf(JsonNode config) {
        var accounts = new LinkedHashMap<String, String>();
        config.properties().forEach(entry -> {
            var key = entry.getKey();
            if (!key.startsWith(Metadata.ACCOUNT_PREFIX)) return;
            var value = entry.getValue().asText("").strip();
            if (!value.isEmpty()) {
                accounts.put(key.substring(Metadata.ACCOUNT_PREFIX.length()), value);
            }
        });
        return accounts;
    }

    /**
     * Vert.x reports an IPv4 peer over a dual-stack listener as {@code ::ffff:10.0.0.5};
     * Incus records the plain form. Without this every such lookup misses and the instance
     * silently gets default credentials.
     */
    static String normalize(String address) {
        var trimmed = address.strip();
        var mapped = "::ffff:";
        if (trimmed.regionMatches(true, 0, mapped, 0, mapped.length())) {
            return trimmed.substring(mapped.length());
        }
        return trimmed;
    }
}
