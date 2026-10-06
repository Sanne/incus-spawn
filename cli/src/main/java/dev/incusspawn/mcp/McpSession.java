package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One MCP session: the instances it holds. Transport-agnostic -- over stdio the session is the
 * {@code isx mcp} process; over the network it is the isx instance that calls (#915), which
 * holds what it made across every restart of its client or of itself ({@link #resume}).
 *
 * <p>An instance belongs to the host user who created it, and is held by one session at a time.
 * A session holds what it created, and what it adopted from a session that ended: a coordinating
 * agent restarts (its context fills, it crashes, the machine reboots) while its workers wait in
 * their instances, and must be able to pick them up again. Ending a session never destroys
 * anything; it releases what the session held, and a later session destroys an orphan only once
 * {@code mcp.orphan-grace-hours} have passed and nobody is working in it ({@link Orphans}).
 *
 * <p>Holding is checked twice for every instance a tool names: the name must be in this
 * session's registry, and the instance Incus has under that name must carry this session's
 * stamp. The second check is what refuses a user's instance recreated under a name this session
 * once used, and an instance another session has since adopted.
 */
final class McpSession {

    private static final Pattern NAME_HINT = Pattern.compile("[a-z0-9][a-z0-9-]{0,15}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final int MAX_PURPOSE = 200;
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    /** An instance this session holds (or is creating); {@code key} is its idempotency key, null for none. */
    record Owned(String name, String template, boolean supportsDelegate, String purpose, Instant created,
                 boolean ready, boolean kept, String key) {
        Owned asReady() { return new Owned(name, template, supportsDelegate, purpose, created, true, kept, key); }
        Owned asKept() { return new Owned(name, template, supportsDelegate, purpose, created, ready, true, key); }
    }

    /** One listing of this user's instances, with the names this session had ready before it was read. */
    record Listing(Set<String> readyBefore, Map<String, Map<String, String>> instances) {}

    final SessionId id;
    final String owner;
    final long clientPid;
    final String cwd;
    private volatile String client = "";

    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;
    private final Predicate<SessionId> alive;
    private final Map<String, Owned> owned = new LinkedHashMap<>();
    /** Why each instance this session held was let go of: what {@link #hold} says once it is. */
    private final Map<String, Hold> letGo = new java.util.HashMap<>();

    McpSession(SessionId id, String owner, long clientPid, String cwd,
               InstanceBackend backend, Supplier<McpConfig> config, Predicate<SessionId> alive) {
        this.id = id;
        this.owner = owner;
        this.clientPid = clientPid;
        this.cwd = cwd;
        this.backend = backend;
        this.config = config;
        this.alive = alive;
    }

    /** The {@code mcp:} config as it is now: it is re-read on every call. */
    McpConfig config() {
        return config.get();
    }

    /** This user's instances other sessions hold, or that are orphaned, from one listing. */
    Map<String, Orphans.Other> others() {
        return others(backend.mcpInstances());
    }

    private Map<String, Orphans.Other> others(Map<String, Map<String, String>> listing) {
        var others = Orphans.othersOf(listing, owner, id, alive);
        synchronized (this) {
            others.keySet().removeIf(owned::containsKey);
        }
        return others;
    }

    /**
     * This user's running instances whose tasks this session does not count as its own: those
     * other sessions hold, and the orphans, from one listing. A kept instance counts while a live
     * session still holds it, which can go on running tasks there; once that session ends it is
     * the user's, as for {@code mcp.max-instances}.
     */
    List<String> taskInstancesElsewhere() {
        var result = new ArrayList<String>();
        backend.mcpInstances().forEach((name, config) -> {
            if (!owner.equals(config.get(Metadata.MCP_OWNER)) || !InstanceBackend.running(config)) return;
            var holder = SessionId.parse(config.get(Metadata.MCP_SESSION));
            if (holder.isPresent() && holder.get().equals(id) && !Orphans.releasedByHolder(config)) return;
            if (config.containsKey(Metadata.MCP_KEPT) && (holder.isEmpty() || !alive.test(holder.get()))) return;
            result.add(name);
        });
        synchronized (this) {
            result.removeIf(owned::containsKey);
        }
        return result;
    }

    void clientName(String name) {
        client = name == null ? "" : name;
    }

    /**
     * The config every instance this session creates is stamped with, by the copy itself, with
     * its idempotency key {@code key} (null for none).
     */
    Map<String, String> stamps(String purpose, String key) {
        var stamps = holderStamps();
        stamps.values().removeIf(java.util.Objects::isNull);
        stamps.put(Metadata.MCP_OWNER, owner);
        if (purpose != null && !purpose.isBlank()) stamps.put(Metadata.MCP_PURPOSE, purpose.strip());
        if (key != null) stamps.put(Metadata.MCP_IDEMPOTENCY_KEY, key);
        return stamps;
    }

    /** What says this session holds an instance: written at creation, and again by adoption. */
    private Map<String, String> holderStamps() {
        // A HashMap: a null value removes the key, which adoption needs for the orphan sweep's stamps.
        var stamps = new HashMap<String, String>();
        stamps.put(Metadata.MCP_SESSION, id.toString());
        stamps.put(Metadata.MCP_CLIENT, client.isEmpty() ? null : client);
        stamps.put(Metadata.MCP_CLIENT_PID, clientPid > 0 ? String.valueOf(clientPid) : null);
        if (cwd != null) stamps.put(Metadata.MCP_CWD, cwd);
        stamps.put(Metadata.MCP_ORPHANED, null);
        // Not MCP_DORMANT: it says the instance still needs starting, until wakeIfDormant has done it.
        stamps.put(Metadata.MCP_CPU_SAMPLE, null);
        return stamps;
    }

    /** Refuse an idempotency key that is not 1-64 of {@code A-Za-z0-9._:-}; null for none. */
    static String checkKey(String key) {
        if (key != null && !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "idempotency_key must be 1-64 characters of A-Z, a-z, 0-9, "
                    + "'.', '_', ':' and '-'");
        }
        return key;
    }

    /**
     * {@code value} if it is an idempotency key, else null. For what was read back: from an
     * instance's config, or from a task's directory in the guest, which anyone in it can write.
     */
    static String keyOf(String value) {
        return value != null && IDEMPOTENCY_KEY.matcher(value).matches() ? value : null;
    }

    /** Refuse a purpose that would not read as one line in a listing. */
    static String checkPurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) return null;
        if (purpose.length() > MAX_PURPOSE || purpose.chars().anyMatch(Character::isISOControl)) {
            throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "purpose must be one line of at most " + MAX_PURPOSE + " characters");
        }
        return purpose.strip();
    }

    /**
     * Reserve a name for a new instance of {@code template}, counting it against the cap before
     * it exists: two concurrent creates must not both pass a cap of one. The cap is per host
     * user, so the instances other sessions hold and the orphans awaiting adoption count too.
     * Registered before the copy, so a session that ends mid-create still releases it.
     *
     * <p>Under idempotency key {@code key} (null for none), against {@code listing}: the one a
     * keyed create read to look for the key, or null to read one. A key this session is creating
     * an instance under already is refused as busy: the create in flight is the one being repeated.
     */
    String reserve(InstanceBackend.TemplateInfo template, String hint, String purpose, String key, Listing listing) {
        if (hint != null && !NAME_HINT.matcher(hint).matches()) {
            throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "name_hint must be 1-16 characters of a-z, 0-9 and '-', "
                    + "starting with a letter or digit");
        }
        purpose = checkPurpose(purpose);
        if (listing == null) listing = listing();
        var instances = listing.instances();
        var elsewhere = others(instances).keySet();
        synchronized (this) {
            var max = config.get().maxInstances();
            // A held instance deleted behind the session's back (from the TUI, say) is let go.
            listing.readyBefore().stream().filter(n -> owned.containsKey(n) && !instances.containsKey(n))
                    .forEach(n -> abandon(n, Hold.GONE));
            // Not in the listing the caller looked in: a create with the same key began since.
            if (key != null && owned.values().stream().anyMatch(o -> key.equals(o.key()))) throw stillUnderWay(key);
            var mine = owned.values().stream().filter(o -> !o.kept()).count();
            var others = elsewhere.stream().filter(n -> !owned.containsKey(n)).count();
            if (mine + others >= max) {
                throw new ToolError(ToolError.Code.LIMIT, "you already have " + (mine + others) + " instance(s) ("
                        + mine + " in this session, " + others + " held by other sessions or orphaned), "
                        + "the most mcp.max-instances allows (" + max + "). Destroy one with "
                        + "destroy_instance first; list_instances shows them all.");
            }
            var base = template.name().startsWith("tpl-") ? template.name().substring(4) : template.name();
            String name;
            do {
                name = "mcp-" + base + (hint != null ? "-" + hint : "") + "-" + randomSuffix(5);
            } while (owned.containsKey(name));
            if (name.length() > 63) {
                throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "instance name '" + name + "' would exceed 63 characters; use a shorter name_hint");
            }
            owned.put(name, new Owned(name, template.name(), template.supportsDelegate(), purpose,
                    Instant.now(), false, false, key));
            letGo.remove(name);
            return name;
        }
    }

    /** This user's instances, from one read. */
    Listing listing() {
        // Only what was ready before the listing can be missing from it for being deleted.
        Set<String> readyBefore;
        synchronized (this) {
            readyBefore = owned.values().stream().filter(Owned::ready).map(Owned::name).collect(Collectors.toSet());
        }
        // Read outside the lock: it is a round trip to Incus.
        return new Listing(readyBefore, backend.mcpInstances());
    }

    /**
     * Which of two instances made under one idempotency key counts as made first: the earlier
     * Incus record ({@link InstanceBackend#CREATED_AT}), then the name. Every choice between
     * them uses it, so that of two creates racing under one key exactly one gives way. Each
     * looks again once its copy is listed, and Incus lists a copy, stamps and all, from the
     * moment its record is made, at the time it records: if only one of the two sees the other,
     * it was made after the other looked, so later, and it gives way. Never
     * {@code user.incus-spawn.created}: a copy carries its source's until the branch writes its
     * own, and that is taken before the write.
     */
    static final java.util.Comparator<Map.Entry<String, Map<String, String>>> FIRST_MADE =
            java.util.Comparator.<Map.Entry<String, Map<String, String>>, Instant>comparing(e -> createdAt(e.getValue()))
                    .thenComparing(Map.Entry::getKey);

    private static Instant createdAt(Map<String, String> config) {
        try {
            return java.time.OffsetDateTime.parse(config.getOrDefault(InstanceBackend.CREATED_AT, "")).toInstant();
        } catch (RuntimeException e) {
            // Never displaces one whose time is known; between two unknown, the name decides.
            return Instant.MAX;
        }
    }

    /**
     * The instance this user made under idempotency key {@code key}, from {@code listing}: its
     * name and config, or null. A race between sessions can leave two, until the later one
     * gives way: the one {@link #FIRST_MADE} is the answer. A copy a session that has since
     * died never finished (it has no address, which the branch gives it after the copy) is
     * not an instance a create promised, and never counts; the orphan sweep removes it.
     */
    Map.Entry<String, Map<String, String>> keyed(Listing listing, String key) {
        return listing.instances().entrySet().stream()
                .filter(e -> owner.equals(e.getValue().get(Metadata.MCP_OWNER))
                        && key.equals(keyOf(e.getValue().get(Metadata.MCP_IDEMPOTENCY_KEY))))
                .filter(e -> !e.getValue().getOrDefault(Metadata.STATIC_IP, "").isEmpty()
                        || SessionId.parse(e.getValue().get(Metadata.MCP_SESSION))
                                .filter(h -> h.equals(id) || alive.test(h)).isPresent())
                .min(FIRST_MADE).orElse(null);
    }

    /**
     * Whether {@code name}, which a create under idempotency key {@code key} made ({@code listed}:
     * its config from the listing it was found in), can be returned for a call repeating that
     * create, before anything about it is compared with the call: returns whether it must be
     * adopted first ({@link #adopt}, with all its checks), false if this session holds it.
     * Refused while the first create is still under way -- this session's ({@code busy}) or a
     * live session's ({@code not_held}) -- as a copy carries its source's config until it is
     * configured, so what it was made from cannot be told yet; and once it was kept (it is the
     * user's). An orphan, or one its holder released, is adopted.
     */
    boolean replayable(String name, Map<String, String> listed, String key) {
        synchronized (this) {
            var held = owned.get(name);
            if (held != null && !held.ready()) throw stillUnderWay(key);
        }
        if (listed.containsKey(Metadata.MCP_KEPT)) {
            throw new ToolError(ToolError.Code.REFUSED, "idempotency_key '" + key + "' made '" + name + "', which was kept: it "
                    + "belongs to the user now, not to agents. Look it up with list_instances instead of repeating the call.");
        }
        var holder = SessionId.parse(listed.get(Metadata.MCP_SESSION));
        if (holder.isPresent() && holder.get().equals(id) && holds(name)) {
            var busy = Metadata.pendingOp(listed);
            if (!busy.isEmpty()) throw new ToolError(ToolError.Code.BUSY, "'" + name + "' is busy (" + busy + "); try again shortly.");
            return false;
        }
        if (holder.isPresent() && !holder.get().equals(id) && alive.test(holder.get()) && !Orphans.releasedByHolder(listed)) {
            throw new ToolError(ToolError.Code.NOT_HELD, "idempotency_key '" + key + "' made '" + name + "', which a session "
                    + "that is still running holds (isx mcp pid " + holder.get().pid() + describeClient(listed)
                    + "), and may still be making. Take it with adopt_instance, with force only if that session is stuck.");
        }
        return true;
    }

    /** A call under {@code key} while one with the same key is still under way in this session. */
    static ToolError stillUnderWay(String key) {
        return new ToolError(ToolError.Code.BUSY, "a call with idempotency_key '" + key + "' is still under way in this "
                + "session; call again once it has returned, which gives its result.");
    }

    synchronized void created(String name) {
        owned.computeIfPresent(name, (k, o) -> o.asReady());
    }

    /**
     * Let go of {@code name}, remembering why: what {@link #hold} answers for it from then on.
     * Every way out of the registry comes through here, so the reason is never a guess.
     */
    synchronized void abandon(String name, Hold why) {
        owned.remove(name);
        letGo.put(name, why);
    }

    /**
     * Why this session no longer holds {@code name}, as it learned when it let go -- no request:
     * {@code GONE} (destroyed, or Incus said so) or {@code RELEASED} (another session's stamp,
     * or never held). {@code HELD} while it does.
     */
    synchronized Hold whyNotHeld(String name) {
        return owned.containsKey(name) ? Hold.HELD : letGo.getOrDefault(name, Hold.RELEASED);
    }

    synchronized List<Owned> instances() {
        return List.copyOf(owned.values());
    }

    synchronized boolean holds(String name) {
        return owned.containsKey(name);
    }

    private Owned lookup(String name) {
        synchronized (this) {
            var entry = owned.get(name);
            if (entry != null) return entry;
        }
        var metadata = backend.metadata(name);
        if (metadata != null && owner.equals(metadata.get(Metadata.MCP_OWNER))
                && metadata.containsKey(Metadata.MCP_SESSION)) {
            throw new ToolError(ToolError.Code.NOT_HELD, "'" + name + "' is one of your instances, but another session holds it. "
                    + "Take it with adopt_instance first.");
        }
        throw new ToolError(ToolError.Code.NOT_FOUND, "'" + name + "' is not an instance this session holds. "
                + "Use list_instances to see yours, or create_instance to make one.");
    }

    /** Whether the instance Incus has under a held name still carries this session's stamp. */
    private boolean ours(Map<String, String> metadata) {
        return id.toString().equals(metadata.get(Metadata.MCP_SESSION));
    }

    /**
     * Check that this session holds {@code name}, in the registry and by the stamp in Incus, and
     * return the instance's {@code user.incus-spawn.*} config read for that check.
     */
    Map<String, String> requireOwned(String name) {
        if (!lookup(name).ready()) throw new ToolError(ToolError.Code.WRONG_STATE, "'" + name + "' is still being created.");
        var metadata = backend.metadata(name);
        if (metadata == null) {
            abandon(name, Hold.GONE);
            throw new ToolError(ToolError.Code.NOT_FOUND, "'" + name + "' no longer exists.");
        }
        if (!ours(metadata)) {
            abandon(name, Hold.RELEASED);
            throw new ToolError(ToolError.Code.NOT_HELD, "'" + name + "' is no longer held by this session: another session adopted it.");
        }
        var busy = Metadata.pendingOp(metadata);
        if (!busy.isEmpty()) throw new ToolError(ToolError.Code.BUSY, "'" + name + "' is busy (" + busy + "); try again shortly.");
        return metadata;
    }

    /**
     * {@link #requireOwned}, for a tool that runs something in the instance: refused, saying how
     * to start it, while it is stopped.
     */
    Map<String, String> requireRunning(String name) {
        var metadata = requireOwned(name);
        if (InstanceBackend.stopped(metadata)) {
            throw new ToolError(ToolError.Code.WRONG_STATE, "'" + name + "' is stopped. Start it with start_instance first.");
        }
        if (!InstanceBackend.running(metadata)) {
            throw new ToolError(ToolError.Code.WRONG_STATE, "'" + name + "' is " + metadata.get(InstanceBackend.STATUS).toLowerCase(java.util.Locale.ROOT)
                    + ", which isx mcp cannot change. Ask the user to look at it: isx shell " + name);
        }
        return metadata;
    }

    /**
     * Take over one of this host user's instances from the session that held it. Refused for an
     * instance kept for the user, for one another user's session made, and -- unless
     * {@code force} -- for one whose session is still running, which would lose it mid-work.
     * Returns what was adopted, with the config read for the check. One the orphan sweep
     * stopped as dormant still needs starting: {@link #wakeIfDormant} (#1028). One the sweep is
     * still stopping when the wait for its mark runs out is held, but refused {@code busy}:
     * reported adopted, it would be stopped under its new holder.
     */
    Owned adopt(String name, boolean force) {
        Owned held;
        synchronized (this) {
            held = owned.get(name);
        }
        // Mid-create the copy already carries our stamp: adopting would mark it ready early.
        if (held != null && !held.ready()) throw new ToolError(ToolError.Code.WRONG_STATE, "'" + name + "' is still being created.");
        if (held != null) {
            // Outside the lock: a round trip to Incus must not hold up every other call.
            requireOwned(name);
            return held;
        }
        var metadata = backend.metadata(name);
        if (metadata == null) throw new ToolError(ToolError.Code.NOT_FOUND, "'" + name + "' does not exist.");
        var session = metadata.get(Metadata.MCP_SESSION);
        if (session == null || !owner.equals(metadata.get(Metadata.MCP_OWNER))) {
            throw new ToolError(ToolError.Code.REFUSED, "'" + name + "' was not created through isx mcp by this user; "
                    + "only those instances can be adopted.");
        }
        if (metadata.containsKey(Metadata.MCP_KEPT)) {
            throw new ToolError(ToolError.Code.REFUSED, "'" + name + "' was kept: it belongs to the user now, not to agents.");
        }
        var busy = Metadata.pendingOp(metadata);
        if (!busy.isEmpty()) throw new ToolError(ToolError.Code.BUSY, "'" + name + "' is busy (" + busy + "); try again shortly.");
        var holder = SessionId.parse(session);
        if (!force && holder.isPresent() && !holder.get().equals(id) && alive.test(holder.get())
                && !Orphans.releasedByHolder(metadata)) {
            throw new ToolError(ToolError.Code.NOT_HELD, "'" + name + "' is held by a session that is still running ("
                    + holder.get().describe() + describeClient(metadata) + "). Adopting it would take it away "
                    + "mid-work; set force: true only if that session is stuck.");
        }
        var template = templateOf(metadata);
        if (!config.get().templates().contains(template)) {
            throw new ToolError(ToolError.Code.NOT_APPROVED, "'" + name + "' comes from template '" + template + "', which is no longer "
                    + "approved for agents. " + TemplatePolicy.HOW_TO_APPROVE);
        }
        backend.stamp(name, holderStamps());
        // Two sessions adopting at once both write; only the last writer holds it.
        var after = backend.metadata(name);
        if (after == null || !ours(after)) throw new ToolError(ToolError.Code.NOT_HELD, "another session adopted '" + name + "' first.");
        after = settled(name, after);
        var adopted = register(name, template, supportsDelegate(template), after);
        if (Metadata.OP_STOPPING.equals(Metadata.pendingOp(after))) throw stillStopping(name);
        return adopted;
    }

    private static ToolError stillStopping(String name) {
        return new ToolError(ToolError.Code.BUSY, "'" + name + "' is yours now, but it is still being stopped (the "
                + "orphan sweep stops one in which nothing moved); call adopt_instance again shortly, which starts it.");
    }

    /**
     * Start {@code name}, which this session holds, if the orphan sweep stopped it as dormant --
     * any {@link Metadata#MCP_DORMANT} stamp, including one a sweep wrote for the previous holder
     * just after this session's adoption -- and clear the stamp. Adoption leaves the stamp for
     * this, so a retry after a start that failed still starts it. Returns whether it started it.
     */
    boolean wakeIfDormant(String name, java.util.function.Consumer<String> progress) {
        var metadata = backend.metadata(name);
        if (metadata == null || !metadata.containsKey(Metadata.MCP_DORMANT)) return false;
        if (Metadata.OP_STOPPING.equals(Metadata.pendingOp(metadata))) throw stillStopping(name);
        var stopped = InstanceBackend.stopped(metadata);
        if (stopped) {
            progress.accept("Starting " + name + ", stopped while nothing in it moved");
            try {
                backend.start(name);
            } catch (RuntimeException e) {
                throw new ToolError(ToolError.Code.UNAVAILABLE, "'" + name + "' is yours now, but it was stopped "
                        + "because nothing in it moved, and could not be started again: " + e.getMessage()
                        + ". Call adopt_instance again, or start_instance.");
            }
        }
        // A HashMap: a null value removes the key.
        var unset = new HashMap<String, String>();
        unset.put(Metadata.MCP_DORMANT, null);
        backend.stamp(name, unset);
        return stopped;
    }

    private boolean supportsDelegate(String template) {
        return backend.template(template).map(InstanceBackend.TemplateInfo::supportsDelegate).orElse(false);
    }

    /** Hold {@code name}, ready, as {@code metadata} describes it: the way back into the registry. */
    private Owned register(String name, String template, boolean delegate, Map<String, String> metadata) {
        var held = new Owned(name, template, delegate, metadata.get(Metadata.MCP_PURPOSE),
                createdOf(metadata), true, false, keyOf(metadata.get(Metadata.MCP_IDEMPOTENCY_KEY)));
        synchronized (this) {
            owned.put(name, held);
            letGo.remove(name);
        }
        return held;
    }

    /** How long {@link #adopt} waits for an operation it finds under way to end. */
    static final Duration ADOPT_SETTLE = Duration.ofSeconds(30);
    /** How often it looks meanwhile; shorter in tests. */
    volatile Duration settleStep = Duration.ofMillis(500);
    /** {@link #ADOPT_SETTLE}; shorter in tests. */
    volatile Duration settleLimit = ADOPT_SETTLE;

    /**
     * {@code after}, the config read back after this session stamped an adoption, once no
     * operation is under way on it. Read after the stamp, a mark means an orphan sweep may have
     * read the holder before the stamp and be deleting the instance, or saw the stamp and is
     * taking its mark back -- or another isx operation (a stop) is running. Only the outcome
     * tells: waits for the mark to go, then answers from what is left. Refusing at once would
     * leave the instance stamped as held by this session without it holding it.
     */
    private Map<String, String> settled(String name, Map<String, String> after) {
        var deadline = System.nanoTime() + settleLimit.toNanos();
        while (!Metadata.pendingOp(after).isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(settleStep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolError(ToolError.Code.UNAVAILABLE, "interrupted while '" + name + "' was busy; adopt_instance again.");
            }
            after = backend.metadata(name);
            if (after == null) {
                throw new ToolError(ToolError.Code.NOT_FOUND, "'" + name + "' was removed as an orphan past its grace period "
                        + "before the adoption could take it.");
            }
            if (!ours(after)) throw new ToolError(ToolError.Code.NOT_HELD, "another session adopted '" + name + "' first.");
        }
        // Still marked after the wait: it is ours by its stamp, so held like any busy instance.
        return after;
    }

    /**
     * The template an instance descends from: {@link Metadata#PROFILE}, the leaf template every
     * copy carries, as {@code InstancePrep} and {@code BranchFlow} read it. {@link Metadata#PARENT}
     * names the instance it was copied from, which for a fork is another instance.
     */
    static String templateOf(Map<String, String> metadata) {
        return java.util.Objects.requireNonNullElse(Metadata.templateOf(metadata), "");
    }

    private static String describeClient(Map<String, String> metadata) {
        var client = metadata.getOrDefault(Metadata.MCP_CLIENT, "");
        var dir = metadata.getOrDefault(Metadata.MCP_CWD, "");
        return (client.isEmpty() ? "" : ", client " + client) + (dir.isEmpty() ? "" : ", started in " + dir);
    }

    private static Instant createdOf(Map<String, String> metadata) {
        try {
            return java.time.LocalDateTime.parse(metadata.getOrDefault(Metadata.CREATED, ""))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    /** Whether this session holds an instance, and if not, whether the instance is gone. */
    enum Hold { HELD, GONE, RELEASED }

    /**
     * Whether this session still holds {@code name}, by its stamp in Incus; forgets it if not,
     * saying why from the same read: {@code GONE} when Incus has no such instance,
     * {@code RELEASED} when another session's stamp is on it or this one never held it. An
     * instance still being created counts as held, and so does one Incus cannot be asked about:
     * this is for letting go of what is certainly gone, never for guessing.
     */
    Hold hold(String name) {
        synchronized (this) {
            var entry = owned.get(name);
            // Let go of already, by a tool call that knew why (a destroy is not an adoption).
            if (entry == null) return whyNotHeld(name);
            if (!entry.ready()) return Hold.HELD;
        }
        Map<String, String> metadata;
        try {
            metadata = backend.metadata(name);
        } catch (RuntimeException e) {
            return Hold.HELD;
        }
        if (metadata != null && ours(metadata)) return Hold.HELD;
        var why = metadata == null ? Hold.GONE : Hold.RELEASED;
        abandon(name, why);
        return why;
    }

    /** Hand an instance to the user: never adopted, counted or reaped again. */
    void keep(String name) {
        requireOwned(name);
        backend.stamp(name, Map.of(Metadata.MCP_KEPT, Metadata.now()));
        synchronized (this) {
            owned.computeIfPresent(name, (k, o) -> o.asKept());
        }
    }

    /** Destroy a held instance. Idempotent for instances already gone. */
    boolean destroy(String name) {
        // Mid-create the copy exists but is not stamped yet: it would read as someone else's.
        if (!lookup(name).ready()) throw new ToolError(ToolError.Code.WRONG_STATE, "'" + name + "' is still being created; destroy it once it is.");
        var metadata = backend.metadata(name);
        if (metadata != null && !ours(metadata)) {
            abandon(name, Hold.RELEASED);
            throw new ToolError(ToolError.Code.NOT_HELD, "'" + name + "' is no longer held by this session: another session adopted it.");
        }
        if (metadata != null) {
            backend.destroy(name);
            backend.refreshProxy();
        }
        abandon(name, Hold.GONE);
        return metadata != null;
    }

    /**
     * Take back what an instance session held before this connection: the instances stamped
     * with its id, not kept and not released. From one listing, and before the first tool call,
     * so the cap counts them from the start. Returns their names and what the listing said of
     * each, for the caller to adopt their tasks.
     *
     * <p>Only what {@link #adopt} would take: one whose template is no longer approved is
     * released instead, as a process session's would be when it ends, so withdrawing approval
     * reaches a coordinator too -- its orphan clock starts, and no session can adopt it while
     * the template stays unapproved.
     *
     * <p>Only for an instance session: a process session's id is new with every process, so
     * nothing can carry it yet.
     */
    Map<String, Map<String, String>> resume() {
        if (!id.isInstance()) return Map.of();
        var resumed = new LinkedHashMap<String, Map<String, String>>();
        var approved = config.get().templates();
        // Workers mostly share a template; each lookup loads definitions and asks Incus.
        var delegates = new HashMap<String, Boolean>();
        backend.mcpInstances().forEach((name, config) -> {
            if (!owner.equals(config.get(Metadata.MCP_OWNER)) || !ours(config)
                    || config.containsKey(Metadata.MCP_KEPT) || Orphans.releasedByHolder(config)) return;
            var template = templateOf(config);
            if (!approved.contains(template)) {
                backend.stamp(name, Map.of(Metadata.MCP_ORPHANED, Orphans.orphanedStamp(Instant.now(), id.toString())));
                System.err.println("isx mcp: released " + name + ": template '" + template
                        + "' is no longer approved for agents");
                return;
            }
            register(name, template, delegates.computeIfAbsent(template, this::supportsDelegate), config);
            resumed.put(name, config);
        });
        return resumed;
    }

    /**
     * Release every instance this session holds and did not keep, stamping when it became an
     * orphan: the grace period before any session may destroy it starts now. Called when the
     * session ends; runs the writes in parallel so it fits in the time a client gives a server
     * to exit. An instance still being created is left unstamped: the next session notices it
     * is orphaned and starts its grace period then.
     */
    List<String> release() {
        // An instance session outlives its connection: the instance still holds what it made.
        if (id.isInstance()) return List.of();
        List<Owned> targets;
        synchronized (this) {
            targets = owned.values().stream().filter(o -> o.ready() && !o.kept()).toList();
        }
        var released = new ArrayList<String>();
        var now = Orphans.orphanedStamp(Instant.now(), id.toString());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = targets.stream().map(o -> pool.submit(() -> {
                try {
                    var metadata = backend.metadata(o.name());
                    // Never touch what another session has adopted meanwhile.
                    if (metadata == null || !ours(metadata)) return null;
                    backend.stamp(o.name(), Map.of(Metadata.MCP_ORPHANED, now));
                    return o.name();
                } catch (RuntimeException e) {
                    System.err.println("isx mcp: could not release " + o.name() + ": " + e.getMessage());
                    return null;
                }
            })).toList();
            for (var f : futures) {
                try {
                    var name = f.get();
                    if (name != null) released.add(name);
                } catch (Exception ignored) {
                    // reported above
                }
            }
        }
        released.forEach(name -> abandon(name, Hold.RELEASED));
        return released;
    }

    /** Random lowercase letters and digits: uniqueness, not secrecy. */
    static String randomSuffix(int length) {
        // No Random in a static field, which the native image would initialize at build time.
        var random = ThreadLocalRandom.current();
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
