package dev.incusspawn.proxy;

import dev.incusspawn.config.ConfirmedCache;
import dev.incusspawn.util.HttpValidator;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The "confirm every serve" cache of {@code confirmed-cache:} domains (#2518): sound for any HTTPS
 * domain, private and overwritten content included, because a stored copy is never served without
 * upstream's say for that very request.
 * <p>
 * Each GET goes upstream as the client sent it, its own credential or signed link included, with
 * {@code If-None-Match} naming the stored copy's {@code ETag}. A {@code 304} is upstream saying
 * both that it let this caller in and that the copy is current, so the stored bytes are served,
 * whole or the one range asked for. Anything else is relayed as it came, and a whole {@code 200}
 * with a strong {@code ETag} becomes the new copy as it passes. Nothing is served while upstream
 * cannot be asked: a failed exchange is a {@code 502}, never a stored copy.
 * <p>
 * The cache is keyed by host, path and query less the parameters that sign the link
 * ({@link ConfirmedCacheStore#unsigned}), since a signed link changes on every request while the
 * object it names does not; the whole query still goes upstream with every check. A
 * ranged request with no copy to check against is relayed as it is, and one download of the whole
 * object is started beside it, once per object, with that request's own headers and link: the
 * first download of an object costs its bytes twice, but no client waits on a fill.
 * <p>
 * Like {@link ArtifactCacheHandler}, it is built from {@link Upstream} alone and is given no
 * credential: it never sends one the client did not send itself.
 */
final class ConfirmedCacheHandler {

    // One range of bytes, as Range and Azure's x-ms-range spell it; several ranges are relayed
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /**
     * Request headers that ask upstream a question of the client's own, or ask for an answer the
     * copy cannot give (Azure's per-range MD5 and CRC64): such a request is relayed as it is.
     */
    private static final List<String> RELAYED_REQUEST_HEADERS = List.of("If-None-Match", "If-Match",
            "If-Modified-Since", "If-Unmodified-Since", "If-Range", "x-ms-if-tags",
            "x-ms-range-get-content-md5", "x-ms-range-get-content-crc64");

    /** The headers a background fill leaves out of the request it copies: it asks for the whole object. */
    private static final Set<String> NOT_FILLED = Set.of("range", "x-ms-range", "accept-encoding");

    /** Response headers that hash the bytes sent: true of a whole copy, never of a range of it. */
    private static final Set<String> WHOLE_BODY_CHECKSUMS = Set.of("content-md5", "x-ms-content-crc64");

    /**
     * The background fills under way at once, at most: each holds a download of a whole object,
     * and a guest that asks one byte of many objects would otherwise start one for each.
     */
    static final int MAX_BACKGROUND_FILLS = 4;

    /**
     * The slowest a background fill may download on average: one slower is given up once it falls
     * behind ({@link #fillDeadlineMillis}), so four that trickle cannot hold every fill slot and
     * their reservations for hours, however large their objects. A link slower than this still has
     * every download relayed.
     */
    static final long MIN_FILL_BYTES_PER_SECOND = 256 * 1024;

    // What every background fill's deadline starts from; tests shorten it
    volatile long fillDeadlineBaseMillis = 120_000;

    /**
     * How long a background fill that has written {@code written} bytes may have run: checked as it
     * goes, so a fill is given up as soon as it falls behind, not only at the end of its length.
     */
    long fillDeadlineMillis(long written) {
        return fillDeadlineBaseMillis + written / MIN_FILL_BYTES_PER_SECOND * 1000;
    }

    // How long a background fill may go without a byte from upstream
    static final long FILL_IDLE_MILLIS = 60_000;

    private final Vertx vertx;
    private final Upstream upstream;
    private volatile List<String> domains = List.of();
    private volatile long maxSize = ConfirmedCache.DEFAULT_MAX_SIZE;
    // The objects a background fill is downloading, so a burst of ranges starts one
    private final ConcurrentHashMap<Path, Object> filling = new ConcurrentHashMap<>();
    // The bytes every fill under way will write, together never more than max-size; a commit evicts room for them
    private final java.util.concurrent.atomic.AtomicLong inFlight = new java.util.concurrent.atomic.AtomicLong();
    // The free disk the host keeps, shared by every fill, made at the first one
    private volatile DiskReserve reserve;

    ConfirmedCacheHandler(Vertx vertx, Upstream upstream) {
        this.vertx = vertx;
        this.upstream = upstream;
    }

    /** Cache these domains (exact names or {@code *.} wildcards) from now on, keeping at most {@code maxSize} bytes. */
    void useConfig(List<String> domains, long maxSize) {
        this.domains = List.copyOf(domains);
        this.maxSize = maxSize;
    }

    /** The domains cached, as config.yaml lists them. */
    List<String> domains() {
        return domains;
    }

    /** Whether requests to {@code host} are this cache's. */
    boolean handles(String requestHost) {
        var host = requestHost.toLowerCase(Locale.ROOT);
        for (var domain : domains) {
            if (ConfirmedCache.covers(domain, host)) return true;
        }
        return false;
    }

    /** A request with no body to a domain this cache {@link #handles}. */
    void handle(HttpServerRequest req, long started, String requestHost) {
        // A Host header's case is the client's: the key and the request go by one spelling
        var host = requestHost.toLowerCase(Locale.ROOT);
        if (req.method() != HttpMethod.GET || req.path() == null || asksItsOwnQuestion(req)) {
            upstream.relayRequest(req, host, started);
            return;
        }
        var range = Range.of(req);
        if (range == Range.UNREADABLE) {
            upstream.relayRequest(req, host, started);
            return;
        }
        var object = ConfirmedCacheStore.objectDir(host, req.path(), req.query());
        // Unordered: every MITM connection shares one context, and this waits on the disk alone
        vertx.executeBlocking(() -> ConfirmedCacheStore.current(object), false).onComplete(ar -> {
            var copy = ar.succeeded() ? ar.result() : null;
            if (copy != null) {
                confirm(req, started, host, object, copy, range, Upstream.mayResend(req, false));
            } else if (range != null) {
                upstream.relayRequest(req, host, started);
                fillInBackground(host, req.uri(), req.headers(), object);
            } else {
                fetch(req, started, host, object, Upstream.mayResend(req, false));
            }
        });
    }

    private static boolean asksItsOwnQuestion(HttpServerRequest req) {
        for (var header : RELAYED_REQUEST_HEADERS) {
            if (req.headers().contains(header)) return true;
        }
        return false;
    }

    /** The client's request with {@code If-None-Match} for {@code copy}: a 304 serves the copy. */
    private void confirm(HttpServerRequest req, long started, String host, Path object,
                         ConfirmedCacheStore.Copy copy, Range range, boolean resend) {
        send(req, started, host, copy.etag()).onSuccess(resp -> {
            var status = resp.statusCode();
            var etag = resp.getHeader("ETag");
            if (status == 304 && (etag == null || etag.equals(copy.etag()))) {
                // No body follows; the connection goes back to the pool once it is read
                resp.end();
                // A copy removed since it was read (superseded, evicted) is asked for again
                serveCopy(req, copy, range, () -> fetch(req, started, host, object, false));
                vertx.executeBlocking(() -> {
                    ConfirmedCacheStore.touch(copy);
                    return null;
                }, false);
            } else if (status == 304) {
                // Not modified, but for another tag than the copy's: the client asked no question
                // of its own, so it gets the object, asked for again without a condition
                resp.end();
                fetch(req, started, host, object, resend);
            } else if (status == 200 && range == null && storable(resp)) {
                relayAndStore(req, started, host, object, resp);
            } else {
                // A ranged answer for another version: its whole object is fetched beside it
                if (status == 206 && strong(etag) && !etag.equals(copy.etag())) {
                    fillInBackground(host, req.uri(), req.headers(), object);
                }
                upstream.relayResponse(req, host, resp, started);
            }
        }).onFailure(err -> failed(req, started, host, err, resend,
                () -> confirm(req, started, host, object, copy, range, false)));
    }

    /** The client's request as it came, with nothing stored to check: a whole 200 is stored as it passes. */
    private void fetch(HttpServerRequest req, long started, String host, Path object, boolean resend) {
        send(req, started, host, null).onSuccess(resp -> {
            if (resp.statusCode() == 200 && storable(resp)) {
                relayAndStore(req, started, host, object, resp);
            } else {
                upstream.relayResponse(req, host, resp, started);
            }
        }).onFailure(err -> failed(req, started, host, err, resend,
                () -> fetch(req, started, host, object, false)));
    }

    /** Never a stored copy for a request upstream could not answer: it is told so, or asked once more. */
    private void failed(HttpServerRequest req, long started, String host, Throwable err, boolean resend, Runnable again) {
        if (resend && !req.response().closed() && Upstream.closedUnanswered(err)) {
            again.run();
            return;
        }
        ProxyLog.warn("Upstream error for " + host + " (confirmed cache): " + err.getMessage());
        upstream.sendError(req.response(), 502, "Upstream error");
    }

    /** The client's GET, its own headers and query, uncompressed, with {@code If-None-Match: etag} when there is one. */
    private Future<HttpClientResponse> send(HttpServerRequest req, long started, String host, String etag) {
        var options = new RequestOptions().setMethod(HttpMethod.GET).setHost(host).setPort(443).setURI(req.uri())
                .setConnectTimeout(Math.min(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS, upstream.timeoutLeftMillis(started)));
        return upstream.requestWithAsyncDns(options).compose(upReq -> {
            upReq.idleTimeout(upstream.timeoutLeftMillis(started));
            upstream.copyRequestHeaders(req, upReq, host);
            // A copy is served as stored, so it is stored as sent
            upReq.headers().remove("Accept-Encoding");
            if (etag != null) upReq.putHeader("If-None-Match", etag);
            return upReq.send();
        });
    }

    /**
     * Whether a whole answer may become a copy: a strong {@code ETag}, a known length the cache has
     * room for, no encoding, and nothing that says it is for this exchange alone.
     */
    private boolean storable(HttpClientResponse resp) {
        var length = HttpValidator.contentLength(resp::getHeader);
        var cacheControl = resp.getHeader("Cache-Control");
        var vary = resp.getHeader("Vary");
        var encoding = resp.getHeader("Content-Encoding");
        return strong(resp.getHeader("ETag")) && length >= 0 && length <= maxSize
                && (encoding == null || encoding.equalsIgnoreCase("identity"))
                && resp.getHeader("Content-Range") == null
                && (cacheControl == null || !cacheControl.toLowerCase(Locale.ROOT).contains("no-store"))
                && (vary == null || vary.isBlank() || vary.strip().equalsIgnoreCase("Accept-Encoding"));
    }

    /** A strong entity tag, quoted: a weak one does not promise the bytes. */
    static boolean strong(String etag) {
        return etag != null && etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"");
    }

    /** Relays a whole 200 as it came, and stores its bytes as the object's copy if all of them passed. */
    private void relayAndStore(HttpServerRequest req, long started, String host, Path object, HttpClientResponse resp) {
        var hold = new ResponseHold(resp);
        var length = HttpValidator.contentLength(resp::getHeader);
        hold.hold();
        openFill(object, length).onComplete(opened -> {
            var fill = opened.succeeded() ? opened.result() : null;
            if (fill == null) {
                upstream.relayResponse(req, host, hold, started, chunk -> {}, v -> {});
                return;
            }
            var headers = storedHeaders(resp);
            var etag = resp.getHeader("ETag");
            // A slow disk holds upstream back as a slow client does, so the object never queues in the heap
            upstream.relayResponse(req, host, hold, started, chunk -> {
                fill.write(chunk);
                holdWhileFull(fill, hold);
            }, v -> fill.end(etag, length, headers));
        });
    }

    /**
     * Downloads the whole object once, with the client's own headers and link but no range, so the
     * next request finds a copy to check against. At most one per object at a time.
     */
    private void fillInBackground(String host, String uri, MultiMap clientHeaders, Path object) {
        var token = new Object();
        if (filling.size() >= MAX_BACKGROUND_FILLS || filling.putIfAbsent(object, token) != null) return;
        // The client's request ends before the fill does: its headers are copied now
        var headers = new ArrayList<Map.Entry<String, String>>();
        for (var header : clientHeaders) {
            if (!NOT_FILLED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                headers.add(Map.entry(header.getKey(), header.getValue()));
            }
        }
        var options = new RequestOptions().setMethod(HttpMethod.GET).setHost(host).setPort(443).setURI(uri)
                .setConnectTimeout(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS);
        upstream.requestWithAsyncDns(options).compose(upReq -> {
            upReq.idleTimeout(FILL_IDLE_MILLIS);
            copyHeaders(headers, upReq, host);
            return upReq.send();
        }).onComplete(ar -> {
            if (ar.failed()) {
                filling.remove(object, token);
                ProxyLog.trace("Confirmed cache fill for " + host + " failed: " + ar.cause().getMessage());
                return;
            }
            var resp = ar.result();
            if (resp.statusCode() != 200 || !storable(resp)) {
                filling.remove(object, token);
                resp.request().reset();
                return;
            }
            var hold = new ResponseHold(resp);
            hold.hold();
            var length = HttpValidator.contentLength(resp::getHeader);
            var etag = resp.getHeader("ETag");
            var stored = storedHeaders(resp);
            openFill(object, length).onComplete(opened -> {
                var fill = opened.succeeded() ? opened.result() : null;
                if (fill == null) {
                    filling.remove(object, token);
                    hold.reset();
                    return;
                }
                // A fill that trickles would hold its slot and its reservation for hours: it is given
                // up as soon as it falls behind, checked as it goes, so a large object's is too
                var began = System.currentTimeMillis();
                var deadline = vertx.setPeriodic(Math.max(1, Math.min(1000, fillDeadlineBaseMillis)), t -> {
                    if (fill.over) {
                        vertx.cancelTimer(t);
                        return;
                    }
                    if (System.currentTimeMillis() - began <= fillDeadlineMillis(fill.written)) return;
                    vertx.cancelTimer(t);
                    ProxyLog.info("Confirmed cache: gave up a background fill for " + host + " past its deadline");
                    filling.remove(object, token);
                    fill.abandon();
                    hold.reset();
                });
                hold.handler(chunk -> {
                    fill.write(chunk);
                    holdWhileFull(fill, hold);
                });
                hold.endHandler(v -> {
                    vertx.cancelTimer(deadline);
                    fill.end(etag, length, stored).onComplete(done -> filling.remove(object, token));
                });
                hold.exceptionHandler(err -> {
                    vertx.cancelTimer(deadline);
                    filling.remove(object, token);
                    fill.abandon();
                });
                hold.release();
            });
        });
    }

    /** Holds upstream's response back while the fill's file has more queued than it takes. */
    private static void holdWhileFull(Fill fill, ResponseHold hold) {
        if (!fill.file.writeQueueFull()) return;
        hold.hold();
        fill.file.drainHandler(v -> hold.release());
    }

    private static void copyHeaders(List<Map.Entry<String, String>> headers, HttpClientRequest upReq, String host) {
        var out = upReq.headers();
        for (var header : headers) out.add(header.getKey(), header.getValue());
        // What the client's own request named hop-by-hop went with it
        for (var hop : List.of("Connection", "Keep-Alive", "Proxy-Connection", "Transfer-Encoding", "TE",
                "Trailer", "Upgrade", "Proxy-Authorization", "Content-Length", "Expect")) {
            out.remove(hop);
        }
        out.set("Host", host);
    }

    private static List<Map.Entry<String, String>> storedHeaders(HttpClientResponse resp) {
        var kept = new ArrayList<Map.Entry<String, String>>();
        for (var header : resp.headers()) {
            if (ConfirmedCacheStore.stored(header.getKey())) kept.add(Map.entry(header.getKey(), header.getValue()));
        }
        return List.copyOf(kept);
    }

    /**
     * A temporary file for {@code length} bytes of {@code object}, or a failure when there is no
     * room. Both are reserved before the file exists, against every fill under way and not only
     * what is on disk now: the fills together stay within {@code max-size}, and the host keeps free
     * what the proxy's spool keeps ({@link DiskReserve}, shared by every fill). A fill that ends
     * gives its reservation back ({@link Fill#release}).
     */
    private Future<Fill> openFill(Path object, long length) {
        var limit = maxSize;
        if (inFlight.addAndGet(length) > limit) {
            inFlight.addAndGet(-length);
            return Future.failedFuture("the fills under way already take what max-size allows");
        }
        return vertx.executeBlocking(() -> {
            if (!reserve().admit(length)) {
                throw new java.io.IOException("not enough free disk for " + length + " bytes: " + reserve().describe());
            }
            try {
                return ConfirmedCacheStore.newTemp(object);
            } catch (java.io.IOException | RuntimeException e) {
                reserve().abandoned(length);
                throw e;
            }
        }, false).onFailure(err -> inFlight.addAndGet(-length))
                .compose(temp -> vertx.fileSystem().open(temp.toString(), new OpenOptions().setWrite(true))
                        .map(file -> new Fill(object, temp, file, length))
                        .recover(err -> {
                            inFlight.addAndGet(-length);
                            reserve.abandoned(length);
                            deleteQuietly(temp);
                            return Future.failedFuture(err);
                        }));
    }

    /** The shared reserve of free disk, made at the first fill (blocking: it samples the filesystem). */
    private DiskReserve reserve() throws java.io.IOException {
        var made = reserve;
        if (made != null) return made;
        synchronized (this) {
            if (reserve == null) {
                Files.createDirectories(ConfirmedCacheStore.dir());
                var store = Files.getFileStore(ConfirmedCacheStore.dir());
                reserve = new DiskReserve(new DiskReserve.Probe() {
                    @Override
                    public long usable() throws java.io.IOException {
                        return store.getUsableSpace();
                    }

                    @Override
                    public long total() throws java.io.IOException {
                        return store.getTotalSpace();
                    }
                }, task -> vertx.executeBlocking(() -> {
                    task.run();
                    return null;
                }, false), System::currentTimeMillis);
            }
            return reserve;
        }
    }

    /** One download being written to a temporary file of its object, committed only whole. */
    private final class Fill {
        final Path object;
        final Path temp;
        final AsyncFile file;
        // What openFill reserved for this fill, given back once by release
        final long reserved;
        long written;
        boolean failed;
        boolean over;
        boolean released;

        Fill(Path object, Path temp, AsyncFile file, long reserved) {
            this.object = object;
            this.temp = temp;
            this.file = file;
            this.reserved = reserved;
            file.exceptionHandler(err -> failed = true);
        }

        /** Gives the reservation back: as written when the copy stayed on disk, else as never written. */
        void release(boolean kept) {
            if (released) return;
            released = true;
            inFlight.addAndGet(-reserved);
            if (kept) reserve.written(reserved);
            else reserve.abandoned(reserved);
        }

        void write(Buffer chunk) {
            if (over || failed) return;
            written += chunk.length();
            file.write(chunk);
        }

        /** Commits the bytes as the copy at {@code etag} when they are all of them, else drops them. */
        Future<Void> end(String etag, long length, List<Map.Entry<String, String>> headers) {
            if (over) return Future.succeededFuture();
            over = true;
            return file.close().transform(closed -> {
                if (closed.failed() || failed || written != length) {
                    release(false);
                    deleteQuietly(temp);
                    return Future.<Void>succeededFuture();
                }
                return vertx.executeBlocking(() -> {
                    ConfirmedCacheStore.commit(object, temp, etag, length, headers);
                    return (Void) null;
                }, false).transform(committed -> {
                    if (committed.failed()) {
                        ProxyLog.warn("Could not store a confirmed-cache copy: " + committed.cause().getMessage());
                        release(false);
                        deleteQuietly(temp);
                        return Future.succeededFuture();
                    }
                    // Stored: what eviction then does, or fails to do, is its own
                    var others = inFlight.get() - reserved;
                    release(true);
                    return vertx.executeBlocking(() -> {
                        // Room too for what the other fills under way will add
                        ConfirmedCacheStore.evict(Math.max(0, maxSize - others));
                        return (Void) null;
                    }, false).recover(err -> {
                        ProxyLog.warn("Could not evict confirmed-cache copies: " + err.getMessage());
                        return Future.succeededFuture();
                    });
                });
            });
        }

        void abandon() {
            if (over) return;
            over = true;
            release(false);
            file.close().onComplete(v -> deleteQuietly(temp));
        }
    }

    private void deleteQuietly(Path temp) {
        vertx.executeBlocking(() -> Files.deleteIfExists(temp), false);
    }

    /** Serves {@code copy}, whole or the one range asked for, with the headers stored beside it. */
    private void serveCopy(HttpServerRequest req, ConfirmedCacheStore.Copy copy, Range range, Runnable gone) {
        var resp = req.response();
        for (var header : copy.headers()) {
            // The whole object's checksum does not describe a range of it
            if (range != null && WHOLE_BODY_CHECKSUMS.contains(header.getKey().toLowerCase(Locale.ROOT))) continue;
            resp.headers().add(header.getKey(), header.getValue());
        }
        resp.putHeader("ETag", copy.etag());
        resp.putHeader("Accept-Ranges", "bytes");
        long offset = 0;
        long length = copy.length();
        if (range != null) {
            var span = range.within(copy.length());
            if (span == null) {
                resp.headers().remove("Content-Type");
                resp.setStatusCode(416).putHeader("Content-Range", "bytes */" + copy.length()).end();
                return;
            }
            offset = span[0];
            length = span[1] - span[0] + 1;
            resp.setStatusCode(206).putHeader("Content-Range",
                    "bytes " + span[0] + "-" + span[1] + "/" + copy.length());
        } else {
            resp.setStatusCode(200);
        }
        resp.sendFile(copy.body().toString(), offset, length).onFailure(err -> {
            ProxyLog.trace("Failed to serve a confirmed-cache copy: " + err.getMessage());
            if (resp.ended() || resp.closed()) return;
            if (!resp.headWritten() && !Files.exists(copy.body())) {
                resp.headers().clear();
                resp.setStatusCode(200);
                gone.run();
            } else {
                upstream.sendError(resp, 500, "Cache read error");
            }
        });
    }

    /**
     * One range of bytes a request asks for: {@code first} to {@code last}, either open (-1). Null
     * for none; {@link #UNREADABLE} for several, a malformed one, or a {@code Range} and an
     * {@code x-ms-range} that disagree, all of which are relayed.
     */
    record Range(long first, long last) {
        static final Range UNREADABLE = new Range(-2, -2);

        static Range of(HttpServerRequest req) {
            var range = req.headers().getAll("Range");
            var azure = req.headers().getAll("x-ms-range");
            if (range.size() > 1 || azure.size() > 1) return UNREADABLE;
            var plain = range.isEmpty() ? null : parse(range.getFirst());
            var ms = azure.isEmpty() ? null : parse(azure.getFirst());
            if (plain != null && ms != null && !plain.equals(ms)) return UNREADABLE;
            return ms != null ? ms : plain;
        }

        private static Range parse(String value) {
            var m = RANGE.matcher(value.strip());
            if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) return UNREADABLE;
            try {
                var first = m.group(1).isEmpty() ? -1 : Long.parseLong(m.group(1));
                var last = m.group(2).isEmpty() ? -1 : Long.parseLong(m.group(2));
                if (first >= 0 && last >= 0 && last < first) return UNREADABLE;
                return new Range(first, last);
            } catch (NumberFormatException tooLong) {
                return UNREADABLE;
            }
        }

        /** The first and last byte this range takes of {@code length}, or null when it takes none (a 416). */
        long[] within(long length) {
            if (first < 0) {
                // The last n bytes
                if (last <= 0 || length == 0) return null;
                return new long[] {Math.max(0, length - last), length - 1};
            }
            if (first >= length) return null;
            return new long[] {first, last < 0 ? length - 1 : Math.min(last, length - 1)};
        }
    }
}
