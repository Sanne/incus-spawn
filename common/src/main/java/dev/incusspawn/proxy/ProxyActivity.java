package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The proxy's {@code /activity} (#898): each instance's Claude model calls -- requests made and
 * in flight, when the last started and ended, and the tokens their responses reported. Only the
 * host is answered.
 */
public record ProxyActivity(Map<String, Instance> instances) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One instance's counts, all counted from {@code countingSince}: when the proxy created them,
     * on the first read or call after it learned of the instance, or again after it dropped them.
     * Two reads subtract only with the same {@code countingSince}. Times are null until there is
     * one; {@link #NONE}, an instance the proxy does not know, has none at all.
     */
    public record Instance(Instant countingSince, long requests, long inFlight,
                           Instant lastRequestAt, Instant lastResponseAt,
                           long inputTokens, long outputTokens,
                           long cacheReadInputTokens, long cacheCreationInputTokens) {
        public static final Instance NONE = new Instance(null, 0, 0, null, null, 0, 0, 0, 0);
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
        var root = JSON.createObjectNode();
        var all = root.putObject("instances");
        instances.forEach((name, c) -> {
            var n = all.putObject(name);
            n.put("counting_since", c.countingSince().toEpochMilli());
            n.put("requests", c.requests());
            n.put("in_flight", c.inFlight());
            if (c.lastRequestAt() != null) n.put("last_request_at", c.lastRequestAt().toEpochMilli());
            if (c.lastResponseAt() != null) n.put("last_response_at", c.lastResponseAt().toEpochMilli());
            n.put("input_tokens", c.inputTokens());
            n.put("output_tokens", c.outputTokens());
            n.put("cache_read_input_tokens", c.cacheReadInputTokens());
            n.put("cache_creation_input_tokens", c.cacheCreationInputTokens());
        });
        return root.toString();
    }

    static ProxyActivity parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            throw new Unavailable("the isx proxy's activity could not be read (" + e.getMessage() + ")");
        }
        var instances = new HashMap<String, Instance>();
        root.path("instances").properties().forEach(e -> {
            var n = e.getValue();
            instances.put(e.getKey(), new Instance(instant(n.path("counting_since")),
                    n.path("requests").asLong(), n.path("in_flight").asLong(),
                    instant(n.path("last_request_at")), instant(n.path("last_response_at")),
                    n.path("input_tokens").asLong(), n.path("output_tokens").asLong(),
                    n.path("cache_read_input_tokens").asLong(), n.path("cache_creation_input_tokens").asLong()));
        });
        return new ProxyActivity(Map.copyOf(instances));
    }

    private static Instant instant(JsonNode millis) {
        return millis.canConvertToLong() && millis.asLong() > 0 ? Instant.ofEpochMilli(millis.asLong()) : null;
    }
}
