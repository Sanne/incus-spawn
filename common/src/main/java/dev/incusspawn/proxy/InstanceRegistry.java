package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Maps a request's source address to the instance that made it, and to the credential
 * accounts that instance is pinned to.
 *
 * <h3>Why source address identifies an instance</h3>
 * Every branch is given a static IP on the bridge by {@code InstanceLifecycle.configureBranch},
 * and every template build container by
 * {@code InstanceNetwork.assignBuildAddress} for as long as it builds (#903), recorded as
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
 * An address missing from the snapshot never authorizes injection. The request's current
 * namespace proofs are checked against the same listing as its account selection; a refused
 * proof can refresh and retry off the event loop, with a floor between listings.
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
     * @param lineageByName   what each instance owning an address was built from, for the
     *                        host routes' {@code templates:} filter (#936)
     */
    record Parsed(Map<String, InstanceAccounts> byAddress, Map<String, String> secretByAddress,
                  java.util.Set<String> mcpCallers, Map<String, String> incarnationByName,
                  Map<String, Lineage> lineageByName, Map<String, Map<String, String>> proofsByAddress) {
        static final Parsed EMPTY = new Parsed(Map.of(), Map.of(), java.util.Set.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * What an instance was built from.
     *
     * @param template     its leaf template ({@link Metadata#templateOf}), "" when none is recorded
     * @param untrusted    whether no host route may admit it, whatever its template: a
     *                     project-local definition went into it ({@code isx project}, or a
     *                     {@code .incus-spawn/} template anywhere in its chain), or it, or an
     *                     ancestor, is an unfinished or failed build ({@link Metadata#untrustedReason})
     *                     or has a parent named so ({@link Metadata#untrustedByName})
     * @param why          the reason, for the refusal's log line; "" when trusted
     */
    public record Lineage(String template, boolean untrusted, String why) {
        public Lineage(String template, boolean projectLocal) {
            this(template, projectLocal, projectLocal ? "built from a project-local definition" : "");
        }

        /** For an instance whose lineage is not known: admitted by nothing. */
        static final Lineage UNKNOWN = new Lineage("", true, "of no known lineage");
        /** A failed or unfinished build, or a copy of one: admitted by nothing. */
        static Lineage untrusted(String why) {
            return new Lineage("", true, why);
        }
    }

    /** An instance that proved who it is ({@link #identify}), with what it was built from. */
    public record Identified(String instanceName, Lineage lineage) {}

    /**
     * @param view from {@link #VIEWS}, so a later snapshot always has a higher one, even from a
     *             registry that replaced another in the same process
     */
    private record Snapshot(Parsed parsed, long takenAt, long view) {}

    private static final Snapshot EMPTY = new Snapshot(Parsed.EMPTY, 0L, 0L);

    private static final java.util.concurrent.atomic.AtomicLong VIEWS = new java.util.concurrent.atomic.AtomicLong();

    private final IncusClient incus;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    /** Listings started, numbering each one; and the highest-numbered one finished, guarded by {@link #listed}. */
    private final AtomicLong listingsStarted = new AtomicLong();
    private final Object listed = new Object();
    private long listedThrough;
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

    /** Address and every requested namespace proof checked against one immutable listing. */
    public Caller prove(String address, Map<String, java.util.List<String>> digests) {
        var current = snapshot;
        var instance = find(current.parsed(), address);
        if (instance == null || digests.isEmpty()) return null;
        var hashes = current.parsed().proofsByAddress().getOrDefault(normalize(address), Map.of());
        for (var entry : digests.entrySet()) {
            var hash = hashes.get(entry.getKey());
            if (entry.getValue().stream().noneMatch(digest -> ProofVerifier.matches(digest, hash))) return null;
        }
        return new Caller(instance, current.parsed().incarnationByName().getOrDefault(instance.instanceName(), ""), current.view());
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
     * The instance a caller is, by {@link #identify}, with its {@link Lineage}: what a host
     * route's {@code templates:} filter is decided on (#936). Null when {@link #identify} is,
     * with the same refresh-and-retry advice.
     */
    public Identified identifyWithLineage(String sourceAddress, String presentedSecret) {
        var current = snapshot.parsed();
        var instance = identify(current, sourceAddress, presentedSecret);
        if (instance == null) return null;
        // Every identified instance owns an address, so has a lineage; fail closed regardless
        var lineage = current.lineageByName().getOrDefault(instance.instanceName(), Lineage.UNKNOWN);
        return new Identified(instance.instanceName(), lineage);
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
        var number = listingsStarted.incrementAndGet();
        try {
            snapshot = new Snapshot(parse(incus.listJsonConfig()), System.currentTimeMillis(), VIEWS.incrementAndGet());
            return true;
        } catch (RuntimeException e) {
            // Retain attribution and account pins, but revoke credential authorization
            // until a listing succeeds. Bump the timestamp so a broken socket is not
            // retried per request.
            var previous = snapshot.parsed();
            var withoutProofs = new Parsed(previous.byAddress(), previous.secretByAddress(), previous.mcpCallers(),
                    previous.incarnationByName(), previous.lineageByName(), Map.of());
            snapshot = new Snapshot(withoutProofs, System.currentTimeMillis(), snapshot.view());
            ProxyLog.warn("Instance registry refresh failed: " + e.getMessage());
            return false;
        } finally {
            synchronized (listed) {
                listedThrough = Math.max(listedThrough, number);
                refreshing.set(false);
                listed.notifyAll();
            }
        }
    }

    /**
     * {@link #refresh} from a listing that starts after this call does. <strong>Blocks</strong>,
     * up to {@code timeoutMillis}. A refresh already under way may have listed before what the
     * caller wants to see (a start that rotated a secret), so it is waited out, never taken as
     * the answer: that is what {@link #refresh}'s single flight would otherwise return.
     */
    public void refreshAfterNow(long timeoutMillis) {
        // Any listing numbered this or later started after this call: whoever runs it, it answers
        // every caller waiting for it, so a burst of refused callers costs one listing, not one each
        var wanted = listingsStarted.get() + 1;
        var deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (true) {
            synchronized (listed) {
                while (listedThrough < wanted && refreshing.get()) {
                    var left = (deadline - System.nanoTime()) / 1_000_000;
                    if (left <= 0) return;
                    try {
                        listed.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                // A listing that failed was tried, and that is the answer too
                if (listedThrough >= wanted || System.nanoTime() >= deadline) return;
            }
            // Nothing under way: list now. Losing the race to another caller's listing is fine,
            // since it started after this call and the loop waits for it
            refresh();
        }
    }

    /** Every instance's pinned accounts, keyed by instance name, in one request. */
    public static Map<String, Map<String, String>> accountsByInstance(IncusClient incus) {
        var byInstance = new LinkedHashMap<String, Map<String, String>>();
        try {
            var root = incus.listJsonConfig();
            if (!root.isArray()) return byInstance;
            for (var instance : root.asList()) {
                var name = instance.pathText("name", "");
                var config = instance.getField("config");
                if (name.isEmpty() || !(config instanceof JqObject)) continue;
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
            var root = incus.listJsonConfig();
            if (!root.isArray()) return states;
            for (var instance : root.asList()) {
                var name = instance.pathText("name", "");
                var config = instance.getField("config");
                if (name.isEmpty() || !(config instanceof JqObject)
                        || !Metadata.TYPE_CLONE.equals(config.pathText(Metadata.TYPE, ""))) continue;
                states.put(name, new AccountState(accountsOf(config), identitiesOf(config),
                        "Running".equalsIgnoreCase(instance.pathText("status", ""))));
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
            return parse(JqValues.parseStrict(instancesJson));
        } catch (Exception e) {
            ProxyLog.warn("Could not parse instance list for the account registry: " + Jjq.brief(e));
            return Parsed.EMPTY;
        }
    }

    static Parsed parse(io.hyperfoil.tools.jjq.value.JqValue root) {
        var claimants = new LinkedHashMap<String, List<Claimant>>();
        try {
            if (!root.isArray()) return Parsed.EMPTY;
            for (var instance : root.asList()) {
                var name = instance.pathText("name", "");
                var config = instance.getField("config");
                if (name.isEmpty() || !(config instanceof JqObject)) continue;

                var address = config.pathText(Metadata.STATIC_IP, "").strip();
                if (address.isEmpty()) continue;

                claimants.computeIfAbsent(normalize(address), a -> new ArrayList<>()).add(new Claimant(
                        new InstanceAccounts(name, accountsOf(config), identitiesOf(config)),
                        "Running".equalsIgnoreCase(instance.pathText("status", "")),
                        config.pathText(Metadata.INSTANCE_SECRET_SHA256, "").strip(),
                        Metadata.isMcpCallerGrant(config.pathText(Metadata.MCP_CALLER, null)),
                        instance.pathText("created_at", "").strip(),
                        config));
            }
        } catch (Exception e) {
            ProxyLog.warn("Could not parse instance list for the account registry: " + Jjq.brief(e));
        }
        var byAddress = new LinkedHashMap<String, InstanceAccounts>();
        var secretByAddress = new LinkedHashMap<String, String>();
        var proofsByAddress = new LinkedHashMap<String, Map<String, String>>();
        var mcpCallers = new java.util.HashSet<String>();
        var incarnationByName = new java.util.HashMap<String, String>();
        var lineageByName = new java.util.HashMap<String, Lineage>();
        var configByName = configsByName(root);
        claimants.forEach((address, all) -> {
            var owner = owner(address, all);
            if (owner == null) return;
            byAddress.put(address, owner.accounts());
            proofsByAddress.put(address, ProofVerifier.parse(owner.config().pathText(Metadata.INSTANCE_PROOF_SHA256, "")));
            if (!owner.secretSha256().isEmpty()) secretByAddress.put(address, owner.secretSha256());
            if (owner.mcpCaller()) mcpCallers.add(owner.accounts().instanceName());
            incarnationByName.put(owner.accounts().instanceName(), owner.createdAt());
            // Only for owners: a build stamp is parsed for no address nobody serves
            lineageByName.put(owner.accounts().instanceName(), lineageOf(owner.config(), configByName));
        });
        return new Parsed(byAddress, secretByAddress, java.util.Set.copyOf(mcpCallers),
                Map.copyOf(incarnationByName), Map.copyOf(lineageByName), Map.copyOf(proofsByAddress));
    }

    private record Claimant(InstanceAccounts accounts, boolean running, String secretSha256, boolean mcpCaller,
                            String createdAt, JqValue config) {}

    /** How many ancestors {@link #lineageOf} asks, against a cycle in hand-edited parents. */
    private static final int MAX_ANCESTORS = 32;

    private static Map<String, JqValue> configsByName(JqValue root) {
        var byName = new java.util.HashMap<String, JqValue>();
        if (!root.isArray()) return byName;
        for (var instance : root.asList()) {
            var name = instance.pathText("name", "");
            var config = instance.getField("config");
            if (!name.isEmpty() && config instanceof JqObject) byName.put(name, config);
        }
        return byName;
    }

    /**
     * What an instance was built from. Untrusted when it, or any ancestor this listing still has,
     * is untrusted by {@link Metadata#untrustedReason} or {@link Metadata#untrustedByName}: a failed
     * or unfinished build carries its parent's template stamps, and so does everything branched
     * from it, which before #936 got no stamp of its own; walking the {@code parent} names catches a
     * renamed failed build, and a branch of a branch, as long as the instances between still exist.
     */
    private static Lineage lineageOf(JqValue config, Map<String, JqValue> configByName) {
        var current = config;
        for (int hop = 0; current != null && hop < MAX_ANCESTORS; hop++) {
            var ancestor = current;
            Function<String, String> read = key -> ancestor.pathText(key, "");
            var reason = Metadata.untrustedReason(read);
            if (!reason.isEmpty()) {
                return Lineage.untrusted((hop == 0 ? "untrusted (" : "descended from an untrusted instance (")
                        + reason + "): an unfinished, failed or project build, or a copy of one");
            }
            var byName = Metadata.untrustedByName(read);
            if (!byName.isEmpty()) {
                return Lineage.untrusted((hop == 0 ? "" : "descended from an instance ") + byName
                        + ", as a branch of a failed build made before isx stamped them is (rename that instance"
                        + " if it is no failed build)");
            }
            var parent = current.pathText(Metadata.PARENT, "");
            current = parent.isEmpty() ? null : configByName.get(parent);
        }
        var template = java.util.Objects.requireNonNullElse(Metadata.templateOf(Map.of(
                Metadata.PROFILE, config.pathText(Metadata.PROFILE, ""),
                Metadata.PARENT, config.pathText(Metadata.PARENT, ""))), "");
        var projectLocal = Metadata.TYPE_PROJECT.equals(config.pathText(Metadata.TYPE, ""))
                || !config.pathText(Metadata.PROJECT, "").isBlank()
                || dev.incusspawn.config.BuildSource.usedProjectLocal(config.pathText(Metadata.BUILD_SOURCE, ""));
        return new Lineage(template, projectLocal);
    }

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
                    + "; refusing credential injection until only one holds it");
        }
        return null;
    }

    private static Map<String, String> identitiesOf(JqValue config) {
        var identities = new LinkedHashMap<String, String>();
        if (!(config instanceof JqObject object)) return identities;
        object.forEach((key, value) -> {
            if (!key.startsWith(Metadata.ACCOUNT_IDENTITY_PREFIX)) return;
            var text = value.asDisplayText("").strip();
            if (!text.isEmpty()) {
                identities.put(key.substring(Metadata.ACCOUNT_IDENTITY_PREFIX.length()), text);
            }
        });
        return identities;
    }

    private static Map<String, String> accountsOf(JqValue config) {
        var accounts = new LinkedHashMap<String, String>();
        if (!(config instanceof JqObject object)) return accounts;
        object.forEach((key, value) -> {
            if (!key.startsWith(Metadata.ACCOUNT_PREFIX)) return;
            var text = value.asDisplayText("").strip();
            if (!text.isEmpty()) {
                accounts.put(key.substring(Metadata.ACCOUNT_PREFIX.length()), text);
            }
        });
        return accounts;
    }

    /**
     * Vert.x reports an IPv4 peer over a dual-stack listener as {@code ::ffff:10.0.0.5};
     * Incus records the plain form. Without this every such lookup misses and the instance
     * cannot prove its credential grant.
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
