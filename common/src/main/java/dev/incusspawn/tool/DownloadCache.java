package dev.incusspawn.tool;

import dev.incusspawn.RuntimeConstants;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * Host-side download manager with a persistent file cache.
 * <p>
 * Files are cached in {@code ~/.cache/incus-spawn/downloads/} keyed by
 * a hash of the URL. When a sha256 checksum is provided, cached files
 * are validated before reuse and downloads are verified before caching.
 */
public class DownloadCache {

    private static final int MAX_REDIRECTS = 10;

    private final Path cacheDir;
    private final Predicate<URI> hostLocal;

    public DownloadCache() {
        this(RuntimeConstants.DOWNLOAD_CACHE_DIR);
    }

    /** Constructor for testing with a custom cache directory. */
    DownloadCache(Path cacheDir) {
        this(cacheDir, DownloadCache::resolvesToHostLocal);
    }

    /** Constructor for testing which addresses count as local to this machine. */
    DownloadCache(Path cacheDir, Predicate<URI> hostLocal) {
        this.cacheDir = cacheDir;
        this.hostLocal = hostLocal;
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
        return download(url, sha256, true);
    }

    private Path download(String url, String sha256, boolean allowLocalFile) throws IOException {
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
            if (sha256.equalsIgnoreCase(computeSha256(cached))) {
                return cached;
            }
        }

        var tmp = Files.createTempFile(cacheDir, "download-", ".tmp");
        try {
            if (localFile) {
                Files.copy(Path.of(uri), tmp, StandardCopyOption.REPLACE_EXISTING);
            } else {
                fetch(uri, url, tmp);
            }

            if (sha256 != null) {
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

    private void fetch(URI uri, String url, Path tmp) throws IOException, InterruptedException {
        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var current = uri;
        for (int hops = 0; ; hops++) {
            var request = HttpRequest.newBuilder(current).GET().build();
            // Only a 200 body reaches the disk: a redirect or error body is discarded unread, so a
            // server cannot fill the host's disk through responses that never become the download.
            var response = client.send(request, info -> info.statusCode() == 200
                    ? HttpResponse.BodySubscribers.ofFile(tmp,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                    : HttpResponse.BodySubscribers.replacing(tmp));
            var status = response.statusCode();
            if (status == 200) return;
            if (!isRedirect(status)) {
                throw new IOException("Download failed: HTTP " + status + " for " + url);
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
