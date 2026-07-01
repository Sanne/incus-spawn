package dev.incusspawn.incus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Transport abstraction for the Incus REST API.
 * Decouples the protocol layer from API semantics. Both Linux and macOS use
 * {@link UnixSocketTransport} (a Unix domain socket directly, or the vfkit vsock tunnel
 * exposed as one). {@link HttpsTransport} is no longer wired into connection selection.
 */
interface IncusTransport {

    record RawResponse(int statusCode, byte[] body) {
        boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
    }

    /**
     * Execute an HTTP request and return the raw response.
     * body must not be null (use empty array for no body).
     */
    RawResponse request(String method, String path,
                        String contentType, Map<String, String> extraHeaders,
                        byte[] body) throws IOException;

    /**
     * Execute an HTTP request with a per-request timeout override.
     * Implementations that support timeouts use this instead of their default.
     */
    default RawResponse request(String method, String path,
                                String contentType, Map<String, String> extraHeaders,
                                byte[] body, int timeoutSeconds) throws IOException {
        return request(method, path, contentType, extraHeaders, body);
    }

    /**
     * Like {@link #request(String, String, String, Map, byte[], int)} but may reuse a pooled
     * keep-alive connection for the request path. Implementations without their own pooling
     * (UnixSocketTransport) override this; those that already pool (HttpsTransport via the JDK
     * HttpClient) inherit the default, which just delegates to request().
     */
    default RawResponse requestPooled(String method, String path,
                                      String contentType, Map<String, String> extraHeaders,
                                      byte[] body, int timeoutSeconds) throws IOException {
        return request(method, path, contentType, extraHeaders, body, timeoutSeconds);
    }

    /** Pooled request using the transport's own default timeout. */
    default RawResponse requestPooled(String method, String path,
                                      String contentType, Map<String, String> extraHeaders,
                                      byte[] body) throws IOException {
        return request(method, path, contentType, extraHeaders, body);
    }

    /**
     * Execute an HTTP request with body streamed from a file.
     * Implementations must stream the content to avoid loading it entirely into memory.
     */
    RawResponse request(String method, String path,
                        String contentType, Map<String, String> extraHeaders,
                        Path bodyFile) throws IOException;

    /**
     * Open a WebSocket connection. wsPath must include the full path and query string,
     * e.g. "/1.0/operations/{id}/websocket?secret={secret}".
     */
    WsConnection openWebSocket(String wsPath) throws IOException;

    /** A bidirectional WebSocket connection. */
    interface WsConnection extends AutoCloseable {
        /** Block until next data payload. Returns null on close/EOF. */
        byte[] readPayload() throws IOException;
        /** Send binary data to the server. */
        void sendData(byte[] data, int offset, int length) throws IOException;
        /** Send a WebSocket ping frame (keepalive). */
        void sendPing() throws IOException;
        /** Send a WebSocket close frame. */
        void sendClose() throws IOException;
        @Override
        void close(); // best-effort, no throw
    }
}
