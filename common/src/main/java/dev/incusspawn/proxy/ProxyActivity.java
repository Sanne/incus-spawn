package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The proxy's {@code /activity} (#898): each instance's Claude model calls -- requests made and
 * in flight, when the last started and ended, and the tokens their responses reported -- and all
 * of its traffic through the proxy, by domain (#935). Traffic from no instance the proxy knows
 * (the host, or an address it has not listed) is counted apart, as {@code unattributed}, from
 * {@code unattributedSince}. How well the proxy tells its callers apart is its {@link Placement}
 * (#1586). Only the host is answered. Fields are only ever added.
 */
public record ProxyActivity(Map<String, Instance> instances, Instant unattributedSince, Traffic unattributed,
                            Placement placement, Map<String, Throttle> throttled) {

    public ProxyActivity(Map<String, Instance> instances, Instant unattributedSince, Traffic unattributed,
                         Placement placement) {
        this(instances, unattributedSince, unattributed, placement, Map.of());
    }

    public ProxyActivity(Map<String, Instance> instances) {
        this(instances, null, Traffic.NONE);
    }

    public ProxyActivity(Map<String, Instance> instances, Instant unattributedSince, Traffic unattributed) {
        this(instances, unattributedSince, unattributed, Placement.NONE);
    }

    /** This activity with {@code placement}. */
    public ProxyActivity placed(Placement placement) {
        return new ProxyActivity(instances, unattributedSince, unattributed, placement, throttled);
    }

    /** This activity with {@code throttled}: what upstream's 429s cost the host, by domain (#2303). */
    public ProxyActivity throttledBy(Map<String, Throttle> throttled) {
        return new ProxyActivity(instances, unattributedSince, unattributed, placement, Map.copyOf(throttled));
    }

    /**
     * What upstream's 429s (too many requests) cost the host at one domain, since the proxy
     * started (#2303). A limit is on the host's address, so it is not any instance's.
     *
     * @param refusals          the 429s upstream answered, to any request
     * @param servedUnconfirmed cached copies served unconfirmed because of them
     * @param refusing          whether the domain's latest answer was a 429
     */
    public record Throttle(long refusals, long servedUnconfirmed, Instant firstAt, Instant lastAt, boolean refusing) {}

    /**
     * Whether the proxy can tell its callers apart (#1586), since it started. Only requests that
     * present something shaped like a credential proof count: the host's own traffic, which
     * brings real credentials, is in neither counter.
     *
     * @param refusedProofs        proofs checked against an instance's and found not to match:
     *                             the instance's to renew
     * @param refusedAddressMisses proofs from an address that is no instance the proxy knows, so
     *                             never checked: the proxy's problem, which nothing inside an instance fixes
     * @param registryStale        whether the proxy's last listing of the instances failed, or the
     *                             one under way has hung: until one succeeds, it refuses every proof
     *                             after a failure, and new or renewed ones while it hangs
     * @param unplacedRunning      the running instances no request has been attributed to since
     *                             their counting started, by name: the ones the proxy cannot tell
     *                             apart, if they made any. An idle one is here too
     * @param lastAddressMissAt    when the last of {@code refusedAddressMisses} was, null before one
     */
    public record Placement(long refusedProofs, long refusedAddressMisses, boolean registryStale,
                            List<String> unplacedRunning, Instant lastAddressMissAt) {
        public static final Placement NONE = new Placement(0, 0, false, List.of());

        public Placement(long refusedProofs, long refusedAddressMisses, boolean registryStale, List<String> unplacedRunning) {
            this(refusedProofs, refusedAddressMisses, registryStale, unplacedRunning, null);
        }
    }


    /**
     * One instance's counts, all counted from {@code countingSince}: when the proxy created them,
     * on the first read or call after it learned of the instance, or again after it dropped them.
     * Two reads subtract only with the same {@code countingSince}. Times are null until there is
     * one; {@link #NONE}, an instance the proxy does not know, has none at all.
     * {@code identifiedByProof}: a request of it was named by its proof or secret alone, where
     * its address named no instance (#1582, #1583).
     */
    public record Instance(Instant countingSince, long requests, long inFlight,
                           Instant lastRequestAt, Instant lastResponseAt,
                           long inputTokens, long outputTokens,
                           long cacheReadInputTokens, long cacheCreationInputTokens,
                           Traffic traffic, boolean identifiedByProof) {
        public static final Instance NONE = new Instance(null, 0, 0, null, null, 0, 0, 0, 0);

        public Instance(Instant countingSince, long requests, long inFlight,
                        Instant lastRequestAt, Instant lastResponseAt,
                        long inputTokens, long outputTokens,
                        long cacheReadInputTokens, long cacheCreationInputTokens, Traffic traffic) {
            this(countingSince, requests, inFlight, lastRequestAt, lastResponseAt,
                    inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, traffic, false);
        }

        /** Model calls only, with no traffic counted. */
        public Instance(Instant countingSince, long requests, long inFlight,
                        Instant lastRequestAt, Instant lastResponseAt,
                        long inputTokens, long outputTokens,
                        long cacheReadInputTokens, long cacheCreationInputTokens) {
            this(countingSince, requests, inFlight, lastRequestAt, lastResponseAt,
                    inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, Traffic.NONE);
        }
    }

    /**
     * Requests (a WebSocket counts as one, in flight while open) and their body bytes, model
     * calls included. Bytes and {@code lastActivityAt} move when a request starts and ends, so
     * a long download or an open WebSocket shows as {@code inFlight}, not as bytes.
     */
    public record Counts(long requests, long inFlight, Instant lastActivityAt, long requestBytes, long responseBytes) {
        public static final Counts NONE = new Counts(0, 0, null, 0, 0);
    }

    /**
     * All of one caller's requests, and the same by domain: an intercepted domain, a
     * subdomain counted under the intercepted domain it is relayed for, or {@link #OTHER_DOMAIN}.
     */
    public record Traffic(Counts total, Map<String, Counts> byDomain) {
        public static final Traffic NONE = new Traffic(Counts.NONE, Map.of());
        /** Where a request to a name the proxy does not intercept is counted; never a DNS name. */
        public static final String OTHER_DOMAIN = "(other)";
    }

    /** No answer worth reading: the proxy is down, or older than {@code /activity}. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** The running proxy predates {@code /activity}: no retry helps until it is restarted. */
    public static final class Outdated extends Unavailable {
        public Outdated(String message) {
            super(message);
        }
    }

    /** What the proxy counted for {@code instance}: {@link Instance#NONE} for one it does not know. */
    public Instance of(String instance) {
        return instances.getOrDefault(instance, Instance.NONE);
    }

    /** Asks the proxy whose health endpoint is at {@code address} ({@link ProxyHealthCheck#healthAddress}). */
    public static ProxyActivity fetch(String address) {
        return fetch(address, ProxyConfig.DEFAULT_HEALTH_PORT);
    }

    static ProxyActivity fetch(String address, int port) {
        ProxyHealthCheck.Answer answer;
        try {
            answer = ProxyHealthCheck.get(address, port, "/activity", 2000);
        } catch (Exception e) {
            throw new Unavailable("the isx proxy is not answering (" + e.getMessage() + ")");
        }
        if (answer.status() == 404) throw new Outdated("the running isx proxy does not report activity; it predates this isx");
        if (answer.status() != 200) throw new Unavailable("the isx proxy answered HTTP " + answer.status());
        return parse(answer.body());
    }

    /** The JSON the proxy serves; {@link #parse} reads it back, so the names live only here. */
    public String toJson() {
        return toJson(0);
    }

    /** Refused credential injections include anonymous and independently authenticated pass-through. */
    public String toJson(long refusedInjections) {
        var all = JqObject.builder(instances.size());
        instances.forEach((name, c) -> {
            var n = JqObject.builder(11);
            n.put("counting_since", c.countingSince().toEpochMilli());
            n.put("requests", c.requests());
            n.put("in_flight", c.inFlight());
            if (c.lastRequestAt() != null) n.put("last_request_at", c.lastRequestAt().toEpochMilli());
            if (c.lastResponseAt() != null) n.put("last_response_at", c.lastResponseAt().toEpochMilli());
            n.put("input_tokens", c.inputTokens());
            n.put("output_tokens", c.outputTokens());
            n.put("cache_read_input_tokens", c.cacheReadInputTokens());
            n.put("cache_creation_input_tokens", c.cacheCreationInputTokens());
            n.put("traffic", trafficJson(JqObject.builder(6), c.traffic()));
            n.put("identified_by_proof", c.identifiedByProof());
            all.put(name, n.build());
        });
        var other = JqObject.builder(7);
        if (unattributedSince != null) other.put("counting_since", unattributedSince.toEpochMilli());
        var unplaced = JqArray.arrayBuilder();
        placement.unplacedRunning().forEach(unplaced::add);
        var limited = JqObject.builder(throttled.size());
        throttled.forEach((domain, t) -> limited.put(domain, JqObject.builder(5)
                .put("refusals", t.refusals())
                .put("served_unconfirmed", t.servedUnconfirmed())
                .put("first_at", t.firstAt().toEpochMilli())
                .put("last_at", t.lastAt().toEpochMilli())
                .put("refusing", t.refusing()).build()));
        var root = JqObject.builder(9);
        root.put("throttled", limited.build());
        if (placement.lastAddressMissAt() != null) root.put("last_address_miss_at", placement.lastAddressMissAt().toEpochMilli());
        return root
                .put("refused_injections", refusedInjections)
                .put("refused_proofs", placement.refusedProofs())
                .put("refused_address_misses", placement.refusedAddressMisses())
                .put("registry_stale", placement.registryStale())
                .put("unplaced_running", unplaced.build())
                .put("instances", all.build())
                .put("unattributed", trafficJson(other, unattributed))
                .build().toJsonString();
    }

    private static JqObject trafficJson(JqObject.Builder n, Traffic traffic) {
        putCounts(n, traffic.total());
        var domains = JqObject.builder(traffic.byDomain().size());
        traffic.byDomain().forEach((domain, counts) -> domains.put(domain, putCounts(JqObject.builder(5), counts).build()));
        return n.put("domains", domains.build()).build();
    }

    private static JqObject.Builder putCounts(JqObject.Builder n, Counts c) {
        n.put("requests", c.requests());
        n.put("in_flight", c.inFlight());
        if (c.lastActivityAt() != null) n.put("last_activity_at", c.lastActivityAt().toEpochMilli());
        n.put("request_bytes", c.requestBytes());
        n.put("response_bytes", c.responseBytes());
        return n;
    }

    static ProxyActivity parse(String json) {
        JqValue root;
        try {
            root = JqValues.parseStrict(json);
        } catch (Exception e) {
            throw new Unavailable("the isx proxy's activity could not be read (" + Jjq.brief(e) + ")");
        }
        var instances = new HashMap<String, Instance>();
        if (root.getField("instances") instanceof JqObject objs) {
            objs.forEach((key, n) -> instances.put(key, new Instance(instant(n.path("counting_since")),
                    n.path("requests").asLong(0), n.path("in_flight").asLong(0),
                    instant(n.path("last_request_at")), instant(n.path("last_response_at")),
                    n.path("input_tokens").asLong(0), n.path("output_tokens").asLong(0),
                    n.path("cache_read_input_tokens").asLong(0), n.path("cache_creation_input_tokens").asLong(0),
                    traffic(n.path("traffic")), n.path("identified_by_proof").asBoolean(false))));
        }
        var other = root.path("unattributed");
        // A proxy older than #1586 reports none: Placement.NONE
        var unplaced = root.path("unplaced_running") instanceof JqArray names
                ? names.asList().stream().filter(JqValue::isString).map(v -> v.asText("")).sorted().toList() : List.<String>of();
        var placement = new Placement(root.path("refused_proofs").asLong(0), root.path("refused_address_misses").asLong(0),
                root.path("registry_stale").asBoolean(false), unplaced, instant(root.path("last_address_miss_at")));
        // A proxy older than #2303 reports none
        var throttled = new HashMap<String, Throttle>();
        if (root.path("throttled") instanceof JqObject limited) {
            limited.forEach((domain, t) -> throttled.put(domain, new Throttle(t.path("refusals").asLong(0),
                    t.path("served_unconfirmed").asLong(0), orEpoch(instant(t.path("first_at"))), orEpoch(instant(t.path("last_at"))),
                    t.path("refusing").asBoolean(false))));
        }
        return new ProxyActivity(Map.copyOf(instances), instant(other.path("counting_since")), traffic(other), placement,
                Map.copyOf(throttled));
    }

    /** A proxy older than #935 reports no traffic: {@link Traffic#NONE}. */
    private static Traffic traffic(JqValue n) {
        if (!(n instanceof JqObject)) return Traffic.NONE;
        var domains = new HashMap<String, Counts>();
        if (n.path("domains") instanceof JqObject byDomain) byDomain.forEach((domain, d) -> domains.put(domain, counts(d)));
        return new Traffic(counts(n), Map.copyOf(domains));
    }

    private static Counts counts(JqValue n) {
        return new Counts(n.path("requests").asLong(0), n.path("in_flight").asLong(0),
                instant(n.path("last_activity_at")), n.path("request_bytes").asLong(0), n.path("response_bytes").asLong(0));
    }

    private static Instant orEpoch(Instant at) {
        return at == null ? Instant.EPOCH : at;
    }

    private static Instant instant(JqValue millis) {
        return millis.isIntegralNumber() && millis.longValue() > 0 ? Instant.ofEpochMilli(millis.longValue()) : null;
    }
}
