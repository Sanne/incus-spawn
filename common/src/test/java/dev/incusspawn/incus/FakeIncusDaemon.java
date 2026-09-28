package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory Incus daemon behind an {@link IncusClient}, recording every request the client
 * sends. Latency regressions in the CLI almost always arrive as extra round trips (a
 * {@code configGet} per key, a GET per listed instance), and a round-trip count is
 * deterministic where wall-clock timing on shared CI runners is not. Tests pin the count for a
 * flow; see {@code InstanceLifecycleRequestBudgetTest}.
 *
 * <p>Serves the subset of the API those flows use: server info, instance GET/PUT/PATCH/state,
 * instance listing, rename and DELETE, the {@code default} pool's volume listing, console log GET,
 * network and profile GET, file push and async-operation waits. Anything else answers 404, so an unexpected request still shows up in
 * {@link #requests()}. Exec is among them: every instance behaves as one whose agent never
 * answers.
 */
public final class FakeIncusDaemon implements IncusTransport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ObjectNode> instances = new LinkedHashMap<>();
    /**
     * Profiles by name. {@code expanded_devices} is always an instance's profiles' devices with
     * its own on top, as Incus computes it: removing an instance device that shadows a profile
     * device brings the profile's back.
     */
    private final Map<String, ObjectNode> profiles = new LinkedHashMap<>();
    private final Map<String, ObjectNode> networks = new LinkedHashMap<>();
    private final List<ObjectNode> extraVolumes = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> refusedWrites = new ArrayList<>();
    private final List<String> apiExtensions = new ArrayList<>();
    private final Map<String, String> pushedModes = new LinkedHashMap<>();
    private final Map<String, String> consoleLogs = new LinkedHashMap<>();
    /** Running instances' init pids; every start gets a fresh one, as a new QEMU process would. */
    private final Map<String, Long> pids = new LinkedHashMap<>();
    private final List<String> shutdownIgnored = new ArrayList<>();
    private final List<String> stateActions = new ArrayList<>();
    private boolean refuseNextWrite;
    private boolean diesOnStart;
    private int nextOperation = 1;
    private long nextPid = 1000;

    public FakeIncusDaemon() {
        network("incusbr0", Map.of("ipv4.address", "10.166.11.1/24"));
        var profile = JSON.createObjectNode();
        profile.put("name", "default");
        var devices = profile.putObject("devices");
        var nic = devices.putObject("eth0");
        nic.put("type", "nic");
        nic.put("network", "incusbr0");
        nic.put("name", "eth0");
        var root = devices.putObject("root");
        root.put("type", "disk");
        root.put("path", "/");
        root.put("pool", "default");
        profiles.put("default", profile);
    }

    /** Add a device to an existing instance, as if it had been configured or copied over. */
    public FakeIncusDaemon device(String instanceName, String deviceName, Map<String, String> config) {
        ((ObjectNode) instances.get(instanceName).get("devices")).set(deviceName, JSON.valueToTree(config));
        expand(instanceName);
        return this;
    }

    /** Add a stopped container with the given config. */
    public FakeIncusDaemon container(String name, Map<String, String> config) {
        return instance(name, "container", "Stopped", config);
    }

    public FakeIncusDaemon instance(String name, String type, String status, Map<String, String> config) {
        var node = JSON.createObjectNode();
        node.put("name", name);
        node.put("type", type);
        node.put("status", status);
        node.put("architecture", "x86_64");
        node.set("config", JSON.valueToTree(config));
        node.putArray("profiles").add("default");
        node.putObject("devices");
        instances.put(name, node);
        if (status.equals("Running")) pids.put(name, nextPid++);
        expand(name);
        return this;
    }

    /** Have the guest ignore a graceful stop, as a wedged one does: only a forced stop works. */
    public FakeIncusDaemon ignoreShutdown(String instanceName) {
        shutdownIgnored.add(instanceName);
        return this;
    }

    /** Each {@code PUT /state} so far, as {@code "<instance> <action>"} plus {@code " force"} if forced. */
    public List<String> stateActions() {
        return stateActions;
    }

    private void expand(String instanceName) {
        var instance = instances.get(instanceName);
        var expanded = JSON.createObjectNode();
        instance.path("profiles").forEach(p -> expanded.setAll((ObjectNode) profiles.get(p.asText()).get("devices")));
        instance.path("devices").properties().forEach(e -> expanded.set(e.getKey(), e.getValue()));
        instance.set("expanded_devices", expanded);
    }

    /**
     * Refuse any instance PUT or PATCH whose body contains {@code needle}, as Incus refuses a
     * setting it cannot apply: a 400 and no change.
     */
    public FakeIncusDaemon refuseWritesContaining(String needle) {
        refusedWrites.add(needle);
        return this;
    }

    /**
     * A volume on the {@code default} pool with no instance of this daemon behind it, as another
     * Incus project's instance has.
     */
    public FakeIncusDaemon volume(String project, String name, String type) {
        var vol = JSON.createObjectNode();
        vol.put("name", name);
        vol.put("type", type);
        vol.put("project", project);
        extraVolumes.add(vol);
        return this;
    }

    /** Refuse the next instance PUT or PATCH, whatever it carries, as a transient failure would. */
    public FakeIncusDaemon refuseNextWrite() {
        refuseNextWrite = true;
        return this;
    }

    /** What the instance's console log holds, as {@code incus console --show-log} would print it. */
    public FakeIncusDaemon consoleLog(String instanceName, String log) {
        consoleLogs.put(instanceName, log);
        return this;
    }

    /** The instance as a GET would return it. */
    public JsonNode instance(String name) {
        return instances.get(name).deepCopy();
    }

    public FakeIncusDaemon network(String name, Map<String, String> config) {
        var node = JSON.createObjectNode();
        node.put("name", name);
        node.set("config", JSON.valueToTree(config));
        networks.put(name, node);
        return this;
    }

    /** Drop a network, the default {@code incusbr0} included, so reads of it fail. */
    public FakeIncusDaemon withoutNetwork(String name) {
        networks.remove(name);
        return this;
    }

    /** A client wired to this daemon. */
    public IncusClient client() {
        return new IncusClient(new IncusApi(this));
    }

    /** The mode a file was last pushed into an instance with, or null if it never was. */
    public String pushedMode(String instanceName, String path) {
        return pushedModes.get(instanceName + path);
    }

    /**
     * Every instance stops again the moment it is started, as a container whose init fails does.
     * The fake cannot answer exec, so this is what lets a test run a flow through its start: the
     * readiness wait sees the instance die on its first poll rather than retrying for its timeout.
     */
    public FakeIncusDaemon diesOnStart() {
        diesOnStart = true;
        return this;
    }

    /** Every request so far, as {@code "METHOD path"}, in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    @Override
    public RawResponse request(String method, String path, String contentType,
                               Map<String, String> extraHeaders, byte[] body) throws IOException {
        requests.add(method + " " + path);
        return handle(method, path, body);
    }

    @Override
    public RawResponse request(String method, String path, String contentType,
                               Map<String, String> extraHeaders, Path bodyFile) {
        requests.add(method + " " + path);
        var name = instanceName(path);
        if (name == null || !instances.containsKey(name) || !path.contains("/files?")) return notFound();
        var file = java.net.URLDecoder.decode(path.substring(path.indexOf("path=") + 5),
                java.nio.charset.StandardCharsets.UTF_8);
        pushedModes.put(name + file, extraHeaders.getOrDefault("X-Incus-mode", ""));
        return sync(JSON.createObjectNode());
    }

    @Override
    public WsConnection openWebSocket(String wsPath) throws IOException {
        requests.add("WS " + wsPath);
        throw new IOException("FakeIncusDaemon does not serve WebSockets");
    }

    /** Advertise these in {@code GET /1.0}'s {@code api_extensions}. */
    public FakeIncusDaemon apiExtensions(String... extensions) {
        apiExtensions.clear();
        apiExtensions.addAll(List.of(extensions));
        return this;
    }

    private RawResponse handle(String method, String path, byte[] body) throws IOException {
        if (path.equals("/1.0") && method.equals("GET")) {
            var server = JSON.createObjectNode();
            server.set("api_extensions", JSON.valueToTree(apiExtensions));
            return sync(server);
        }
        if (path.startsWith("/1.0/operations/") && method.equals("GET")) {
            var metadata = JSON.createObjectNode();
            metadata.put("status", "Success");
            return sync(metadata);
        }
        if (path.startsWith("/1.0/instances?") && method.equals("GET")) {
            var list = JSON.createArrayNode();
            instances.values().forEach(list::add);
            return sync(list);
        }
        if (path.startsWith("/1.0/storage-pools?") && method.equals("GET")) {
            var pool = JSON.createObjectNode();
            pool.put("name", "default");
            pool.put("driver", "btrfs");
            return sync(JSON.createArrayNode().add(pool));
        }
        if (path.equals("/1.0/instances") && method.equals("POST")) {
            return copy(JSON.readTree(body));
        }
        if (path.startsWith("/1.0/profiles/") && method.equals("GET")) {
            var profile = profiles.get(path.substring("/1.0/profiles/".length()));
            return profile == null ? notFound() : sync(profile.deepCopy());
        }
        if (path.startsWith("/1.0/storage-pools/default/volumes?") && method.equals("GET")) {
            // Incus keeps a volume per instance, named after it, on the instance's pool.
            var list = JSON.createArrayNode();
            instances.values().forEach(i -> {
                var vol = list.addObject();
                vol.put("name", i.path("name").asText());
                vol.put("type", i.path("type").asText());
                vol.put("project", "default");
            });
            list.addAll(extraVolumes);
            return sync(list);
        }
        if (path.startsWith("/1.0/networks/") && method.equals("GET")) {
            var network = networks.get(path.substring("/1.0/networks/".length()));
            return network == null ? notFound() : sync(network);
        }
        var name = instanceName(path);
        var instance = name == null ? null : instances.get(name);
        if (instance == null) return notFound();

        var rest = path.substring(("/1.0/instances/" + name).length());
        if (rest.isEmpty() && method.equals("GET")) return sync(instance);
        if (rest.isEmpty() && method.equals("POST")) {
            var newName = JSON.readTree(body).path("name").asText();
            instances.remove(name);
            instance.put("name", newName);
            instances.put(newName, instance);
            return async();
        }
        if (rest.isEmpty() && method.equals("DELETE")) {
            instances.remove(name);
            return async();
        }
        if (rest.isEmpty() && (method.equals("PUT") || method.equals("PATCH")) && body != null) {
            if (refuseNextWrite) {
                refuseNextWrite = false;
                return badRequest();
            }
            var text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            if (refusedWrites.stream().anyMatch(text::contains)) return badRequest();
        }
        if (rest.isEmpty() && method.equals("PUT")) {
            // A full replacement: how Incus removes devices (PATCH cannot).
            var replacement = JSON.readTree(body);
            instance.set("config", replacement.path("config").deepCopy());
            instance.set("devices", replacement.path("devices").deepCopy());
            expand(name);
            return sync(JSON.createObjectNode());
        }
        if (rest.isEmpty() && method.equals("PATCH")) {
            applyPatch(instance, JSON.readTree(body));
            expand(name);
            return sync(JSON.createObjectNode());
        }
        if (rest.equals("/console") && method.equals("GET")) {
            var log = consoleLogs.getOrDefault(name, "");
            return new RawResponse(200, log.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (rest.equals("/state") && method.equals("GET")) {
            var state = JSON.createObjectNode();
            state.put("status", instance.path("status").asText());
            state.put("pid", pids.getOrDefault(name, 0L));
            return sync(state);
        }
        if (rest.equals("/state") && method.equals("PUT")) {
            var request = JSON.readTree(body);
            var action = request.path("action").asText();
            boolean force = request.path("force").asBoolean(false);
            stateActions.add(name + " " + action + (force ? " force" : ""));
            if (action.equals("stop") && !force && shutdownIgnored.contains(name)) return badRequest();
            if (action.equals("start") && !diesOnStart) {
                instance.put("status", "Running");
                pids.put(name, nextPid++);
            } else {
                instance.put("status", "Stopped");
                pids.remove(name);
            }
            return async();
        }
        return notFound();
    }

    /**
     * Create an instance as a copy, as Incus does: the source's config and own devices, where
     * a device in the request replaces the source's whole and an empty config value unsets.
     */
    private RawResponse copy(JsonNode request) {
        var source = request.path("source");
        var original = instances.get(source.path("source").asText());
        if (!"copy".equals(source.path("type").asText()) || original == null) return notFound();
        var name = request.path("name").asText();
        var copy = original.deepCopy();
        copy.put("name", name);
        copy.put("status", "Stopped");
        var config = (ObjectNode) copy.get("config");
        request.path("config").properties().forEach(e -> {
            if (e.getValue().asText().isEmpty()) config.remove(e.getKey());
            else config.set(e.getKey(), e.getValue());
        });
        var devices = (ObjectNode) copy.get("devices");
        request.path("devices").properties().forEach(e -> devices.set(e.getKey(), e.getValue()));
        instances.put(name, copy);
        expand(name);
        return async();
    }

    private static void applyPatch(ObjectNode instance, JsonNode patch) {
        var config = (ObjectNode) instance.get("config");
        patch.path("config").properties().forEach(e -> {
            if (e.getValue().isNull()) config.remove(e.getKey());
            else config.set(e.getKey(), e.getValue());
        });
        // Each device in a PATCH replaces the instance's device of that name whole
        var devices = (ObjectNode) instance.get("devices");
        patch.path("devices").properties().forEach(e -> devices.set(e.getKey(), e.getValue()));
    }

    /** The instance a {@code /1.0/instances/<name>[/...][?...]} path addresses, or null. */
    private static String instanceName(String path) {
        var prefix = "/1.0/instances/";
        if (!path.startsWith(prefix)) return null;
        var rest = path.substring(prefix.length());
        var end = rest.length();
        for (var c : new char[] {'/', '?'}) {
            var i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return rest.substring(0, end);
    }

    private static RawResponse sync(JsonNode metadata) {
        var body = JSON.createObjectNode();
        body.put("type", "sync");
        body.put("status_code", 200);
        body.set("metadata", metadata);
        return new RawResponse(200, bytes(body));
    }

    private RawResponse async() {
        var body = JSON.createObjectNode();
        body.put("type", "async");
        body.put("status_code", 100);
        body.put("operation", "/1.0/operations/op-" + nextOperation++);
        return new RawResponse(202, bytes(body));
    }

    private static RawResponse badRequest() {
        var body = JSON.createObjectNode();
        body.put("type", "error");
        body.put("error", "Invalid devices: refused by FakeIncusDaemon");
        body.put("error_code", 400);
        return new RawResponse(400, bytes(body));
    }

    private static RawResponse notFound() {
        var body = JSON.createObjectNode();
        body.put("type", "error");
        body.put("error", "Not Found");
        body.put("error_code", 404);
        // As Incus sends it: present and null, so a missing instance reads as a NullNode.
        body.putNull("metadata");
        return new RawResponse(404, bytes(body));
    }

    private static byte[] bytes(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
