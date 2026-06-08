package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level HTTP client for the Incus REST API.
 * Delegates all protocol-level I/O to an {@link IncusTransport} (Unix socket or HTTPS).
 * Handles request serialization, response parsing, async operation waiting, and WebSocket exec.
 */
class IncusApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WAIT_TIMEOUT_SECONDS = 120;

    private final IncusTransport transport;

    IncusApi(IncusTransport transport) {
        this.transport = transport;
    }

    /**
     * Try each candidate Unix socket path, then fall back to an HTTPS remote if configured.
     * Returns an IncusApi instance if a probe request succeeds, or null if nothing is accessible.
     */
    static IncusApi tryConnect() {
        // Try Unix socket first (Linux)
        for (var candidate : UnixSocketTransport.SOCKET_CANDIDATES) {
            if (!Files.exists(Path.of(candidate))) continue;
            try {
                var http = new IncusApi(new UnixSocketTransport(candidate));
                if (http.get("/1.0").isSuccess()) return http;
            } catch (IncusException ignored) {
                // Socket exists but not accessible or connection refused — try next.
            }
        }
        // Try HTTPS remote (macOS or configured remote)
        var httpsTransport = HttpsTransport.fromClientConfig();
        if (httpsTransport != null) {
            try {
                var http = new IncusApi(httpsTransport);
                if (http.get("/1.0").isSuccess()) return http;
            } catch (IncusException ignored) {}
        }
        return null;
    }

    /**
     * Examine the socket paths and return a human-readable, actionable error message
     * explaining why a connection could not be established.
     */
    static String diagnoseConnectionFailure() {
        for (var candidate : UnixSocketTransport.SOCKET_CANDIDATES) {
            if (!Files.exists(Path.of(candidate))) continue;
            // The socket file exists — probe it to get a precise error.
            try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(candidate));
                // If we connected now, the daemon became available after startup.
                return "Incus socket at " + candidate + " is now accessible — please retry.";
            } catch (BindException e) {
                // Java throws BindException (not AccessDeniedException) for EACCES on socket connect.
                return """
                        Cannot access the Incus socket at %s (permission denied).

                        Your user is probably in the 'incus-admin' group but the group is not \
                        active in this login session.

                        Fix (choose one):
                          newgrp incus-admin          activates the group in this shell only
                          Log out and log back in     activates the group for all new sessions

                        If you have not yet been added to the group, run:
                          isx init
                        """.formatted(candidate);
            } catch (ConnectException e) {
                return """
                        The Incus daemon is not running (socket at %s is not accepting connections).

                        Start the daemon:   sudo systemctl start incus
                        Enable on boot:    sudo systemctl enable incus
                        """.formatted(candidate);
            } catch (IOException e) {
                return "Cannot connect to Incus socket at " + candidate + ": " + e.getMessage();
            }
        }
        // No socket file found at any candidate path.
        return """
                Incus socket not found. Checked:
                  /run/incus/unix.socket
                  /var/lib/incus/unix.socket

                Ensure Incus is installed and the daemon is running:
                  sudo systemctl enable --now incus

                First-time setup: run 'isx init'
                """;
    }

    record ApiResponse(int statusCode, JsonNode body) {
        boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        boolean isAsync() {
            return statusCode == 202;
        }

        String operationPath() {
            return body.path("operation").asText("");
        }
    }

    ApiResponse get(String path) {
        return request("GET", path, null);
    }

    ApiResponse delete(String path) {
        return request("DELETE", path, null);
    }

    ApiResponse put(String path, Object body) {
        return request("PUT", path, body);
    }

    /**
     * Set a single property on a device, merging with the device's current expanded config.
     * A PATCH with an incomplete device config (e.g. just {"size": "10GB"}) is rejected by
     * Incus with "Missing device type". We read the expanded_devices (which includes
     * profile-inherited device config), merge the new key in, then PATCH the full device.
     */
    ApiResponse deviceConfigSet(String instanceName, String deviceName, String key, String value) {
        var getResp = get("/1.0/instances/" + instanceName);
        if (!getResp.isSuccess()) throw new IncusException("Failed to get instance " + instanceName);

        var metadata = getResp.body().path("metadata");
        // Start from expanded_devices (includes profile defaults), fall back to instance devices
        var expandedDevice = metadata.path("expanded_devices").path(deviceName);
        var deviceNode = expandedDevice.isMissingNode()
                ? metadata.path("devices").path(deviceName)
                : expandedDevice;

        var merged = JSON.createObjectNode();
        if (!deviceNode.isMissingNode()) {
            deviceNode.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        }
        merged.put(key, value);

        return requestAndWait("PATCH", "/1.0/instances/" + instanceName,
                Map.of("devices", Map.of(deviceName, merged)));
    }

    /**
     * Remove a named device from an instance.
     * Incus PATCH cannot remove devices (null/empty are rejected), so this does a
     * read-modify-write: GET the current config, drop the device, PUT the full config back.
     */
    ApiResponse removeDevice(String instanceName, String deviceName) {
        var getResp = get("/1.0/instances/" + instanceName);
        if (!getResp.isSuccess()) throw new IncusException("Failed to get instance " + instanceName);

        var metadata = getResp.body().path("metadata");

        var devicesNode = JSON.createObjectNode();
        metadata.path("devices").fields().forEachRemaining(e -> {
            if (!e.getKey().equals(deviceName)) devicesNode.set(e.getKey(), e.getValue());
        });

        var putBody = JSON.createObjectNode();
        putBody.put("architecture", metadata.path("architecture").asText());
        putBody.set("config", metadata.path("config").deepCopy());
        putBody.put("description", metadata.path("description").asText(""));
        putBody.set("devices", devicesNode);
        putBody.put("ephemeral", metadata.path("ephemeral").asBoolean(false));
        var profiles = putBody.putArray("profiles");
        metadata.path("profiles").forEach(p -> profiles.add(p.asText()));
        putBody.put("stateful", metadata.path("stateful").asBoolean(false));

        return requestAndWait("PUT", "/1.0/instances/" + instanceName, putBody);
    }

    ApiResponse removeDevices(String instanceName, java.util.Collection<String> deviceNames) {
        var getResp = get("/1.0/instances/" + instanceName);
        if (!getResp.isSuccess()) throw new IncusException("Failed to get instance " + instanceName);

        var metadata = getResp.body().path("metadata");
        var devicesNode = JSON.createObjectNode();
        metadata.path("devices").fields().forEachRemaining(e -> {
            if (!deviceNames.contains(e.getKey())) devicesNode.set(e.getKey(), e.getValue());
        });

        var putBody = JSON.createObjectNode();
        putBody.put("architecture", metadata.path("architecture").asText());
        putBody.set("config", metadata.path("config").deepCopy());
        putBody.put("description", metadata.path("description").asText(""));
        putBody.set("devices", devicesNode);
        putBody.put("ephemeral", metadata.path("ephemeral").asBoolean(false));
        var profiles = putBody.putArray("profiles");
        metadata.path("profiles").forEach(p -> profiles.add(p.asText()));
        putBody.put("stateful", metadata.path("stateful").asBoolean(false));

        return requestAndWait("PUT", "/1.0/instances/" + instanceName, putBody);
    }

    ApiResponse patch(String path, Object body) {
        return request("PATCH", path, body);
    }

    ApiResponse post(String path, Object body) {
        return request("POST", path, body);
    }

    /**
     * Execute a state-changing request and block until the async operation completes.
     * Incus returns HTTP 202 with an operation URL for async operations (start, stop, copy, etc.).
     */
    ApiResponse requestAndWait(String method, String apiPath, Object body) {
        var resp = request(method, apiPath, body);
        if (!resp.isAsync()) return resp;
        var opPath = resp.operationPath();
        if (opPath.isEmpty()) throw new IncusException("Async response missing operation path");
        return waitForOperation(opPath);
    }

    private ApiResponse waitForOperation(String operationPath) {
        var result = get(operationPath + "/wait?timeout=" + WAIT_TIMEOUT_SECONDS);
        if (!result.isSuccess()) {
            throw new IncusException("Operation wait failed: " + result.body().path("error").asText());
        }
        var metadata = result.body().path("metadata");
        if ("Failure".equals(metadata.path("status").asText())) {
            throw new IncusException("Operation failed: " + metadata.path("err").asText("unknown"));
        }
        return result;
    }

    /**
     * Push a single file into an instance at the given path.
     * The file is written as root (uid=0, gid=0) with mode 0644.
     */
    ApiResponse filePush(String instanceName, String destPath, Path sourceFile) {
        try {
            var content = Files.readAllBytes(sourceFile);
            var extraHeaders = Map.of(
                    "X-Incus-uid", "0",
                    "X-Incus-gid", "0",
                    "X-Incus-mode", posixModeString(sourceFile),
                    "X-Incus-type", "file");
            return requestRaw("POST", filesPath(instanceName, destPath),
                    "application/octet-stream", extraHeaders, content);
        } catch (IOException e) {
            throw new IncusException("Failed to read file for push: " + sourceFile, e);
        }
    }

    private static String posixModeString(Path file) {
        try {
            var perms = Files.getPosixFilePermissions(file);
            int mode = 0;
            for (var p : perms) {
                mode |= switch (p) {
                    case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
                    case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
                };
            }
            return String.format("0%o", mode);
        } catch (Exception e) {
            return "0644";
        }
    }

    /**
     * Create a directory inside an instance at the given path.
     */
    ApiResponse mkdirInInstance(String instanceName, String destPath) {
        var extraHeaders = Map.of(
                "X-Incus-uid", "0",
                "X-Incus-gid", "0",
                "X-Incus-mode", "0755",
                "X-Incus-type", "directory");
        return requestRaw("POST", filesPath(instanceName, destPath),
                "application/octet-stream", extraHeaders, new byte[0]);
    }

    private static String filesPath(String instanceName, String destPath) {
        return "/1.0/instances/" + instanceName + "/files?path="
                + URLEncoder.encode(destPath, StandardCharsets.UTF_8);
    }

    /**
     * Recursively push a host path into an instance.
     * If sourcePath is a regular file, it is pushed as destPath/filename —
     * matching the behaviour of 'incus file push -r file container/dest'.
     * YamlToolSetup calls this for each direct child of an extracted archive,
     * which may be a flat binary (e.g. the starship binary) rather than a directory.
     */
    void filePushRecursive(String instanceName, String destPath, Path sourcePath) {
        try {
            if (Files.isRegularFile(sourcePath)) {
                // Single file: place it at destPath/filename, creating the parent dir first.
                assertFileOp(mkdirInInstance(instanceName, destPath),
                        "mkdir " + instanceName + ":" + destPath);
                assertFileOp(filePush(instanceName, destPath + "/" + sourcePath.getFileName(), sourcePath),
                        "push " + sourcePath + " to " + instanceName + ":" + destPath);
                return;
            }
            // Directory: push as destPath/dirName/... to match 'incus file push -r' behavior.
            var targetDir = destPath + "/" + sourcePath.getFileName();
            assertFileOp(mkdirInInstance(instanceName, destPath),
                    "mkdir " + instanceName + ":" + destPath);
            assertFileOp(mkdirInInstance(instanceName, targetDir),
                    "mkdir " + instanceName + ":" + targetDir);
            try (var stream = Files.walk(sourcePath)) {
                stream.forEach(p -> {
                    var relative = sourcePath.relativize(p).toString();
                    if (relative.isEmpty()) return;
                    var containerPath = targetDir + "/" + relative;
                    if (Files.isDirectory(p)) {
                        assertFileOp(mkdirInInstance(instanceName, containerPath),
                                "mkdir " + instanceName + ":" + containerPath);
                    } else {
                        assertFileOp(filePush(instanceName, containerPath, p),
                                "push " + p + " to " + instanceName + ":" + containerPath);
                    }
                });
            }
        } catch (IOException e) {
            throw new IncusException(
                    "Failed to push " + sourcePath + " to " + instanceName + ":" + destPath, e);
        }
    }

    private static void assertFileOp(ApiResponse resp, String description) {
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to " + description
                    + " (HTTP " + resp.statusCode() + ")");
        }
    }

    private ApiResponse request(String method, String path, Object bodyObj) {
        try {
            byte[] bodyBytes = bodyObj != null ? JSON.writeValueAsBytes(bodyObj) : new byte[0];
            var raw = transport.request(method, path, "application/json", Map.of(), bodyBytes);
            var bodyJson = raw.body().length == 0 ? JSON.nullNode() : JSON.readTree(raw.body());
            return new ApiResponse(raw.statusCode(), bodyJson);
        } catch (IOException e) {
            throw new IncusException("Incus REST request failed: " + method + " " + path, e);
        }
    }

    private ApiResponse requestRaw(String method, String path, String contentType,
                                   Map<String, String> extraHeaders, byte[] bodyBytes) {
        try {
            var raw = transport.request(method, path, contentType, extraHeaders, bodyBytes);
            var bodyJson = raw.body().length == 0 ? JSON.nullNode() : JSON.readTree(raw.body());
            return new ApiResponse(raw.statusCode(), bodyJson);
        } catch (IOException e) {
            throw new IncusException("Incus REST request failed: " + method + " " + path, e);
        }
    }

    // =========================================================================
    // Exec via WebSocket
    //
    // Protocol (verified against Incus 6.23):
    //   POST /1.0/instances/{name}/exec  →  202 with operation + fd secrets
    //   fd keys (non-interactive): "0"=stdin, "1"=stdout, "2"=stderr, "control"
    //   fd keys (interactive PTY): "0"=stdin+stdout muxed, "control"
    //   Each fd gets its own WebSocket connection.
    //   Exit code: body.metadata.metadata.return  (double-nested JSON)
    // =========================================================================

    /**
     * Non-interactive exec — captures stdout/stderr, returns exit code + output.
     */
    IncusClient.ExecResult execCapture(String instance, List<String> command,
                                       Integer uid, Integer gid, String cwd,
                                       Map<String, String> env) {
        var postResp = post("/1.0/instances/" + instance + "/exec",
                buildExecBody(command, uid, gid, cwd, env, false, 0, 0));
        if (!postResp.isAsync()) throw new IncusException(
                "exec POST failed (" + postResp.statusCode() + "): " +
                postResp.body().path("error").asText());

        var opMeta  = postResp.body().path("metadata");
        var opId    = opMeta.path("id").asText();
        var fds     = opMeta.path("metadata").path("fds");
        var opPath  = "/1.0/operations/" + opId;

        var stdoutBuf = new ByteArrayOutputStream();
        var stderrBuf = new ByteArrayOutputStream();

        // Close stdin — many commands don't read it and we don't need to send data.
        wsCloseOnly(opPath, fds.path("0").asText());

        // Read stdout and stderr concurrently with virtual threads.
        var stdoutThread = Thread.ofVirtual().start(() ->
                wsCollect(opPath, fds.path("1").asText(), stdoutBuf));
        var stderrThread = Thread.ofVirtual().start(() ->
                wsCollect(opPath, fds.path("2").asText(), stderrBuf));
        joinQuietly(stdoutThread, stderrThread);

        int exitCode = waitForExecOp(opPath);
        return new IncusClient.ExecResult(exitCode,
                stdoutBuf.toString(StandardCharsets.UTF_8),
                stderrBuf.toString(StandardCharsets.UTF_8));
    }

    /**
     * Non-interactive exec — streams stdout/stderr to provided OutputStreams in real time.
     * Pass null for stdout/stderr to discard that stream. Returns exit code.
     */
    int execStream(String instance, List<String> command,
                   Integer uid, Integer gid, String cwd, Map<String, String> env,
                   OutputStream stdout, OutputStream stderr) {
        var postResp = post("/1.0/instances/" + instance + "/exec",
                buildExecBody(command, uid, gid, cwd, env, false, 0, 0));
        if (!postResp.isAsync()) throw new IncusException(
                "exec POST failed (" + postResp.statusCode() + "): " +
                postResp.body().path("error").asText());

        var opMeta = postResp.body().path("metadata");
        var opId   = opMeta.path("id").asText();
        var fds    = opMeta.path("metadata").path("fds");
        var opPath = "/1.0/operations/" + opId;

        wsCloseOnly(opPath, fds.path("0").asText());

        var outDst = stdout != null ? stdout : OutputStream.nullOutputStream();
        var errDst = stderr != null ? stderr : OutputStream.nullOutputStream();

        var stdoutThread = Thread.ofVirtual().start(() ->
                wsStream(opPath, fds.path("1").asText(), outDst));
        var stderrThread = Thread.ofVirtual().start(() ->
                wsStream(opPath, fds.path("2").asText(), errDst));
        joinQuietly(stdoutThread, stderrThread);

        return waitForExecOp(opPath);
    }

    /**
     * Interactive PTY exec — bridges System.in/out to the container PTY.
     * Sets the local terminal to raw mode for the duration. Returns exit code.
     */
    int execPty(String instance, List<String> command,
                Integer uid, Integer gid, String cwd, Map<String, String> env,
                int width, int height) {
        var postResp = post("/1.0/instances/" + instance + "/exec",
                buildExecBody(command, uid, gid, cwd, env, true, width, height));
        if (!postResp.isAsync()) throw new IncusException(
                "exec POST failed (" + postResp.statusCode() + "): " +
                postResp.body().path("error").asText());

        var opMeta = postResp.body().path("metadata");
        var opId   = opMeta.path("id").asText();
        var fds    = opMeta.path("metadata").path("fds");
        var opPath = "/1.0/operations/" + opId;

        // Interactive PTY uses two WebSockets:
        //   fd "0" — muxed stdin+stdout
        //   "control" — REQUIRED by wait-for-websocket; Incus closes it when the process exits.
        //
        // Incus does NOT close the fd "0" WebSocket when the process exits — it only closes
        // "control". The operation (and its exit code in /wait) is finalized only after BOTH
        // WebSocket connections are closed. So when control closes, we close the fd "0"
        // connection to unblock the read loop and allow waitForExecOp to return the exit code.
        var controlThread = Thread.ofVirtual().start(() ->
                wsDiscard(opPath, fds.path("control").asText()));

        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + fds.path("0").asText())) {
            // Watcher: close fd "0" as soon as control closes (command exited).
            Thread.ofVirtual().start(() -> {
                joinQuietly(controlThread);
                ws.close();
            });

            setRawTerminal();
            try {
                // Thread: System.in → WebSocket.
                // Does NOT send a close frame on EOF — doing so terminates the PTY session
                // immediately, losing buffered output. The session ends via the watcher above.
                var stdinThread = Thread.ofVirtual().start(() -> {
                    try {
                        var buf = new byte[4096];
                        int n;
                        while ((n = System.in.read(buf)) != -1) ws.sendData(buf, 0, n);
                    } catch (IOException ignored) {}
                });

                // Main: WebSocket → System.out.
                // Exits when the watcher closes the connection (IOException → caught).
                byte[] payload;
                while ((payload = ws.readPayload()) != null) {
                    System.out.write(payload);
                    System.out.flush();
                }
            } catch (IOException ignored) {
                // Connection closed by watcher — normal PTY session end.
            } finally {
                restoreTerminal();
            }
        } catch (IOException e) {
            throw new IncusException("PTY exec failed", e);
        }
        joinQuietly(controlThread);

        return waitForExecOp(opPath);
    }

    // ---- WebSocket helpers ----

    // The incus CLI injects these defaults into every exec'd process.
    // The REST API does not — we must provide them explicitly.
    private static final Map<String, String> DEFAULT_EXEC_ENV = Map.of(
            "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM", "xterm-256color"
    );

    Map<String, Object> buildExecBody(List<String> command,
                                              Integer uid, Integer gid, String cwd,
                                              Map<String, String> env,
                                              boolean interactive, int width, int height) {
        var mergedEnv = new LinkedHashMap<>(DEFAULT_EXEC_ENV);
        if (env != null) mergedEnv.putAll(env);
        var body = new LinkedHashMap<String, Object>();
        body.put("command", command);
        body.put("environment", mergedEnv);
        body.put("wait-for-websocket", true);
        body.put("interactive", interactive);
        body.put("record-output", false);
        if (uid != null) body.put("user", uid);
        if (gid != null) body.put("group", gid);
        if (cwd != null) body.put("cwd", cwd);
        if (interactive) {
            body.put("width", width > 0 ? width : 80);
            body.put("height", height > 0 ? height : 24);
        }
        return body;
    }

    /** Connect a WebSocket and read/discard all frames until the server closes it. */
    private void wsDiscard(String opPath, String secret) {
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            while (ws.readPayload() != null) {}
        } catch (IOException ignored) {}
    }

    /** Connect a WebSocket and immediately send a close frame. */
    private void wsCloseOnly(String opPath, String secret) {
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            ws.sendClose();
        } catch (IOException e) {
            // Non-fatal: stdin close failure just means the command may hang waiting for input.
        }
    }

    /** Connect a WebSocket and collect all data into buf until the connection closes. */
    private void wsCollect(String opPath, String secret, ByteArrayOutputStream buf) {
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            byte[] payload;
            while ((payload = ws.readPayload()) != null) buf.write(payload);
        } catch (IOException ignored) {}
    }

    /** Connect a WebSocket and stream all data to dst until the connection closes. */
    private void wsStream(String opPath, String secret, OutputStream dst) {
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            byte[] payload;
            while ((payload = ws.readPayload()) != null) {
                dst.write(payload);
                dst.flush();
            }
        } catch (IOException ignored) {}
    }

    /** GET /1.0/operations/{id}/wait and extract the exit code from metadata.metadata.return. */
    private int waitForExecOp(String opPath) {
        var waitResp = get(opPath + "/wait?timeout=" + WAIT_TIMEOUT_SECONDS);
        if (!waitResp.isSuccess()) {
            System.err.println("Warning: exec operation lost (HTTP " + waitResp.statusCode()
                    + " on " + opPath + "/wait) — exit code unknown");
            return -1;
        }
        return waitResp.body().path("metadata").path("metadata").path("return").asInt(0);
    }

    private static void joinQuietly(Thread... threads) {
        for (var t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * GET a text/plain endpoint (e.g. a log file path) and return the body as a String.
     * Returns empty string on any failure.
     */
    String getText(String path) {
        try {
            var raw = transport.request("GET", path, null, Map.of(), new byte[0]);
            if (!raw.isSuccess()) return "";
            return new String(raw.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Non-interactive exec — streams stdout/stderr to provided OutputStreams and forwards
     * the provided InputStream to the container's stdin. Needed for binary protocols like
     * the git pack protocol. Returns exit code.
     */
    int execBidirectional(String instance, List<String> command,
                          Integer uid, Integer gid, String cwd, Map<String, String> env,
                          InputStream stdin, OutputStream stdout, OutputStream stderr) {
        var postResp = post("/1.0/instances/" + instance + "/exec",
                buildExecBody(command, uid, gid, cwd, env, false, 0, 0));
        if (!postResp.isAsync()) throw new IncusException(
                "exec POST failed (" + postResp.statusCode() + "): " +
                postResp.body().path("error").asText());

        var opMeta = postResp.body().path("metadata");
        var opId   = opMeta.path("id").asText();
        var fds    = opMeta.path("metadata").path("fds");
        var opPath = "/1.0/operations/" + opId;

        var stdinSecret  = fds.path("0").asText();
        var stdoutSecret = fds.path("1").asText();
        var stderrSecret = fds.path("2").asText();

        var outDst = stdout != null ? stdout : OutputStream.nullOutputStream();
        var errDst = stderr != null ? stderr : OutputStream.nullOutputStream();

        // Forward stdin to the container
        var stdinThread  = Thread.ofVirtual().start(() -> wsForward(opPath, stdinSecret, stdin));
        var stdoutThread = Thread.ofVirtual().start(() -> wsStream(opPath, stdoutSecret, outDst));
        var stderrThread = Thread.ofVirtual().start(() -> wsStream(opPath, stderrSecret, errDst));

        // Join stdout/stderr first: they finish when the container process exits.
        // In the git pack protocol, git closes its write pipe to stdin *before* reading
        // the full pack from stdout, so stdinThread normally finishes before stdoutThread.
        // Joining in this order prevents a permanent hang if the container exits before
        // the caller's stdin pipe is closed: after 2 s the virtual thread is abandoned
        // (System.in.read() cannot be interrupted; the thread dies when the JVM exits).
        joinQuietly(stdoutThread, stderrThread);
        try {
            stdinThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return waitForExecOp(opPath);
    }

    /**
     * Open a WebSocket, read from src and send frames, close when src reaches EOF.
     */
    private void wsForward(String opPath, String secret, InputStream src) {
        if (src == null) {
            wsCloseOnly(opPath, secret);
            return;
        }
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            var buf = new byte[4096];
            int n;
            while ((n = src.read(buf)) != -1) {
                ws.sendData(buf, 0, n);
            }
            ws.sendClose();
        } catch (IOException e) {
            // EOF or closed — normal when the git pack exchange completes.
        }
    }

    // ---- Terminal raw mode (for interactive PTY shell) ----

    private static void setRawTerminal() {
        try {
            new ProcessBuilder("stty", "raw", "-echo")
                    .inheritIO()
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .start().waitFor();
        } catch (Exception ignored) {}
    }

    private static void restoreTerminal() {
        try {
            new ProcessBuilder("stty", "sane")
                    .inheritIO()
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .start().waitFor();
        } catch (Exception ignored) {}
    }

    /**
     * Query current terminal dimensions from stty.
     * Returns {width, height}, defaulting to {80, 24} on failure.
     */
    static int[] terminalSize() {
        try {
            var pb = new ProcessBuilder("stty", "size");
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectErrorStream(true);
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            // output is "rows cols" e.g. "24 80"
            var parts = output.split("\\s+");
            if (parts.length >= 2) {
                return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[0])};
            }
        } catch (Exception ignored) {}
        return new int[]{80, 24};
    }
}
