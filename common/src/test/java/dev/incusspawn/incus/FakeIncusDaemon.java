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
 * <p>Serves the subset of the API those flows use: instance GET/PATCH/state, instance listing,
 * network GET, file push and async-operation waits. Anything else answers 404, so an
 * unexpected request still shows up in {@link #requests()}.
 */
public final class FakeIncusDaemon implements IncusTransport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, ObjectNode> instances = new LinkedHashMap<>();
    private final Map<String, ObjectNode> networks = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private int nextOperation = 1;

    public FakeIncusDaemon() {
        network("incusbr0", Map.of("ipv4.address", "10.166.11.1/24"));
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
        node.putObject("devices");
        var nic = node.putObject("expanded_devices").putObject("eth0");
        nic.put("type", "nic");
        nic.put("network", "incusbr0");
        nic.put("name", "eth0");
        instances.put(name, node);
        return this;
    }

    public FakeIncusDaemon network(String name, Map<String, String> config) {
        var node = JSON.createObjectNode();
        node.put("name", name);
        node.set("config", JSON.valueToTree(config));
        networks.put(name, node);
        return this;
    }

    /** A client wired to this daemon. */
    public IncusClient client() {
        return new IncusClient(new IncusApi(this));
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
        return name != null && instances.containsKey(name) && path.contains("/files?")
                ? sync(JSON.createObjectNode())
                : notFound();
    }

    @Override
    public WsConnection openWebSocket(String wsPath) throws IOException {
        requests.add("WS " + wsPath);
        throw new IOException("FakeIncusDaemon does not serve WebSockets");
    }

    private RawResponse handle(String method, String path, byte[] body) throws IOException {
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
        if (path.startsWith("/1.0/networks/") && method.equals("GET")) {
            var network = networks.get(path.substring("/1.0/networks/".length()));
            return network == null ? notFound() : sync(network);
        }
        var name = instanceName(path);
        var instance = name == null ? null : instances.get(name);
        if (instance == null) return notFound();

        var rest = path.substring(("/1.0/instances/" + name).length());
        if (rest.isEmpty() && method.equals("GET")) return sync(instance);
        if (rest.isEmpty() && method.equals("PATCH")) {
            applyPatch(instance, JSON.readTree(body));
            return sync(JSON.createObjectNode());
        }
        if (rest.equals("/state") && method.equals("PUT")) {
            var action = JSON.readTree(body).path("action").asText();
            instance.put("status", action.equals("start") ? "Running" : "Stopped");
            return async();
        }
        return notFound();
    }

    private static void applyPatch(ObjectNode instance, JsonNode patch) {
        var config = (ObjectNode) instance.get("config");
        patch.path("config").properties().forEach(e -> {
            if (e.getValue().isNull()) config.remove(e.getKey());
            else config.set(e.getKey(), e.getValue());
        });
        var devices = (ObjectNode) instance.get("devices");
        var expanded = (ObjectNode) instance.get("expanded_devices");
        patch.path("devices").properties().forEach(e -> {
            devices.set(e.getKey(), e.getValue());
            expanded.set(e.getKey(), e.getValue());
        });
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

    private static RawResponse notFound() {
        var body = JSON.createObjectNode();
        body.put("type", "error");
        body.put("error", "Not Found");
        body.put("error_code", 404);
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
