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
    volatile String execStdout = "";
    volatile int execExit = 0;
    volatile RuntimeException createFailure;
    /** When set, answers each exec script with its stdout (exit 0), instead of execStdout/execExit. */
    volatile java.util.function.Function<String, String> responder;

    FakeBackend template(String name, boolean built, String... tools) {
        templates.add(new TemplateInfo(name, name + " template", built, false, List.of(tools), false));
        if (built) instances.put(name, new ConcurrentHashMap<>(Map.of(Metadata.TYPE, Metadata.TYPE_BASE)));
        return this;
    }

    FakeBackend projectLocalTemplate(String name) {
        templates.add(new TemplateInfo(name, "", true, false, List.of(), true));
        return this;
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
    public CreatedInstance create(String template, String name, Map<String, String> stamps) {
        if (createFailure != null) throw createFailure;
        var config = new ConcurrentHashMap<String, String>(stamps);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.put(Metadata.PARENT, template);
        instances.put(name, config);
        return new CreatedInstance(name, "10.0.0.2", "/home/agentuser");
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

    volatile int proxyRefreshes;

    @Override
    public void refreshProxy() {
        proxyRefreshes++;
    }

    @Override
    public Map<String, String> metadata(String name) {
        var config = instances.get(name);
        return config == null ? null : new LinkedHashMap<>(config);
    }

    @Override
    public void stamp(String name, Map<String, String> config) {
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
            if (config.containsKey(Metadata.MCP_SESSION)) result.put(name, Map.copyOf(config));
        });
        return result;
    }

    @Override
    public int exec(String name, String script, InputStream stdin, OutputStream stdout, OutputStream stderr) {
        if (!instances.containsKey(name)) throw new IllegalStateException("Instance not found: " + name);
        scripts.add(script);
        try {
            stdins.add(stdin == null ? "" : new String(stdin.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        var answer = responder != null ? responder.apply(script) : execStdout;
        try {
            if (stdout != null) stdout.write(answer.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return responder != null ? 0 : execExit;
    }
}
