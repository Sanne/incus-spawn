package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Low-level HTTP/1.1 client for the Incus REST API via the daemon's Unix socket.
 * Handles request serialization, response parsing (including chunked encoding),
 * and waiting for async operations.
 */
class IncusHttp {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WAIT_TIMEOUT_SECONDS = 120;
    static final List<String> SOCKET_CANDIDATES = List.of(
            "/run/incus/unix.socket",
            "/var/lib/incus/unix.socket"
    );

    private final String socketPath;

    IncusHttp(String socketPath) {
        this.socketPath = socketPath;
    }

    /**
     * Try each candidate socket path. Returns an IncusHttp instance if a probe request
     * succeeds, or null if none are accessible (e.g. permission denied or daemon not running).
     */
    static IncusHttp tryConnect() {
        for (var candidate : SOCKET_CANDIDATES) {
            if (!Files.exists(Path.of(candidate))) continue;
            try {
                var http = new IncusHttp(candidate);
                http.get("/1.0");
                return http;
            } catch (IncusException ignored) {
                // Socket exists but not accessible or connection refused — try next.
            }
        }
        return null;
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
                    "X-Incus-mode", "0644",
                    "X-Incus-type", "file");
            return requestRaw("POST", "/1.0/instances/" + instanceName + "/files?path=" + destPath,
                    "application/octet-stream", extraHeaders, content);
        } catch (IOException e) {
            throw new IncusException("Failed to read file for push: " + sourceFile, e);
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
        return requestRaw("POST", "/1.0/instances/" + instanceName + "/files?path=" + destPath,
                "application/octet-stream", extraHeaders, new byte[0]);
    }

    /**
     * Recursively push a host directory into an instance.
     */
    void filePushRecursive(String instanceName, String destPath, Path sourceDir) {
        try {
            mkdirInInstance(instanceName, destPath);
            try (var stream = Files.walk(sourceDir)) {
                stream.forEach(p -> {
                    var relative = sourceDir.relativize(p).toString();
                    if (relative.isEmpty()) return;
                    var containerPath = destPath + "/" + relative;
                    try {
                        if (Files.isDirectory(p)) {
                            mkdirInInstance(instanceName, containerPath);
                        } else {
                            filePush(instanceName, containerPath, p);
                        }
                    } catch (Exception e) {
                        throw new IncusException("Failed to push " + p + " to " + containerPath, e);
                    }
                });
            }
        } catch (IOException e) {
            throw new IncusException("Failed to push directory " + sourceDir + " to " + instanceName + ":" + destPath, e);
        }
    }

    private ApiResponse request(String method, String path, Object bodyObj) {
        try {
            byte[] bodyBytes = bodyObj != null ? JSON.writeValueAsBytes(bodyObj) : new byte[0];
            return requestRaw(method, path, "application/json", Map.of(), bodyBytes);
        } catch (IOException e) {
            throw new IncusException("Failed to serialize request body", e);
        }
    }

    private ApiResponse requestRaw(String method, String path, String contentType,
                                    Map<String, String> extraHeaders, byte[] bodyBytes) {
        var addr = UnixDomainSocketAddress.of(socketPath);
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(addr);
            var out = Channels.newOutputStream(channel);
            var in = Channels.newInputStream(channel);
            writeRequest(out, method, path, contentType, extraHeaders, bodyBytes);
            channel.shutdownOutput();
            return readResponse(in);
        } catch (IOException e) {
            throw new IncusException("Incus REST request failed: " + method + " " + path, e);
        }
    }

    private void writeRequest(OutputStream out, String method, String path,
                               String contentType, Map<String, String> extraHeaders,
                               byte[] bodyBytes) throws IOException {
        var header = new StringBuilder();
        header.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        header.append("Host: localhost\r\n");
        header.append("Accept: application/json\r\n");
        header.append("Connection: close\r\n");
        if (bodyBytes.length > 0 || !extraHeaders.isEmpty()) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
        }
        for (var entry : extraHeaders.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        if (bodyBytes.length > 0) {
            out.write(bodyBytes);
        }
        out.flush();
    }

    private ApiResponse readResponse(InputStream in) throws IOException {
        var statusLine = readLine(in);
        var parts = statusLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("Invalid HTTP status line: " + statusLine);
        int statusCode = Integer.parseInt(parts[1]);

        int contentLength = -1;
        boolean chunked = false;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            var lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(lower.substring(15).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }

        byte[] bodyBytes;
        if (chunked) {
            bodyBytes = readChunkedBody(in);
        } else if (contentLength >= 0) {
            bodyBytes = in.readNBytes(contentLength);
        } else {
            bodyBytes = in.readAllBytes();
        }

        var bodyJson = bodyBytes.length == 0 ? JSON.nullNode() : JSON.readTree(bodyBytes);
        return new ApiResponse(statusCode, bodyJson);
    }

    // Package-private for testing
    String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            sb.append((char) c);
        }
        return sb.toString();
    }

    // Package-private for testing
    byte[] readChunkedBody(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        while (true) {
            var sizeLine = readLine(in).trim();
            if (sizeLine.isEmpty()) continue;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
            int chunkSize = Integer.parseInt(sizeLine, 16);
            if (chunkSize == 0) break;
            out.write(in.readNBytes(chunkSize));
            readLine(in);
        }
        return out.toByteArray();
    }
}
