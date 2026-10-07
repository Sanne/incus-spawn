package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * What each instance has asked of the Claude Messages API (#898): requests made and in flight,
 * when the last one started and ended, and the tokens their responses report, each instance's
 * counted from when its counters were created, which the snapshot says. Counters belong to one
 * incarnation of a name: an instance destroyed and branched again under it starts afresh (#1063). Served to the host only, on {@code /activity}, so {@code isx mcp} can tell
 * a working agent from a stuck or silently finished one without touching its instance.
 *
 * <p>Only model calls count -- Claude Code's other traffic to the same domain (settings,
 * telemetry) would make an idle agent look busy.
 */
final class ApiActivity {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Counters> byInstance = new ConcurrentHashMap<>();

    ApiActivity() {
        this(System::currentTimeMillis);
    }

    ApiActivity(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Whether a request to an Anthropic domain is a model call: a Messages API create, or
     * its Vertex form. Token counting and batches are not.
     */
    static boolean isModelCall(String path) {
        if (path == null) return false;
        if (path.equals("/v1/messages")) return true;
        return path.startsWith("/v1/projects/") && !path.contains("count-tokens")
                && (path.endsWith(":rawPredict") || path.endsWith(":streamRawPredict"));
    }

    /**
     * Counts a model call from {@code instance} as started; {@link Exchange#end()} counts it done.
     * {@code incarnation} tells which instance of that name made it ({@code created_at}, empty
     * when the listing lacks it), read in the same lookup as the name, from the registry's
     * listing {@code view} ({@code InstanceRegistry.resolve}).
     */
    Exchange begin(String instance, String incarnation, long view) {
        var charged = new Counters[1];
        // Atomic per key with the prune in snapshot(): a call never lands on dropped counters
        byInstance.compute(instance, (k, existing) -> {
            var kept = kept(existing, incarnation, view);
            // Kept counters of another incarnation are a later view's: the call is a stale one's
            var c = kept.incarnation.equals(incarnation) ? kept : new Counters(clock.getAsLong(), incarnation, view);
            synchronized (c) {
                c.requests++;
                c.inFlight++;
                c.lastRequestAt = clock.getAsLong();
            }
            charged[0] = c;
            return kept;
        });
        return new Exchange(charged[0]);
    }

    /**
     * The counters of every instance {@code known} names (to its incarnation), made now for one
     * that has none or has another incarnation's, so a known instance always reports when its
     * counting started. Counters of an instance
     * {@code known} no longer names are dropped once nothing of it is in flight, so those of
     * destroyed instances do not pile up. Dropping is never silent: counters made again carry a
     * later {@code since}, so a client subtracting two reads can tell they do not count from the
     * same start.
     */
    ProxyActivity snapshot(Map<String, String> known, long view) {
        known.forEach((name, incarnation) -> byInstance.compute(name, (k, c) -> kept(c, incarnation, view)));
        for (var name : byInstance.keySet()) {
            if (known.containsKey(name)) continue;
            byInstance.computeIfPresent(name, (k, c) -> {
                synchronized (c) {
                    // Not a read from a listing older than the one that made them
                    return c.inFlight == 0 && view >= c.view ? null : c;
                }
            });
        }
        var instances = new java.util.HashMap<String, ProxyActivity.Instance>();
        byInstance.forEach((name, c) -> {
            synchronized (c) {
                instances.put(name, new ProxyActivity.Instance(Instant.ofEpochMilli(c.since),
                        c.requests, c.inFlight, instant(c.lastRequestAt), instant(c.lastResponseAt),
                        c.usage.input, c.usage.output, c.usage.cacheRead, c.usage.cacheCreation));
            }
        });
        return new ProxyActivity(instances);
    }

    /**
     * The counters a name keeps after a call or read from listing {@code view} says it is
     * {@code incarnation}.
     *
     * <p>Another incarnation from a later view than the counters were last confirmed by gets
     * fresh counters, so a destroyed instance's are never carried on by the next of its name
     * (calls it still has in flight end on counters nothing reports any more). One from an
     * earlier view -- a call identified, or a read listed, before the name changed hands -- keeps
     * them, and {@link #begin} charges its call to counters nothing reports, or two views would
     * reset them in turn. Views are ordered by when the registry listed, never by {@code created_at}: a rename
     * hands a name to an instance created before the one that had it. Call only inside
     * {@code byInstance.compute} for the name.
     */
    private Counters kept(Counters existing, String incarnation, long view) {
        if (existing == null) return new Counters(clock.getAsLong(), incarnation, view);
        if (existing.incarnation.equals(incarnation)) {
            existing.view = Math.max(existing.view, view);
            return existing;
        }
        return view <= existing.view ? existing : new Counters(clock.getAsLong(), incarnation, view);
    }

    private static Instant instant(long millis) {
        return millis > 0 ? Instant.ofEpochMilli(millis) : null;
    }

    private static final class Counters {
        final long since;
        final String incarnation;
        /** The latest listing view that confirmed {@link #incarnation}; only under the map's lock. */
        long view;
        long requests;
        long inFlight;
        long lastRequestAt;
        long lastResponseAt;
        final Usage usage = new Usage();

        Counters(long since, String incarnation, long view) {
            this.since = since;
            this.incarnation = incarnation;
            this.view = view;
        }
    }

    /** One model call. Ends once, however its response finishes: completed, failed or abandoned. */
    final class Exchange {
        private final Counters counters;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile UsageTap tap;

        private Exchange(Counters counters) {
            this.counters = counters;
        }

        /** Reads the usage out of the response about to be relayed, unless it has none to read. */
        void respond(int status, String contentType, String contentEncoding) {
            if (status < 200 || status >= 300) return;
            // Asked for identity (MitmProxy.sendApiRequest); an upstream that compressed anyway is not read
            if (contentEncoding != null && !contentEncoding.isBlank() && !"identity".equalsIgnoreCase(contentEncoding)) {
                return;
            }
            tap = new UsageTap(contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream"));
        }

        /** One chunk of the response being relayed. */
        void accept(Buffer chunk) {
            var read = tap;
            if (read != null) read.accept(chunk);
        }

        void end() {
            if (!ended.compareAndSet(false, true)) return;
            var read = tap;
            var usage = read == null ? null : read.finish();
            synchronized (counters) {
                counters.inFlight--;
                counters.lastResponseAt = clock.getAsLong();
                if (usage != null) counters.usage.add(usage);
            }
        }
    }

    /** Token counts as the Messages API names them. */
    static final class Usage {
        long input;
        long output;
        long cacheRead;
        long cacheCreation;

        /** Streamed counts are cumulative, so each field keeps the largest value seen. */
        void merge(JsonNode usage) {
            if (usage == null || !usage.isObject()) return;
            input = Math.max(input, usage.path("input_tokens").asLong(0));
            output = Math.max(output, usage.path("output_tokens").asLong(0));
            cacheRead = Math.max(cacheRead, usage.path("cache_read_input_tokens").asLong(0));
            cacheCreation = Math.max(cacheCreation, usage.path("cache_creation_input_tokens").asLong(0));
        }

        void add(Usage other) {
            input += other.input;
            output += other.output;
            cacheRead += other.cacheRead;
            cacheCreation += other.cacheCreation;
        }
    }

    /**
     * Reads {@code usage} from a response as it streams past, holding at most one event line
     * (or, for a plain JSON answer, the bounded body): a stream carries it in
     * {@code message_start} and, cumulatively, in {@code message_delta}. Runs on the event loop
     * for every chunk relayed, so a line is only decoded when it carries a usage.
     */
    static final class UsageTap {
        static final int MAX_LINE = 256 * 1024;
        static final int MAX_BODY = 4 * 1024 * 1024;
        private static final byte[] DATA = "data:".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] USAGE = "\"usage\"".getBytes(StandardCharsets.US_ASCII);

        private final boolean eventStream;
        private final int limit;
        private byte[] pending = new byte[1024];
        private int count;
        private final Usage usage = new Usage();
        private boolean overflow;
        private boolean seen;

        UsageTap(boolean eventStream) {
            this.eventStream = eventStream;
            this.limit = eventStream ? MAX_LINE : MAX_BODY;
        }

        void accept(Buffer chunk) {
            int from = 0;
            int length = chunk.length();
            if (eventStream) {
                for (int i = 0; i < length; i++) {
                    if (chunk.getByte(i) != '\n') continue;
                    append(chunk, from, i);
                    line();
                    from = i + 1;
                }
            }
            append(chunk, from, length);
        }

        /** The usage read, or null if the response carried none this could read. */
        Usage finish() {
            if (eventStream) {
                line();
            } else if (!overflow && count > 0) {
                read(0, count);
            }
            count = 0;
            return seen ? usage : null;
        }

        private void append(Buffer chunk, int from, int to) {
            int length = to - from;
            if (overflow || length == 0) return;
            if (count + length > limit) {
                overflow = true;
                count = 0;
                return;
            }
            if (count + length > pending.length) {
                pending = java.util.Arrays.copyOf(pending, Math.min(limit, Math.max(count + length, pending.length * 2)));
            }
            chunk.getBytes(from, to, pending, count);
            count += length;
        }

        private void line() {
            var whole = !overflow;
            overflow = false;
            if (whole && startsWith(DATA) && contains(USAGE)) read(DATA.length, count - DATA.length);
            count = 0;
        }

        private boolean startsWith(byte[] prefix) {
            return count >= prefix.length && java.util.Arrays.equals(pending, 0, prefix.length, prefix, 0, prefix.length);
        }

        private boolean contains(byte[] needle) {
            outer:
            for (int i = 0; i + needle.length <= count; i++) {
                for (int k = 0; k < needle.length; k++) {
                    if (pending[i + k] != needle[k]) continue outer;
                }
                return true;
            }
            return false;
        }

        private void read(int offset, int length) {
            JsonNode node;
            try {
                node = JSON.readTree(pending, offset, length);
            } catch (java.io.IOException e) {
                return;
            }
            if (node == null) return;
            for (var candidate : new JsonNode[] {node.get("usage"), node.path("message").get("usage")}) {
                if (candidate != null && candidate.isObject()) {
                    usage.merge(candidate);
                    seen = true;
                }
            }
        }
    }
}
