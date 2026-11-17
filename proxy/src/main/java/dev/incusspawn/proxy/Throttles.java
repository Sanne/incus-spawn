package dev.incusspawn.proxy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What upstream's 429s have cost the host, per domain (#2303): the refusals counted, the cached
 * copies served unconfirmed because of them, and whether the domain still refuses. A rate limit
 * is on the host's address, so these are the host's, not an instance's, and they are observations
 * like {@link ApiActivity}'s: a restart loses them. Domains are the ones the proxy asked, so
 * they are bounded by {@link #MAX_DOMAINS} against a redirect to many hosts. A domain's first 429
 * and its first answer after are each one log line, however many requests follow.
 */
final class Throttles {

    static final int MAX_DOMAINS = 128;

    private final LongSupplier clock;
    private final HashMap<String, Tally> byDomain = new HashMap<>();

    Throttles() {
        this(System::currentTimeMillis);
    }

    Throttles(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class Tally {
        long refusals;
        long servedUnconfirmed;
        long firstAt;
        long lastAt;
        boolean refusing;
    }

    /**
     * Upstream {@code domain} answered with {@code status}; any request's answer, whoever asked.
     * Logged under the lock, so a start and the end that follows it are logged in that order.
     */
    synchronized void answered(String domain, int status) {
        if (domain == null) return;
        var tally = byDomain.get(domain);
        if (status == 429) {
            if (tally == null) {
                if (byDomain.size() >= MAX_DOMAINS) return;
                byDomain.put(domain, tally = new Tally());
            }
            var now = clock.getAsLong();
            if (tally.refusals++ == 0) tally.firstAt = now;
            tally.lastAt = now;
            if (!tally.refusing) {
                tally.refusing = true;
                ProxyLog.warn(domain + " answers 429 (too many requests): the limit is upstream's, not isx's");
            }
        } else if (tally != null && tally.refusing && status < 400) {
            // Only a success ends it: a 404 or a 5xx says nothing of the limit
            tally.refusing = false;
            ProxyLog.info(domain + " answers again after " + tally.refusals + " refusal(s) with 429");
        }
    }

    /**
     * A cached copy of {@code domain}'s was served unconfirmed, after an answer of {@code status}
     * (0 or less: none, upstream was not asked). Counted when that answer was a 429, or when the
     * domain still refuses: a copy served during the backoff a 429 started (#2301) is not asked
     * about upstream at all, and is unconfirmed because of that 429 just the same.
     */
    synchronized void servedUnconfirmed(String domain, int status) {
        var tally = byDomain.get(domain);
        if (tally != null && (status == 429 || tally.refusing)) tally.servedUnconfirmed++;
    }

    synchronized Map<String, ProxyActivity.Throttle> snapshot() {
        var all = new HashMap<String, ProxyActivity.Throttle>();
        byDomain.forEach((domain, t) -> all.put(domain, new ProxyActivity.Throttle(t.refusals, t.servedUnconfirmed,
                Instant.ofEpochMilli(t.firstAt), Instant.ofEpochMilli(t.lastAt), t.refusing)));
        return Map.copyOf(all);
    }
}
