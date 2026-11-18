package dev.incusspawn.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * GitHub's tag, tree and raw-content endpoints for one repository at one commit, on loopback,
 * as {@link PinnedSkills} reads them: a test sets files, modes and what the tag names.
 */
public final class FakeGitHub implements AutoCloseable {
    public static final String COMMIT = "c0ffee0000000000000000000000000000000001";
    private static final String TAG_OBJECT = "7a90000000000000000000000000000000000001";

    public record File(String mode, String type, byte[] content, String sha) {}

    private final HttpServer server;
    private final String repo;
    private final Map<String, File> files = new LinkedHashMap<>();
    private final Map<String, byte[]> served = new LinkedHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String tagNames = COMMIT;

    public FakeGitHub(String repo) throws IOException {
        this.repo = repo;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String apiBase() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api"; }
    public String rawBase() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/raw"; }

    public PinnedSkills skills(java.nio.file.Path cache) { return new PinnedSkills(cache, apiBase(), rawBase()); }

    public FakeGitHub file(String path, String content) { return file(path, "100644", content); }

    public FakeGitHub file(String path, String mode, String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        files.put(path, new File(mode, "blob", bytes, PinnedSkills.gitBlobId(bytes)));
        return this;
    }

    /** A tree entry of another type (a submodule's {@code commit}) at {@code path}. */
    public FakeGitHub entry(String path, String mode, String type) {
        files.put(path, new File(mode, type, new byte[0], "0".repeat(40)));
        return this;
    }

    /** Serves {@code content} for {@code path} while the tree keeps the original's blob ID. */
    public FakeGitHub tamper(String path, String content) {
        served.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public FakeGitHub tagNames(String commit) { tagNames = commit; return this; }

    public List<String> requests() { return List.copyOf(requests); }

    private void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getRawPath();
        var query = exchange.getRequestURI().getRawQuery();
        requests.add(path + (query == null ? "" : "?" + query));
        var api = "/api/repos/" + repo;
        if (path.equals(api + "/git/ref/tags/v1")) {
            send(exchange, "{\"object\":{\"type\":\"tag\",\"sha\":\"" + TAG_OBJECT + "\"}}");
        } else if (path.equals(api + "/git/tags/" + TAG_OBJECT)) {
            send(exchange, "{\"object\":{\"type\":\"commit\",\"sha\":\"" + tagNames + "\"}}");
        } else if (path.equals(api + "/git/trees/" + COMMIT) && "recursive=1".equals(query)) {
            send(exchange, tree());
        } else if (path.startsWith("/raw/" + repo + "/" + COMMIT + "/")) {
            var file = java.net.URLDecoder.decode(path.substring(("/raw/" + repo + "/" + COMMIT + "/").length()),
                    StandardCharsets.UTF_8);
            var f = files.get(file);
            if (f == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            var bytes = served.getOrDefault(file, f.content());
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private String tree() {
        var dirs = new java.util.TreeSet<String>();
        for (var path : files.keySet()) {
            for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) dirs.add(path.substring(0, i));
        }
        var entries = new ArrayList<String>();
        for (var dir : dirs) {
            entries.add("{\"path\":\"" + dir + "\",\"mode\":\"040000\",\"type\":\"tree\",\"sha\":\"" + "1".repeat(40) + "\"}");
        }
        for (var e : files.entrySet()) {
            entries.add("{\"path\":\"" + e.getKey() + "\",\"mode\":\"" + e.getValue().mode() + "\",\"type\":\""
                    + e.getValue().type() + "\",\"sha\":\"" + e.getValue().sha() + "\",\"size\":"
                    + e.getValue().content().length + "}");
        }
        return "{\"sha\":\"" + COMMIT + "\",\"tree\":[" + String.join(",", entries) + "],\"truncated\":false}";
    }

    private static void send(HttpExchange exchange, String json) throws IOException {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @Override
    public void close() { server.stop(0); }
}
