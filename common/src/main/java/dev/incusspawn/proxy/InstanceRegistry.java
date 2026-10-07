package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maps a request's source address to the instance that made it, and to the credential
 * accounts that instance is pinned to.
 *
 * <h3>Why source address identifies an instance</h3>
 * Every branch is given a static IP on the bridge by {@code InstanceLifecycle.configureBranch},
 * and every template build container that pins an account by
 * {@code InstanceLifecycle.assignBuildAddress} for as long as it builds (#903), recorded as
 * {@link Metadata#STATIC_IP}, and the iptables REDIRECT that sends :443 to the proxy preserves
 * the source address. So the address a connection arrives from is the
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
 * failing: the proxy also serves host-side traffic, which has no account pinning and must keep
 * working. The window where a freshly branched instance or build container is not yet in the
 * snapshot is closed by {@code isx branch} and {@code isx build} signalling the proxy before it
 * starts, with {@link #STALE_AFTER_MS} as the backstop if that signal is missed.
 *
 * <p>Note this is deliberately Vert.x-free: {@link IncusClient} calls block, so the proxy
 * drives {@link #refresh} from a worker thread and only ever calls {@link #lookup} on the
 * event loop.
 */
public final class InstanceRegistry {

    /** How long a snapshot is served before a refresh is wanted. */
    public static final long STALE_AFTER_MS = 10_000L;

    /**
     * Floor between refreshes triggered by an unknown address.
     *
     * <p>A miss is not rare: host-side traffic never appears in the map and misses on every
     * single request, as does a build container that started before the proxy was signalled.
     * Without a floor, a steady stream of such calls would each schedule a full instance
     * listing. {@code isx branch}, {@code isx build} and {@code isx destroy} signal the proxy
     * directly, so the miss path is only a backstop and can afford to be lazy.
     */
    public static final long MISS_REFRESH_INTERVAL_MS = 1_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One instance's identity, as far as credential selection is concerned.
     *
     * @param bakedIdentities what its build derived from each namespace's account (its
     *                        {@code account-identity} stamps), which the account it is served
     *                        must still match where the tool cannot re-derive it
     */
    public record InstanceAccounts(String instanceName, Map<String, String> accountsByNamespace,
                                   Map<String, String> bakedIdentities) {
        public InstanceAccounts {
            accountsByNamespace = accountsByNamespace == null
                    ? Map.of() : Map.copyOf(accountsByNamespace);
            bakedIdentities = bakedIdentities == null ? Map.of() : Map.copyOf(bakedIdentities);
        }

        public InstanceAccounts(String instanceName, Map<String, String> accountsByNamespace) {
            this(instanceName, accountsByNamespace, Map.of());
        }

        /** The instance pins nothing, so the configured defaults apply. */
        public boolean usesDefaults() { return accountsByNamespace.isEmpty(); }
    }

    /**
     * An isx instance's account state, for deciding who a default change moves.
     *
     * @param pins            namespace to pinned account; a namespace absent follows the default
     * @param bakedIdentities its {@code account-identity} stamps
     */
    public record AccountState(Map<String, String> pins, Map<String, String> bakedIdentities,
                               boolean running) {

        /** Whether this instance follows the global default for {@code namespace}. */
        public boolean followsDefault(String namespace) {
            return !pins.containsKey(namespace);
        }
    }

    /** Every instance in the registry's current snapshot, as the proxy would serve it. */
    public java.util.Collection<InstanceAccounts> instances() {
        return snapshot.parsed().byAddress().values();
    }

    /**
     * What one listing says.
     *
     * @param secretByAddress the hash of the secret each address's instance was given at its
     *                        last start ({@link InstanceSecret}). Kept beside the accounts rather
     *                        than in {@link InstanceAccounts}, which the proxy caches by value:
     *                        a key that changed on every restart would grow those caches.
     * @param mcpCallers      the instances, of those owning an address, stamped
     *                        {@link Metadata#MCP_CALLER}: allowed to call {@code isx mcp} (#915)
     * @param incarnationByName when each instance owning an address was created, which tells a
     *                        new instance from a destroyed one that had its name (#1063); kept
     *                        out of {@link InstanceAccounts} for the same reason as the secret
     */
    record Parsed(Map<String, InstanceAccounts> byAddress, Map<String, String> secretByAddress,
                  java.util.Set<String> mcpCallers, Map<String, String> incarnationByName) {}

    /**
     * @param view from {@link #VIEWS}, so a later snapshot always has a higher one, even from a
     *             registry that replaced another in the same process
     */
    private record Snapshot(Parsed parsed, long takenAt, long view) {}

    private static final Snapshot EMPTY = new Snapshot(new Parsed(Map.of(), Map.of(), java.util.Set.of(), Map.of()), 0L, 0L);

    private static final java.util.concurrent.atomic.AtomicLong VIEWS = new java.util.concurrent.atomic.AtomicLong();

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
        return find(snapshot.parsed(), sourceAddress);
    }

    private static InstanceAccounts find(Parsed current, String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank()) return null;
        return current.byAddress().get(normalize(sourceAddress));
    }

    /**
     * The instance at this address and when it was created ({@code created_at}, empty if the
     * listing lacked it), both from one snapshot; null when the address is not a known instance.
     * Non-blocking.
     */
    public Caller resolve(String sourceAddress) {
        var current = snapshot;
        var instance = find(current.parsed(), sourceAddress);
        return instance == null ? null : new Caller(instance,
                current.parsed().incarnationByName().getOrDefault(instance.instanceName(), ""), current.view());
    }

    /**
     * An instance a caller resolved to, with the incarnation of its name it is: a name destroyed
     * and branched again, or another instance renamed to it, is a new one (#1063).
     *
     * @param view which listing said so ({@link Incarnations#view})
     */
    public record Caller(InstanceAccounts accounts, String incarnation, long view) {}

    /**
     * Each instance's incarnation as one listing has it.
     *
     * @param byName every instance owning an address, by name, to when it was created
     *               ({@code created_at}, empty if the listing lacked it)
     * @param view   which listing: a later one has a higher view. What tells two views of a name
     *               apart in time, never {@code created_at} -- a rename gives a name an instance
     *               made before the one that had it
     */
    public record Incarnations(Map<String, String> byName, long view) {}

    /**
     * The instance a caller is, judged by its source address <em>and</em> the secret it
     * presents ({@link InstanceSecret#HEADER}); null unless both belong to the same instance.
     * Neither alone passes: a secret from any other address, one from before the instance's
     * last start, or the right address with no secret are all refused. Non-blocking, like
     * {@link #lookup}.
     *
     * <p>A refusal right after a start may come from a snapshot that predates it: a start does
     * not signal the proxy, which costs every start more than this costs its first caller. A
     * refused caller may {@link #refresh} and ask again before treating the secret as wrong --
     * but only when {@link #wantsMissRefresh} allows, as for a lookup miss: refusals are what a
     * guest presenting a wrong secret can make at will, and each refresh lists every instance.
     */
    public InstanceAccounts identify(String sourceAddress, String presentedSecret) {
        return identify(snapshot.parsed(), sourceAddress, presentedSecret);
    }

    private static InstanceAccounts identify(Parsed current, String sourceAddress, String presentedSecret) {
        if (sourceAddress == null || sourceAddress.isBlank()) return null;
        var address = normalize(sourceAddress);
        var instance = current.byAddress().get(address);
        if (instance == null
                || !InstanceSecret.matches(presentedSecret, current.secretByAddress().get(address))) return null;
        return instance;
    }

    /**
     * The name of the instance a caller is, if it may call {@code isx mcp} over the network:
     * {@link #identify} by address and secret, and stamped {@link Metadata#MCP_CALLER}. Null
     * otherwise -- the same refresh-and-retry advice as for {@link #identify} applies.
     */
    public String identifyMcpCaller(String sourceAddress, String presentedSecret) {
        var current = snapshot.parsed();
        var instance = identify(current, sourceAddress, presentedSecret);
        return instance != null && current.mcpCallers().contains(instance.instanceName())
                ? instance.instanceName() : null;
    }

    /**
     * The incarnation of every instance owning an address in the snapshot (those
     * {@link #instances} lists). Non-blocking.
     */
    public Incarnations incarnations() {
        var current = snapshot;
        return new Incarnations(current.parsed().incarnationByName(), current.view());
    }

    /** Whether the snapshot still has {@code instanceName} as an MCP caller. Non-blocking. */
    public boolean isMcpCaller(String instanceName) {
        return snapshot.parsed().mcpCallers().contains(instanceName);
    }

    /** Whether the snapshot is old enough that a refresh is worth doing. */
    public boolean isStale() {
        return System.currentTimeMillis() - snapshot.takenAt() > STALE_AFTER_MS;
    }

    /**
     * Whether an unknown address should trigger a refresh, rate-limited to
     * {@link #MISS_REFRESH_INTERVAL_MS}. Callers that miss on every request -- which is the
     * normal case for host-side traffic -- would otherwise list every instance each time.
     */
    public boolean wantsMissRefresh() {
        return System.currentTimeMillis() - snapshot.takenAt() > MISS_REFRESH_INTERVAL_MS;
    }

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
            snapshot = new Snapshot(parse(incus.listJsonConfig()), System.currentTimeMillis(), VIEWS.incrementAndGet());
            return true;
        } catch (RuntimeException e) {
            // Keep serving the previous snapshot: a transient Incus hiccup should not
            // strip every instance of its pinned account and silently fall back to
            // defaults. Bump the timestamp so a broken socket is not retried per request.
            snapshot = new Snapshot(snapshot.parsed(), System.currentTimeMillis(), snapshot.view());
            ProxyLog.warn("Instance registry refresh failed: " + e.getMessage());
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    /** Every instance's pinned accounts, keyed by instance name, in one request. */
    public static Map<String, Map<String, String>> accountsByInstance(IncusClient incus) {
        var byInstance = new LinkedHashMap<String, Map<String, String>>();
        try {
            var root = JSON.readTree(incus.listJsonConfig());
            if (!root.isArray()) return byInstance;
            for (var instance : root) {
                var name = instance.path("name").asText("");
                var config = instance.path("config");
                if (name.isEmpty() || !config.isObject()) continue;
                var accounts = accountsOf(config);
                if (!accounts.isEmpty()) byInstance.put(name, accounts);
            }
        } catch (Exception e) {
            throw new dev.incusspawn.incus.IncusException(
                    "Could not read instance account pinning: " + e.getMessage(), e);
        }
        return byInstance;
    }

    /**
     * Every branched instance's account state, keyed by name, in one request -- unpinned ones
     * included, which {@link #accountsByInstance} leaves out: they are the ones a change of the
     * global default moves.
     *
     * <p>Branches only. A template makes no proxied requests of its own, and its pins travel to
     * every branch through the CoW copy: listing it here would let "keep them on the old
     * account" pin every future branch of it, and report it as refused when it never asks.
     * Failed builds are debris to inspect, not instances to re-point.
     */
    public static Map<String, AccountState> accountStates(IncusClient incus) {
        var states = new LinkedHashMap<String, AccountState>();
        try {
            var root = JSON.readTree(incus.listJsonConfig());
            if (!root.isArray()) return states;
            for (var instance : root) {
                var name = instance.path("name").asText("");
                var config = instance.path("config");
                if (name.isEmpty() || !config.isObject()
                        || !Metadata.TYPE_CLONE.equals(config.path(Metadata.TYPE).asText(""))) continue;
                states.put(name, new AccountState(accountsOf(config), identitiesOf(config),
                        "Running".equalsIgnoreCase(instance.path("status").asText(""))));
            }
        } catch (Exception e) {
            throw new dev.incusspawn.incus.IncusException(
                    "Could not read instance account pinning: " + e.getMessage(), e);
        }
        return states;
    }

    /**
     * Build the address map from the JSON of {@code /1.0/instances?recursion=1}.
     * Package-private and static so it can be tested without a running Incus.
     */
    static Parsed parse(String instancesJson) {
        var claimants = new LinkedHashMap<String, List<Claimant>>();
        try {
            var root = JSON.readTree(instancesJson);
            if (!root.isArray()) return new Parsed(Map.of(), Map.of(), java.util.Set.of(), Map.of());
            for (var instance : root) {
                var name = instance.path("name").asText("");
                var config = instance.path("config");
                if (name.isEmpty() || !config.isObject()) continue;

                var address = config.path(Metadata.STATIC_IP).asText("").strip();
                if (address.isEmpty()) continue;

                claimants.computeIfAbsent(normalize(address), a -> new ArrayList<>()).add(new Claimant(
                        new InstanceAccounts(name, accountsOf(config), identitiesOf(config)),
                        "Running".equalsIgnoreCase(instance.path("status").asText("")),
                        config.path(Metadata.INSTANCE_SECRET_SHA256).asText("").strip(),
                        Metadata.isMcpCallerGrant(config.path(Metadata.MCP_CALLER).asText(null)),
                        instance.path("created_at").asText("").strip()));
            }
        } catch (Exception e) {
            ProxyLog.warn("Could not parse instance list for the account registry: " + e.getMessage());
        }
        var byAddress = new LinkedHashMap<String, InstanceAccounts>();
        var secretByAddress = new LinkedHashMap<String, String>();
        var mcpCallers = new java.util.HashSet<String>();
        var incarnationByName = new java.util.HashMap<String, String>();
        claimants.forEach((address, all) -> {
            var owner = owner(address, all);
            if (owner == null) return;
            byAddress.put(address, owner.accounts());
            if (!owner.secretSha256().isEmpty()) secretByAddress.put(address, owner.secretSha256());
            if (owner.mcpCaller()) mcpCallers.add(owner.accounts().instanceName());
            incarnationByName.put(owner.accounts().instanceName(), owner.createdAt());
        });
        return new Parsed(byAddress, secretByAddress, java.util.Set.copyOf(mcpCallers),
                Map.copyOf(incarnationByName));
    }

    private record Claimant(InstanceAccounts accounts, boolean running, String secretSha256, boolean mcpCaller,
                            String createdAt) {}

    /**
     * Who an address belongs to. isx gives each address to one instance, but a copy made by an
     * older isx, or by {@code incus copy}, carries its source's (#815). Incus refuses to start
     * an instance whose NIC address another NIC holds, so of several claimants only a running
     * one can be sending traffic from it; never whichever the listing happens to put last.
     */
    private static Claimant owner(String address, List<Claimant> claimants) {
        if (claimants.size() == 1) return claimants.getFirst();
        var running = claimants.stream().filter(Claimant::running).toList();
        var names = claimants.stream().map(c -> c.accounts().instanceName()).toList();
        if (running.size() == 1) {
            ProxyLog.warn("Address " + address + " is claimed by " + names + "; serving "
                    + running.getFirst().accounts().instanceName() + ", the one running");
            return running.getFirst();
        }
        // Stopped claimants send nothing. Several running is not something isx creates: map
        // the address to none of them rather than guess, and say so
        if (!running.isEmpty()) {
            ProxyLog.warn("Address " + address + " is claimed by running instances " + names
                    + "; serving it the defaults until only one holds it");
        }
        return null;
    }

    private static Map<String, String> identitiesOf(JsonNode config) {
        var identities = new LinkedHashMap<String, String>();
        config.properties().forEach(entry -> {
            var key = entry.getKey();
            if (!key.startsWith(Metadata.ACCOUNT_IDENTITY_PREFIX)) return;
            var value = entry.getValue().asText("").strip();
            if (!value.isEmpty()) {
                identities.put(key.substring(Metadata.ACCOUNT_IDENTITY_PREFIX.length()), value);
            }
        });
        return identities;
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
