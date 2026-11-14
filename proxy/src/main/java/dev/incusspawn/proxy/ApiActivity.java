package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * What each instance has asked of the Claude Messages API (#898): requests made and in flight,
 * when the last one started and ended, and the tokens their responses report, each instance's
 * counted from when its counters were created, which the snapshot says. Counters belong to one
 * incarnation of a name: an instance destroyed and branched again under it starts afresh (#1063). Served to the host only, on {@code /activity}, so {@code isx mcp} can tell
 * a working agent from a stuck or silently finished one without touching its instance.
 *
 * <p>Only model calls count as model calls -- Claude Code's other traffic to the same domain
 * (settings, telemetry) would make an idle agent look busy. All of an instance's traffic, model
 * calls included, is counted apart, by domain ({@link Flow}, #935), on the same counters, and
 * that of callers that are no known instance under {@code unattributed}. Counters are
 * observations, the only state of the proxy a restart loses: the snapshot says since when each counts.
 */
final class ApiActivity {


    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Counters> byInstance = new ConcurrentHashMap<>();
    private final long startedAt;
    private final Traffic unattributed = new Traffic();
    /** Where each ended model call is recorded (#2241); null records nothing. */
    private volatile EventLog events;

    ApiActivity() {
        this(System::currentTimeMillis);
    }

    ApiActivity(LongSupplier clock) {
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** Records every model call that ends from now on in {@code events}, null for none. */
    void recordTo(EventLog events) {
        this.events = events;
    }

    /**
     * The domain a request to {@code host} is counted under: {@code host} if intercepted, else
     * the closest intercepted domain it is a subdomain of (dnsmasq sends every subdomain here),
     * else {@link ProxyActivity.Traffic#OTHER_DOMAIN}. The Host header is the guest's to write,
     * so only names from the configuration ever become keys.
     */
    static String trafficDomain(String host, Set<String> intercepted) {
        if (host == null) return ProxyActivity.Traffic.OTHER_DOMAIN;
        if (intercepted.contains(host)) return host;
        // Each dot from the left leaves a shorter parent: the first intercepted one is the closest
        for (int dot = host.indexOf('.'); dot >= 0; dot = host.indexOf('.', dot + 1)) {
            var parent = host.substring(dot + 1);
            if (intercepted.contains(parent)) return parent;
        }
        return ProxyActivity.Traffic.OTHER_DOMAIN;
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
        var started = clock.getAsLong();
        return new Exchange(charge(instance, incarnation, view, c -> {
            synchronized (c) {
                c.requests++;
                c.inFlight++;
                c.lastRequestAt = started;
            }
        }), instance, incarnation, started);
    }

    /**
     * Counts a request, or a WebSocket, to {@code domain} ({@link #trafficDomain}) as started,
     * from {@code instance} as {@link #begin} names it, or from a caller that is no known
     * instance when it is null; {@link Flow#end} counts it done.
     */
    Flow flow(String instance, String incarnation, long view, String domain) {
        var flow = pendingFlow(domain);
        flow.begin(instance, incarnation, view);
        return flow;
    }

    /**
     * A request or WebSocket to {@code domain} whose caller is not known yet: {@link Flow#begin}
     * counts it once it is, and it counts as unattributed if it ends first.
     */
    Flow pendingFlow(String domain) {
        return new Flow(domain, false);
    }

    /**
     * A {@link #pendingFlow} for a caller only its credential proof or secret identifies (#1582,
     * #1583): until that is checked it is not counted in flight, and the instance it names is
     * marked identified by proof (#1586).
     */
    Flow proofFlow(String domain) {
        return new Flow(domain, true);
    }

    /**
     * The counters a call from {@code instance} lands on, {@code start} run on them atomically
     * with the prune in {@link #snapshot}, so nothing lands on counters already dropped.
     */
    private Counters charge(String instance, String incarnation, long view, Consumer<Counters> start) {
        var charged = new Counters[1];
        byInstance.compute(instance, (k, existing) -> {
            var kept = kept(existing, incarnation, view);
            // Kept counters of another incarnation are a later view's: the call is a stale one's
            var c = kept.incarnation.equals(incarnation) ? kept : new Counters(clock.getAsLong(), incarnation, view);
            start.accept(c);
            charged[0] = c;
            return kept;
        });
        return charged[0];
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
                    return c.inFlight == 0 && c.traffic.idle() && view >= c.view ? null : c;
                }
            });
        }
        var instances = new HashMap<String, ProxyActivity.Instance>();
        byInstance.forEach((name, c) -> {
            synchronized (c) {
                instances.put(name, new ProxyActivity.Instance(Instant.ofEpochMilli(c.since),
                        c.requests, c.inFlight, instant(c.lastRequestAt), instant(c.lastResponseAt),
                        c.usage.input, c.usage.output, c.usage.cacheRead, c.usage.cacheCreation,
                        c.traffic.read(), c.byProof));
            }
        });
        return new ProxyActivity(instances, Instant.ofEpochMilli(startedAt), unattributed.read());
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
        /** Named by its proof or secret alone at least once ({@link #proofFlow}); only ever set. */
        volatile boolean byProof;
        final Usage usage = new Usage();
        final Traffic traffic = new Traffic();

        Counters(long since, String incarnation, long view) {
            this.since = since;
            this.incarnation = incarnation;
            this.view = view;
        }
    }

    /**
     * One caller's requests, in all and by domain. Guarded by itself: a request starts and ends
     * on the event loop, so this is one short lock each time, never contended for long.
     */
    private static final class Traffic {
        private final Tally total = new Tally(null);
        private final HashMap<String, Tally> byDomain = new HashMap<>();

        synchronized Tally begin(String domain, long now) {
            var tally = byDomain.get(domain);
            if (tally == null) byDomain.put(domain, tally = new Tally(total));
            tally.begin(now);
            return tally;
        }

        synchronized void end(Tally tally, long now, long sent, long received) {
            tally.end(now, sent, received);
        }

        synchronized boolean idle() {
            return total.inFlight == 0;
        }

        synchronized ProxyActivity.Traffic read() {
            var domains = new HashMap<String, ProxyActivity.Counts>();
            byDomain.forEach((domain, tally) -> domains.put(domain, tally.read()));
            return new ProxyActivity.Traffic(total.read(), Map.copyOf(domains));
        }
    }

    /** Counts of one domain, or of all of them ({@code total} null); only under its {@link Traffic}'s lock. */
    private static final class Tally {
        private final Tally total;
        long requests;
        long inFlight;
        long lastActivityAt;
        long requestBytes;
        long responseBytes;

        Tally(Tally total) {
            this.total = total;
        }

        void begin(long now) {
            requests++;
            inFlight++;
            lastActivityAt = now;
            if (total != null) total.begin(now);
        }

        void end(long now, long sent, long received) {
            inFlight--;
            lastActivityAt = now;
            requestBytes += sent;
            responseBytes += received;
            if (total != null) total.end(now, sent, received);
        }

        ProxyActivity.Counts read() {
            return new ProxyActivity.Counts(requests, inFlight, instant(lastActivityAt), requestBytes, responseBytes);
        }
    }

    /**
     * One request or WebSocket. Ends once, however it finishes: answered, refused, or its
     * connection gone. Its body bytes are added as they are known (a WebSocket's frame by
     * frame, a request's at its end) and counted when it ends.
     */
    final class Flow {
        private final String domain;
        /** Its caller is named by proof or secret alone, not by address ({@link #proofFlow}). */
        private final boolean byProof;
        /** Set once by {@link #begin}, under this flow's lock. */
        private Traffic traffic;
        private Tally tally;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicLong requestBytes = new AtomicLong();
        private final AtomicLong responseBytes = new AtomicLong();
        private volatile Exchange call;
        private volatile long endedAt;

        private Flow(String domain, boolean byProof) {
            this.domain = domain;
            this.byProof = byProof;
        }

        /**
         * Counts this flow as started, from {@code instance} as {@link ApiActivity#begin} names it,
         * or from no known instance when it is null. Once: a later call does nothing.
         */
        synchronized void begin(String instance, String incarnation, long view) {
            if (traffic != null) return;
            if (instance == null) {
                traffic = unattributed;
                tally = unattributed.begin(domain, clock.getAsLong());
                return;
            }
            var started = new Tally[1];
            var counters = charge(instance, incarnation, view, c -> {
                started[0] = c.traffic.begin(domain, clock.getAsLong());
                if (byProof && !c.byProof) c.byProof = true;
            });
            traffic = counters.traffic;
            tally = started[0];
        }

        void addRequestBytes(long n) {
            requestBytes.addAndGet(n);
        }

        void addResponseBytes(long n) {
            responseBytes.addAndGet(n);
        }

        /**
         * {@code call} is the model call this request makes, ended by this flow's end on the same
         * reading of the clock (#1281): the response's handlers end them one after the other, and
         * two readings would put the call's response after the last activity of its own domain.
         * A flow already over (its connection gone while the body was read) ends it now.
         */
        void carries(Exchange call) {
            this.call = call;
            // Each side writes before it reads the other's, so at least one ends the call; once only
            var at = endedAt;
            if (at != 0) call.end(at);
        }

        void end() {
            if (!ended.compareAndSet(false, true)) return;
            var now = clock.getAsLong();
            endedAt = now;
            // The call first: a read in between sees the domain still busy, never the call
            var carried = call;
            if (carried != null) {
                carried.end(now);
                call = null;
            }
            synchronized (this) {
                // Ended before anything named its caller: no known instance's
                begin(null, null, 0);
                traffic.end(tally, now, requestBytes.get(), responseBytes.get());
            }
        }
    }

    /** One model call. Ends once, however its response finishes: completed, failed or abandoned. */
    final class Exchange {
        private final Counters counters;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final String instance;
        private final String incarnation;
        private final long startedAt;
        private volatile UsageTap tap;
        private volatile String domain = "";
        private volatile int status;

        private Exchange(Counters counters, String instance, String incarnation, long startedAt) {
            this.counters = counters;
            this.instance = instance;
            this.incarnation = incarnation;
            this.startedAt = startedAt;
        }

        /** The intercepted domain the call goes to, as the event log names it. */
        Exchange to(String domain) {
            this.domain = domain;
            return this;
        }

        /** Reads the usage out of the response about to be relayed, unless it has none to read. */
        void respond(int status, String contentType, String contentEncoding) {
            this.status = status;
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
            end(clock.getAsLong());
        }

        private void end(long now) {
            if (!ended.compareAndSet(false, true)) return;
            var read = tap;
            var usage = read == null ? null : read.finish();
            synchronized (counters) {
                counters.inFlight--;
                counters.lastResponseAt = now;
                if (usage != null) counters.usage.add(usage);
            }
            var log = events;
            if (log != null) {
                var u = usage == null ? new Usage() : usage;
                log.modelCall(new EventLog.ModelCall(Instant.ofEpochMilli(now), instance, incarnation, domain,
                        read == null ? null : read.model(), status, Math.max(0, now - startedAt),
                        u.input, u.output, u.cacheRead, u.cacheCreation));
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
        void merge(JqValue usage) {
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
        private String model;

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

        /** The model the response names, or null; a stream names it in {@code message_start}. */
        String model() {
            return model;
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

        /** A model name is the upstream's, shown beside the guest's: bounded, with no control character. */
        private static String bounded(String name) {
            var b = new StringBuilder();
            name.codePoints().limit(100).forEach(cp -> b.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
            return b.toString();
        }

        private void read(int offset, int length) {
            JqValue node;
            try {
                node = Jjq.parseForeign(java.util.Arrays.copyOfRange(pending, offset, offset + length), "the API event");
            } catch (RuntimeException e) {
                return;
            }
            if (model == null) {
                for (var named : new JqValue[] {node.getField("model"), node.path("message").getField("model")}) {
                    if (named != null && named.isString()) {
                        model = bounded(named.asText());
                        break;
                    }
                }
            }
            for (var candidate : new JqValue[] {node.getField("usage"), node.path("message").getField("usage")}) {
                if (candidate.isObject()) {
                    usage.merge(candidate);
                    seen = true;
                }
            }
        }
    }
}
