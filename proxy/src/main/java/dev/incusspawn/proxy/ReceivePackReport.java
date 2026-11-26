package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The report-status of a {@code git-receive-pack} answer (#2266), read as the answer streams past:
 * which refs GitHub says it updated. GitHub answers a push with 200 even when it refuses a branch
 * (non-fast-forward, a protected branch), and says so per ref in this report, so the HTTP status
 * alone cannot tell a branch pushed from one refused.
 *
 * <p>The answer is pkt-lines. With side-band (git asks for {@code side-band-64k}) each pkt-line's
 * payload starts with its band: band 1 carries the report, itself pkt-lines, band 2 progress and
 * band 3 an error. Without it the answer is the report. Only the report's bytes are kept, at most
 * {@link #MAX_REPORT} of them, so a long progress stream costs nothing; whatever is cut off or is
 * not pkt-lines reports no ref.
 */
final class ReceivePackReport {

    static final int MAX_REPORT = 16 * 1024;

    private enum Mode { UNKNOWN, SIDEBAND, PLAIN, DONE }

    private Mode mode = Mode.UNKNOWN;
    private final byte[] header = new byte[4];
    private int headerLength;
    private int remaining;
    private boolean atPayloadStart;
    private boolean inReport;
    private final byte[] report = new byte[MAX_REPORT];
    private int reportLength;

    /** One chunk of the answer as it is relayed. */
    void feed(Buffer chunk) {
        var at = 0;
        var length = chunk.length();
        while (at < length && mode != Mode.DONE) {
            if (mode == Mode.PLAIN) {
                keep(chunk, at, length - at);
                return;
            }
            if (remaining == 0) {
                header[headerLength++] = chunk.getByte(at++);
                if (headerLength < 4) continue;
                headerLength = 0;
                var size = hex4(header, 0);
                // a flush ends the answer, and a pkt-line too short to hold a band is not one
                if (size < 5) { mode = Mode.DONE; return; }
                remaining = size - 4;
                atPayloadStart = true;
                continue;
            }
            if (atPayloadStart) {
                atPayloadStart = false;
                var band = chunk.getByte(at);
                if (mode == Mode.UNKNOWN) {
                    if (band < 1 || band > 3) {
                        mode = Mode.PLAIN;
                        keep(Buffer.buffer(header), 0, 4);
                        continue;
                    }
                    mode = Mode.SIDEBAND;
                }
                inReport = band == 1;
                at++;
                remaining--;
                continue;
            }
            var take = Math.min(remaining, length - at);
            if (inReport) keep(chunk, at, take);
            at += take;
            remaining -= take;
        }
    }

    private void keep(Buffer chunk, int from, int count) {
        var take = Math.min(count, report.length - reportLength);
        if (take <= 0) return;
        chunk.getBytes(from, from + take, report, reportLength);
        reportLength += take;
    }

    /**
     * The refs the report says were updated ({@code ok <ref>}), each whole; empty when no report
     * was read. A ref refused ({@code ng <ref> <why>}) is not among them.
     */
    Set<String> updated() {
        var ok = new HashSet<String>();
        var at = 0;
        while (at + 4 <= reportLength) {
            var size = hex4(report, at);
            if (size <= 4 || at + size > reportLength) break;
            var line = new String(report, at + 4, size - 4, StandardCharsets.UTF_8);
            if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
            if (line.startsWith("ok ") && line.length() > 3) ok.add(line.substring(3));
            at += size;
        }
        return ok;
    }

    private static int hex4(byte[] buf, int at) {
        var n = 0;
        for (var i = at; i < at + 4; i++) {
            var d = Character.digit(buf[i], 16);
            if (d < 0) return -1;
            n = n * 16 + d;
        }
        return n;
    }
}
