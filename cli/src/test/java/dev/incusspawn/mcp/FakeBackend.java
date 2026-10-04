package dev.incusspawn.mcp;

import dev.incusspawn.incus.Metadata;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory {@link InstanceBackend}: instances are just config maps. */
class FakeBackend implements InstanceBackend {

    final Map<String, Map<String, String>> instances = new ConcurrentHashMap<>();
    final List<TemplateInfo> templates = new ArrayList<>();
    final List<String> destroyed = new CopyOnWriteArrayList<>();
    final List<String> scripts = new CopyOnWriteArrayList<>();
    /** What each exec in {@link #scripts} got on stdin ("" for none), in the same order. */
    final List<String> stdins = new CopyOnWriteArrayList<>();
    /** Instances whose status is Stopped; every other instance is Running, unless {@link #statuses} says. */
    final java.util.Set<String> stopped = ConcurrentHashMap.newKeySet();
    /** Statuses other than Running and Stopped (Error, Frozen), by instance. */
    final Map<String, String> statuses = new ConcurrentHashMap<>();
    /** When set, every exec throws it, as Incus does for an instance it cannot run a command in. */
    volatile RuntimeException execFailure;
    volatile String execStdout = "";
    volatile int execExit = 0;
    volatile RuntimeException createFailure;
    /** Run while an instance is being created: what happens concurrently with a slow copy. */
    volatile Runnable onCreate;
    /** When set, answers each exec script with its stdout (exit 0), instead of execStdout/execExit. */
    volatile java.util.function.Function<String, String> responder;
    /** Like {@link #responder}, given the instance too: (instance, script) to stdout. Checked first. */
    volatile java.util.function.BiFunction<String, String, String> instanceResponder;

    FakeBackend template(String name, boolean built, String... tools) {
        templates.add(new TemplateInfo(name, name + " template", built, false, List.of(tools), null, false, Map.of()));
        if (built) instances.put(name, new ConcurrentHashMap<>(Map.of(Metadata.TYPE, Metadata.TYPE_BASE)));
        return this;
    }

    FakeBackend projectLocalTemplate(String name) {
        templates.add(new TemplateInfo(name, "", true, false, List.of(), null, true, Map.of()));
        return this;
    }

    FakeBackend delegateModel(String template, String model) {
        templates.replaceAll(t -> t.name().equals(template) ? new TemplateInfo(t.name(), t.description(), t.built(),
                t.stale(), t.tools(), model, t.projectLocal(), t.definitions()) : t);
        return this;
    }

    /** The account pins a created instance gets, as its template's accounts would give. */
    final Map<String, String> createdAccounts = new ConcurrentHashMap<>();
    /** The configured default account of every namespace, which an unpinned instance uses. */
    volatile String defaultAccount = "personal";

    @Override
    public String effectiveAccount(String namespace, String pinned) {
        return pinned != null ? pinned : defaultAccount;
    }

    FakeBackend instance(String name, Map<String, String> config) {
        instances.put(name, new ConcurrentHashMap<>(config));
        return this;
    }

    @Override
    public List<TemplateInfo> templates() {
        return List.copyOf(templates);
    }

    @Override
    public CreatedInstance create(TemplateInfo info, String name, Map<String, String> stamps) {
        var template = info.name();
        if (createFailure != null) throw createFailure;
        if (onCreate != null) onCreate.run();
        var config = new ConcurrentHashMap<String, String>(stamps);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.put(Metadata.PARENT, template);
        config.put(Metadata.PROFILE, template); // every copy of a template carries it
        createdAccounts.forEach((ns, account) -> config.put(Metadata.accountKey(ns), account));
        instances.put(name, config);
        return new CreatedInstance(name, "10.0.0.2", "/home/agentuser", Map.copyOf(createdAccounts));
    }

    /** Every fork, as {@code source -> name}. */
    final List<String> forks = new CopyOnWriteArrayList<>();

