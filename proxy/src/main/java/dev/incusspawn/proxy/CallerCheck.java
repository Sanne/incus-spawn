package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What the names the proxy answers itself ({@code isx mcp}, host routes) share: identifying the
 * instance behind a request by address and per-start secret (on macOS, where the address names no
 * instance, by the secret alone, #1583), and refusing a request in plain text.
 */
final class CallerCheck {

    /**
     * How long a refused caller waits for a listing, on a worker thread: the registry reports a
     * listing that runs longer than this as hung, so the two are one number (#1596).
     */
    static final long REFRESH_WAIT_MILLIS = InstanceRegistry.LISTING_HUNG_AFTER_MS;

    private CallerCheck() {}

    /**
     * Who sent {@code req}, by {@code identify} against {@code registry}'s snapshot, handed to
     * {@code then}; null when nobody passes. A refusal right after a start may come from a
     * snapshot that predates it, since a start rotates the secret without telling the proxy:
     * then one refresh, throttled by {@link InstanceRegistry#wantsMissRefresh}, and one more try.
     * Within the throttle's interval the caller is asked again once it has passed, from a listing
     * that started after the refusal, not refused: a branch signals the proxy just before it
     * starts the instance, so the snapshot is that young when the instance first calls, and it
     * predates the secret (#1800). Whatever {@code then} throws on that path answers 502, as it
     * would from the request handler.
     */
    static <T> void identify(Vertx vertx, HttpServerRequest req, InstanceRegistry registry,
                             Function<InstanceRegistry, T> identify, Consumer<T> then) {
        var caller = identify.apply(registry);
        if (caller != null) {
            then.accept(caller);
            return;
        }
        // The caller's secret is older than its call: a listing that starts from now has it
        var wanted = registry.nextListing();
        var wait = registry.missRefreshDelayMillis();
        if (wait == 0) {
            refreshAndRetry(vertx, req, registry, identify, then, wanted);
            return;
        }
        // Each refused request waits once, and a listing is still taken at most once an interval:
        // one that started after the refusal meanwhile is the answer
        vertx.setTimer(wait, id -> {
            var again = identify.apply(registry);
            if (again != null || registry.listedFrom(wanted)) answer(req, then, again);
            else refreshAndRetry(vertx, req, registry, identify, then, wanted);
        });
    }

    private static <T> void refreshAndRetry(Vertx vertx, HttpServerRequest req, InstanceRegistry registry,
                                            Function<InstanceRegistry, T> identify, Consumer<T> then, long wanted) {
        vertx.executeBlocking(() -> {
            // Not refresh(): the request path schedules one for the same miss, which may be listing
            // already, and from before the start that rotated the secret
            registry.refreshFrom(wanted, REFRESH_WAIT_MILLIS);
            return identify.apply(registry);
        }, false).onComplete(ar -> answer(req, then, ar.succeeded() ? ar.result() : null));
    }

    /** {@code then} told {@code caller}, off the request handler: what it throws answers 502. */
    private static <T> void answer(HttpServerRequest req, Consumer<T> then, T caller) {
        try {
            then.accept(caller);
        } catch (RuntimeException e) {
            ProxyLog.warn("Request to " + req.authority() + " failed: " + e.getMessage());
            refuse(req, 502, "Internal proxy error");
        }
    }

    /**
     * Why {@code identify} named nobody, for the 403 of a call to {@code what}: the address is no
     * instance, unless the secret alone may identify (#1583); otherwise the secret is wrong.
     */
    static String unidentified(InstanceRegistry registry, String address, boolean bySecretAlone, String what) {
        if (!bySecretAlone && registry.lookup(address) == null) return "the proxy serves " + what + " only to isx instances, and this request did not come from one";
        return "this request did not carry the instance's current secret: send the contents of the file $"
                + InstanceSecret.FILE_ENV_VAR + " names, as the " + InstanceSecret.HEADER + " header";
    }

    /** Answer {@code req} with {@code status} and a plain-text line, reading and dropping any body. */
    static void refuse(HttpServerRequest req, int status, String message) {
        req.resume();
        var resp = req.response();
        if (resp.ended() || resp.closed()) return;
        try {
            resp.setStatusCode(status).putHeader("Content-Type", "text/plain; charset=utf-8").end(message + "\n");
        } catch (IllegalStateException ignored) {
            // the client went away meanwhile
        }
    }
}
