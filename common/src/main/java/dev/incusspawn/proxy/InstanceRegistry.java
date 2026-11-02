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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

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
 * <p>On macOS the premise fails: the VM NATs every container to its own address, which is no
 * instance, so there the proxy has {@link #prove(String, Map, boolean)} identify such a caller by
 * its credential proof alone (#1582), and {@link #identify(String, String, boolean)} by its
 * per-start secret alone (#1583).
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

    /** How long a listing may run before {@link #listingUnavailable} says it hung: a caller's refresh wait. */
    public static final long LISTING_HUNG_AFTER_MS = 5_000L;

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
     * @param addressByProof  per namespace, each verifier hash to the address of the running
     *                        instance it is stamped on, for {@link #prove} when the address names no instance
     *                        (#1582); a hash several instances share maps by {@link #owner}'s rule
     * @param addressBySecret the same for the hash of each running address owner's secret, for
     *                        {@link #identify} when the address names no instance (#1583)
     * @param running         the names of the address owners that are running (#1586)
     * @param chatGptByAddress the ChatGPT session binding of each address's instance (#1332),
     *                        for those that have one; kept apart for the same reason as the secret
     */
    record Parsed(Map<String, InstanceAccounts> byAddress, Map<String, String> secretByAddress,
                  java.util.Set<String> mcpCallers, Map<String, String> incarnationByName,
                  Map<String, Lineage> lineageByName, Map<String, Map<String, String>> proofsByAddress,
                  Map<String, ProofState> proofStates, Map<String, Map<String, String>> addressByProof,
                  Map<String, String> addressBySecret, java.util.Set<String> running,
                  Map<String, ChatGptBinding> chatGptByAddress) {
        static final Parsed EMPTY = new Parsed(Map.of(), Map.of(), java.util.Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), java.util.Set.of(), Map.of());
    }

    /** Host-only acknowledgement of persisted proof state; contains no usable credential. */
    public record ProofState(String generation, String verifierHash) {}

    public ProofState proofState(String name) {
        var current = snapshot;
        return clock.getAsLong() - current.takenAt() > STALE_AFTER_MS
                ? null : current.parsed().proofStates().get(name);
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

    /**
     * An instance that proved who it is ({@link #identify}), with what it was built from and
     * whether it may call {@code isx mcp}, all from the listing that identified it.
     */
    public record Identified(Caller caller, Lineage lineage, boolean mcpCaller) {
        public String instanceName() { return caller.accounts().instanceName(); }
    }

    /**
     * What an instance's proofs for a ChatGPT session were derived for, and the verifiers of
     * those proofs ({@code ChatGptProof}), as its metadata records them.
     */
    public record ChatGptBinding(String instanceName, String account, String sessionId,
                                 String accessSha256, String refreshSha256) {

        /**
         * The binding {@code config} records, or null when it records none, only part of one, or
         * one made with a secret that is no longer the instance's: inherited by a copy, or from
         * before a start that did not renew it.
         */
        static ChatGptBinding of(String instanceName, JqValue config) {
            var secret = config.pathText(Metadata.INSTANCE_SECRET_SHA256, "").strip();
            if (secret.isEmpty() || !secret.equals(config.pathText(Metadata.CHATGPT_PROOF_SECRET_SHA256, "").strip())) {
                return null;
            }
            var session = config.pathText(Metadata.CHATGPT_SESSION, "").strip();
            var access = config.pathText(Metadata.CHATGPT_ACCESS_PROOF_SHA256, "").strip();
            var refresh = config.pathText(Metadata.CHATGPT_REFRESH_PROOF_SHA256, "").strip();
            var colon = session.indexOf(':');
            if (colon <= 0 || access.isEmpty() || refresh.isEmpty()) return null;
            var account = session.substring(0, colon);
            var sessionId = session.substring(colon + 1);
            if (!ChatGptProof.isValidName(account) || !ChatGptProof.isValidName(sessionId)) return null;
            return new ChatGptBinding(instanceName, account, sessionId, access, refresh);
        }
    }

    /**
     * The ChatGPT session binding of the instance at this address, or null when the address is
     * no known instance or its instance has none. Non-blocking.
     */
    public ChatGptBinding chatGptBinding(String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank()) return null;
        return snapshot.parsed().chatGptByAddress().get(normalize(sourceAddress));
    }

    /**
     * @param view from {@link #VIEWS}, so a later snapshot always has a higher one, even from a
     *             registry that replaced another in the same process
     * @param listingFailed the listing this was made from failed, so it holds no proofs (#1586)
     */
    private record Snapshot(Parsed parsed, long takenAt, long view, boolean listingFailed) {}

    private static final Snapshot EMPTY = new Snapshot(Parsed.EMPTY, 0L, 0L, false);

    private static final AtomicLong VIEWS = new AtomicLong();

    private final IncusClient incus;
    private final LongSupplier clock;
    /**
     * When the listing under way started, in epoch millis; 0 while none is, {@link #CLAIMED} while
     * one has been claimed and is reading the clock. One value, so no read sees a listing under way
     * with an earlier one's start (#1596).
     */
    private final AtomicLong listingStartedAt = new AtomicLong();
    private static final long CLAIMED = -1;
    /** Listings started, numbering each one; and the highest-numbered one finished, guarded by {@link #listed}. */
    private final AtomicLong listingsStarted = new AtomicLong();
    private final Object listed = new Object();
    private long listedThrough;
    private volatile Snapshot snapshot = EMPTY;

    public InstanceRegistry(IncusClient incus) {
        this(incus, System::currentTimeMillis);
    }

    /**
     * {@code clock}: epoch millis, for the snapshot's age (staleness, the miss-refresh rate) and how
     * long a listing has run.
     */
    InstanceRegistry(IncusClient incus, LongSupplier clock) {
        this.incus = incus;
        this.clock = clock;
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
        return instance == null ? null : callerOf(current, instance);
    }

    /** {@code instance} as {@code current} has it, with its incarnation and view from the same listing. */
    private static Caller callerOf(Snapshot current, InstanceAccounts instance) {
        return new Caller(instance, current.parsed().incarnationByName().getOrDefault(instance.instanceName(), ""),
                current.view());
    }

    /** Address and every requested namespace proof checked against one immutable listing. */
    public Caller prove(String address, Map<String, java.util.List<String>> digests) {
        return prove(address, digests, false);
    }

    /**
     * As {@link #prove(String, Map)}; with {@code byProofAlone}, a caller whose address is no
     * known instance is the instance its proofs name, when they all name the same one (#1582).
     *
     * <p>For where the address identifies nothing: on macOS every container reaches the proxy
     * from the VM's address. A proof is keyed by a host secret the guest never sees, so it cannot
     * be forged, but it can be replayed from another instance, which only the address check
     * stops. A caller at a known instance's address is always held to that instance's proofs.
     */
    public Caller prove(String address, Map<String, java.util.List<String>> digests, boolean byProofAlone) {
        var current = snapshot;
        if (digests.isEmpty()) return null;
        var instance = find(current.parsed(), address);
        if (instance == null && byProofAlone) {
            // Every namespace's proof already verified, by finding its hash
            var proven = addressProven(current.parsed(), digests);
            instance = proven == null ? null : current.parsed().byAddress().get(proven);
            return instance == null ? null : callerOf(current, instance);
        }
        if (instance == null) return null;
        var hashes = current.parsed().proofsByAddress().getOrDefault(normalize(address), Map.of());
        for (var entry : digests.entrySet()) {
            var hash = hashes.get(entry.getKey());
            if (entry.getValue().stream().noneMatch(digest -> ProofVerifier.matches(digest, hash))) return null;
        }
        return callerOf(current, instance);
    }

    /**
     * The address of the one instance every namespace's presented digests are stamped on, or
     * null. A lookup by the digest's hash: the hash of a secret value, so its timing tells nothing.
     */
    private static String addressProven(Parsed current, Map<String, java.util.List<String>> digests) {
        String proven = null;
        for (var entry : digests.entrySet()) {
            var byHash = current.addressByProof().getOrDefault(entry.getKey(), Map.of());
            var addresses = entry.getValue().stream().filter(java.util.Objects::nonNull)
                    .map(digest -> byHash.get(dev.incusspawn.tool.ToolDef.sha256hex(digest)))
                    .filter(java.util.Objects::nonNull).distinct().toList();
            if (addresses.size() != 1 || proven != null && !proven.equals(addresses.getFirst())) return null;
            proven = addresses.getFirst();
        }
        return proven;
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
     * @param running the names in {@code byName} that are running (#1586), from the same listing
     */
    public record Incarnations(Map<String, String> byName, long view, java.util.Set<String> running) {}

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
        return identify(sourceAddress, presentedSecret, false);
    }

    /**
     * As {@link #identify(String, String)}; with {@code bySecretAlone}, a caller whose address is
     * no known instance is the running instance whose secret it presents (#1583).
     *
     * <p>For where the address identifies nothing: on macOS every container reaches the proxy
     * from the VM's address. The secret is 256 random bits, rotated on every start and recorded
     * only as its hash, so it cannot be guessed, but it can be replayed from another instance,
     * which only the address check stops. A caller at a known instance's address is always held
     * to that instance's secret.
     */
    public InstanceAccounts identify(String sourceAddress, String presentedSecret, boolean bySecretAlone) {
        return identify(snapshot.parsed(), sourceAddress, presentedSecret, bySecretAlone);
    }

    private static InstanceAccounts identify(Parsed current, String sourceAddress, String presentedSecret,
                                             boolean bySecretAlone) {
        if (sourceAddress == null || sourceAddress.isBlank()) return null;
        var address = normalize(sourceAddress);
        var instance = current.byAddress().get(address);
        if (instance == null && bySecretAlone) {
            // A lookup by the secret's hash, so its timing tells nothing of the secret
            var presented = InstanceSecret.presentedSha256(presentedSecret);
            var proven = presented == null ? null : current.addressBySecret().get(presented);
            return proven == null ? null : current.byAddress().get(proven);
        }
        if (instance == null
                || !InstanceSecret.matches(presentedSecret, current.secretByAddress().get(address))) return null;
        return instance;
    }

    /**
     * The instance a caller is, by {@link #identify}, with its {@link Lineage}: what a host
     * route's {@code templates:} filter is decided on (#936), and with the {@link Metadata#MCP_CALLER}
     * stamp {@code isx mcp} is. Null when {@link #identify} is, with the same refresh-and-retry advice.
     */
    public Identified identifyWithLineage(String sourceAddress, String presentedSecret, boolean bySecretAlone) {
        var current = snapshot;
        var instance = identify(current.parsed(), sourceAddress, presentedSecret, bySecretAlone);
        if (instance == null) return null;
        // Every identified instance owns an address, so has a lineage; fail closed regardless
        var lineage = current.parsed().lineageByName().getOrDefault(instance.instanceName(), Lineage.UNKNOWN);
        return new Identified(callerOf(current, instance), lineage,
                current.parsed().mcpCallers().contains(instance.instanceName()));
    }

    /**
     * The incarnation of every instance owning an address in the snapshot (those
     * {@link #instances} lists). Non-blocking.
     */
    public Incarnations incarnations() {
        var current = snapshot;
        return new Incarnations(current.parsed().incarnationByName(), current.view(), current.parsed().running());
    }

    /** Whether the snapshot still has {@code instanceName} as an MCP caller. Non-blocking. */
    public boolean isMcpCaller(String instanceName) {
        return snapshot.parsed().mcpCallers().contains(instanceName);
    }

    /**
     * Whether the last listing failed, or the one under way has not answered for
     * {@link #LISTING_HUNG_AFTER_MS}: a hung Incus throws nothing. Proofs a new start or renewal
     * brought are refused until one succeeds (#1586). Non-blocking.
     */
    public boolean listingUnavailable() {
        var started = listingStartedAt.get();
        return snapshot.listingFailed() || started > 0 && clock.getAsLong() - started > LISTING_HUNG_AFTER_MS;
    }

    /** Whether the snapshot is old enough that a refresh is worth doing. */
    public boolean isStale() {
        return clock.getAsLong() - snapshot.takenAt() > STALE_AFTER_MS;
    }

    /**
     * Whether an unknown address should trigger a refresh, rate-limited to
     * {@link #MISS_REFRESH_INTERVAL_MS}. Callers that miss on every request -- which is the
     * normal case for host-side traffic -- would otherwise list every instance each time.
     */
    public boolean wantsMissRefresh() {
        return missRefreshDelayMillis() == 0;
    }

    public long missRefreshDelayMillis() {
        return Math.max(0, MISS_REFRESH_INTERVAL_MS + 1 - (clock.getAsLong() - snapshot.takenAt()));
    }

    /**
     * Rebuild the snapshot from Incus. <strong>Blocks</strong> -- call from a worker thread.
     * Single-flight: a concurrent call returns immediately rather than queueing a second
     * listing behind the first.
     *
     * @return true if this call performed the refresh
     */
    public boolean refresh() {
        var number = claim();
        return number != 0 && list(number);
    }

    /**
     * Claim the next listing: its number, or 0 when one is already under way. Numbered before it
     * lists, so a listing whose number a caller wanted lists after that caller read the count.
     * Claimed without {@link #listed} it is unordered against a listing's end, which {@link #list}
     * records under it; {@link #refreshAfterNow} claims under it for that reason.
     */
    private long claim() {
        return listingStartedAt.compareAndSet(0, CLAIMED) ? listingsStarted.incrementAndGet() : 0;
    }

    /** Run the listing claimed as {@code number}, then release the claim. */
    private boolean list(long number) {
        try {
            // Timed after the claim: a time read before it may be long gone when it wins.
            // At least 1: 0 is idle and CLAIMED is below. Inside the try, so finally always releases the claim
            listingStartedAt.set(Math.max(1, clock.getAsLong()));
            snapshot = new Snapshot(parse(incus.listJsonConfig()), clock.getAsLong(), VIEWS.incrementAndGet(), false);
            return true;
        } catch (RuntimeException e) {
            // Retain attribution and account pins, but revoke credential authorization
            // until a listing succeeds. Bump the timestamp so a broken socket is not
            // retried per request.
            var previous = snapshot.parsed();
            var withoutProofs = new Parsed(previous.byAddress(), previous.secretByAddress(), previous.mcpCallers(),
                    previous.incarnationByName(), previous.lineageByName(), Map.of(), Map.of(), Map.of(), Map.of(),
                    previous.running(), Map.of());
            snapshot = new Snapshot(withoutProofs, clock.getAsLong(), snapshot.view(), true);
            ProxyLog.warn("Instance registry refresh failed: " + e.getMessage());
            return false;
        } finally {
            synchronized (listed) {
                listedThrough = Math.max(listedThrough, number);
                listingStartedAt.set(0);
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
        refreshFrom(nextListing(), timeoutMillis);
    }

    /**
     * The number of the next listing to start: any listing numbered this or later started after
     * this call. Non-blocking.
     */
    public long nextListing() {
        return listingsStarted.get() + 1;
    }

    /** Whether a listing numbered {@code wanted} or later has ended, listed or failed. Non-blocking. */
    public boolean listedFrom(long wanted) {
        synchronized (listed) {
            return listedThrough >= wanted;
        }
    }

    /**
     * {@link #refreshAfterNow} for a caller that took {@link #nextListing} earlier: a listing
     * numbered {@code wanted} or later answers it. <strong>Blocks</strong>, up to {@code timeoutMillis}.
     */
    public void refreshFrom(long wanted, long timeoutMillis) {
        // Whoever runs the listing, it answers every caller waiting for it, so a burst of refused
        // callers costs one listing, not one each
        var deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        long number;
        synchronized (listed) {
            while (true) {
                // A listing that failed was tried, and that is the answer too
                if (listedThrough >= wanted) return;
                var left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) return;
                // Claimed under the lock a listing ends under: claimed after it, another waiter's
                // listing could start and end in between, and this one would list a second time for
                // the same burst (#1769). A claim lost here was taken after the check above saw none
                // under way, so that listing is numbered wanted or later, and the loop waits for it
                number = claim();
                if (number != 0) break;
                try {
                    listed.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        list(number);
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

                claimants.computeIfAbsent(normalize(address), a -> new ArrayList<>()).add(new Claimant(normalize(address),
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
        var running = new java.util.HashSet<String>();
        var configByName = configsByName(root);
        var proofStates = new LinkedHashMap<String, ProofState>();
        configByName.forEach((name, config) -> proofStates.put(name, new ProofState(
                config.pathText(Metadata.INSTANCE_PROOF_ID, ""),
                dev.incusspawn.tool.ToolDef.sha256hex(config.pathText(Metadata.INSTANCE_PROOF_SHA256, "")))));
        // Per namespace and verifier hash, the address owners stamped with it; the same for secrets
        var stampedWith = new LinkedHashMap<String, Map<String, List<Claimant>>>();
        var givenSecret = new LinkedHashMap<String, List<Claimant>>();
        var chatGptByAddress = new LinkedHashMap<String, ChatGptBinding>();
        claimants.forEach((address, all) -> {
            var owner = owner("Address " + address, all, "refusing credential injection until only one holds it");
            if (owner == null) return;
            byAddress.put(address, owner.accounts());
            var proofs = ProofVerifier.parse(owner.config().pathText(Metadata.INSTANCE_PROOF_SHA256, ""));
            proofsByAddress.put(address, proofs);
            proofs.forEach((namespace, hash) -> stampedWith.computeIfAbsent(namespace, n -> new LinkedHashMap<>())
                    .computeIfAbsent(hash, h -> new ArrayList<>()).add(owner));
            if (!owner.secretSha256().isEmpty()) {
                secretByAddress.put(address, owner.secretSha256());
                givenSecret.computeIfAbsent(owner.secretSha256(), h -> new ArrayList<>()).add(owner);
            }
            if (owner.mcpCaller()) mcpCallers.add(owner.accounts().instanceName());
            if (owner.running()) running.add(owner.accounts().instanceName());
            incarnationByName.put(owner.accounts().instanceName(), owner.createdAt());
            // Only for owners: a build stamp is parsed for no address nobody serves
            lineageByName.put(owner.accounts().instanceName(), lineageOf(owner.config(), configByName));
            var chatGpt = ChatGptBinding.of(owner.accounts().instanceName(), owner.config());
            if (chatGpt != null) chatGptByAddress.put(address, chatGpt);
        });
        var addressByProof = new LinkedHashMap<String, Map<String, String>>();
        stampedWith.forEach((namespace, byHash) -> addressByProof.put(namespace,
                runningAddressByHash("A " + namespace + " proof verifier", byHash)));
        return new Parsed(byAddress, secretByAddress, java.util.Set.copyOf(mcpCallers),
                Map.copyOf(incarnationByName), Map.copyOf(lineageByName), Map.copyOf(proofsByAddress), Map.copyOf(proofStates),
                Map.copyOf(addressByProof), runningAddressByHash("An instance secret", givenSecret), java.util.Set.copyOf(running),
                Map.copyOf(chatGptByAddress));
    }

    /**
     * Each hash to the address of the running instance it names, for a caller whose address names
     * none. Only an incus copy made outside isx shares a hash: as for an address, the one running.
     * Unlike an address, never a stopped one: it sends nothing, and a listing from before its start
     * costs one refresh, which a miss makes.
     */
    private static Map<String, String> runningAddressByHash(String claimed, Map<String, List<Claimant>> byHash) {
        var addresses = new LinkedHashMap<String, String>();
        byHash.forEach((hash, holders) -> {
            var owner = owner(claimed, holders, "a caller its address does not name is not identified by it");
            if (owner != null && owner.running()) addresses.put(hash, owner.address());
        });
        return Map.copyOf(addresses);
    }

    private record Claimant(String address, InstanceAccounts accounts, boolean running, String secretSha256, boolean mcpCaller,
                            String createdAt, JqValue config) {}

    /** How many ancestors {@link #lineageOf} asks, against a cycle in hand-edited parents. */
    private static final int MAX_ANCESTORS = 32;

    /** Each instance's config in a listing, by name: what {@link #lineageOf} walks. */
    public static Map<String, JqValue> configsByName(JqValue root) {
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
     * The one judgement of lineage: host routes ask it here, and {@code isx mcp} for a coordinator's
     * scope (#1812).
     */
    public static Lineage lineageOf(JqValue config, Map<String, JqValue> configByName) {
        var current = config;
        for (int hop = 0; current != null && hop < MAX_ANCESTORS; hop++) {
            var ancestor = current;
            Function<String, String> read = key -> ancestor.pathText(key, "");
            var reason = Metadata.untrustedReason(read);
            // A template runs only to be built or updated, and an update grants it proofs (#1743):
            // refused as itself, never as an ancestor, so its branches are not refused meanwhile
            if (hop == 0 && reason.isEmpty() && Metadata.TYPE_BASE.equals(read.apply(Metadata.TYPE))) {
                return Lineage.untrusted("a template, which runs only to be built or updated");
            }
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
     * one can be sending traffic from it; never whichever the listing happens to put last. A
     * proof verifier such a copy shares is decided the same way ({@code claimed} names which, and
     * {@code refusal} what having no owner refuses).
     */
    private static Claimant owner(String claimed, List<Claimant> claimants, String refusal) {
        if (claimants.size() == 1) return claimants.getFirst();
        var running = claimants.stream().filter(Claimant::running).toList();
        var names = claimants.stream().map(c -> c.accounts().instanceName()).toList();
        if (running.size() == 1) {
            ProxyLog.warn(claimed + " is claimed by " + names + "; serving "
                    + running.getFirst().accounts().instanceName() + ", the one running");
            return running.getFirst();
        }
        // Stopped claimants send nothing. Several running is not something isx creates: map
        // the address to none of them rather than guess, and say so
        if (!running.isEmpty()) {
            ProxyLog.warn(claimed + " is claimed by running instances " + names + "; " + refusal);
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
