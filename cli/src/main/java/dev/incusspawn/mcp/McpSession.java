package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.Metadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One MCP session: the instances it created and may use. Transport-agnostic -- over stdio the
 * session is the {@code isx mcp} process; over a future HTTP transport it would be a token.
 *
 * <p>Ownership is checked twice for every instance a tool names: the name must be one this
 * session created (the in-memory registry), and the instance Incus has under that name must
 * carry this session's stamp. The second check is what refuses a user's instance that happens to
 * have been recreated under a name this session once used.
 */
final class McpSession {

    private static final Pattern NAME_HINT = Pattern.compile("[a-z0-9][a-z0-9-]{0,15}");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    /** An instance this session created (or is creating). */
    record Owned(String name, String template, boolean supportsDelegate, Instant created,
                 boolean ready, boolean kept) {
        Owned asReady() { return new Owned(name, template, supportsDelegate, created, true, kept); }
        Owned asKept() { return new Owned(name, template, supportsDelegate, created, ready, true); }
    }

    final SessionId id;
    final String owner;
    final long clientPid;
    final String cwd;
    private volatile String client = "";

    private final InstanceBackend backend;
    private final Supplier<McpConfig> config;
    private final Map<String, Owned> owned = new LinkedHashMap<>();

    McpSession(SessionId id, String owner, long clientPid, String cwd,
               InstanceBackend backend, Supplier<McpConfig> config) {
        this.id = id;
        this.owner = owner;
        this.clientPid = clientPid;
        this.cwd = cwd;
        this.backend = backend;
        this.config = config;
    }

    void clientName(String name) {
        client = name == null ? "" : name;
    }

    /** The config every instance this session creates is stamped with, by the copy itself. */
    Map<String, String> stamps() {
        var stamps = new LinkedHashMap<String, String>();
        stamps.put(Metadata.MCP_SESSION, id.toString());
        stamps.put(Metadata.MCP_OWNER, owner);
        if (!client.isEmpty()) stamps.put(Metadata.MCP_CLIENT, client);
        if (clientPid > 0) stamps.put(Metadata.MCP_CLIENT_PID, String.valueOf(clientPid));
        if (cwd != null) stamps.put(Metadata.MCP_CWD, cwd);
        return stamps;
    }

    /**
     * Reserve a name for a new instance of {@code template}, counting it against the cap before
     * it exists: two concurrent creates must not both pass a cap of one. Registered before the
     * copy, so a session that ends mid-create still reaps it.
     */
    synchronized String reserve(InstanceBackend.TemplateInfo template, String hint) {
        var max = config.get().maxInstances();
        var active = owned.values().stream().filter(o -> !o.kept()).count();
        if (active >= max) {
            throw new ToolError("this session already has " + active + " instance(s), the most "
                    + "mcp.max-instances allows (" + max + "). Destroy one with destroy_instance first.");
        }
        if (hint != null && !NAME_HINT.matcher(hint).matches()) {
            throw new ToolError("name_hint must be 1-16 characters of a-z, 0-9 and '-', "
                    + "starting with a letter or digit");
        }
        var base = template.name().startsWith("tpl-") ? template.name().substring(4) : template.name();
        String name;
        do {
            name = "mcp-" + base + (hint != null ? "-" + hint : "") + "-" + randomSuffix();
        } while (owned.containsKey(name));
        if (name.length() > 63) {
            throw new ToolError("instance name '" + name + "' would exceed 63 characters; use a shorter name_hint");
        }
        owned.put(name, new Owned(name, template.name(), template.supportsDelegate(), Instant.now(), false, false));
        return name;
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

    private Owned lookup(String name) {
        Owned entry;
        synchronized (this) {
            entry = owned.get(name);
        }
        if (entry == null) {
            throw new ToolError("'" + name + "' is not an instance this session created. "
                    + "Use list_instances to see yours, or create_instance to make one.");
        }
        return entry;
    }

    /** Whether the instance Incus has under an owned name still carries this session's stamp. */
    private boolean ours(Map<String, String> metadata) {
        return id.toString().equals(metadata.get(Metadata.MCP_SESSION));
    }

    /**
     * Check that this session owns {@code name}, in the registry and by the stamp in Incus, and
     * return the instance's {@code user.incus-spawn.*} config read for that check.
     */
    Map<String, String> requireOwned(String name) {
        if (!lookup(name).ready()) throw new ToolError("'" + name + "' is still being created.");
        var metadata = backend.metadata(name);
        if (metadata == null) {
            abandon(name);
            throw new ToolError("'" + name + "' no longer exists.");
        }
        if (!ours(metadata)) throw new ToolError("'" + name + "' is not owned by this session any more.");
        if (!metadata.getOrDefault(Metadata.PENDING_OP, "").isEmpty()) {
            throw new ToolError("'" + name + "' is busy (" + metadata.get(Metadata.PENDING_OP) + ").");
        }
        return metadata;
    }

    /** Hand an instance to the user: it outlives this session and is never reaped. */
    void keep(String name) {
        requireOwned(name);
        backend.stamp(name, Metadata.MCP_KEPT, Metadata.now());
        synchronized (this) {
            owned.computeIfPresent(name, (k, o) -> o.asKept());
        }
    }

    /** Destroy an owned instance. Idempotent for instances already gone. */
    boolean destroy(String name) {
        lookup(name);
        var metadata = backend.metadata(name);
        if (metadata != null && !ours(metadata)) {
            throw new ToolError("'" + name + "' is not owned by this session any more.");
        }
        if (metadata != null) {
            backend.destroy(name);
            backend.refreshProxy();
        }
        abandon(name);
        return metadata != null;
    }

    /**
     * Destroy every instance this session owns and did not keep. Called when the session ends;
     * runs the deletions in parallel so it fits in the time a client gives a server to exit.
     */
    List<String> reap() {
        List<Owned> targets;
        synchronized (this) {
            targets = owned.values().stream().filter(o -> !o.kept()).toList();
        }
        var reaped = new ArrayList<String>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = targets.stream().map(o -> pool.submit(() -> {
                try {
                    var metadata = backend.metadata(o.name());
                    // Never delete what is not ours, even at exit.
                    if (metadata == null || !ours(metadata) || metadata.containsKey(Metadata.MCP_KEPT)) {
                        return null;
                    }
                    backend.destroy(o.name());
                    return o.name();
                } catch (RuntimeException e) {
                    System.err.println("isx mcp: could not reap " + o.name() + ": " + e.getMessage());
                    return null;
                }
            })).toList();
            for (var f : futures) {
                try {
                    var name = f.get();
                    if (name != null) reaped.add(name);
                } catch (Exception ignored) {
                    // reported above
                }
            }
        }
        if (!reaped.isEmpty()) backend.refreshProxy();
        synchronized (this) {
            reaped.forEach(owned::remove);
        }
        return reaped;
    }

    private static String randomSuffix() {
        // Uniqueness, not secrecy; and no Random in a static field, which the native image
        // would initialize at build time.
        var random = ThreadLocalRandom.current();
        var sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
