package dev.incusspawn.tool;

import dev.incusspawn.RuntimeConstants;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Host-side download manager with a persistent file cache.
 * <p>
 * Files are cached in {@code ~/.cache/incus-spawn/downloads/} keyed by
 * a hash of the URL. When a sha256 checksum is provided, cached files
 * are validated before reuse and downloads are verified before caching.
 */
public class DownloadCache {

    private static final int MAX_REDIRECTS = 10;

    /**
     * Pauses before each retry of a download that failed transiently (a 5xx, a 429 or a broken
     * connection); one entry per retry. GitHub release downloads have been seen to answer 500
     * transiently, around when a new base image had just been published and was first pulled.
     */
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(8));

    /** Statuses a later attempt may not get; other errors (501, 505, every other 4xx) are final. */
    private static final Set<Integer> RETRIED_STATUSES = Set.of(429, 500, 502, 503, 504);

    /** The longest {@code Retry-After} honoured, so a server cannot park a build. */
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** Waits before a retry; a seam so tests neither sleep nor lose what was asked for. */
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Observes a download as it runs, so a caller can show progress for a large file. Methods
     * are called on the downloading thread; {@link #received} once per chunk, so keep it cheap.
     */
    public interface Listener {
        Listener NONE = new Listener() {};

        /** A cached copy exists and its checksum is being verified before it is reused. */
        default void verifyingCached() {}

        /**
         * Bytes of the body written so far; a {@code file://} copy reports only its start and end.
         *
         * @param total the {@code Content-Length}, or {@code -1} when the server sent none
         */
        default void received(long bytes, long total) {}

        /**
         * An attempt failed transiently and the download starts over from byte 0 after a pause;
         * {@link #received} then counts from zero again.
         *
         * @param reason why the previous attempt failed, e.g. {@code HTTP 500 from example.com}
         */
        default void retrying(String reason, int attempt, int attempts) {}

        /** The download finished and its checksum is being verified. */
        default void verifying() {}
    }

    private final Path cacheDir;
    private final Predicate<URI> hostLocal;
    private final List<Duration> retryDelays;
    private final Pause pause;

    public DownloadCache() {
        this(RuntimeConstants.DOWNLOAD_CACHE_DIR);
    }

    /** Constructor for testing with a custom cache directory. */
    DownloadCache(Path cacheDir) {
        this(cacheDir, DownloadCache::resolvesToHostLocal);
    }

    /** Constructor for testing which addresses count as local to this machine. */
    DownloadCache(Path cacheDir, Predicate<URI> hostLocal) {
        this(cacheDir, hostLocal, RETRY_DELAYS, Thread::sleep);
    }

    /** Constructor for testing the waits before each retry. */
    DownloadCache(Path cacheDir, Predicate<URI> hostLocal, List<Duration> retryDelays, Pause pause) {
        this.cacheDir = cacheDir;
        this.hostLocal = hostLocal;
        this.retryDelays = retryDelays;
        this.pause = pause;
    }

    /**
     * Download a URL and return the path to the cached file.
     * If sha256 is provided and a cached file matches, the download is skipped.
     * If sha256 is null, the file is always re-downloaded.
     * <p>
     * Every download runs on the host on behalf of a definition, and the result lands in a
     * container. So only {@code http(s)} is accepted, never a host address: {@code file://}
     * would copy host files, and a loopback or link-local address would reach services that
     * only the host can see (the proxy, local dev servers, cloud metadata). Redirects are
     * checked hop by hop, since a public URL could otherwise redirect to one of those.
     */
    public Path download(String url, String sha256) throws IOException {
        return download(url, sha256, false);
    }

    /**
     * As {@link #download}, but also accepts {@code file://}. Only for base images, where a
     * local tarball is how a freshly built image is tested. The caller confines the path when
     * the URL comes from a project-local definition.
     */
    public Path downloadAllowingLocalFile(String url, String sha256) throws IOException {
        return downloadAllowingLocalFile(url, sha256, Listener.NONE);
    }

    /** As {@link #downloadAllowingLocalFile(String, String)}, reporting progress to {@code listener}. */
    public Path downloadAllowingLocalFile(String url, String sha256, Listener listener) throws IOException {
        return download(url, sha256, true, listener);
    }

    private Path download(String url, String sha256, boolean allowLocalFile) throws IOException {
        return download(url, sha256, allowLocalFile, Listener.NONE);
    }

    private Path download(String url, String sha256, boolean allowLocalFile, Listener listener) throws IOException {
        var uri = parse(url);
        var localFile = allowLocalFile && "file".equalsIgnoreCase(uri.getScheme());
        if (!localFile) requireRemote(uri, url);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            // NIO filesystem exceptions carry the path as their entire message, which reads as a
            // mystery path inside a caller's "failed to install <tool>" wrapper.
            throw new IOException("Cannot create download cache directory " + cacheDir, e);
        }

        var filename = cacheFilename(url);
        var cached = cacheDir.resolve(filename);

        // Cache hit: file exists and sha256 matches
        if (sha256 != null && Files.exists(cached)) {
            listener.verifyingCached();
            if (sha256.equalsIgnoreCase(computeSha256(cached))) {
                return cached;
            }
        }

        var tmp = Files.createTempFile(cacheDir, "download-", ".tmp");
        try {
            if (localFile) {
                var source = Path.of(uri);
                var size = Files.size(source);
                listener.received(0, size);
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                listener.received(size, size);
            } else {
                fetch(uri, url, tmp, listener);
            }

            if (sha256 != null) {
                listener.verifying();
                var actual = computeSha256(tmp);
                if (!sha256.equalsIgnoreCase(actual)) {
                    throw new IOException("SHA-256 mismatch for " + url
                            + ": expected " + sha256 + " but got " + actual);
                }
            }

            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void fetch(URI uri, String url, Path tmp, Listener listener) throws IOException, InterruptedException {
        try (var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build()) {
            var failures = new ArrayList<TransientDownloadException>();
            for (int attempt = 0; ; attempt++) {
                try {
                    // Each attempt starts again from the original URL: a redirect target is often
                    // a short-lived signed URL, and the failing hop may be the redirector itself.
                    fetchOnce(client, uri, url, tmp, listener);
                    return;
                } catch (TransientDownloadException e) {
                    failures.add(e);
                    if (attempt == retryDelays.size()) throw exhausted(url, failures);
                    var delay = e.retryAfter != null && e.retryAfter.compareTo(retryDelays.get(attempt)) > 0
                            ? e.retryAfter : retryDelays.get(attempt);
                    listener.retrying(e.reason, attempt + 2, retryDelays.size() + 1);
                    pause.sleep(delay);
                }
            }
        }
    }

    /**
     * The error for a download that failed on every attempt. Earlier attempts may have failed
     * differently, on another hop, so they are named too rather than only attached.
     */
    private static IOException exhausted(String url, List<TransientDownloadException> failures) {
        var last = failures.getLast();
        var reasons = new LinkedHashSet<String>();
        for (var f : failures) reasons.add(f.reason);
        var message = "Download failed: " + String.join("; then ", reasons) + " for " + url;
        if (failures.size() > 1) message += " (after " + failures.size() + " attempts)";
        var e = new IOException(message, last.getCause());
        for (var f : failures.subList(0, failures.size() - 1)) e.addSuppressed(f);
        return e;
    }

    /**
     * A failure worth retrying: the server or the connection broke, not the request, our own
     * policy or the local disk. {@code reason} is short and names the host, as a status line
     * can show it: behind a redirect, the URL the user sees is not the one that failed.
     */
    private static final class TransientDownloadException extends IOException {
        final String reason;
        final Duration retryAfter;

        TransientDownloadException(String reason, String url, Throwable cause, Duration retryAfter) {
            super("Download failed: " + reason + " for " + url, cause);
            this.reason = reason;
            this.retryAfter = retryAfter;
        }
    }

    private void fetchOnce(HttpClient client, URI uri, String url, Path tmp, Listener listener)
            throws IOException, InterruptedException {
        // Checked again on every attempt, just before connecting: a retry comes seconds after the
        // first check, by when the JDK's address cache may have let a rebinding domain move to
        // 127.0.0.1.
        requireRemote(uri, url);
        var current = uri;
        for (int hops = 0; ; hops++) {
            var request = HttpRequest.newBuilder(current).GET().build();
            var body = new AtomicReference<CountingSubscriber<Path>>();
            // Only a 200 body reaches the disk: a redirect or error body is discarded unread, so a
            // server cannot fill the host's disk through responses that never become the download.
            HttpResponse<Path> response;
            try {
                response = client.send(request, info -> {
                    if (info.statusCode() != 200) return HttpResponse.BodySubscribers.replacing(tmp);
                    body.set(new CountingSubscriber<>(HttpResponse.BodySubscribers.ofFile(tmp,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                            info.headers().firstValueAsLong("Content-Length").orElse(-1), listener));
                    return body.get();
                });
            } catch (IOException e) {
                var counting = body.get();
                if (counting != null && counting.localFailure != null) {
                    // The listener threw: a bug here, not a network error, so neither retried nor
                    // blamed on the host.
                    throw counting.localFailure;
                }
                // Once the body has started, only a failure the connection delivered is worth
                // retrying: anything else came from writing the file (a full disk, say), which
                // is neither the host's fault nor one another attempt would fix.
                if (counting != null && !counting.networkFailure) {
                    throw new IOException("Download failed writing to " + tmp.getParent() + ": "
                            + e.getMessage() + " (" + url + ")", e);
                }
                var reason = describe(e, current.getHost());
                if (counting != null || !isPermanent(e)) throw new TransientDownloadException(reason, url, e, null);
                throw new IOException("Download failed: " + reason + " for " + url, e);
            }
            var status = response.statusCode();
            if (status == 200) return;
            if (!isRedirect(status)) {
                var reason = "HTTP " + status + " from " + current.getHost();
                if (RETRIED_STATUSES.contains(status)) {
                    throw new TransientDownloadException(reason, url, null, retryAfter(response));
                }
                throw new IOException("Download failed: " + reason + " for " + url);
            }
            if (hops == MAX_REDIRECTS) {
                throw new IOException("Download failed: more than " + MAX_REDIRECTS + " redirects for " + url);
            }
            var location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Download failed: HTTP " + status
                            + " without a Location header for " + url));
            URI next;
            try {
                next = current.resolve(location);
            } catch (IllegalArgumentException e) {
                throw new IOException("Download failed: invalid redirect to " + location + " for " + url, e);
            }
            // The same rule HttpClient.Redirect.NORMAL applies: never downgrade https to http.
            if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                throw new IOException("Download failed: refusing redirect from https to " + next + " for " + url);
            }
            requireRemote(next, url);
            current = next;
        }
    }

    /**
     * A {@code Retry-After} given in seconds, capped at {@link #MAX_RETRY_AFTER}; the HTTP-date
     * form is ignored, since the servers isx downloads from send seconds.
     */
    private static Duration retryAfter(HttpResponse<?> response) {
        var value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) return null;
        try {
            var seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER.toSeconds()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Failures before the response that another attempt cannot fix: a name that does not
     * resolve, a TLS certificate or handshake the host fails, a response that breaks HTTP.
     */
    private static boolean isPermanent(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException
                    || t instanceof SSLHandshakeException || t instanceof SSLPeerUnverifiedException
                    || t instanceof ProtocolException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Passes the body through to {@code delegate}, reporting the running byte count, and records
     * where a failure came from: the connection ({@link #onError}) or this side of it.
     */
    static final class CountingSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final long total;
        private final Listener listener;
        private Flow.Subscription subscription;
        private long received;
        volatile boolean networkFailure;
        volatile RuntimeException localFailure;

        CountingSubscriber(HttpResponse.BodySubscriber<T> delegate, long total, Listener listener) {
            this.delegate = delegate;
            this.total = total;
            this.listener = listener;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (report(0)) delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            // Counted before the delegate consumes the buffers and leaves them with nothing remaining.
            for (var item : items) received += item.remaining();
            delegate.onNext(items);
            report(received);
        }

        private boolean report(long bytes) {
            try {
                listener.received(bytes, total);
                return true;
            } catch (RuntimeException e) {
                localFailure = e;
                subscription.cancel();
                delegate.onError(e);
                return false;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // A file write that failed has already completed the body; an error signalled after
            // that is the connection being torn down in response, not a network failure.
            if (!delegate.getBody().toCompletableFuture().isDone()) networkFailure = true;
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    /**
     * What went wrong, and where: the innermost cause usually says it best ("Connection reset"),
     * and an unresolvable name surfaces as a {@link java.net.ConnectException} with no message.
     */
    private static String describe(IOException e, String host) {
        String message = null;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) {
                return "cannot resolve " + host;
            }
            if (t.getMessage() != null) message = t.getMessage();
        }
        return (message != null ? message : e.getClass().getSimpleName()) + " from " + host;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static URI parse(String url) throws IOException {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid download URL: " + url, e);
        }
    }

    private void requireRemote(URI uri, String url) throws IOException {
        var scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("Refusing to download " + uri
                    + ": only http:// and https:// URLs are allowed" + via(uri, url));
        }
        if (uri.getHost() == null) {
            throw new IOException("Invalid download URL (no host): " + uri + via(uri, url));
        }
        if (hostLocal.test(uri)) {
            throw new IOException("Refusing to download " + uri + ": " + uri.getHost()
                    + " is a loopback or link-local address, reachable only from this machine" + via(uri, url));
        }
    }

    private static String via(URI uri, String url) {
        return uri.toString().equals(url) ? "" : " (redirected from " + url + ")";
    }

    /**
     * Whether the host names this machine or its link: loopback, the wildcard address (which
     * connects to loopback), or link-local (which includes the 169.254.169.254 cloud metadata
     * service). An unresolvable host is left to fail in the request itself.
     */
    static boolean resolvesToHostLocal(URI uri) {
        var host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Generate a stable cache filename from the URL.
     * Uses the last path segment (e.g. "apache-maven-3.9.14-bin.tar.gz")
     * prefixed with a short hash of the full URL for uniqueness.
     */
    static String cacheFilename(String url) {
        var lastSlash = url.lastIndexOf('/');
        var basename = lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
        // Short hash prefix to avoid collisions between different URLs with same filename
        var hash = Integer.toHexString(url.hashCode() & 0x7fffffff);
        return hash + "-" + basename;
    }

    static String computeSha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var is = Files.newInputStream(file)) {
                var buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
