package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.incusspawn.Environment;

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
import java.util.Set;

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

    // Short enough that a stalled vsock fails fast (a healthy connect is sub-millisecond),
    // so reachability polling stays responsive instead of blocking the full request watchdog.
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /**
     * Try each candidate Unix socket path: Linux daemon sockets, then vsock (macOS).
     * Returns an IncusApi instance if a probe request succeeds, or null if nothing is accessible.
     * Uses a short timeout for the probe so a wedged vsock fails fast instead of hanging.
     */
    static IncusApi tryConnect() {
        var socketCandidates = new ArrayList<>(UnixSocketTransport.SOCKET_CANDIDATES);
        var vsockSocket = Environment.vmVsockSocket();
        if (Files.exists(vsockSocket)) {
            socketCandidates.add(vsockSocket.toString());
        }
        for (var candidate : socketCandidates) {
            if (!Files.exists(Path.of(candidate))) continue;
            try {
                var probeTransport = new UnixSocketTransport(candidate, PROBE_TIMEOUT_SECONDS);
                var http = new IncusApi(probeTransport);
                if (http.get("/1.0").isSuccess()) {
                    return new IncusApi(new UnixSocketTransport(candidate));
                }
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
        // Check vsock socket (macOS)
        var vsockSocket = Environment.vmVsockSocket();
        if (Files.exists(vsockSocket)) {
            try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(vsockSocket));
                return "vsock socket at " + vsockSocket + " is accessible — please retry.";
            } catch (IOException e) {
                return "vsock socket exists at " + vsockSocket
                        + " but connection failed: " + e.getMessage()
                        + "\nThe VM may still be booting. Wait a few seconds and retry.";
            }
        }
        return """
                Incus not reachable. No Unix socket found and no vsock socket found.

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

    ApiResponse requestRawAndWait(String method, String apiPath,
                                  String contentType, Map<String, String> headers,
                                  byte[] body) {
        var resp = requestRaw(method, apiPath, contentType, headers, body);
        if (!resp.isAsync()) return resp;
        var opPath = resp.operationPath();
        if (opPath.isEmpty()) throw new IncusException("Async response missing operation path");
        return waitForOperation(opPath);
    }

    ApiResponse requestFromFileAndWait(String method, String apiPath,
                                       String contentType, Map<String, String> headers,
                                       Path bodyFile) {
        var resp = requestRawFromFile(method, apiPath, contentType, headers, bodyFile);
        if (!resp.isAsync()) return resp;
        var opPath = resp.operationPath();
        if (opPath.isEmpty()) throw new IncusException("Async response missing operation path");
        return waitForOperation(opPath);
    }

    private ApiResponse waitForOperation(String operationPath) {
        var waitPath = operationPath + "/wait?timeout=" + WAIT_TIMEOUT_SECONDS;
        var result = requestWithTimeout("GET", waitPath, null,
                WAIT_TIMEOUT_SECONDS + 30);
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
     * The file is written as root (uid=0, gid=0) with mode derived from source.
     */
    ApiResponse filePush(String instanceName, String destPath, Path sourceFile) {
        return filePush(instanceName, destPath, sourceFile, "0", "0", null);
    }

    /**
     * Push a single file with explicit ownership and mode, avoiding a
     * separate chown/chmod exec round trip.
     */
    ApiResponse filePush(String instanceName, String destPath, Path sourceFile,
                         String uid, String gid, String mode) {
        try {
            var content = Files.readAllBytes(sourceFile);
            var extraHeaders = Map.of(
                    "X-Incus-uid", uid,
                    "X-Incus-gid", gid,
                    "X-Incus-mode", mode != null ? mode : posixModeString(sourceFile),
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

    private ApiResponse requestWithTimeout(String method, String path, Object bodyObj,
                                           int timeoutSeconds) {
        try {
            byte[] bodyBytes = bodyObj != null ? JSON.writeValueAsBytes(bodyObj) : new byte[0];
            var raw = transport.request(method, path, "application/json", Map.of(),
                    bodyBytes, timeoutSeconds);
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

    private ApiResponse requestRawFromFile(String method, String path, String contentType,
                                           Map<String, String> extraHeaders, Path bodyFile) {
        try {
            var raw = transport.request(method, path, contentType, extraHeaders, bodyFile);
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
        return retryOnNotRunning(() ->
                execCaptureWebSocket(instance, command, uid, gid, cwd, env));
    }

    private IncusClient.ExecResult execCaptureWebSocket(String instance, List<String> command,
                                                        Integer uid, Integer gid, String cwd,
                                                        Map<String, String> env) {
        var exec = postExec(instance, command, uid, gid, cwd, env, false, 0, 0);
        var stdoutBuf = new ByteArrayOutputStream();
        var stderrBuf = new ByteArrayOutputStream();
        int exitCode = execWebSocket(exec, stdoutBuf, stderrBuf, null);
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
        return retryOnNotRunning(() -> {
            var exec = postExec(instance, command, uid, gid, cwd, env, false, 0, 0);
            return execWebSocket(exec, stdout, stderr, null);
        });
    }

    private static final long WS_PING_INTERVAL_MS = 15_000;

    /**
     * Interactive PTY exec — bridges System.in/out to the container PTY.
     * Sets the local terminal to raw mode for the duration.
     * Returns true if the session ended due to a connection loss (for reconnect logic).
     * Returns immediately after the shell exits without waiting for Incus operation finalization.
     */
    boolean execPty(String instance, List<String> command,
                    Integer uid, Integer gid, String cwd, Map<String, String> env,
                    int width, int height) {
        var exec = postExec(instance, command, uid, gid, cwd, env, true, width, height);

        // Interactive PTY uses two WebSockets:
        //   fd "0" — muxed stdin+stdout
        //   "control" — REQUIRED by wait-for-websocket; Incus closes it when the process exits.
        //
        // When control closes (process exited), the watcher below closes fd "0" to end the session
        // and we return immediately. We do NOT call waitForExecOp — that waits ~8s for Incus to
        // finalize the operation. For interactive logout, instant detach is the right behavior.
        var controlLostConnection = new java.util.concurrent.atomic.AtomicBoolean(false);
        var controlThread = Thread.ofVirtual().start(() ->
                wsDiscardTrackedWithKeepalive(exec.opPath, exec.fds.path("control").asText(), controlLostConnection));
        assertAllFdsConnected(exec.fds, Set.of("0", "control"));

        try (var ws = transport.openWebSocket(exec.opPath + "/websocket?secret=" + exec.fds.path("0").asText())) {
            // Watcher: closes fd "0" when control closes.
            Thread.ofVirtual().start(() -> {
                joinQuietly(controlThread);
                ws.close();
            });

            // Keepalive: periodic pings to prevent idle connection timeout.
            var keepaliveThread = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(WS_PING_INTERVAL_MS);
                        ws.sendPing();
                    }
                } catch (IOException | InterruptedException ignored) {}
            });

            setRawTerminal();
            try {
                // Thread: System.in → WebSocket (no close frame on EOF to avoid losing output).
                var stdinThread = Thread.ofVirtual().start(() -> {
                    try {
                        var buf = new byte[4096];
                        int n;
                        while ((n = System.in.read(buf)) != -1) ws.sendData(buf, 0, n);
                    } catch (IOException ignored) {}
                });

                // Main: WebSocket → System.out (exits when watcher closes the connection).
                byte[] payload;
                while ((payload = ws.readPayload()) != null) {
                    System.out.write(payload);
                    System.out.flush();
                }
            } catch (IOException ignored) {
                // Connection closed by watcher — normal PTY session end.
            } finally {
                keepaliveThread.interrupt();
                restoreTerminal();
            }
        } catch (IOException e) {
            throw new IncusException("PTY exec failed", e);
        }
        joinQuietly(controlThread);

        return controlLostConnection.get();
    }

    // ---- WebSocket helpers ----

    private static final int EXEC_RETRY_MAX = 5;
    private static final long EXEC_RETRY_DELAY_MS = 200;

    private <T> T retryOnNotRunning(java.util.function.Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (IncusException e) {
                if (attempt < EXEC_RETRY_MAX && isTransientExecError(e)) {
                    try { Thread.sleep(EXEC_RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    private static boolean isTransientExecError(IncusException e) {
        var msg = e.getMessage();
        return msg != null && msg.contains("Instance is not running");
    }

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

    private record ExecOp(String opPath, JsonNode fds) {}

    private ExecOp postExec(String instance, List<String> command,
                            Integer uid, Integer gid, String cwd, Map<String, String> env,
                            boolean interactive, int width, int height) {
        var postResp = post("/1.0/instances/" + instance + "/exec",
                buildExecBody(command, uid, gid, cwd, env, interactive, width, height));
        if (!postResp.isAsync()) throw new IncusException(
                "exec POST failed (" + postResp.statusCode() + "): " +
                postResp.body().path("error").asText());
        var opMeta = postResp.body().path("metadata");
        return new ExecOp(
                "/1.0/operations/" + opMeta.path("id").asText(),
                opMeta.path("metadata").path("fds"));
    }

    // After the operation completes we drain the data sockets before force-closing them, so
    // trailing output isn't truncated when close frames never arrive (macOS vsock). Rather than
    // a blind fixed wait (too short risks truncation on a slow tunnel; too long adds that latency
    // to *every* exec on machines where close frames don't arrive), the drain is adaptive: wait a
    // short minimum for in-flight bytes, extend while output is still arriving, and close once it
    // has been idle briefly — bounded by an absolute cap. On the healthy path the close frames
    // arrive and the reader threads finish at once, so none of this is reached.
    private static final long DRAIN_MIN_MS  = 200;   // always wait this for bytes in flight
    private static final long DRAIN_IDLE_MS = 200;   // then close once output has been idle this long
    private static final long DRAIN_MAX_MS  = 5000;  // absolute ceiling

    /**
     * Unified non-interactive exec over WebSockets, used for capture, streaming and
     * bidirectional (git) exec — the destination streams just differ.
     *
     * Streams stdout/stderr to the provided sinks in real time. Crucially, process
     * completion is detected via the operation /wait endpoint (a normal HTTP request
     * backed by the daemon's operation state machine) — NOT via WebSocket close frames,
     * which the macOS vsock tunnel may never deliver. Once the operation completes we
     * give the data sockets a brief grace window to drain, then force-close them so the
     * reader threads always unblock. This makes the termination signal independent of
     * the (unreliable) close-frame propagation that every prior iteration depended on.
     *
     * @param stdin null → stdin is closed immediately; non-null → forwarded to the container.
     */
    private int execWebSocket(ExecOp exec, OutputStream stdout, OutputStream stderr, InputStream stdin) {
        var outDst = stdout != null ? stdout : OutputStream.nullOutputStream();
        var errDst = stderr != null ? stderr : OutputStream.nullOutputStream();
        try (var controlWs = transport.openWebSocket(wsUrl(exec, "control"));
             var stdoutWs  = transport.openWebSocket(wsUrl(exec, "1"));
             var stderrWs  = transport.openWebSocket(wsUrl(exec, "2"))) {

            // Control fd is connected only to satisfy wait-for-websocket and kept alive
            // with pings; it is no longer used as the termination signal.
            // Keepalive pings on EVERY fd, not just control: each exec fd is a separate
            // vsock connection (a separate forwarder child in the VM), so an inactivity
            // reaper in the forwarder would otherwise collect the stdout/stderr connections
            // of a long, quiet command and truncate it. Pings keep every live connection
            // visibly alive so only genuinely-dead ones can be reaped.
            var keepalive    = startKeepalive(controlWs);
            var stdoutAlive  = startKeepalive(stdoutWs);
            var stderrAlive  = startKeepalive(stderrWs);
            var controlDrain = Thread.ofVirtual().start(() -> drainQuietly(controlWs));

            // Timestamp of the last byte received on either data fd, for the adaptive drain.
            var lastData = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
            var stdoutThread = Thread.ofVirtual().start(() -> wsWriteTo(stdoutWs, outDst, lastData));
            var stderrThread = Thread.ofVirtual().start(() -> wsWriteTo(stderrWs, errDst, lastData));

            Thread stdinThread = null;
            if (stdin != null) {
                stdinThread = Thread.ofVirtual().start(() ->
                        wsForward(exec.opPath, exec.fds.path("0").asText(), stdin));
            } else {
                wsCloseOnly(exec.opPath, exec.fds.path("0").asText());
            }
            assertAllFdsConnected(exec.fds, Set.of("0", "1", "2", "control"));

            // Authoritative completion + exit code (HTTP, reliable over vsock).
            int exitCode = waitForExecOp(exec.opPath);

            // Process has exited: stop pinging the data fds, drain, then force-close so the
            // reader threads unblock even if no close frame arrives.
            stdoutAlive.interrupt();
            stderrAlive.interrupt();
            drainThenClose(lastData, stdoutThread, stderrThread, stdoutWs, stderrWs);

            keepalive.interrupt();
            controlWs.close();
            joinQuietly(controlDrain);

            if (stdinThread != null) {
                // In the git pack protocol the caller closes stdin before we reach here;
                // otherwise abandon the (uninterruptible) System.in read after 2 s.
                try { stdinThread.join(2000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return exitCode;
        } catch (IOException e) {
            throw new IncusException("Failed to open exec WebSocket", e);
        }
    }

    private String wsUrl(ExecOp exec, String fd) {
        return exec.opPath + "/websocket?secret=" + exec.fds.path(fd).asText();
    }

    /** Start a virtual thread that pings the socket until interrupted (idle-timeout guard). */
    private Thread startKeepalive(IncusTransport.WsConnection ws) {
        return Thread.ofVirtual().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(WS_PING_INTERVAL_MS);
                    ws.sendPing();
                }
            } catch (IOException | InterruptedException ignored) {}
        });
    }

    /** Read and discard all frames until the socket closes. */
    private static void drainQuietly(IncusTransport.WsConnection ws) {
        try {
            while (ws.readPayload() != null) {}
        } catch (IOException ignored) {}
    }

    /**
     * Drain the data reader threads, then force-close their sockets. Returns immediately when
     * the readers finish on their own (close frames arrived — the healthy path). Otherwise it
     * waits a short minimum for bytes still in flight, extends while output is still arriving
     * (so trailing output isn't truncated), and force-closes once output has been idle for
     * {@link #DRAIN_IDLE_MS} — bounded by {@link #DRAIN_MAX_MS}.
     */
    private static void drainThenClose(java.util.concurrent.atomic.AtomicLong lastData,
                                       Thread stdoutThread, Thread stderrThread,
                                       IncusTransport.WsConnection stdoutWs,
                                       IncusTransport.WsConnection stderrWs) {
        long start = System.nanoTime();
        while (stdoutThread.isAlive() || stderrThread.isAlive()) {
            long now = System.nanoTime();
            long sinceStartMs = (now - start) / 1_000_000L;
            long sinceDataMs  = (now - lastData.get()) / 1_000_000L;
            if (sinceStartMs >= DRAIN_MAX_MS) break;
            if (sinceStartMs >= DRAIN_MIN_MS && sinceDataMs >= DRAIN_IDLE_MS) break;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (stdoutThread.isAlive() || stderrThread.isAlive()) {
            stdoutWs.close();
            stderrWs.close();
            joinQuietly(stdoutThread, stderrThread);
        }
    }

    private static void assertAllFdsConnected(JsonNode fds, Set<String> connected) {
        var missed = new ArrayList<String>();
        fds.fieldNames().forEachRemaining(name -> {
            if (!connected.contains(name)) missed.add(name);
        });
        if (!missed.isEmpty()) {
            throw new IncusException(
                    "WebSocket exec bug: fds not connected: " + missed
                    + " — wait-for-websocket requires all fds to be connected");
        }
    }

    /**
     * Connect a WebSocket, read/discard all frames with keepalive pings until it closes,
     * and set connectionLost if the socket died due to an I/O error (used by execPty for
     * reconnect logic).
     */
    private void wsDiscardTrackedWithKeepalive(String opPath, String secret,
                                               java.util.concurrent.atomic.AtomicBoolean connectionLost) {
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            var keepalive = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(WS_PING_INTERVAL_MS);
                        ws.sendPing();
                    }
                } catch (IOException | InterruptedException ignored) {}
            });
            try {
                while (ws.readPayload() != null) {}
            } finally {
                keepalive.interrupt();
            }
        } catch (IOException e) {
            connectionLost.set(true);
        }
    }

    /** Connect a WebSocket and immediately send a close frame. */
    private void wsCloseOnly(String opPath, String secret) {
        try (var ws = transport.openWebSocket(opPath + "/websocket?secret=" + secret)) {
            ws.sendClose();
        } catch (IOException e) {
            // Non-fatal: stdin close failure just means the command may hang waiting for input.
        }
    }

    /**
     * Read all data from an already-open WebSocket into dst until the connection closes,
     * recording the arrival time of each payload so the adaptive drain knows when output has
     * stopped flowing.
     */
    private static void wsWriteTo(IncusTransport.WsConnection ws, OutputStream dst,
                                  java.util.concurrent.atomic.AtomicLong lastData) {
        try {
            byte[] payload;
            while ((payload = ws.readPayload()) != null) {
                lastData.set(System.nanoTime());
                dst.write(payload);
                dst.flush();
            }
        } catch (IOException ignored) {}
    }

    /**
     * Block until the exec operation finishes and return the command's exit code.
     *
     * The /wait long-poll returns after at most WAIT_TIMEOUT_SECONDS even if the command
     * is still running, so we re-issue it until the operation leaves the Running/Pending
     * state. This is the authoritative termination signal for {@link #execWebSocket} — it
     * must report completion only when the process has actually exited, otherwise we would
     * force-close the data sockets mid-stream and truncate output on long commands.
     */
    // Absolute ceiling so a zombie process or daemon bug cannot block the caller forever.
    // Well above any real command (4 hours); the per-iteration budget is WAIT_TIMEOUT_SECONDS.
    private static final long MAX_EXEC_WAIT_SECONDS = 4 * 3600L;

    private int waitForExecOp(String opPath) {
        long deadline = System.nanoTime() + MAX_EXEC_WAIT_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            var waitResp = requestWithTimeout("GET",
                    opPath + "/wait?timeout=" + WAIT_TIMEOUT_SECONDS,
                    null, WAIT_TIMEOUT_SECONDS + 30);
            if (!waitResp.isSuccess()) {
                System.err.println("Warning: exec operation lost (HTTP " + waitResp.statusCode()
                        + " on " + opPath + "/wait) — exit code unknown");
                return -1;
            }
            var meta = waitResp.body().path("metadata");
            var status = meta.path("status").asText();
            if ("Running".equals(status) || "Pending".equals(status)) {
                continue; // long-poll window elapsed; command still running
            }
            return meta.path("metadata").path("return").asInt(0);
        }
        System.err.println("Warning: exec operation timed out after "
                + MAX_EXEC_WAIT_SECONDS + "s on " + opPath + " — exit code unknown");
        return -1;
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
        return retryOnNotRunning(() -> execBidirectionalWs(instance, command, uid, gid, cwd, env,
                stdin, stdout, stderr));
    }

    private int execBidirectionalWs(String instance, List<String> command,
                                    Integer uid, Integer gid, String cwd, Map<String, String> env,
                                    InputStream stdin, OutputStream stdout, OutputStream stderr) {
        var exec = postExec(instance, command, uid, gid, cwd, env, false, 0, 0);
        return execWebSocket(exec, stdout, stderr, stdin);
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
