package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes per-exchange debug log files for API traffic inspection.
 * Each HTTP exchange (request + response) gets its own numbered file.
 */
public class ApiTrafficLog {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_BODY_CAPTURE = 65536;

    private final Path logDir;
    private final AtomicInteger counter = new AtomicInteger();

    public ApiTrafficLog(Path logDir) throws IOException {
        this.logDir = logDir;
        Files.createDirectories(logDir);
    }

    public Path logDir() {
        return logDir;
    }

    /**
     * Log a complete API exchange to a numbered file.
     *
     * @param originalRequest  request line + headers before proxy modification
     * @param originalBody     request body before proxy modification (may be null)
     * @param forwardedRequest request line + headers as sent to upstream (null if unchanged)
     * @param forwardedBody    request body as sent to upstream (null if unchanged)
     * @param responseDump     response status line + headers
     * @param responseBody     response body (null if streamed/too large)
     */
    public void logExchange(String originalRequest, byte[] originalBody,
                            String forwardedRequest, byte[] forwardedBody,
                            String responseDump, byte[] responseBody) {
        try {
            var index = counter.incrementAndGet();
            var firstLine = originalRequest.lines().findFirst().orElse("UNKNOWN");
            var parts = firstLine.split(" ", 3);
            var method = parts.length > 0 ? parts[0] : "UNKNOWN";
            var path = parts.length > 1 ? sanitize(parts[1]) : "unknown";

            var filename = String.format("%03d-%s-%s.log", index, method, path);
            var sb = new StringBuilder();

            sb.append(">>> REQUEST\n");
            sb.append(originalRequest);
            appendBody(sb, originalBody);

            if (forwardedRequest != null) {
                sb.append("\n>>> FORWARDED AS\n");
                sb.append(forwardedRequest);
                appendBody(sb, forwardedBody);
            }

            sb.append("\n<<< RESPONSE\n");
            sb.append(responseDump);
            appendBody(sb, responseBody);

            Files.writeString(logDir.resolve(filename), sb.toString());
            System.err.println("[debug] " + method + " " + (parts.length > 1 ? parts[1] : "") +
                    " -> " + filename);
        } catch (IOException e) {
            System.err.println("Warning: failed to write debug log: " + e.getMessage());
        }
    }

    /**
     * Read a small response body into memory for logging while relaying to the client.
     * Returns the captured bytes if Content-Length <= {@link #MAX_BODY_CAPTURE},
     * or null if the body is too large or chunked/streamed.
     * When non-null is returned, the caller must write those bytes to the client
     * (they have already been consumed from upstreamIn).
     */
    public static byte[] captureSmallResponseBody(HttpMessage response, InputStream upstreamIn)
            throws IOException {
        var cl = response.header("Content-Length");
        if (cl == null) return null;
        long length = Long.parseLong(cl.trim());
        if (length > MAX_BODY_CAPTURE) return null;

        var body = new byte[(int) length];
        int offset = 0;
        while (offset < body.length) {
            int n = upstreamIn.read(body, offset, body.length - offset);
            if (n == -1) break;
            offset += n;
        }
        return body;
    }

    private void appendBody(StringBuilder sb, byte[] body) {
        if (body == null || body.length == 0) return;
        sb.append('\n');
        sb.append(formatBody(body));
        sb.append('\n');
    }

    private String formatBody(byte[] body) {
        try {
            var tree = JSON.readTree(body);
            return JSON.writeValueAsString(tree);
        } catch (Exception e) {
            return new String(body, 0, Math.min(body.length, 4096));
        }
    }

    private static String sanitize(String path) {
        var s = path.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.substring(0, Math.min(s.length(), 50));
    }
}
