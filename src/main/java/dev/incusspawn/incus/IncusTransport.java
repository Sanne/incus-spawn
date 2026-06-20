package dev.incusspawn.incus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Transport abstraction for the Incus REST API.
 * Decouples the protocol layer (Unix socket, HTTPS) from API semantics.
 * Linux uses UnixSocketTransport; macOS uses HttpsTransport.
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
