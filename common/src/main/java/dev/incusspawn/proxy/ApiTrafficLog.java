package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Writes per-exchange debug log files for API traffic inspection.
 * Each HTTP exchange (request + response) gets its own numbered file.
 */
public class ApiTrafficLog {
    private static final Pattern UNSAFE_FILENAME_CHAR = Pattern.compile("[^a-zA-Z0-9._-]");

    /** The most of one body a log holds: a body is never held whole to be logged (#1315). */
    static final int MAX_BODY_CAPTURE = 65536;
    /** How much of a body that is not JSON, or was cut, the log shows. */
    private static final int DISPLAYED_RAW_BYTES = 4096;

    /**
     * The first {@link #MAX_BODY_CAPTURE} bytes of a body, taken as it passes, and how long it was.
     * Fed and read on the exchange's own event loop.
     */
    public static final class Capture {
        /** Copies {@code [start, end)} of a chunk into {@code dst} at {@code dstIndex}, as Vert.x's {@code Buffer.getBytes} does. */
        @FunctionalInterface
        public interface Chunk {
            void copyTo(int start, int end, byte[] dst, int dstIndex);
        }

        private byte[] prefix;
        private int size;
        private long total;

        /** All of {@code body}, uncut: for a caller that holds it whole anyway, as {@code isx proxy dump} does. */
        public static Capture whole(byte[] body) {
            var capture = new Capture();
            capture.prefix = body;
            capture.size = body.length;
            capture.total = body.length;
            return capture;
        }

        /** The first {@link #MAX_BODY_CAPTURE} bytes of {@code body}. */
        public static Capture of(byte[] body) {
            var capture = new Capture();
            capture.accept(body.length, (start, end, dst, dstIndex) -> System.arraycopy(body, start, dst, dstIndex, end - start));
            return capture;
        }

        /** A chunk of {@code length} bytes passed: its start is kept while there is room. */
        public void accept(int length, Chunk chunk) {
            total += length;
            var take = Math.min(length, (prefix == null ? MAX_BODY_CAPTURE : prefix.length) - size);
            if (take <= 0) return;
            if (prefix == null) prefix = new byte[MAX_BODY_CAPTURE];
            chunk.copyTo(0, take, prefix, size);
            size += take;
        }

        byte[] prefix() {
            return prefix == null ? new byte[0] : java.util.Arrays.copyOf(prefix, size);
        }

        long total() {
            return total;
        }
    }

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
        logExchange(originalRequest, captured(originalBody), forwardedRequest, captured(forwardedBody),
                responseDump, captured(responseBody));
    }

    private static Capture captured(byte[] body) {
        return body == null ? null : Capture.whole(body);
    }

    /** As above, with bodies captured as they passed; a body cut at the capture says so. */
    public void logExchange(String originalRequest, Capture originalBody,
                            String forwardedRequest, Capture forwardedBody,
                            String responseDump, Capture responseBody) {
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

            if (forwardedRequest != null || forwardedBody != null) {
                sb.append("\n>>> FORWARDED AS\n");
                if (forwardedRequest != null) sb.append(forwardedRequest);
                appendBody(sb, forwardedBody);
            }

            sb.append("\n<<< RESPONSE\n");
            sb.append(responseDump);
            appendBody(sb, responseBody);

            Files.writeString(logDir.resolve(filename), sb.toString());
            BuildOutput.note("[debug] " + method + " " + (parts.length > 1 ? parts[1] : "") + " -> " + filename);
        } catch (IOException e) {
            BuildOutput.warn("failed to write debug log: " + e.getMessage());
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

    private void appendBody(StringBuilder sb, Capture body) {
        if (body == null || body.total() == 0) return;
        var prefix = body.prefix();
        sb.append('\n');
        if (prefix.length < body.total()) {
            sb.append("[captured ").append(prefix.length).append(" of ").append(body.total()).append(" bytes]\n");
            // As much as an uncut body that is not JSON shows
            sb.append(new String(prefix, 0, Math.min(prefix.length, DISPLAYED_RAW_BYTES)));
        } else {
            sb.append(formatBody(prefix));
        }
        sb.append('\n');
    }

    private String formatBody(byte[] body) {
        try {
            return JqValues.toPrettyJsonString(Jjq.parseForeign(body, "the logged body"));
        } catch (Exception e) {
            return new String(body, 0, Math.min(body.length, DISPLAYED_RAW_BYTES));
        }
    }

    private static String sanitize(String path) {
        var s = UNSAFE_FILENAME_CHAR.matcher(path).replaceAll("_");
        return s.substring(0, Math.min(s.length(), 50));
    }
}
