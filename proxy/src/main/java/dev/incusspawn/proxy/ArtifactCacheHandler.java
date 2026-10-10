package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The proxy's artifact cache: container registry blobs, npm tarballs, and Maven/Gradle
 * artifacts confirmed with upstream per {@link ArtifactCacheTiers}.
 * <p>
 * It serves only public repositories and never sees credentials: it is built from the
 * {@link Upstream} it fetches through and the cache settings, and is given no
 * {@code MitmProxy.RequestContext}, so it has nothing to inject. {@link MitmProxy#routeRequest}
 * decides which requests reach it.
 */
class ArtifactCacheHandler {

    private static final Set<String> REGISTRY_DOMAINS = ProxyConfig.REGISTRY_DOMAINS;
    private static final Set<String> MAVEN_DOMAINS = ProxyConfig.MAVEN_DOMAINS;
    private static final Set<String> GRADLE_DOMAINS = ProxyConfig.GRADLE_DOMAINS;
    private static final Set<String> NPM_DOMAINS = ProxyConfig.NPM_DOMAINS;

    // OCI blob URL pattern: /v2/<name>/blobs/sha256:<64-hex-chars>
    // Group 1 = image name (e.g. "library/postgres"), group 2 = digest
    private static final Pattern BLOB_DIGEST_PATTERN = Pattern.compile(
            "/v2/(.+)/blobs/(sha256:[a-f0-9]{64})");

    // Gradle distribution archive: /distributions/gradle-<version>-<variant>.zip
    // Group 1 = filename (e.g. "gradle-9.2.1-bin.zip")
    private static final Pattern GRADLE_DIST_PATTERN = Pattern.compile(
            "/distributions/(gradle-[\\w.\\-]+-(?:bin|all)\\.zip)");

    // npm tarball: /<scope>/<name>/-/<name>-<version>.tgz or /<name>/-/<name>-<version>.tgz
    // Group 1 = full path after leading slash (used as cache key)
    static final Pattern NPM_TARBALL_PATTERN = Pattern.compile(
            "/((?:@[^/]+/)?[^/]+/-/[^/]+-\\d[^/]*\\.tgz)");

    // npm packument: /<name> or /@scope/name (no further path segments)
    // Group 1 = package name
    static final Pattern NPM_PACKUMENT_PATTERN = Pattern.compile(
            "/((?:@[^/]+/)?[^/]+)");

    private static Path registryCacheDir() {
        return Environment.registryCacheDir();
    }

    private static Path mavenCacheDir() {
        return Environment.mavenCacheDir();
    }

    private static Path gradleCacheDir() {
        return Environment.gradleCacheDir();
    }

    private static Path npmCacheDir() {
        return Environment.npmCacheDir();
    }

    private static Path m2Repository() {
        return Environment.m2Repository();
    }

    // URL path prefix preceding Maven coordinates on each domain
    private static final java.util.Map<String, String> MAVEN_PATH_PREFIX = java.util.Map.of(
            "repo.maven.apache.org", "/maven2/",
            "repo1.maven.org", "/maven2/",
            "plugins.gradle.org", "/m2/"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Vertx vertx;
    private final Upstream upstream;

    // How long a download to the cache may go without a byte from upstream before it is
    // resumed (#925)
    int downloadIdleSeconds = 20;
    // From config.yaml's artifact-cache: section, through useConfig()
    volatile ArtifactCacheTiers artifactCacheTiers = ArtifactCacheTiers.DEFAULT;

    ArtifactCacheHandler(Vertx vertx, Upstream upstream) {
        this.vertx = vertx;
        this.upstream = upstream;
    }

    /** Says where each cache lives, and deletes the caches stored without verification. */
    void start() {
        System.out.println("Registry cache: " + registryCacheDir() +
                " (domains: " + REGISTRY_DOMAINS + ")");
        System.out.println("Maven cache: " + mavenCacheDir() +
                " (domains: " + MAVEN_DOMAINS + ")");
        System.out.println("Maven/Gradle confirmations: " + artifactCacheTiers);
        System.out.println("Maven .m2 fallback: " +
                (Files.isDirectory(m2Repository()) ? m2Repository() : "not available"));
        System.out.println("Gradle cache: " + gradleCacheDir() +
                " (domains: " + GRADLE_DOMAINS + ")");
        deleteUnverifiedLegacyCaches();
        System.out.println("npm cache: " + npmCacheDir() +
                " (domains: " + NPM_DOMAINS + ")");
    }

    /** The handler of a domain served from a cache, or null: it sees only requests without a body. */
    Consumer<HttpServerRequest> cacheHandler(String domain, long started) {
        if (REGISTRY_DOMAINS.contains(domain)) return req -> handleRegistryRequest(req, started, domain);
        if (MAVEN_DOMAINS.contains(domain)) {
            return req -> handleArtifactRequest(req, started, domain, path -> mavenTarget(domain, path));
        }
        if (GRADLE_DOMAINS.contains(domain)) {
            return req -> handleArtifactRequest(req, started, domain, ArtifactCacheHandler::gradleTarget);
        }
        if (NPM_DOMAINS.contains(domain)) return req -> handleNpmRequest(req, started, domain);
        return null;
    }

    // --- Registry blob caching ---

    /**
     * Handle a request to a container registry domain.
     * GET requests for blobs with a SHA256 digest are served from cache or
     * fetched, cached, and served. Everything else is relayed transparently.
     */
    private void handleRegistryRequest(HttpServerRequest clientReq, long started, String domain) {
        var path = clientReq.path();

        if (clientReq.method() == HttpMethod.GET && path != null) {
            var matcher = BLOB_DIGEST_PATTERN.matcher(path);
            if (matcher.matches()) {
                var imageName = matcher.group(1);
                var digest = matcher.group(2);
                var imageRef = domain + "/" + imageName;
                var cacheFile = registryCacheDir().resolve(digest.replace(":", "-"));

                cachedFileSize(cacheFile).onSuccess(size -> {
                    if (size >= 0) {
                        System.out.println("Registry cache hit: " + imageRef +
                                " " + digest.substring(0, 19) +
                                "... (" + formatSize(size) + ")");
                        serveCachedFile(clientReq.response(), cacheFile, digest);
                    } else {
                        fetchCacheAndServe(clientReq, started, domain, cacheFile, imageRef,
                                Verification.ofDigest(digest));
                    }
                }).onFailure(err -> {
                    System.err.println("Cache check error: " + err.getMessage());
                    upstream.relayRequest(clientReq, domain);
                });
                return;
            }
        }

        // Non-cacheable (auth tokens, manifests, HEAD, tag lookups) — relay
        upstream.relayRequest(clientReq, domain);
    }

    private Future<Long> cachedFileSize(Path cacheFile) {
        return vertx.executeBlocking(() -> Files.isRegularFile(cacheFile) ? Files.size(cacheFile) : -1L);
    }

    /**
     * Serve a cached file with a synthetic HTTP 200 response.
     * If {@code digest} is non-null, includes a Docker-Content-Digest header (OCI blobs).
     */
    private void serveCachedFile(HttpServerResponse clientResp, Path cacheFile, String digest) {
        clientResp.setStatusCode(200);
        clientResp.putHeader("Content-Type", "application/octet-stream");
        if (digest != null) {
            clientResp.putHeader("Docker-Content-Digest", digest);
        }
        clientResp.sendFile(cacheFile.toString()).onFailure(err -> {
            System.err.println("Failed to serve cached file: " + err.getMessage());
            if (!clientResp.ended() && !clientResp.closed()) {
                upstream.sendError(clientResp, 500, "Cache read error");
            }
        });
    }

    /**
     * Fetch a file from upstream, tee-stream it to the client and a temp file,
     * and commit it to the cache only if it passes {@code verification}.
     */
    private void fetchCacheAndServe(HttpServerRequest clientReq, long started, String domain,
                                    Path cacheFile, String ref, Verification verification) {
        var options = new RequestOptions()
                .setMethod(clientReq.method())
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri())
                .setConnectTimeout(Math.min(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS, upstream.timeoutLeftMillis(started)));

        upstream.requestWithAsyncDns(options).onSuccess(upReq -> {
            // A head that never comes is an error the client can retry, not a silent drop
            upReq.idleTimeout(upstream.timeoutLeftMillis(started));
            upstream.copyRequestHeaders(clientReq, upReq, domain);
            upReq.putHeader("Connection", "close");
            // Don't let upstream gzip the response — we cache raw bytes
            // and serve them directly via sendFile on cache hits.
            upReq.headers().remove("Accept-Encoding");

            upReq.send().onSuccess(upResp -> {
                var statusCode = upResp.statusCode();

                if (statusCode == 200) {
                    new CachingDownload(clientReq.response(), started, upResp, cacheFile, ref, verification).start();
                } else if (statusCode >= 300 && statusCode < 400) {
                    // Follow redirect manually — Vert.x setFollowRedirects carries
                    // the original Host header, which breaks cross-domain redirects
                    // (e.g. plugins.gradle.org -> plugins-artifacts.gradle.org).
                    followRedirect(clientReq, upResp, cacheFile, ref, verification, 0, started);
                } else {
                    var clientResp = clientReq.response();
                    clientResp.setStatusCode(statusCode);
                    clientResp.setStatusMessage(upResp.statusMessage());
                    upstream.copyResponseHeaders(upResp, clientResp);
                    upstream.pipeResponse(upResp, clientResp);
                }
            }).onFailure(err -> {
                ProxyLog.warn("Upstream error fetching " + ref + ": " + err.getMessage());
                upstream.sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            ProxyLog.warn("Connect error fetching " + ref + ": " + err.getMessage());
            upstream.sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    private static final int MAX_REDIRECTS = 10;

    /**
     * Follow a 3xx redirect from upstream, making a new request to the Location URL.
     * Uses the redirect target's host for both the connection and Host header.
     * Handles multi-hop cross-domain redirects manually because Vert.x's built-in
     * setFollowRedirects carries the original Host header across domains.
     */
    private void followRedirect(HttpServerRequest clientReq, HttpClientResponse upResp,
                                Path cacheFile, String ref,
                                Verification verification, int depth, long started) {
        if (depth >= MAX_REDIRECTS) {
            System.err.println("Too many redirects for " + ref);
            upstream.sendError(clientReq.response(), 502, "Too many redirects");
            return;
        }

        var location = upResp.getHeader("Location");
        if (location == null) {
            System.err.println("Redirect with no Location header for " + ref);
            upstream.sendError(clientReq.response(), 502, "Redirect with no Location");
            return;
        }

        var from = upResp.request();
        var redirectUri = redirectTarget(from.getHost(), from.getPort(), from.getURI(), location);
        if (redirectUri == null) {
            System.err.println("Invalid redirect Location for " + ref + ": " + location);
            upstream.sendError(clientReq.response(), 502, "Invalid redirect Location");
            return;
        }

        var redirectHost = redirectUri.getHost();
        var redirectPort = redirectUri.getPort() > 0 ? redirectUri.getPort() : 443;
        var redirectPath = rawPathAndQuery(redirectUri);

        var redirectOptions = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(redirectHost)
                .setPort(redirectPort)
                .setURI(redirectPath)
                .setConnectTimeout(Math.min(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS, upstream.timeoutLeftMillis(started)));

        upstream.requestWithAsyncDns(redirectOptions).onSuccess(redReq -> {
            redReq.idleTimeout(upstream.timeoutLeftMillis(started));
            redReq.putHeader("Host", redirectHost);
            redReq.putHeader("Connection", "close");

            redReq.send().onSuccess(redResp -> {
                var statusCode = redResp.statusCode();
                if (statusCode == 200) {
                    new CachingDownload(clientReq.response(), started, redResp, cacheFile, ref, verification).start();
                } else if (statusCode >= 300 && statusCode < 400) {
                    followRedirect(clientReq, redResp, cacheFile, ref, verification, depth + 1, started);
                } else {
                    ProxyLog.warn("Redirect target " + redirectHost + " returned " +
                            statusCode + " for " + ref + " (Location: " + location + ")");
                    var clientResp = clientReq.response();
                    clientResp.setStatusCode(statusCode);
                    clientResp.setStatusMessage(redResp.statusMessage());
                    upstream.copyResponseHeaders(redResp, clientResp);
                    upstream.pipeResponse(redResp, clientResp);
                }
            }).onFailure(err -> {
                ProxyLog.warn("Redirect fetch error for " + ref + ": " + err.getMessage());
                upstream.sendError(clientReq.response(), 502, "Redirect fetch failed");
            });
        }).onFailure(err -> {
            ProxyLog.warn("Redirect connect error for " + ref + ": " + err.getMessage());
            upstream.sendError(clientReq.response(), 502, "Redirect connection failed");
        });
    }

    // How often a download may be resumed in a row without getting further
    private static final int MAX_DOWNLOAD_RESUMES = 3;
    // The least of the client's silence budget worth starting a resume with
    private static final long MIN_RESUME_MILLIS = 1_000;

    /**
     * One download, streamed to the client and a temp file at once, and committed to the cache
     * once it passes {@code verification}. When upstream stalls or breaks off mid-body, the rest
     * is asked for with a Range request, so the client still sees one unbroken response: npm
     * drops an optional dependency whose download breaks, and exits 0 without it (#925).
     */
    private final class CachingDownload {
        private final HttpServerResponse clientResp;
        private final HttpClientResponse first;
        private final Path cacheFile;
        private final String ref;
        private final Verification verification;
        private final boolean isGzip;
        private final long length;
        // What the rest must still be for a Range request to continue it (If-Range); without
        // one, a changed file would be spliced onto the old one's first bytes
        private final String validator;
        // Why a break could never be resumed, whatever happens: then a stall is not cut either
        private final String neverResumable;
        // The response bytes are coming from; null while a resume is being asked for
        private HttpClientResponse current;
        private io.vertx.core.file.AsyncFile file;
        private Path tempFile;
        private long received;
        // Resumes since the download last got further
        private int resumes;
        private boolean failed;
        private boolean done;
        // The client left: the download goes on for the cache alone, as it always has, with
        // nobody left to write to or to keep within a silence budget
        private boolean clientGone;
        // Whether upstream is paused on our own backpressure: then it is not stalled
        private boolean waitingForDrain;
        // When the client last heard from us (from its request on, head wait included), and
        // upstream's last sign of life
        private long clientActive;
        private long upstreamActive = System.nanoTime();
        private long stallTimer;

        CachingDownload(HttpServerResponse clientResp, long started, HttpClientResponse first,
                        Path cacheFile, String ref, Verification verification) {
            this.clientResp = clientResp;
            this.clientActive = started;
            this.first = first;
            this.cacheFile = cacheFile;
            this.ref = ref;
            this.verification = verification;
            var contentEncoding = first.getHeader("Content-Encoding");
            this.isGzip = contentEncoding != null && contentEncoding.toLowerCase().contains("gzip");
            this.length = contentLength(first);
            // A server ignores a weak ETag in If-Range, and where there is an ETag, RFC 9110
            // (13.1.5) does not let Last-Modified stand in for it: then there is no resume
            var etag = first.getHeader("ETag");
            this.validator = etag == null ? strongLastModified(first)
                    : etag.startsWith("W/") ? null : etag;
            // Compressed on the fly, the same file need not give the same bytes twice
            this.neverResumable = isGzip ? "it is gzip-encoded"
                    : length < 0 ? "its length is unknown"
                    : validator == null
                    ? "upstream gave nothing to resume against (a strong ETag, or Last-Modified without one)"
                    : null;
        }

        void start() {
            clientResp.setStatusCode(200);
            clientResp.putHeader("Content-Type", "application/octet-stream");
            // What length the resumes and the end-of-body check go by, not a header they could not parse
            if (length >= 0) {
                clientResp.putHeader("Content-Length", String.valueOf(length));
            }
            verification.responseHeaders().accept(clientResp, first);
            if (length < 0) {
                clientResp.setChunked(true);
            }
            // Before anything asynchronous: an error with no handler yet is only logged by Vert.x
            attach(first);
            watchForStalls();
            clientResp.closeHandler(v -> clientLeft());
            // It may have left while we waited for the head, before there was a handler to tell
            if (clientResp.closed()) clientLeft();

            vertx.executeBlocking(() -> {
                Files.createDirectories(cacheFile.getParent());
                return Files.createTempFile(cacheFile.getParent(), "dl-", ".tmp");
            }, false).compose(temp -> {
                tempFile = temp;
                return vertx.fileSystem().open(temp.toString(),
                        new io.vertx.core.file.OpenOptions().setCreate(true).setWrite(true));
            }).onSuccess(opened -> {
                file = opened;
                file.exceptionHandler(err -> {
                    ProxyLog.warn("Disk write error caching " + ref + ": " + err.getMessage());
                    fail("Cache write error");
                });
                if (failed) {
                    discardTempFile();
                } else {
                    // Upstream sat paused on us while the file opened: that was no stall
                    upstreamActive = System.nanoTime();
                    resumeWhenWritable();
                }
            }).onFailure(err -> {
                ProxyLog.warn("Failed to open temp file for caching: " + err.getMessage());
                if (tempFile != null) discardTempFile();
                if (failed) return;
                if (current == null || clientGone) {
                    fail("Cache write error");
                } else {
                    finish();
                    // Relay what is left uncached; the handlers above only knew the cache
                    current.handler(null).endHandler(null).exceptionHandler(null);
                    upstream.pipeResponse(current, clientResp);
                    current.resume();
                }
            });
        }

        private void clientLeft() {
            if (failed || done || clientGone) return;
            ProxyLog.info("Client left before " + ref + " was downloaded (" + received
                    + " of " + length + " bytes); finishing it for the cache");
            clientGone = true;
            resumeWhenWritable();
        }

        private void attach(HttpClientResponse upResp) {
            current = upResp;
            upstreamActive = System.nanoTime();
            upResp.pause();
            upResp.handler(chunk -> {
                if (upResp != current) return;
                received += chunk.length();
                resumes = 0;
                clientActive = upstreamActive = System.nanoTime();
                if (!clientGone) clientResp.write(chunk);
                file.write(chunk);
                if (clientBackedUp() || file.writeQueueFull()) {
                    upResp.pause();
                    waitingForDrain = true;
                    resumeWhenWritable();
                }
            });
            upResp.endHandler(v -> {
                if (upResp != current) return;
                if (length >= 0 && received < length) {
                    broken(new IOException("upstream ended at " + received + " of " + length + " bytes"), false);
                } else {
                    complete();
                }
            });
            upResp.exceptionHandler(err -> {
                if (upResp == current) broken(err, false);
            });
        }

        private void resumeWhenWritable() {
            if (failed || done || file == null) return;
            if (clientBackedUp()) {
                clientResp.drainHandler(v -> {
                    // The client took what we had: it was not left waiting. A disk that drains
                    // tells it nothing, so only this renews its budget
                    clientActive = System.nanoTime();
                    resumeWhenWritable();
                });
            } else if (file.writeQueueFull()) {
                file.drainHandler(v -> resumeWhenWritable());
            } else if (current != null) {
                if (waitingForDrain) {
                    // Paused on us, upstream did not stall
                    waitingForDrain = false;
                    upstreamActive = System.nanoTime();
                }
                current.resume();
                // Checked again now, not an idle period after the pause began
                vertx.cancelTimer(stallTimer);
                watchForStalls();
            }
        }

        private boolean clientBackedUp() {
            return !clientGone && clientResp.writeQueueFull();
        }

        /**
         * Vert.x's request idle timeout stops at the response head, and the upstream client's
         * read-idle timeout outlasts the MITM server's, so a body that stops arriving is noticed here.
         * Not while upstream waits on our own backpressure or for the temp file to open, or while
         * a resume is being asked for (that request has its own timeouts). Each of those ends in
         * {@link #resumeWhenWritable}, which checks at once: a head or a resume that arrived late
         * may leave less of the client's budget than one stall check, and past it the MITM server
         * drops the client silently.
         */
        private void watchForStalls() {
            long wait;
            if (current == null || waitingForDrain || file == null) {
                wait = idleMillis();
            } else {
                var quiet = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - upstreamActive);
                // However little budget is left, upstream just let go of gets a moment to send
                var grace = MIN_RESUME_MILLIS - quiet;
                String why;
                if (neverResumable != null) {
                    // Cutting a stall could only fail the download, which may yet go on: only the
                    // client's budget ends it, with a line. With nobody waiting, nothing does
                    // but the upstream client's read-idle timeout, as before resumes
                    if (clientGone) return;
                    wait = Math.max(upstream.silenceLeftMillis(clientActive), grace);
                    why = "no byte for the client in " + upstream.clientSilenceBudgetSeconds + "s";
                } else {
                    wait = Math.min(idleMillis() - quiet, Math.max(resumeBudgetMillis(), grace));
                    why = "no data for " + quiet / 1000 + "s";
                }
                if (wait <= 0) {
                    broken(new java.util.concurrent.TimeoutException(why), false);
                    if (!failed) watchForStalls();
                    return;
                }
            }
            stallTimer = vertx.setTimer(wait, id -> {
                if (!failed && !done) watchForStalls();
            });
        }

        private long idleMillis() {
            return TimeUnit.SECONDS.toMillis(downloadIdleSeconds);
        }

        /** What a resume can still take before the client has waited too long for a byte. */
        private long resumeBudgetMillis() {
            return clientGone ? Long.MAX_VALUE : upstream.silenceLeftMillis(clientActive) - MIN_RESUME_MILLIS;
        }

        /** A resume's connect and head timeouts: what the budget leaves, if anyone is waiting. */
        private long resumeTimeoutMillis(long cap) {
            return clientGone ? cap : Math.min(cap, upstream.timeoutLeftMillis(clientActive));
        }

        private void finish() {
            done = true;
            vertx.cancelTimer(stallTimer);
        }

        private void complete() {
            finish();
            file.close().onComplete(closed -> {
                if (!clientGone) clientResp.end();
                verification.expected().apply(first).onComplete(ar -> vertx.executeBlocking(() -> {
                    finalizeCacheFile(tempFile, cacheFile, ref, isGzip,
                            verification, ar.succeeded() ? ar.result() : null);
                    return null;
                }, false));
            });
        }

        private void broken(Throwable err, boolean resumeFailed) {
            if (failed || done) return;
            abandonCurrent();
            var cannot = whyNotResumable();
            if (cannot != null) {
                ProxyLog.warn("Stream error caching " + ref + " at " + received + " of " + length
                        + " bytes (" + err.getMessage() + "), not resuming: " + cannot);
                fail("Upstream stream error");
                return;
            }
            resumes++;
            ProxyLog.warn("Download of " + ref + " broke off at " + received + " of " + length
                    + " bytes (" + err.getMessage() + "); resuming");
            // At once after a stall or a broken body; after a failed resume, give the network a moment
            if (resumeFailed) {
                var backoff = Math.min(TimeUnit.SECONDS.toMillis(resumes - 1), resumeBudgetMillis());
                vertx.setTimer(Math.max(1, backoff), id -> resume());
            } else {
                resume();
            }
        }

        private String whyNotResumable() {
            if (neverResumable != null) return neverResumable;
            if (received >= length) return "every byte had arrived";
            if (resumes >= MAX_DOWNLOAD_RESUMES) return MAX_DOWNLOAD_RESUMES + " resumes in a row got nowhere";
            if (resumeBudgetMillis() < 0) {
                return "the client has waited " + upstream.clientSilenceBudgetSeconds + "s for the next byte";
            }
            return null;
        }

        private void resume() {
            if (failed) return;
            var from = first.request();
            var options = new RequestOptions()
                    .setMethod(HttpMethod.GET)
                    .setHost(from.getHost())
                    .setPort(from.getPort())
                    .setURI(from.getURI())
                    .setConnectTimeout(resumeTimeoutMillis(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS));
            var headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap().setAll(from.headers());
            var resumeAt = received;
            upstream.requestWithAsyncDns(options).compose(req -> {
                req.idleTimeout(resumeTimeoutMillis(idleMillis()));
                req.headers().setAll(headers);
                req.putHeader("Range", "bytes=" + resumeAt + "-");
                // Only the same bytes may continue: a changed file comes back whole (200)
                req.putHeader("If-Range", validator);
                req.putHeader("Connection", "close");
                return req.send();
            }).onSuccess(resp -> {
                if (failed) {
                    resp.request().reset();
                } else if (resp.statusCode() == 206 && resumesAt(resp, resumeAt)) {
                    attach(resp);
                    resumeWhenWritable();
                } else {
                    resp.request().reset();
                    if (resp.statusCode() >= 500 || resp.statusCode() == 429) {
                        // An overloaded upstream, as a stall often is: tried again like a failed connect
                        broken(new IOException("upstream answered " + resp.statusCode()), true);
                        return;
                    }
                    ProxyLog.warn("Could not resume " + ref + ": upstream answered " + resp.statusCode()
                            + " to a Range request");
                    fail("Upstream stream error");
                }
            }).onFailure(err -> broken(err, true));
        }

        /** Detached first: its reset reports back to its handlers, which must not take it as a new break. */
        private void abandonCurrent() {
            var upResp = current;
            current = null;
            if (upResp != null) upResp.request().reset();
        }

        /** Whether a 206 carries the rest of this file, from {@code offset} to its end. */
        private boolean resumesAt(HttpClientResponse resp, long offset) {
            return ("bytes " + offset + "-" + (length - 1) + "/" + length).equals(resp.getHeader("Content-Range"));
        }

        private void fail(String message) {
            if (failed || done) return;
            failed = true;
            vertx.cancelTimer(stallTimer);
            abandonCurrent();
            upstream.sendError(clientResp, 502, message);
            if (file != null) discardTempFile();
        }

        private void discardTempFile() {
            var closed = file != null ? file.close() : Future.<Void>succeededFuture();
            closed.onComplete(v -> vertx.executeBlocking(() -> {
                Files.deleteIfExists(tempFile);
                return null;
            }));
        }
    }

    /**
     * Last-Modified if it is a strong validator: at least a second before the response's Date
     * (RFC 9110 8.8.2.2), or a file changed twice within that second would pass If-Range.
     */
    static String strongLastModified(HttpClientResponse resp) {
        var lastModified = resp.getHeader("Last-Modified");
        var date = resp.getHeader("Date");
        if (lastModified == null || date == null) return null;
        try {
            var format = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
            var modified = java.time.ZonedDateTime.parse(lastModified, format).toInstant();
            var sent = java.time.ZonedDateTime.parse(date, format).toInstant();
            return sent.minusSeconds(1).isBefore(modified) ? null : lastModified;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static long contentLength(HttpClientResponse resp) {
        var header = resp.getHeader("Content-Length");
        try {
            return header == null ? -1 : Long.parseLong(header.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void finalizeCacheFile(Path tempFile, Path cacheFile, String ref, boolean isGzip,
                                   Verification verification, byte[] expected) {
        try {
            if (isGzip) {
                var decompFile = Files.createTempFile(cacheFile.getParent(), "gz-", ".tmp");
                try (var gzIn = new GZIPInputStream(Files.newInputStream(tempFile));
                     var decompOut = Files.newOutputStream(decompFile)) {
                    gzIn.transferTo(decompOut);
                }
                Files.move(decompFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            var checksum = verification.checksum();
            if (expected == null) {
                System.out.println("No usable " + checksum.extension + " for " + ref + ", not caching");
                Files.deleteIfExists(tempFile);
            } else if (VerifiedArtifactStore.verifyAndCommit(tempFile, cacheFile, checksum,
                    expected, verification.storeSidecar())) {
                System.out.println("Cached: " + ref + " (" + checksum.extension + " verified, " +
                        formatSize(Files.size(cacheFile)) + ")");
            } else {
                System.err.println("Cache: " + checksum.extension + " mismatch for " +
                        ref + ", not caching");
            }
        } catch (Exception e) {
            System.err.println("Failed to finalize cache for " + ref + ": " + e.getMessage());
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }

    // --- npm tarball caching ---

    record NpmPackageRef(String packageName, String version) {}

    /**
     * Parse a matched npm tarball path into a package name and version.
     * Input format: {@code @scope/name/-/name-version.tgz} or {@code name/-/name-version.tgz}.
     */
    static NpmPackageRef parseNpmTarballPath(String tarballPath) {
        var sepIdx = tarballPath.indexOf("/-/");
        if (sepIdx < 0) return null;
        var packageName = tarballPath.substring(0, sepIdx);
        var filename = tarballPath.substring(sepIdx + 3);
        var basename = packageName.contains("/")
                ? packageName.substring(packageName.lastIndexOf('/') + 1)
                : packageName;
        if (filename.length() <= basename.length() + 5) return null;
        var version = filename.substring(basename.length() + 1, filename.length() - 4);
        return new NpmPackageRef(packageName, version);
    }

    /**
     * Handle a request to registry.npmjs.org.
     * <p>
     * Three request types:
     * <ul>
     *   <li><b>Tarball</b> ({@code GET /<pkg>/-/<name>-<ver>.tgz}): served from cache when
     *       the package's ETag hasn't changed since the tarball was verified. When the ETag
     *       has changed (a new version was published or a version was republished), the
     *       tarball's shasum is re-verified against per-version metadata. Cache misses
     *       are verified on store.</li>
     *   <li><b>Packument</b> ({@code GET /<pkg>}): relayed fresh to upstream. The response's
     *       ETag header is stored so tarball cache hits can be served without re-verification
     *       when the packument is unchanged.</li>
     *   <li><b>Everything else</b> (search, audit, publish, per-version metadata):
     *       relayed transparently.</li>
     * </ul>
     */
    private void handleNpmRequest(HttpServerRequest clientReq, long started, String domain) {
        var path = clientReq.path();
        if (path == null) {
            upstream.relayRequest(clientReq, domain);
            return;
        }

        var cacheDir = npmCacheDir();

        if (clientReq.method() == HttpMethod.GET) {
            var matcher = NPM_TARBALL_PATTERN.matcher(path);
            if (matcher.matches()) {
                var tarballPath = matcher.group(1);
                var pkgRef = parseNpmTarballPath(tarballPath);
                if (pkgRef != null) {
                    var cacheFile = cacheDir.resolve(tarballPath).normalize();
                    if (!cacheFile.startsWith(cacheDir)) {
                        upstream.relayRequest(clientReq, domain);
                        return;
                    }
                    var ref = domain + path;
                    fetchNpmTarballAndServe(clientReq, started, domain, cacheFile, ref,
                            pkgRef.packageName(), pkgRef.version());
                    return;
                }
            }
        }

        var packumentMatcher = NPM_PACKUMENT_PATTERN.matcher(path);
        if (packumentMatcher.matches()) {
            relayNpmPackument(clientReq, domain, packumentMatcher.group(1));
            return;
        }

        upstream.relayRequest(clientReq, domain);
    }

    /**
     * Relay a packument request to upstream and store the response ETag.
     * The ETag is used by tarball cache hits to skip re-verification when
     * the packument hasn't changed.
     */
    private void relayNpmPackument(HttpServerRequest clientReq, String domain,
                                    String packageName) {
        upstream.relayRequest(clientReq, domain, upResp -> {
            var etag = upResp.getHeader("ETag");
            if (etag != null && !etag.isBlank()) {
                vertx.executeBlocking(() -> {
                    storePackageEtag(packageName, etag);
                    return null;
                });
            }
        });
    }

    static void storePackageEtag(String packageName, String etag) {
        try {
            var cacheDir = npmCacheDir();
            var etagFile = cacheDir.resolve(packageName).resolve(".etag").normalize();
            if (!etagFile.startsWith(cacheDir)) return;
            Files.createDirectories(etagFile.getParent());
            Files.writeString(etagFile, etag);
        } catch (IOException e) {
            System.err.println("npm: failed to store ETag for " + packageName +
                    ": " + e.getMessage());
        }
    }

    static String readFileOrNull(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }

    record NpmVerifyResult(boolean cacheHit, long size, String digest) {}

    /**
     * Serve an npm tarball from cache or fetch fresh.
     * <p>
     * <b>Cache hit + ETag unchanged</b>: serve directly (zero cost — no upstream,
     * no hash computation). The package's ETag hasn't changed since this tarball was
     * last verified, so the shasum is guaranteed unchanged.
     * <p>
     * <b>Cache hit + ETag changed/missing</b>: fetch per-version metadata, compare
     * the upstream shasum with the stored sidecar. Same shasum → update the tarball's
     * ETag marker and serve. Different shasum → evict and re-fetch.
     * <p>
     * <b>Cache miss</b>: fetch per-version shasum, download with digest verification,
     * write shasum + ETag sidecar files alongside the cached tarball.
     */
    private void fetchNpmTarballAndServe(HttpServerRequest clientReq, long started, String domain,
                                          Path cacheFile, String ref,
                                          String packageName, String version) {
        // The disk work stays ordered, behind the packument's ETag write (relayNpmPackument) and
        // other tarballs' sidecar writes. That queue is the one every request on this server
        // shares, so the shasum lookup waits outside it: inside, a cold install's lookups ran
        // one at a time (#960).
        vertx.executeBlocking(() -> {
            var cacheDir = npmCacheDir();
            var etagFile = cacheDir.resolve(packageName).resolve(".etag").normalize();
            var packageEtag = etagFile.startsWith(cacheDir)
                    ? readFileOrNull(etagFile) : null;
            return new NpmCacheState(packageEtag, freshNpmHit(cacheFile, packageEtag));
        }).compose(state -> state.hit() != null
                ? Future.succeededFuture(state.hit())
                : fetchNpmVersion(domain, packageName, version, started).compose(document -> vertx.executeBlocking(
                        () -> checkNpmTarballCache(cacheFile, state.packageEtag(), ref, npmShasum(document))))
        ).onSuccess(result -> {
            if (result == null) {
                // Said here, so a relay that fails points at the lookup that sent it there
                ProxyLog.warn("No npm shasum for " + ref + "; relaying it uncached");
                upstream.relayRequest(clientReq, domain);
            } else if (result.cacheHit()) {
                System.out.println("npm cache hit: " + ref +
                        " (" + formatSize(result.size()) + ")");
                serveCachedFile(clientReq.response(), cacheFile, null);
            } else {
                fetchCacheAndServe(clientReq, started, domain, cacheFile, ref,
                        Verification.ofDigest(result.digest()));
            }
        }).onFailure(err -> {
            System.err.println("npm integrity check error for " + ref +
                    ": " + err.getMessage());
            upstream.relayRequest(clientReq, domain);
        });
    }

    /** The package's stored ETag, and the cached tarball when that ETag vouches for it without a lookup. */
    record NpmCacheState(String packageEtag, NpmVerifyResult hit) {}

    /** A hit on the cached tarball when the package's ETag is the one it was verified under, else null. */
    static NpmVerifyResult freshNpmHit(Path cacheFile, String packageEtag) throws IOException {
        if (packageEtag == null || packageEtag.isEmpty() || !Files.isRegularFile(cacheFile)) return null;
        return packageEtag.equals(readFileOrNull(Path.of(cacheFile + ".etag")))
                ? new NpmVerifyResult(true, Files.size(cacheFile), null) : null;
    }

    /**
     * Settle a tarball that {@link #freshNpmHit} could not serve, given its current upstream
     * {@code shasum} (null when the lookup failed).
     */
    static NpmVerifyResult checkNpmTarballCache(Path cacheFile, String packageEtag,
                                                 String ref, String shasum)
            throws IOException {
        var etagPath = Path.of(cacheFile + ".etag");
        var shasumPath = Path.of(cacheFile + ".shasum");

        if (Files.isRegularFile(cacheFile)) {
            if (shasum == null) {
                return new NpmVerifyResult(true, Files.size(cacheFile), null);
            }

            var storedShasum = readFileOrNull(shasumPath);
            if (shasum.equals(storedShasum)) {
                if (packageEtag != null) {
                    Files.writeString(etagPath, packageEtag);
                }
                return new NpmVerifyResult(true, Files.size(cacheFile), null);
            }

            System.out.println("npm cache stale: " + ref +
                    " (shasum changed), evicting");
            Files.deleteIfExists(cacheFile);
            Files.deleteIfExists(shasumPath);
            Files.deleteIfExists(etagPath);
            var digest = "sha1:" + shasum;
            writeNpmSidecarFiles(cacheFile, shasum, packageEtag);
            return new NpmVerifyResult(false, 0, digest);
        }

        if (shasum == null) return null;
        var digest = "sha1:" + shasum;
        writeNpmSidecarFiles(cacheFile, shasum, packageEtag);
        return new NpmVerifyResult(false, 0, digest);
    }

    static void writeNpmSidecarFiles(Path cacheFile, String shasum,
                                              String packageEtag) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(Path.of(cacheFile + ".shasum"), shasum);
            if (packageEtag != null) {
                Files.writeString(Path.of(cacheFile + ".etag"), packageEtag);
            }
        } catch (IOException e) {
            System.err.println("npm: failed to write sidecar files: " + e.getMessage());
        }
    }

    // A version document is a few KB; old ones can carry their readme
    private static final int MAX_NPM_VERSION_BYTES = 1024 * 1024;

    /**
     * Fetch an npm package version's metadata document ({@code /<package>/<version>}),
     * which carries its shasum, through {@code probeClient} like every other checksum, so
     * lookups run concurrently on shared connections. Completes with the document, or null
     * on any failure; {@link #npmShasum} parses it, on a worker thread.
     * <p>
     * A 5xx is asked once more while over half the client's silence budget is left, so the
     * tarball's head still has time: without a shasum the tarball is relayed uncached, and a
     * relayed body that stalls cannot be resumed (#925, #929). A 429 is not: asked again at
     * once, it would only be refused again.
     */
    Future<byte[]> fetchNpmVersion(String domain, String packageName, String version, long started) {
        var path = "/" + packageName.replace("/", "%2F") + "/" + version;
        return fetchSmallBody(domain, path, MAX_NPM_VERSION_BYTES)
                .compose(answer -> answer.status() >= 500
                        && upstream.silenceLeftMillis(started) > TimeUnit.SECONDS.toMillis(upstream.clientSilenceBudgetSeconds) / 2
                        ? fetchSmallBody(domain, path, MAX_NPM_VERSION_BYTES)
                        : Future.succeededFuture(answer))
                .map(answer -> answer.status() == 200 ? answer.body() : null);
    }

    /** The {@code dist.shasum} of a version document, or null when it has none we can use. */
    static String npmShasum(byte[] body) {
        if (body == null) return null;
        try {
            var dist = JSON.readTree(body).path("dist").path("shasum");
            if (dist.isTextual()) {
                var hex = dist.asText().trim().toLowerCase();
                if (hex.matches("[a-f0-9]{40}")) return hex;
            }
        } catch (Exception e) { /* JSON parse error */ }
        return null;
    }

    // --- Maven/Gradle artifact caching ---
    //
    // Serve a cached artifact only when a direct fetch would return the same bytes:
    // - an artifact is stored only after matching a checksum from upstream, and that
    //   checksum is stored with it as a sidecar (VerifiedArtifactStore);
    // - a hit is served on its last confirmation with upstream while artifactCacheTiers
    //   trusts it (confirmed again in the background once it ages), and confirmed before
    //   it is served after that, the way the domain's Revalidation says (a HEAD's
    //   X-Checksum-SHA1, or a fresh sidecar);
    // - sidecar requests follow their artifact: the stored copy while it is trusted,
    //   else upstream, and the answer is reconciled with the stored copy;
    // - past the tiers, only when upstream cannot be reached are copies served unconfirmed.

    /**
     * Upstream's answer about an artifact's checksum or a sidecar.
     * {@link #UNREACHABLE}: no connection could be made, or it broke. {@link #UNUSABLE}:
     * upstream answered with something we cannot use (an oversized body, a redirect
     * we will not follow), so it confirms nothing and nothing is served from the
     * cache on its strength. {@code fromHeader}: the checksum came from a
     * server-computed header rather than a separately uploaded sidecar.
     */
    record SidecarAnswer(int status, byte[] body, boolean fromHeader) {
        static final SidecarAnswer UNREACHABLE = new SidecarAnswer(-1, null);
        static final SidecarAnswer UNUSABLE = new SidecarAnswer(0, null);

        SidecarAnswer(int status, byte[] body) {
            this(status, body, false);
        }

        /**
         * No connection, or upstream failing (5xx) or throttling (429): without the
         * cache the client would get an error.
         */
        boolean unreachable() {
            return this == UNREACHABLE || status >= 500 || status == 429;
        }

        /** The status to answer with when nothing can be served. */
        int errorStatus() {
            return status >= 500 || status == 429 ? status : 502;
        }

        /** Whether this answer may evict a cached artifact on its own. */
        boolean mayEvict(Revalidation revalidation) {
            return fromHeader || revalidation.sidecarsAuthoritative();
        }
    }

    /**
     * What a download must match before it is committed to the cache: a checksum in
     * {@code checksum}'s algorithm, found once the response is in hand. With
     * {@code storeSidecar} the checksum is committed beside the artifact as that
     * sidecar. {@code responseHeaders} adds what this kind of cache tells the client.
     */
    record Verification(Sidecar checksum,
                        Function<HttpClientResponse, Future<byte[]>> expected,
                        boolean storeSidecar,
                        BiConsumer<HttpServerResponse, HttpClientResponse> responseHeaders) {

        /** A content digest known up front, {@code sha256:<hex>} or {@code sha1:<hex>} (OCI blobs, npm tarballs). */
        static Verification ofDigest(String digest) {
            var checksum = digestAlgorithm(digest);
            // An algorithm we cannot check is never cached
            byte[] expected = checksum == null ? null : digestHex(digest).getBytes(StandardCharsets.US_ASCII);
            return new Verification(checksum == null ? Sidecar.SHA256 : checksum,
                    resp -> Future.succeededFuture(expected), false,
                    (clientResp, upResp) -> clientResp.putHeader("Docker-Content-Digest", digest));
        }

        /** A Maven/Gradle artifact, committed with the checksum it was verified against. */
        static Verification ofArtifact(Sidecar checksum, Function<HttpClientResponse, Future<byte[]>> expected) {
            return new Verification(checksum, expected, true, (clientResp, upResp) -> {
                // Pass on the checksums upstream sent with these bytes (Maven smart checksums)
                for (var header : new String[] {"X-Checksum-SHA1", "X-Checksum-MD5"}) {
                    var value = upResp.getHeader(header);
                    if (value != null) clientResp.putHeader(header, value);
                }
            });
        }

        /** The checksum type a {@code sha256:}/{@code sha1:} digest names, or null for any other. */
        static Sidecar digestAlgorithm(String digest) {
            var colon = digest.indexOf(':');
            return colon < 0 ? null : switch (digest.substring(0, colon)) {
                case "sha256" -> Sidecar.SHA256;
                case "sha1" -> Sidecar.SHA1;
                default -> null;
            };
        }

        static String digestHex(String digest) {
            return digest.substring(digest.indexOf(':') + 1);
        }
    }

    /** Where a request's artifact is cached, the checksum describing it, and any copy in the host's ~/.m2. */
    record ArtifactTarget(Path artifact, Sidecar checksum, Path hostCopy) {}

    private static final int MAX_SIDECAR_BYTES = 64 * 1024;

    /**
     * Handle a GET for an artifact or one of its sidecars on a domain vetted for
     * caching (see {@link Revalidation} for why only those): {@code targetOf} maps
     * the artifact's path to where it is cached, or null for a path that must not
     * be. Everything else is relayed, including a request with a query string: the
     * cache is keyed by path, and a query may select something else.
     */
    private void handleArtifactRequest(HttpServerRequest clientReq, long started, String domain,
                                       Function<String, ArtifactTarget> targetOf) {
        var path = clientReq.path();
        var revalidation = Revalidation.forDomain(domain);

        if (clientReq.method() == HttpMethod.GET && path != null && clientReq.query() == null
                && revalidation != null) {
            var sidecar = Sidecar.of(path);
            var artifactPath = sidecar == null ? path : sidecar.artifactPath(path);
            // A sidecar of a sidecar (foo.jar.asc.sha1) has no artifact to cache
            var target = Sidecar.of(artifactPath) == null ? targetOf.apply(artifactPath) : null;
            if (target != null && revalidation.fits(target.checksum())) {
                if (sidecar != null) {
                    serveSidecar(clientReq, domain, revalidation, sidecar, target);
                } else {
                    serveArtifact(clientReq, started, domain, revalidation, target);
                }
                return;
            }
        }

        upstream.relayRequest(clientReq, domain);
    }

    /** A Maven-layout repository: metadata and SNAPSHOTs are not cached ({@link #isMavenCacheable}). */
    private static ArtifactTarget mavenTarget(String domain, String path) {
        if (!isMavenCacheable(path)) return null;
        var artifact = mavenCacheFile(domain, path);
        return artifact == null ? null : new ArtifactTarget(artifact, Sidecar.SHA1, resolveM2Path(domain, path));
    }

    /** services.gradle.org: only distribution archives, checked against the {@code .sha256} Gradle publishes. */
    private static ArtifactTarget gradleTarget(String path) {
        var matcher = GRADLE_DIST_PATTERN.matcher(path);
        return matcher.matches()
                ? new ArtifactTarget(gradleCacheDir().resolve(matcher.group(1)), Sidecar.SHA256, null)
                : null;
    }

    private record LocalCopies(VerifiedArtifactStore.CachedCopy cached, boolean hostCopy) {}

    /**
     * Serve an artifact from the verified cache, confirmed with upstream as recently
     * as {@link #artifactCacheTiers} asks, or download, verify and store it.
     */
    private void serveArtifact(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                               ArtifactTarget target) {
        var ref = domain + clientReq.path();

        vertx.executeBlocking(() -> {
            var cached = VerifiedArtifactStore.cachedCopy(target.artifact(), target.checksum(), null);
            return new LocalCopies(cached,
                    cached == null && target.hostCopy() != null && Files.isRegularFile(target.hostCopy()));
        }, false).onSuccess(local -> {
            var cached = local.cached();
            if (cached != null) {
                var tier = trustTier(domain, cached);
                if (tier == ArtifactCacheTiers.Tier.EXPIRED) {
                    revalidateAndServe(clientReq, started, domain, revalidation, target, ref, cached.size());
                } else {
                    serveTrusted(clientReq, started, domain, revalidation, target, ref, cached, tier);
                }
            } else if (local.hostCopy() && !inBackoff(domain)) {
                // The checksum is needed before deciding whether to download at all
                probe(domain, clientReq.path(), revalidation, target.checksum()).onSuccess(answer ->
                        importOrDownload(clientReq, started, domain, revalidation, target, ref, answer));
            } else {
                // No copy to fall back on, so even in a backoff upstream is asked
                download(clientReq, started, domain, revalidation, target, ref);
            }
        }).onFailure(err -> {
            System.err.println("Artifact cache check error for " + ref + ": " + err.getMessage());
            upstream.relayRequest(clientReq, domain);
        });
    }

    /**
     * How far the artifact's last confirmation is trusted: the configured tiers, except
     * on a domain that can withdraw what it published ({@link Revalidation#mayWithdraw}),
     * whose every hit is confirmed first.
     */
    private ArtifactCacheTiers.Tier trustTier(String domain, VerifiedArtifactStore.CachedCopy cached) {
        var tiers = Revalidation.mayWithdraw(domain) ? ArtifactCacheTiers.CONFIRM_EVERY_HIT : artifactCacheTiers;
        return tiers.tierOf(cached.confirmedAt(), Instant.now());
    }

    /**
     * Serve a copy whose last confirmation is still trusted, with no upstream check.
     * The proxy vouches for its stored checksum as for the bytes: it goes out as the
     * checksum header where upstream would send one, so Maven skips its own
     * {@code .sha1} request, which would otherwise cost the round trip saved here.
     */
    private void serveTrusted(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                              ArtifactTarget target, String ref, VerifiedArtifactStore.CachedCopy cached,
                              ArtifactCacheTiers.Tier tier) {
        // The header must describe the bytes sendFile is about to open. Vert.x opens by
        // path, so check the path is still the file the checksum was read with, here on
        // the thread that opens it next (a stat; sendFile's own open blocks likewise).
        // A copy replaced since goes through the confirm-first path instead.
        if (!VerifiedArtifactStore.unchanged(target.artifact(), cached)) {
            revalidateAndServe(clientReq, started, domain, revalidation, target, ref, cached.size());
            return;
        }
        recordHit(tier == ArtifactCacheTiers.Tier.STALE
                ? ArtifactCacheStats.Hit.TRUSTED_CHECKING : ArtifactCacheStats.Hit.TRUSTED, cached.size());
        if (revalidation.header != null) {
            var hex = target.checksum().hex(cached.checksum());
            if (hex != null) clientReq.response().putHeader(revalidation.header, hex);
        }
        serveCachedFile(clientReq.response(), target.artifact(), null);
        if (tier == ArtifactCacheTiers.Tier.STALE) {
            confirmInBackground(domain, clientReq.path(), revalidation, target, ref);
        }
    }

    // Per-hit counts, logged as one summary line at most every cacheStatsIntervalMs
    final ArtifactCacheStats cacheStats = new ArtifactCacheStats();
    // Overridable for tests, which read the counts themselves
    long cacheStatsIntervalMs = ArtifactCacheStats.INTERVAL_SECONDS * 1000L;
    private final java.util.concurrent.atomic.AtomicBoolean cacheStatsPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Count a hit. The first one after a quiet spell schedules the summary, so an
     * idle proxy neither logs nor wakes up for it.
     */
    private void recordHit(ArtifactCacheStats.Hit hit, long size) {
        cacheStats.record(hit, size);
        if (cacheStatsPending.compareAndSet(false, true)) {
            vertx.setTimer(cacheStatsIntervalMs, id -> logCacheStats());
        }
    }

    void logCacheStats() {
        // Cleared first: a hit landing now schedules the next summary, whichever one counts it
        cacheStatsPending.set(false);
        var summary = cacheStats.drain();
        if (summary != null) System.out.println(summary);
    }

    // Artifacts with a background confirmation queued or in flight, so overlapping hits share one
    private final Set<Path> backgroundConfirmations = ConcurrentHashMap.newKeySet();
    // Past the limit they wait their turn here; guarded by itself, as is confirmationsInFlight
    private final java.util.ArrayDeque<Runnable> queuedConfirmations = new java.util.ArrayDeque<>();
    private int confirmationsInFlight;
    // Overridable for tests
    int maxBackgroundConfirmations = 16;

    /**
     * Confirm a copy that was just served from its trusted-but-aging confirmation.
     * A match renews the confirmation; a change or withdrawal evicts the copy, so
     * the next request fetches it. An answer that settles neither (a 403, an
     * unusable sidecar) expires the confirmation, so the next hit is confirmed first
     * and gets that path's handling. Past {@link #maxBackgroundConfirmations} in
     * flight, confirmations queue rather than being dropped.
     */
    private void confirmInBackground(String domain, String path, Revalidation revalidation,
                                     ArtifactTarget target, String ref) {
        var artifact = target.artifact();
        if (!backgroundConfirmations.add(artifact)) return;
        Runnable confirmation = () -> confirm(domain, path, revalidation, target).onComplete(ar -> {
            if (ar.failed()) {
                System.err.println("Background confirmation error for " + ref + ": " + ar.cause().getMessage());
            } else {
                var outcome = ar.result().outcome();
                logEviction(outcome, ref, target.checksum(), ar.result().answer());
                if (outcome != null && outcome != VerifiedArtifactStore.Outcome.MATCHED
                        && outcome != VerifiedArtifactStore.Outcome.EVICTED) {
                    vertx.executeBlocking(() -> {
                        VerifiedArtifactStore.expireConfirmation(artifact, target.checksum());
                        return null;
                    }, false);
                }
            }
            backgroundConfirmations.remove(artifact);
            startNextConfirmation();
        });
        synchronized (queuedConfirmations) {
            if (confirmationsInFlight >= maxBackgroundConfirmations) {
                queuedConfirmations.add(confirmation);
                return;
            }
            confirmationsInFlight++;
        }
        confirmation.run();
    }

    private void startNextConfirmation() {
        Runnable next;
        synchronized (queuedConfirmations) {
            next = queuedConfirmations.poll();
            if (next == null) {
                confirmationsInFlight--;
                return;
            }
        }
        next.run();
    }

    /** Ask upstream for the artifact's current checksum, the way the domain allows. */
    private Future<SidecarAnswer> probe(String domain, String path, Revalidation revalidation, Sidecar checksum) {
        return revalidation.header != null
                ? fetchChecksumHeader(domain, path, revalidation)
                : fetchSidecar(domain, path + checksum.extension);
    }

    /** Upstream's answer, and what it did to the cached copy: no outcome when upstream was unreachable. */
    private record Confirmed(SidecarAnswer answer, VerifiedArtifactStore.Outcome outcome) {}

    /** Ask upstream about a cached artifact and reconcile the copy with the answer. */
    private Future<Confirmed> confirm(String domain, String path, Revalidation revalidation, ArtifactTarget target) {
        var probed = inBackoff(domain)
                ? Future.succeededFuture(SidecarAnswer.UNREACHABLE)
                : probe(domain, path, revalidation, target.checksum());
        return probed.compose(answer -> answer.unreachable()
                ? Future.succeededFuture(new Confirmed(answer, null))
                : vertx.executeBlocking(() -> new Confirmed(answer, VerifiedArtifactStore.reconcile(
                        target.artifact(), target.checksum(), answer.status(), answer.body(),
                        answer.mayEvict(revalidation))), false));
    }

    private void revalidateAndServe(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                                    ArtifactTarget target, String ref, long size) {
        confirm(domain, clientReq.path(), revalidation, target).onSuccess(confirmed -> {
            if (confirmed.outcome() == null) {
                recordHit(ArtifactCacheStats.Hit.UNCONFIRMED, size);
                serveCachedFile(clientReq.response(), target.artifact(), null);
            } else if (confirmed.outcome() == VerifiedArtifactStore.Outcome.MATCHED) {
                recordHit(ArtifactCacheStats.Hit.CONFIRMED, size);
                serveConfirmed(clientReq, target.artifact(), revalidation, confirmed.answer());
            } else {
                // Changed, withdrawn, or not confirmed (a 403, a disagreeing sidecar): let upstream answer
                logEviction(confirmed.outcome(), ref, target.checksum(), confirmed.answer());
                download(clientReq, started, domain, revalidation, target, ref);
            }
        }).onFailure(err -> {
            System.err.println("Artifact revalidation error for " + ref + ": " + err.getMessage());
            upstream.relayRequest(clientReq, domain);
        });
    }

    /**
     * Serve a copy upstream has just confirmed. Where upstream itself sends a
     * checksum header, the fresh value is passed on, so the client checks these
     * bytes against upstream (and Maven skips its own {@code .sha1} request).
     * Never send one from the store for a copy whose confirmation is not trusted:
     * that would turn the client's check against upstream into a check against our
     * own cache (see {@link #serveTrusted} for the copies it is trusted for).
     */
    private void serveConfirmed(HttpServerRequest clientReq, Path artifact,
                                Revalidation revalidation, SidecarAnswer answer) {
        if (revalidation.header != null) {
            var hex = revalidation.headerChecksum.hex(answer.body());
            if (hex != null) clientReq.response().putHeader(revalidation.header, hex);
        }
        serveCachedFile(clientReq.response(), artifact, null);
    }

    private void importOrDownload(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                                  ArtifactTarget target, String ref, SidecarAnswer answer) {
        if (answer == SidecarAnswer.UNREACHABLE) {
            // Downloading would only wait out the same failed connection
            upstream.sendError(clientReq.response(), 502, "Upstream unreachable");
            return;
        }
        var checksum = target.checksum();
        if (answer.status() != 200 || checksum.hex(answer.body()) == null) {
            download(clientReq, started, domain, revalidation, target, ref);
            return;
        }
        vertx.executeBlocking(() -> VerifiedArtifactStore.importCopy(
                target.hostCopy(), target.artifact(), checksum, answer.body())
                ? Files.size(target.artifact()) : -1L, false
        ).onSuccess(importedSize -> {
            if (importedSize >= 0) {
                recordHit(ArtifactCacheStats.Hit.HOST_COPY, importedSize);
                serveConfirmed(clientReq, target.artifact(), revalidation, answer);
            } else {
                System.out.println("Maven .m2 copy differs from upstream: " + ref);
                download(clientReq, started, domain, revalidation, target, ref);
            }
        }).onFailure(err -> {
            System.err.println("Maven .m2 import error for " + ref + ": " + err.getMessage());
            download(clientReq, started, domain, revalidation, target, ref);
        });
    }

    /**
     * Download the artifact, streaming it to the client, and commit it only if it
     * matches upstream's checksum: the download's own checksum header where the
     * domain sends one, else the sidecar fetched once the download is done.
     * Without either it is served but not cached.
     */
    private void download(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                          ArtifactTarget target, String ref) {
        var sidecarPath = clientReq.path() + target.checksum().extension;
        fetchCacheAndServe(clientReq, started, domain, target.artifact(), ref,
                Verification.ofArtifact(target.checksum(), upResp -> {
                    var hex = revalidation.checksumFrom(upResp);
                    if (hex != null) return Future.succeededFuture(hex.getBytes(StandardCharsets.US_ASCII));
                    return fetchSidecar(domain, sidecarPath).map(answer -> answer.status() == 200 ? answer.body() : null);
                }));
    }

    /**
     * Serve a sidecar. While the artifact's last confirmation is trusted
     * ({@link #artifactCacheTiers}) its stored copy is served, as the artifact
     * itself would be; an aging one is confirmed again in the background.
     * Otherwise it is served as upstream has it now, and used to check the cached
     * artifact (see {@link VerifiedArtifactStore#reconcile}); the stored copy then
     * stands in only when upstream cannot be reached. Where a server-computed header
     * outranks sidecars ({@link Revalidation#sidecarsAuthoritative}), a
     * disagreement is settled by that header instead of evicting.
     */
    private void serveSidecar(HttpServerRequest clientReq, String domain, Revalidation revalidation,
                              Sidecar sidecar, ArtifactTarget target) {
        var artifact = target.artifact();
        vertx.executeBlocking(() -> VerifiedArtifactStore.cachedCopy(artifact, target.checksum(), sidecar), false
        ).onComplete(ar -> {
            var cached = ar.succeeded() ? ar.result() : null;
            var stored = cached == null ? null : cached.sidecar();
            var tier = stored == null ? ArtifactCacheTiers.Tier.EXPIRED : trustTier(domain, cached);
            if (tier != ArtifactCacheTiers.Tier.EXPIRED || (stored != null && inBackoff(domain))) {
                sendSidecar(clientReq.response(), 200, stored);
                if (tier == ArtifactCacheTiers.Tier.STALE) {
                    var artifactPath = sidecar.artifactPath(clientReq.path());
                    confirmInBackground(domain, artifactPath, revalidation, target, domain + artifactPath);
                }
            } else {
                // Without a stored copy, upstream may still answer even in a backoff
                fetchAndServeSidecar(clientReq, domain, revalidation, sidecar, artifact, stored);
            }
        });
    }

    /** {@code stored}: the stored copy, served in upstream's place when it cannot be reached. */
    private void fetchAndServeSidecar(HttpServerRequest clientReq, String domain, Revalidation revalidation,
                                      Sidecar sidecar, Path artifact, byte[] stored) {
        var path = clientReq.path();
        var clientResp = clientReq.response();

        fetchSidecar(domain, path).onSuccess(answer -> {
            if (answer.unreachable()) {
                if (stored != null) {
                    sendSidecar(clientResp, 200, stored);
                } else {
                    upstream.sendError(clientResp, answer.errorStatus(), "Upstream unreachable");
                }
                return;
            }
            if (answer == SidecarAnswer.UNUSABLE) {
                upstream.sendError(clientResp, 502, "Unusable upstream answer");
                return;
            }
            var artifactPath = sidecar.artifactPath(path);
            // Reconcile before answering, so the client's next request sees any eviction
            vertx.executeBlocking(() -> VerifiedArtifactStore.reconcile(artifact, sidecar,
                    answer.status(), answer.body(), revalidation.sidecarsAuthoritative()), false
            ).compose(outcome -> outcome == VerifiedArtifactStore.Outcome.DISAGREES
                    ? settleWithHeader(domain, artifactPath, artifact, revalidation, sidecar, answer)
                    : Future.succeededFuture(outcome)
            ).onComplete(ar -> {
                if (ar.failed()) {
                    System.err.println("Sidecar check error for " + domain + path + ": " + ar.cause().getMessage());
                } else {
                    logEviction(ar.result(), domain + artifactPath, sidecar, answer);
                }
                sendSidecar(clientResp, answer.status(), answer.body());
            });
        });
    }

    /**
     * A sidecar disagreed with the stored copy on a domain whose artifacts carry a
     * server-computed checksum: let that checksum decide, and nothing else (a
     * header-less answer falls back to the very sidecar that disagreed). If it
     * still confirms the artifact, the stored sidecar follows upstream: a fresh
     * signature is kept, and one upstream no longer has is dropped. A checksum
     * sidecar that contradicts the artifact is not stored, since stored checksums
     * describe the artifact beside them.
     */
    private Future<VerifiedArtifactStore.Outcome> settleWithHeader(String domain, String artifactPath, Path artifact,
                                                                   Revalidation revalidation, Sidecar sidecar,
                                                                   SidecarAnswer sidecarAnswer) {
        return fetchChecksumHeader(domain, artifactPath, revalidation).compose(header -> {
            if (!header.fromHeader()) {
                return Future.succeededFuture(VerifiedArtifactStore.Outcome.UNCHANGED);
            }
            return vertx.executeBlocking(() -> {
                var outcome = VerifiedArtifactStore.reconcile(
                        artifact, revalidation.headerChecksum, header.status(), header.body(), true);
                if (outcome == VerifiedArtifactStore.Outcome.MATCHED) {
                    var status = sidecarAnswer.status();
                    if (status == 404 || status == 410) {
                        VerifiedArtifactStore.dropSidecar(artifact, sidecar);
                    } else if (status == 200 && !sidecar.isChecksum()) {
                        VerifiedArtifactStore.storeSidecar(artifact, sidecar, sidecarAnswer.body());
                    }
                }
                return outcome;
            }, false);
        });
    }

    private static void logEviction(VerifiedArtifactStore.Outcome outcome, String ref,
                                    Sidecar sidecar, SidecarAnswer answer) {
        if (outcome != VerifiedArtifactStore.Outcome.EVICTED) return;
        System.out.println("Evicted " + ref + ": upstream " + sidecar.extension +
                (answer.status() == 200 ? " no longer matches" : " is gone (HTTP " + answer.status() + ")"));
    }

    private static void sendSidecar(HttpServerResponse resp, int status, byte[] body) {
        if (resp.ended() || resp.closed()) return;
        resp.setStatusCode(status);
        resp.putHeader("Content-Type", "text/plain");
        resp.end(Buffer.buffer(body == null ? new byte[0] : body));
    }

    /**
     * Fetch a sidecar from upstream, following redirects (Gradle's go to another
     * host). Never fails; see {@link SidecarAnswer} for what comes back instead.
     */
    Future<SidecarAnswer> fetchSidecar(String domain, String path) {
        return fetchSmallBody(domain, path, MAX_SIDECAR_BYTES);
    }

    /** {@link #fetchSidecar} for any small document, of at most {@code maxBytes}. */
    Future<SidecarAnswer> fetchSmallBody(String domain, String path, int maxBytes) {
        return retryOnceAfterConnect(() -> fetchSmallBody(domain, 443, path, 0, maxBytes))
                .recover(err -> Future.succeededFuture(SidecarAnswer.UNREACHABLE));
    }

    /**
     * The artifact's current checksum from a HEAD's checksum header, as if it
     * were the sidecar's body. Only a 200 without the header falls back to fetching
     * the sidecar; any other status is returned as is.
     */
    Future<SidecarAnswer> fetchChecksumHeader(String domain, String path, Revalidation revalidation) {
        return retryOnceAfterConnect(() -> upstream.requestWithAsyncDns(upstream.probeClient, probeOptions(HttpMethod.HEAD, domain, 443, path))
                        .compose(req -> afterConnect(req.send().compose(resp -> resp.end().map(v -> {
                            var status = resp.statusCode();
                            if (status != 200) return new SidecarAnswer(status, null, true);
                            var hex = revalidation.checksumFrom(resp);
                            return hex == null ? null : new SidecarAnswer(200, hex.getBytes(StandardCharsets.US_ASCII), true);
                        })))))
                .recover(err -> Future.succeededFuture(SidecarAnswer.UNREACHABLE))
                .compose(answer -> answer != null
                        ? Future.succeededFuture(answer)
                        : fetchSidecar(domain, path + revalidation.headerChecksum.extension));
    }

    private static RequestOptions probeOptions(HttpMethod method, String host, int port, String uri) {
        return new RequestOptions()
                .setMethod(method)
                .setHost(host)
                .setPort(port)
                .setURI(uri)
                .setIdleTimeout(30_000);
    }

    boolean inBackoff(String domain) {
        var since = upstream.unreachableSince.get(domain);
        if (since == null) return false;
        if (System.nanoTime() - since < TimeUnit.SECONDS.toNanos(Upstream.UNREACHABLE_BACKOFF_SECONDS)) return true;
        upstream.unreachableSince.remove(domain, since);
        return false;
    }

    /** A connection was made, then the exchange on it failed. */
    private static final class AfterConnect extends RuntimeException {
        AfterConnect(Throwable cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }

    private static <T> Future<T> afterConnect(Future<T> exchange) {
        return exchange.recover(err -> Future.failedFuture(
                err instanceof AfterConnect ? err : new AfterConnect(err)));
    }

    /**
     * A pooled keep-alive connection may have died while idle (the network dropped,
     * the server closed it); acquiring it succeeds, the exchange on it fails. Try
     * once more, on a connection the pool now has to check or open. A timeout is
     * not retried: that would double the wait on a black-holed network.
     */
    private static <T> Future<T> retryOnceAfterConnect(java.util.function.Supplier<Future<T>> exchange) {
        return exchange.get().recover(err -> err instanceof AfterConnect
                && !(err.getCause() instanceof java.util.concurrent.TimeoutException)
                ? exchange.get() : Future.failedFuture(err));
    }

    // Fails when no connection could be made or the exchange broke (fetchSmallBody maps
    // both to UNREACHABLE); an answer we cannot use is UNUSABLE.
    private Future<SidecarAnswer> fetchSmallBody(String host, int port, String uri, int depth, int maxBytes) {
        return upstream.requestWithAsyncDns(upstream.probeClient, probeOptions(HttpMethod.GET, host, port, uri))
                .compose(req -> afterConnect(req.send().compose(resp -> {
                    var location = resp.getHeader("Location");
                    if (resp.statusCode() >= 300 && resp.statusCode() < 400 && location != null) {
                        var target = redirectTarget(host, port, uri, location);
                        if (depth >= MAX_REDIRECTS || target == null) {
                            ProxyLog.warn("Not following redirect to " + location + " for " + host + uri);
                            // Over h2 the reset reaches this response as an error; it is the one we asked for
                            resp.exceptionHandler(ignored -> {});
                            resp.request().reset();
                            return Future.succeededFuture(SidecarAnswer.UNUSABLE);
                        }
                        return resp.end().compose(v -> fetchSmallBody(target.getHost(),
                                target.getPort() > 0 ? target.getPort() : 443, rawPathAndQuery(target), depth + 1,
                                maxBytes));
                    }
                    return readSmallBody(resp, maxBytes);
                })));
    }

    /**
     * Where a redirect points, resolved against the raw request URI when relative;
     * null when it cannot be followed. Upstream connections are always TLS, so an
     * http Location is followed over https, as it always has been.
     */
    static URI redirectTarget(String host, int port, String rawUri, String location) {
        try {
            var target = URI.create(location);
            if (!target.isAbsolute()) {
                target = URI.create("https://" + host + (port == 443 ? "" : ":" + port) + rawUri).resolve(target);
            }
            var scheme = target.getScheme();
            if ("http".equalsIgnoreCase(scheme)) {
                target = URI.create("https" + target.toString().substring(scheme.length()));
            } else if (!"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return target.getHost() != null ? target : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String rawPathAndQuery(URI uri) {
        return uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    private static Future<SidecarAnswer> readSmallBody(HttpClientResponse resp, int maxBytes) {
        var promise = Promise.<SidecarAnswer>promise();
        var body = Buffer.buffer();
        resp.handler(chunk -> {
            if (body.length() + chunk.length() > maxBytes) {
                resp.handler(null);
                // An oversized sidecar is unusable; an oversized error page is still that error
                promise.tryComplete(resp.statusCode() == 200
                        ? SidecarAnswer.UNUSABLE : new SidecarAnswer(resp.statusCode(), null));
                resp.request().reset();
            } else {
                body.appendBuffer(chunk);
            }
        });
        resp.endHandler(v -> promise.tryComplete(new SidecarAnswer(resp.statusCode(), body.getBytes())));
        resp.exceptionHandler(promise::tryFail);
        return promise.future();
    }

    private void deleteUnverifiedLegacyCaches() {
        vertx.executeBlocking(() -> {
            for (var dir : Environment.unverifiedLegacyCacheDirs()) {
                if (Files.isDirectory(dir)) {
                    System.out.println("Deleting cache stored without verification: " + dir);
                    FileTrees.delete(dir);
                }
            }
            return null;
        }, false).onFailure(err -> ProxyLog.warn("Failed to delete legacy cache: " + err.getMessage()));
    }

    // --- Maven helpers ---

    /**
     * Check whether a Maven repository path goes through the artifact cache.
     * Hits are confirmed with upstream (within {@link ArtifactCacheTiers}), so these
     * exclusions are not about content changing:
     * <ul>
     *   <li>{@code maven-metadata.xml}: confirming a hit costs the same round trip
     *       as fetching the file, so caching it gains nothing.</li>
     *   <li>SNAPSHOTs: no cached domain serves them (Central and the Plugin Portal
     *       reject them). Were one to, its sidecars would be unsound confirmation
     *       (see {@link Revalidation#SIDECAR}), and offline serves of content
     *       replaced on every deploy would rarely be what a fetch returns.</li>
     * </ul>
     */
    private static boolean isMavenCacheable(String path) {
        if (path.contains("..")) return false;
        if (path.endsWith("/")) return false;
        if (path.contains("maven-metadata.xml")) return false;
        if (path.contains("-SNAPSHOT")) return false;
        return true;
    }

    /**
     * Map a Maven repository URL path to the corresponding path in ~/.m2/repository.
     * Returns null if the domain is unknown or the path doesn't match.
     */
    static Path resolveM2Path(String domain, String urlPath) {
        var prefix = MAVEN_PATH_PREFIX.get(domain);
        if (prefix == null || !urlPath.startsWith(prefix)) return null;
        var relativePath = urlPath.substring(prefix.length());
        if (relativePath.contains("..")) return null;
        return m2Repository().resolve(relativePath);
    }

    /** Where a Maven repository URL path is cached, or null if it would land outside the domain's cache. */
    static Path mavenCacheFile(String domain, String urlPath) {
        var domainRoot = mavenCacheDir().resolve(domain);
        var file = domainRoot.resolve(urlPath.substring(1)).normalize();
        return file.startsWith(domainRoot) && !file.equals(domainRoot) ? file : null;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

}
