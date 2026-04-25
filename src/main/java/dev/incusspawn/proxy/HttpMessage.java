package dev.incusspawn.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP/1.1 request parser and serializer for the MITM proxy.
 * <p>
 * Handles reading request lines and headers, injecting/replacing headers,
 * and relaying request/response bodies with proper Content-Length and
 * chunked transfer encoding support. SSE streaming responses are relayed
 * with immediate flushing.
 */
public class HttpMessage {

    static final int BUFFER_SIZE = 64 * 1024;

    private String requestLine;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();

    private HttpMessage() {}

    /**
     * Read an HTTP/1.1 request line and headers from the stream.
     * Does NOT read the body — call {@link #relayRequestBody} for that.
     *
     * @return the parsed message, or null if the stream is closed
     */
    public static HttpMessage readRequest(InputStream in) throws IOException {
        var msg = new HttpMessage();
        msg.requestLine = readLine(in);
        if (msg.requestLine == null || msg.requestLine.isEmpty()) {
            return null;
        }

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            var colon = line.indexOf(':');
            if (colon < 0) continue;
            var name = line.substring(0, colon).trim();
            var value = line.substring(colon + 1).trim();
            msg.headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        return msg;
    }

    public String requestLine() {
        return requestLine;
    }

    /**
     * Get the first value for a header (case-insensitive lookup).
     */
    public String header(String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                var values = entry.getValue();
                return values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    /**
     * Set a header, replacing any existing values (case-insensitive match).
     */
    public void setHeader(String name, String value) {
        // Remove any existing header with the same name (case-insensitive)
        headers.entrySet().removeIf(e -> e.getKey().equalsIgnoreCase(name));
        var list = new ArrayList<String>();
        list.add(value);
        headers.put(name, list);
    }

    /**
     * Remove a header (case-insensitive match).
     */
    public void removeHeader(String name) {
        headers.entrySet().removeIf(e -> e.getKey().equalsIgnoreCase(name));
    }

    /**
     * Read an HTTP/1.1 response status line and headers from the stream.
     * Same wire format as a request — just the first line is a status line
     * instead of a request line.
     */
    public static HttpMessage readResponse(InputStream in) throws IOException {
        return readRequest(in);
    }

    /**
     * Relay the response body from the stream based on Content-Length,
     * chunked transfer encoding, or until EOF.
     * Call after {@link #readResponse} has consumed the status line and headers.
     */
    public void relayResponseBody(InputStream in, OutputStream out) throws IOException {
        var cl = header("Content-Length");
        var te = header("Transfer-Encoding");
        if (cl != null) {
            relayFixedLength(in, out, Long.parseLong(cl.trim()));
        } else if (te != null && te.toLowerCase().contains("chunked")) {
            relayChunked(in, out);
        } else {
            relayUntilEof(in, out);
        }
    }

    /**
     * Extract the HTTP method from the request line (e.g. "GET", "POST").
     */
    public String method() {
        if (requestLine == null) return null;
        var space = requestLine.indexOf(' ');
        return space > 0 ? requestLine.substring(0, space) : requestLine;
    }

    /**
     * Extract the request path (e.g. "/v2/library/postgres/blobs/sha256:abc").
     */
    public String path() {
        if (requestLine == null) return null;
        var firstSpace = requestLine.indexOf(' ');
        var lastSpace = requestLine.lastIndexOf(' ');
        if (firstSpace > 0 && lastSpace > firstSpace) {
            return requestLine.substring(firstSpace + 1, lastSpace);
        }
        return null;
    }

    /**
     * Extract the HTTP status code from a response status line (e.g. 200, 307).
     */
    public int statusCode() {
        if (requestLine == null) return -1;
        var parts = requestLine.split("\\s+", 3);
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    /**
     * Extract the Host header value, stripping any port suffix.
     */
    public String host() {
        var h = header("Host");
        if (h == null) return null;
        // Strip port (e.g. "api.github.com:443" -> "api.github.com")
        var colon = h.indexOf(':');
        return colon > 0 ? h.substring(0, colon) : h;
    }

    /**
     * Format the request/status line and all headers as a string (for debug logging).
     */
    public String dump() {
        var sb = new StringBuilder();
        sb.append(requestLine).append('\n');
        for (var entry : headers.entrySet()) {
            for (var value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Write the request line and headers to the output stream.
     * Does NOT write the body.
     */
    public void writeTo(OutputStream out) throws IOException {
        write(out, requestLine + "\r\n");
        for (var entry : headers.entrySet()) {
            for (var value : entry.getValue()) {
                write(out, entry.getKey() + ": " + value + "\r\n");
            }
        }
        write(out, "\r\n");
        out.flush();
    }

    /**
     * Relay the request body from client to upstream based on Content-Length
     * or chunked transfer encoding.
     */
    public void relayRequestBody(InputStream clientIn, OutputStream upstreamOut) throws IOException {
        var contentLength = header("Content-Length");
        var transferEncoding = header("Transfer-Encoding");

        if (contentLength != null) {
            relayFixedLength(clientIn, upstreamOut, Long.parseLong(contentLength.trim()));
        } else if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            relayChunked(clientIn, upstreamOut);
        }
        // No body (GET, HEAD, etc.) — nothing to relay
    }

    /**
     * Read the full request body into a byte array based on Content-Length.
     * Used when the proxy needs to inspect the body (e.g. to extract the model
     * name for Vertex AI translation).
     *
     * @return the body bytes, or empty array if no body
     */
    public byte[] readRequestBody(InputStream clientIn) throws IOException {
        var contentLength = header("Content-Length");
        if (contentLength == null) return new byte[0];

        int length = Integer.parseInt(contentLength.trim());
        var body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = clientIn.read(body, offset, length - offset);
            if (n == -1) break;
            offset += n;
        }
        return body;
    }

    /**
     * Replace the request line path component.
     * E.g. "POST /v1/messages HTTP/1.1" -> "POST /new/path HTTP/1.1"
     */
    public void setPath(String newPath) {
        if (requestLine == null) return;
        var firstSpace = requestLine.indexOf(' ');
        var lastSpace = requestLine.lastIndexOf(' ');
        if (firstSpace > 0 && lastSpace > firstSpace) {
            requestLine = requestLine.substring(0, firstSpace + 1) + newPath +
                    requestLine.substring(lastSpace);
        }
    }

    // --- Relay helpers ---

    private static void relayFixedLength(InputStream in, OutputStream out, long length) throws IOException {
        var buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n == -1) break;
            out.write(buffer, 0, n);
            out.flush();
            remaining -= n;
        }
    }

    private static void relayChunked(InputStream in, OutputStream out) throws IOException {
        // Relay raw chunked encoding: read and forward chunk headers + data
        while (true) {
            var chunkHeader = readLine(in);
            if (chunkHeader == null) break;
            write(out, chunkHeader + "\r\n");
            out.flush();

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(chunkHeader.trim().split(";")[0], 16);
            } catch (NumberFormatException e) {
                break;
            }

            if (chunkSize == 0) {
                // Terminal chunk — read and forward trailing CRLF
                var trailer = readLine(in);
                if (trailer != null) {
                    write(out, trailer + "\r\n");
                }
                out.flush();
                break;
            }

            // Relay chunk data
            relayFixedLength(in, out, chunkSize);
            // Read trailing CRLF after chunk data
            var crlf = readLine(in);
            if (crlf != null) {
                write(out, crlf + "\r\n");
            }
            out.flush();
        }
    }

    static void relayUntilEof(InputStream in, OutputStream out) throws IOException {
        var buffer = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            out.flush();
        }
    }

    // --- Low-level I/O ---

    static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next == '\n') break;
                sb.append((char) c);
                if (next != -1) sb.append((char) next);
            } else if (c == '\n') {
                break;
            } else {
                sb.append((char) c);
            }
        }
        return sb.isEmpty() && c == -1 ? null : sb.toString();
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes());
    }
}
