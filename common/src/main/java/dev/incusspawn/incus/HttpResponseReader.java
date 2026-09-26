package dev.incusspawn.incus;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The one HTTP/1.1 response parser for the Unix-socket transports, shared by the one-shot path
 * ({@link UnixSocketTransport}), the keep-alive path ({@link KeepAliveConnection}) and the
 * WebSocket handshake.
 *
 * It is strict about EOF on purpose. The macOS vsock tunnel drops connections mid-exchange, and
 * a parser that reads EOF as "end of line" or "end of headers" turns a lost response into a
 * smaller successful one: a 200 with an empty body, a WebSocket that "upgraded" on a dead
 * socket, or a chunked read that spins until the watchdog fires. Every early EOF is an
 * {@link EOFException} here; only a response framed by connection close may end at EOF.
 */
final class HttpResponseReader {

    private HttpResponseReader() {}

    /** The connection closed (or was reset) before the first byte of the status line. */
    static final class NoResponseException extends EOFException {
        NoResponseException(String message) { super(message); }
    }

    /**
     * Status line and the headers the transports act on.
     * @param closeDelimited no length framing: the body runs until the server closes
     */
    record Head(int statusCode, int contentLength, boolean chunked, boolean connectionClose) {
        boolean closeDelimited() { return !chunked && contentLength < 0; }
    }

    record Response(int statusCode, byte[] body, boolean reusable) {}

    /** Read one full response. {@code reusable} is false if the server will close the connection. */
    static Response read(InputStream in) throws IOException {
        var head = readHead(in);
        byte[] body;
        if (head.chunked()) {
            body = readChunkedBody(in);
        } else if (head.contentLength() >= 0) {
            body = in.readNBytes(head.contentLength());
            if (body.length < head.contentLength()) {
                throw new EOFException("truncated response body (" + body.length + "/" + head.contentLength() + ")");
            }
        } else {
            body = in.readAllBytes();
        }
        return new Response(head.statusCode(), body, !head.connectionClose() && !head.closeDelimited());
    }

    /** Read the status line and headers, stopping after the blank line. */
    static Head readHead(InputStream in) throws IOException {
        String statusLine;
        try {
            statusLine = readLine(in);
        } catch (java.net.SocketException e) {
            // A reset before any response byte means the same as a close: nothing was answered.
            throw new NoResponseException("connection reset before the response: " + e.getMessage());
        }
        if (statusLine == null) throw new NoResponseException("connection closed before the response");
        var parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Invalid HTTP status line: " + statusLine);
        }
        int statusCode = parseInt(parts[1], 10, "status code");

        int contentLength = -1;
        boolean chunked = false;
        boolean close = false;
        String line;
        while (!(line = requireLine(in, "headers")).isEmpty()) {
            var lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                contentLength = parseInt(lower.substring(15).trim(), 10, "Content-Length");
                if (contentLength < 0) throw new IOException("Invalid Content-Length: " + line);
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            } else if (lower.startsWith("connection:") && lower.contains("close")) {
                close = true;
            }
        }
        return new Head(statusCode, contentLength, chunked, close);
    }

    /**
     * Read a CRLF- or LF-terminated line without the terminator. Returns null on EOF before any
     * byte; EOF partway through a line is an {@link EOFException}, never a short line.
     */
    static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c = in.read();
        if (c == -1) return null;
        while (c != '\n') {
            if (c == -1) throw new EOFException("connection closed mid-line: " + sb);
            if (c != '\r') sb.append((char) c);
            c = in.read();
        }
        return sb.toString();
    }

    static byte[] readChunkedBody(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        while (true) {
            var sizeLine = requireLine(in, "chunked body").trim();
            if (sizeLine.isEmpty()) continue;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
            int chunkSize = parseInt(sizeLine, 16, "chunk size");
            if (chunkSize < 0) throw new IOException("Invalid chunk size: " + sizeLine);
            if (chunkSize == 0) {
                // Consume the trailer section (possibly empty) up to the terminating blank
                // line, so a keep-alive connection is positioned at the next response.
                while (!requireLine(in, "chunked trailer").isEmpty()) { /* skip trailers */ }
                break;
            }
            var chunk = in.readNBytes(chunkSize);
            if (chunk.length < chunkSize) {
                throw new EOFException("truncated chunk (" + chunk.length + "/" + chunkSize + ")");
            }
            buf.write(chunk);
            requireLine(in, "chunked body"); // trailing CRLF
        }
        return buf.toByteArray();
    }

    private static String requireLine(InputStream in, String where) throws IOException {
        var line = readLine(in);
        if (line == null) throw new EOFException("connection closed in the response " + where);
        return line;
    }

    private static int parseInt(String s, int radix, String what) throws IOException {
        try {
            return Integer.parseInt(s, radix);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + what + ": " + s);
        }
    }
}