    /** As Incus's copy and configureBranch: the source's config, its mcp-* keys replaced by the stamps. */
    @Override
    public CreatedInstance fork(TemplateInfo lineage, String source, String name, Map<String, String> stamps) {
        if (createFailure != null) throw createFailure;
        var config = new ConcurrentHashMap<String, String>(instances.get(source));
        config.keySet().removeIf(Metadata::isMcpKey);
        config.putAll(stamps);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.put(Metadata.PARENT, source);
        instances.put(name, config);
        forks.add(source + " -> " + name);
        var accounts = new LinkedHashMap<String, String>();
        config.forEach((k, v) -> {
            if (k.startsWith(Metadata.ACCOUNT_PREFIX)) accounts.put(k.substring(Metadata.ACCOUNT_PREFIX.length()), v);
        });
        return new CreatedInstance(name, "10.0.0.3", "/home/agentuser", accounts);
    }

    @Override
    public java.util.Optional<TemplateInfo> template(String name) {
        return templates.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    @Override
    public void destroy(String name) {
        instances.remove(name);
        destroyed.add(name);
    }

    /** Run between {@link #destroyIfHeldBy}'s mark and its re-read: what another session does meanwhile. */
    volatile Runnable onMarked;
    /** Run before each {@link #stamp} is applied: what another session did just before it. */
    volatile Runnable onStamp;

    @Override
    public boolean destroyIfHeldBy(String name, String session, boolean onlyIfStopped) {
        var instance = instances.get(name);
        if (instance == null) return false;
        instance.put(Metadata.PENDING_OP, Metadata.OP_DELETING);
        if (onMarked != null) onMarked.run();
        if (!session.equals(instance.get(Metadata.MCP_SESSION)) || onlyIfStopped && !stopped.contains(name)) {
            instance.remove(Metadata.PENDING_OP);
            return false;
        }
        destroy(name);
        return true;
    }

    @Override
    public void stop(String name) {
        stopped.add(name);
    }

    @Override
    public void start(String name) {
        stopped.remove(name);
        statuses.remove(name);
    }

    volatile int proxyRefreshes;

    @Override
    public void refreshProxy() {
        proxyRefreshes++;
    }

    /** When set, every metadata read throws it, as a backend whose daemon cannot answer does. */
    volatile RuntimeException metadataFailure;

    /** Run before each {@link #metadata} read: what happened meanwhile. */
    volatile Runnable onRead;

    /** Every {@link #metadata} call, as a real backend's instance GETs. */
    final java.util.concurrent.atomic.AtomicInteger metadataReads = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public Map<String, String> metadata(String name) {
        metadataReads.incrementAndGet();
        if (onRead != null) onRead.run();
        if (metadataFailure != null) throw metadataFailure;
        var config = instances.get(name);
        return config == null ? null : withStatus(name, config);
    }

    private Map<String, String> withStatus(String name, Map<String, String> config) {
        var result = new LinkedHashMap<>(config);
        result.put(STATUS, stopped.contains(name) ? "Stopped" : statuses.getOrDefault(name, "Running"));
        return result;
    }

    @Override
    public void stamp(String name, Map<String, String> config) {
        if (onStamp != null) onStamp.run();
        var instance = instances.get(name);
        config.forEach((k, v) -> {
            if (v == null) instance.remove(k);
            else instance.put(k, v);
        });
    }

    void stamp(String name, String key, String value) {
        stamp(name, java.util.Collections.singletonMap(key, value));
    }

    @Override
    public Map<String, Map<String, String>> mcpInstances() {
        var result = new LinkedHashMap<String, Map<String, String>>();
        instances.forEach((name, config) -> {
            if (config.containsKey(Metadata.MCP_SESSION)) result.put(name, withStatus(name, config));
        });
        return result;
    }

    @Override
    public int exec(String name, String script, InputStream stdin, OutputStream stdout, OutputStream stderr) {
        if (!instances.containsKey(name)) throw new IllegalStateException("Instance not found: " + name);
        if (execFailure != null) throw execFailure;
        if (stopped.contains(name)) throw new IllegalStateException("Instance is not running");
        scripts.add(script);
        try {
            stdins.add(stdin == null ? "" : new String(stdin.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        var answer = instanceResponder != null ? instanceResponder.apply(name, script)
                : responder != null ? responder.apply(script) : execStdout;
        try {
            if (stdout != null) stdout.write(answer.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return instanceResponder != null || responder != null ? 0 : execExit;
    }
}
