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
 * {@code isx mcp} process; over a future HTTP transport it would be a token.
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
    private static final int MAX_PURPOSE = 200;
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    /** An instance this session holds (or is creating). */
    record Owned(String name, String template, boolean supportsDelegate, String purpose, Instant created,
                 boolean ready, boolean kept) {
        Owned asReady() { return new Owned(name, template, supportsDelegate, purpose, created, true, kept); }
        Owned asKept() { return new Owned(name, template, supportsDelegate, purpose, created, ready, true); }
    }

    final SessionId id;
    final String owner;
    final long clientPid;
    final String cwd;
    private volatile String client = "";

    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;
    private final Predicate<SessionId> alive;
    private final Map<String, Owned> owned = new LinkedHashMap<>();

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

    void clientName(String name) {
        client = name == null ? "" : name;
    }

    /** The config every instance this session creates is stamped with, by the copy itself. */
    Map<String, String> stamps(String purpose) {
        var stamps = holderStamps();
        stamps.values().removeIf(java.util.Objects::isNull);
        stamps.put(Metadata.MCP_OWNER, owner);
        if (purpose != null && !purpose.isBlank()) stamps.put(Metadata.MCP_PURPOSE, purpose.strip());
        return stamps;
    }

    /** What says this session holds an instance: written at creation, and again by adoption. */
    private Map<String, String> holderStamps() {
        // A HashMap: a null value removes the key, which adoption needs for MCP_ORPHANED.
        var stamps = new HashMap<String, String>();
        stamps.put(Metadata.MCP_SESSION, id.toString());
        stamps.put(Metadata.MCP_CLIENT, client.isEmpty() ? null : client);
        stamps.put(Metadata.MCP_CLIENT_PID, clientPid > 0 ? String.valueOf(clientPid) : null);
        if (cwd != null) stamps.put(Metadata.MCP_CWD, cwd);
        stamps.put(Metadata.MCP_ORPHANED, null);
        return stamps;
    }

    /** Refuse a purpose that would not read as one line in a listing. */
    static String checkPurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) return null;
        if (purpose.length() > MAX_PURPOSE || purpose.chars().anyMatch(Character::isISOControl)) {
            throw new ToolError("purpose must be one line of at most " + MAX_PURPOSE + " characters");
        }
        return purpose.strip();
    }

    /**
     * Reserve a name for a new instance of {@code template}, counting it against the cap before
     * it exists: two concurrent creates must not both pass a cap of one. The cap is per host
     * user, so the instances other sessions hold and the orphans awaiting adoption count too.
     * Registered before the copy, so a session that ends mid-create still releases it.
     */
    String reserve(InstanceBackend.TemplateInfo template, String hint, String purpose) {
        if (hint != null && !NAME_HINT.matcher(hint).matches()) {
            throw new ToolError("name_hint must be 1-16 characters of a-z, 0-9 and '-', "
                    + "starting with a letter or digit");
        }
        purpose = checkPurpose(purpose);
        // Only what was ready before the listing can be missing from it for being deleted.
        Set<String> readyBefore;
        synchronized (this) {
            readyBefore = owned.values().stream().filter(Owned::ready).map(Owned::name).collect(Collectors.toSet());
        }
        // Read before the lock: it is a round trip to Incus.
        var listing = backend.mcpInstances();
        var elsewhere = others(listing).keySet();
        synchronized (this) {
            var max = config.get().maxInstances();
            // A held instance deleted behind the session's back (from the TUI, say) is let go.
            owned.keySet().removeIf(n -> readyBefore.contains(n) && !listing.containsKey(n));
            var mine = owned.values().stream().filter(o -> !o.kept()).count();
            var others = elsewhere.stream().filter(n -> !owned.containsKey(n)).count();
            if (mine + others >= max) {
                throw new ToolError("you already have " + (mine + others) + " instance(s) ("
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
                throw new ToolError("instance name '" + name + "' would exceed 63 characters; use a shorter name_hint");
            }
            owned.put(name, new Owned(name, template.name(), template.supportsDelegate(), purpose,
                    Instant.now(), false, false));
            return name;
        }
    }

    synchronized void created(String name) {
        owned.computeIfPresent(name, (k, o) -> o.asReady());
    }

    /** Forget a reservation whose create failed and was cleaned up. */
    synchronized void abandon(String name) {
        owned.remove(name);
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
            throw new ToolError("'" + name + "' is one of your instances, but another session holds it. "
                    + "Take it with adopt_instance first.");
        }
        throw new ToolError("'" + name + "' is not an instance this session holds. "
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
        if (!lookup(name).ready()) throw new ToolError("'" + name + "' is still being created.");
        var metadata = backend.metadata(name);
        if (metadata == null) {
            abandon(name);
            throw new ToolError("'" + name + "' no longer exists.");
        }
        if (!ours(metadata)) {
            abandon(name);
            throw new ToolError("'" + name + "' is no longer held by this session: another session adopted it.");
        }
        var busy = Metadata.pendingOp(metadata);
        if (!busy.isEmpty()) throw new ToolError("'" + name + "' is busy (" + busy + "); try again shortly.");
        return metadata;
    }

    /**
     * {@link #requireOwned}, for a tool that runs something in the instance: refused, saying how
     * to start it, while it is stopped.
     */
    Map<String, String> requireRunning(String name) {
        var metadata = requireOwned(name);
        if (!InstanceBackend.running(metadata)) {
            throw new ToolError("'" + name + "' is " + metadata.get(InstanceBackend.STATUS).toLowerCase(java.util.Locale.ROOT)
                    + ". Start it with start_instance first.");
        }
        return metadata;
    }

    /**
     * Take over one of this host user's instances from the session that held it. Refused for an
     * instance kept for the user, for one another user's session made, and -- unless
     * {@code force} -- for one whose session is still running, which would lose it mid-work.
     * Returns what was adopted, with the config read for the check.
     */
    Owned adopt(String name, boolean force) {
        Owned held;
        synchronized (this) {
            held = owned.get(name);
        }
        // Mid-create the copy already carries our stamp: adopting would mark it ready early.
        if (held != null && !held.ready()) throw new ToolError("'" + name + "' is still being created.");
        if (held != null) {
            // Outside the lock: a round trip to Incus must not hold up every other call.
            requireOwned(name);
            return held;
        }
        var metadata = backend.metadata(name);
        if (metadata == null) throw new ToolError("'" + name + "' does not exist.");
        var session = metadata.get(Metadata.MCP_SESSION);
        if (session == null || !owner.equals(metadata.get(Metadata.MCP_OWNER))) {
            throw new ToolError("'" + name + "' was not created through isx mcp by this user; "
                    + "only those instances can be adopted.");
        }
        if (metadata.containsKey(Metadata.MCP_KEPT)) {
            throw new ToolError("'" + name + "' was kept: it belongs to the user now, not to agents.");
        }
        var busy = Metadata.pendingOp(metadata);
        if (!busy.isEmpty()) throw new ToolError("'" + name + "' is busy (" + busy + "); try again shortly.");
        var holder = SessionId.parse(session);
        if (!force && holder.isPresent() && !holder.get().equals(id) && alive.test(holder.get())) {
            throw new ToolError("'" + name + "' is held by a session that is still running (isx mcp pid "
                    + holder.get().pid() + describeClient(metadata) + "). Adopting it would take it away "
                    + "mid-work; set force: true only if that session is stuck.");
        }
        var template = templateOf(metadata);
        if (!config.get().templates().contains(template)) {
            throw new ToolError("'" + name + "' comes from template '" + template + "', which is no longer "
                    + "approved for agents. " + TemplatePolicy.HOW_TO_APPROVE);
        }
        backend.stamp(name, holderStamps());
        // Two sessions adopting at once both write; only the last writer holds it.
        var after = backend.metadata(name);
        if (after == null || !ours(after)) throw new ToolError("another session adopted '" + name + "' first.");
        after = settled(name, after);
        var delegate = backend.template(template).map(InstanceBackend.TemplateInfo::supportsDelegate).orElse(false);
        var adopted = new Owned(name, template, delegate, after.get(Metadata.MCP_PURPOSE),
                createdOf(after), true, false);
        synchronized (this) {
            owned.put(name, adopted);
        }
        return adopted;
    }

    /** How long {@link #adopt} waits for an operation it finds under way to end. */
    static final Duration ADOPT_SETTLE = Duration.ofSeconds(30);
    /** How often it looks meanwhile; shorter in tests. */
    volatile Duration settleStep = Duration.ofMillis(500);

    /**
     * {@code after}, the config read back after this session stamped an adoption, once no
     * operation is under way on it. Read after the stamp, a mark means an orphan sweep may have
     * read the holder before the stamp and be deleting the instance, or saw the stamp and is
     * taking its mark back -- or another isx operation (a stop) is running. Only the outcome
     * tells: waits for the mark to go, then answers from what is left. Refusing at once would
     * leave the instance stamped as held by this session without it holding it.
     */
    private Map<String, String> settled(String name, Map<String, String> after) {
        var deadline = System.nanoTime() + ADOPT_SETTLE.toNanos();
        while (!Metadata.pendingOp(after).isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(settleStep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolError("interrupted while '" + name + "' was busy; adopt_instance again.");
            }
            after = backend.metadata(name);
            if (after == null) {
                throw new ToolError("'" + name + "' was removed as an orphan past its grace period "
                        + "before the adoption could take it.");
            }
            if (!ours(after)) throw new ToolError("another session adopted '" + name + "' first.");
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

    /**
     * Whether this session still holds {@code name}, by its stamp in Incus; forgets it if not.
     * An instance still being created counts as held. When Incus cannot be asked, it stays held:
     * this is for letting go of what is certainly gone, never for guessing.
     */
    boolean stillHolds(String name) {
        synchronized (this) {
            var entry = owned.get(name);
            if (entry == null) return false;
            if (!entry.ready()) return true;
        }
        Map<String, String> metadata;
        try {
            metadata = backend.metadata(name);
        } catch (RuntimeException e) {
            return true;
        }
        if (metadata != null && ours(metadata)) return true;
        abandon(name);
        return false;
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
        if (!lookup(name).ready()) throw new ToolError("'" + name + "' is still being created; destroy it once it is.");
        var metadata = backend.metadata(name);
        if (metadata != null && !ours(metadata)) {
            abandon(name);
            throw new ToolError("'" + name + "' is no longer held by this session: another session adopted it.");
        }
        if (metadata != null) {
            backend.destroy(name);
            backend.refreshProxy();
        }
        abandon(name);
        return metadata != null;
    }

    /**
     * Release every instance this session holds and did not keep, stamping when it became an
     * orphan: the grace period before any session may destroy it starts now. Called when the
     * session ends; runs the writes in parallel so it fits in the time a client gives a server
     * to exit. An instance still being created is left unstamped: the next session notices it
     * is orphaned and starts its grace period then.
     */
    List<String> release() {
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
        synchronized (this) {
            released.forEach(owned::remove);
        }
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
