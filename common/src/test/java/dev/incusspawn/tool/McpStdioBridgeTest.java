package dev.incusspawn.tool;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The stdio bridge runs under real Node against a Streamable HTTP server of the JDK's (#2476). */
class McpStdioBridgeTest {

    @TempDir Path dir;

    /** What the server saw of each request: method, path-free, with the headers the bridge owes it. */
    private record Seen(String method, String auth, String secret, String session, String body) {}

    @Test
    void forwardsEachMessageReadingTheCredentialFreshAndKeepingTheSession() throws Exception {
        var node = node();
        var seen = new CopyOnWriteArrayList<Seen>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestHeaders().getFirst("Authorization"),
                    ex.getRequestHeaders().getFirst("X-Isx-Instance-Secret"),
                    ex.getRequestHeaders().getFirst("Mcp-Session-Id"), body));
            byte[] answer;
            if (body.contains("\"initialize\"")) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s-1");
                ex.getResponseHeaders().add("Content-Type", "application/json");
                answer = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}".getBytes();
            } else if (body.contains("\"tools/list\"")) {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                answer = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n\n".getBytes();
            } else if (body.contains("\"boom\"")) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            } else {
                ex.sendResponseHeaders(body.isEmpty() ? 200 : 202, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, answer.length);
            ex.getResponseBody().write(answer);
            ex.close();
        });
        server.start();
        try {
            var secret = dir.resolve("secret");
            Files.writeString(secret, "one\n");
            var script = dir.resolve("bridge.cjs");
            Files.writeString(script, McpStdioBridge.script());
            var process = new ProcessBuilder(node, script.toString(), "--url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    "--header", "X-Isx-Instance-Secret", "--header-file", secret.toString())
                    .redirectErrorStream(false).start();
            var in = process.getOutputStream();
            var out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n".getBytes());
            in.flush();
            assertTrue(out.readLine().contains("\"protocolVersion\""));
            in.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n".getBytes());
            Files.writeString(secret, "two\n");
            in.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n".getBytes());
            in.flush();
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}", out.readLine(), "an event stream's data is relayed");
            in.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"boom\"}\n".getBytes());
            in.flush();
            var error = out.readLine();
            assertTrue(error.contains("\"id\":3") && error.contains("\"error\"") && error.contains("500"), error);
            in.close();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));

            assertEquals("one", seen.get(0).secret(), "the credential is read when the request is sent");
            assertNull(seen.get(0).session());
            var tools = seen.stream().filter(s -> s.body().contains("tools/list")).findFirst().orElseThrow();
            assertEquals("two", tools.secret(), "a rotated secret reaches the next request");
            assertEquals("s-1", tools.session());
            var ended = seen.getLast();
            assertEquals("DELETE", ended.method());
            assertEquals("s-1", ended.session());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsABearerTokenFromTheVariableAtRequestTime() throws Exception {
        var node = node();
        var auth = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            ex.getRequestBody().readAllBytes();
            synchronized (auth) { auth.add(ex.getRequestHeaders().getFirst("Authorization")); }
            var answer = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, answer.length);
            ex.getResponseBody().write(answer);
            ex.close();
        });
        server.start();
        try {
            var script = dir.resolve("bridge.cjs");
            Files.writeString(script, McpStdioBridge.script());
            var pb = new ProcessBuilder(node, script.toString(), "--url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    "--bearer-env", "ISX_TEST_TOKEN");
            pb.environment().put("ISX_TEST_TOKEN", "tok-1");
            var process = pb.start();
            process.getOutputStream().write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes());
            process.getOutputStream().close();
            assertTrue(new String(process.getInputStream().readAllBytes()).contains("\"id\":1"));
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(List.of("Bearer tok-1"), auth);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void initializesAgainWhenTheServerEndedTheSessionAndRetriesOnce() throws Exception {
        var node = node();
        var seen = new CopyOnWriteArrayList<Seen>();
        var live = new java.util.concurrent.atomic.AtomicReference<>("s-1");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            seen.add(new Seen(ex.getRequestMethod(), null, null, session, body));
            if (ex.getRequestMethod().equals("DELETE")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
            byte[] answer;
            if (body.contains("\"initialize\"")) {
                // a new initialize ends the previous session, as the proxy's does
                var next = seen.stream().filter(x -> x.body().contains("\"initialize\"")).count() == 1 ? "s-1" : "s-2";
                live.set(next);
                ex.getResponseHeaders().add("Mcp-Session-Id", next);
                answer = ("{\"jsonrpc\":\"2.0\",\"id\":" + (body.contains("isx-renew") ? "\"isx-renew\"" : "1")
                        + ",\"result\":{\"protocolVersion\":\"2025-06-18\"}}").getBytes();
            } else if (!live.get().equals(session)) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            } else if (body.contains("\"tools/list\"")) {
                answer = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}".getBytes();
            } else {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, answer.length);
            ex.getResponseBody().write(answer);
            ex.close();
        });
        server.start();
        try {
            var script = dir.resolve("bridge.cjs");
            Files.writeString(script, McpStdioBridge.script());
            var secret = dir.resolve("secret");
            Files.writeString(secret, "x\n");
            var process = new ProcessBuilder(node, script.toString(), "--url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    "--header", "X-Isx-Instance-Secret", "--header-file", secret.toString()).start();
            var in = process.getOutputStream();
            var out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            in.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}\n".getBytes());
            in.flush();
            assertTrue(out.readLine().contains("\"protocolVersion\""));
            live.set("gone"); // another client initialized, or the proxy restarted
            in.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n".getBytes());
            in.flush();
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}", out.readLine(), "agy never sees the 404");
            in.close();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));

            var renew = seen.stream().filter(x -> x.body().contains("isx-renew")).findFirst().orElseThrow();
            assertNull(renew.session(), "the renewal starts clean");
            assertTrue(renew.body().contains("\"capabilities\""), "with what agy sent the first time: " + renew.body());
            var listed = seen.stream().filter(x -> x.body().contains("tools/list")).toList();
            assertEquals(2, listed.size(), "tried once, then once more");
            assertEquals("s-2", listed.get(1).session());
            assertTrue(seen.stream().anyMatch(x -> x.body().contains("notifications/initialized") && "s-2".equals(x.session())));
        } finally {
            server.stop(0);
        }
    }

    private static String node() {
        for (var d : System.getenv("PATH").split(":")) {
            if (Files.isExecutable(Path.of(d, "node"))) return Path.of(d, "node").toString();
        }
        assumeTrue(false, "no node on the PATH");
        return null;
    }
}
