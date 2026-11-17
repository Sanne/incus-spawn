package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.util.HttpValidator;

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
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The proxy's artifact cache: container registry blobs and manifests ({@link ManifestCache}), npm
 * tarballs, and Maven/Gradle artifacts confirmed with upstream per {@link ArtifactCacheTiers}.
 * <p>
 * It never chooses a credential: it is built from the {@link Upstream} it fetches through and the
 * cache settings, and is given no {@code MitmProxy.RequestContext}, so it has nothing to inject.
 * A private Maven repository's request comes with the one header to send ({@link #servePrivate}),
 * and a container image's tag is confirmed with the client's own headers before its copy is served. {@link MitmProxy#routeRequest}
 * decides which requests reach it.
 */
class ArtifactCacheHandler {

    private static final Set<String> REGISTRY_DOMAINS = ProxyConfig.REGISTRY_DOMAINS;
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

    private final Vertx vertx;
    private final Upstream upstream;

    // How long a download to the cache may go without a byte from upstream before it is
    // resumed (#925)
    int downloadIdleSeconds = 20;
    // From config.yaml's artifact-cache: section, through useConfig(); null when it has none, so a
    // repository's own default applies (Revalidation.tiersOr)
    volatile ArtifactCacheTiers artifactCacheTiers;
    // The repositories whose artifacts are cached, by domain, the longest prefix first (#2312)
    private volatile Map<String, List<CachedRepository>> repositories = CachedRepository.byDomain(CachedRepository.BUILT_IN);
    // The private ones, reached only through a proven request's credential (servePrivate)
    private volatile Map<String, List<CachedRepository>> privateRepositories = Map.of();
    // Keys the per-credential partitions of the private cache, read once a private repository is declared
    private volatile byte[] partitionKey;
    // Each credential's partition name, so a hit computes no HMAC; a handful of entries, one per credential seen
    private final ConcurrentHashMap<Credential, String> partitions = new ConcurrentHashMap<>();

    /** Where private repositories' copies live: one partition per credential, never a domain's name. */
    static Path privateCacheDir() {
        return mavenCacheDir().resolve(".private");
    }

    /** The private repository on {@code domain} whose prefix holds {@code path}, or null. */
    CachedRepository privateRepository(String domain, String path) {
        var cached = privateRepositories.get(domain);
        return cached == null || path == null ? null : repositoryOf(cached, path);
    }

    /**
     * Where a proven GET for an artifact of a private repository (#2312) is cached for its
     * {@code credential}, or null for a request the cache does not take (a sidecar, metadata, a
     * SNAPSHOT, a query), which goes through the credentialed relay instead. Decides only: nothing
     * is read or sent.
     */
    PrivateTarget privateTarget(HttpServerRequest clientReq, String domain, Credential credential) {
        var path = clientReq.path();
        var repository = privateRepository(domain, path);
        var key = partitionKey;
        if (repository == null || key == null || clientReq.method() != HttpMethod.GET || clientReq.query() != null
                || Sidecar.of(path) != null || !isMavenCacheable(path)) {
            return null;
        }
        var partition = privateCacheDir().resolve(partitions.computeIfAbsent(credential,
                c -> PrivatePartitions.of(key, c)));
        var artifact = cacheFileUnder(partition, domain, path);
        return artifact == null ? null
                : new PrivateTarget(repository, new ArtifactTarget(artifact, Sidecar.SHA1, null, credential));
    }

    /** A private repository's request the cache takes, and where its copy lives. */
    record PrivateTarget(CachedRepository repository, ArtifactTarget target) {}

    /**
     * Serve {@code taken} from the copy cached for its credential, or download and cache it there.
     * Every hit is confirmed first with a HEAD carrying that credential: upstream decides each
     * serve, so an account that lost access is refused at once, and nothing is served while
     * upstream cannot be asked.
     */
    void servePrivate(HttpServerRequest clientReq, long started, String domain, PrivateTarget taken) {
        serveArtifact(clientReq, started, domain, taken.repository().revalidation(), taken.target());
    }

    ArtifactCacheHandler(Vertx vertx, Upstream upstream) {
        this.vertx = vertx;
        this.upstream = upstream;
    }

    /** Cache these repositories' artifacts from now on: the built-in ones and config.yaml's. */
    void useRepositories(List<CachedRepository> cached) {
        repositories = CachedRepository.byDomain(cached.stream().filter(r -> !r.isPrivate()).toList());
        var privateOnes = cached.stream().filter(CachedRepository::isPrivate).toList();
        // A reload may have replaced a credential: its old partition's name goes with it
        partitions.clear();
        if (!privateOnes.isEmpty() && partitionKey == null) {
            partitionKey = PrivatePartitions.loadOrCreateKey(privateCacheDir(), ProxyLog::warn);
            if (partitionKey == null) {
                ProxyLog.warn("Private Maven repositories are relayed uncached: their cache has no usable key");
                privateOnes = List.of();
            }
        }
        privateRepositories = CachedRepository.byDomain(privateOnes);
        upstream.backoffDomains = backoffDomains(cached);
    }

    /** {@link Upstream#backoffDomains} while {@code cached} are the cached repositories. */
    static Set<String> backoffDomains(List<CachedRepository> cached) {
        var domains = new java.util.HashSet<>(NPM_DOMAINS);
        var refused = new java.util.HashSet<String>();
        for (var repository : cached) {
            if (repository.isPrivate() || repository.revalidation().servesOnlyConfirmed()) {
                refused.add(repository.domain());
            } else {
                domains.add(repository.domain());
            }
        }
        // A domain with one such repository keeps none: its backoff would refuse that one's confirmations too
        domains.removeAll(refused);
        return Set.copyOf(domains);
    }

    /** Says where each cache lives, and deletes the caches stored without verification. */
    void start() {
        ProxyLog.stdout("Registry cache: " + registryCacheDir() +
                " (domains: " + REGISTRY_DOMAINS + ")");
        ProxyLog.stdout("Maven cache: " + mavenCacheDir() +
                " (domains: " + new java.util.TreeSet<>(repositories.keySet()) + ")");
        var configured = artifactCacheTiers;
        ProxyLog.stdout("Maven/Gradle/npm confirmations: " + (configured != null ? configured
                : ArtifactCacheTiers.DEFAULT + "; Maven Central: " + ArtifactCacheTiers.CENTRAL_DEFAULT));
        ProxyLog.stdout("Maven .m2 fallback: " +
                (Files.isDirectory(m2Repository()) ? m2Repository() : "not available"));
        ProxyLog.stdout("Gradle cache: " + gradleCacheDir() +
                " (domains: [" + CachedRepository.GRADLE_DISTRIBUTIONS.domain() + "])");
        deleteUnverifiedLegacyCaches();
        ProxyLog.stdout("npm cache: " + npmCacheDir() +
                " (domains: " + NPM_DOMAINS + ")");
    }

    /** The handler of a domain served from a cache, or null: it sees only requests without a body. */
    Consumer<HttpServerRequest> cacheHandler(String domain, long started) {
        if (REGISTRY_DOMAINS.contains(domain)) return req -> handleRegistryRequest(req, started, domain);
        var cached = repositories.get(domain);
        if (cached != null) return req -> handleArtifactRequest(req, started, domain, cached);
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
                        ProxyLog.trace("Registry cache hit: " + imageRef +
                                " " + digest.substring(0, 19) +
                                "... (" + formatSize(size) + ")");
                        serveCachedFile(clientReq.response(), cacheFile, digest);
                    } else {
                        fetchCacheAndServe(clientReq, started, domain, cacheFile, imageRef,
                                Verification.ofDigest(digest));
                    }
                }).onFailure(err -> {
                    ProxyLog.trace("Cache check error: " + err.getMessage());
                    upstream.relayRequest(clientReq, domain, started);
                });
                return;
            }
        }

        if (clientReq.method() == HttpMethod.GET && path != null && clientReq.query() == null) {
            var manifest = ManifestCache.MANIFEST_PATTERN.matcher(path);
            if (manifest.matches()) {
                serveManifest(clientReq, started, domain, manifest.group(1), manifest.group(2));
                return;
            }
        }

        // Not cached: auth tokens, a HEAD (Docker Hub does not count one), anything else
        upstream.relayRequest(clientReq, domain, started);
    }

    // --- Registry manifest caching (#2306) ---

    // How often one manifest is checked for being public, at most: the check is three requests
    private static final long PUBLIC_CHECK_INTERVAL_NANOS = TimeUnit.HOURS.toNanos(6);
    /** When this proxy last checked each manifest ({@code domain/name@digest}) for being public (nanoTime). */
    private final ConcurrentHashMap<String, Long> publicChecks = new ConcurrentHashMap<>();

    /**
     * A manifest GET, by digest or by tag. A copy is served only on a {@code HEAD} carrying the
     * client's own headers, its {@code Authorization} and {@code Accept} included, that a registry
     * answers 200 (naming the copy's digest, for a tag): the registry vouches for the caller's
     * access, and for a tag's current digest, and Docker Hub counts a manifest GET as a pull but
     * never a HEAD. A digest is confirmed too, since one is often published: knowing it proves no
     * access. When that HEAD cannot be answered (a connect failure, 5xx, 429), the copy is served
     * unconfirmed only for a digest an anonymous check found public in that repository. Everything
     * else is fetched, served and cached, or relayed.
     */
    private void serveManifest(HttpServerRequest clientReq, long started, String domain, String name, String reference) {
        var byDigest = ManifestCache.isDigest(reference);
        var ref = domain + "/" + name + (byDigest ? "@" : ":") + reference;
        var tag = byDigest ? null : ManifestCache.tag(domain, name, reference, clientReq.headers().getAll("Accept"));
        vertx.executeBlocking(() -> {
            var digest = byDigest ? reference : ManifestCache.resolvedDigest(tag);
            return digest == null || ManifestCache.storedType(digest) == null ? null : digest;
        }, false).onComplete(ar -> {
            var last = ar.succeeded() ? ar.result() : null;
            // No copy to serve: the HEAD would only add a round trip before the GET
            if (last == null) {
                fetchManifest(clientReq, started, domain, name, reference, tag, ref);
                return;
            }
            confirmManifest(clientReq, domain).onComplete(confirmed -> {
                var answer = confirmed.succeeded() ? confirmed.result() : SidecarAnswer.UNREACHABLE;
                if (answer.status() == 200) {
                    // A digest names its bytes, whatever the answer's header says; a tag is what the header says
                    var digest = byDigest ? reference
                            : answer.body() == null ? null : new String(answer.body(), StandardCharsets.US_ASCII);
                    serveConfirmedManifest(clientReq, started, domain, name, reference, tag, ref, digest);
                } else if (answer.unreachable()) {
                    serveManifestUnconfirmed(clientReq, started, domain, name, last, ref, answer);
                } else {
                    // The registry refuses this caller or does not know the manifest: it says so itself
                    upstream.relayRequest(clientReq, domain, started);
                }
            });
        });
    }

    /**
     * A HEAD for the client's manifest request, with the client's own headers: a 200 carries the
     * {@code Docker-Content-Digest} it names as its body, or none. Never fails; an exchange that
     * broke is {@link SidecarAnswer#UNREACHABLE}.
     */
    private Future<SidecarAnswer> confirmManifest(HttpServerRequest clientReq, String domain) {
        var options = probeOptions(HttpMethod.HEAD, domain, 443, clientReq.uri());
        return retryOnceAfterConnect(() -> upstream.requestWithAsyncDns(upstream.probeClient, options)
                        .compose(req -> {
                            upstream.copyRequestHeaders(clientReq, req, domain);
                            req.headers().remove("Accept-Encoding");
                            return afterConnect(upstream.sendProbe(req, (resp, read) -> resp.end().map(v -> {
                                var digest = ManifestCache.usableDigest(resp.getHeader("Docker-Content-Digest"));
                                return resp.statusCode() == 200
                                        ? new SidecarAnswer(200, digest == null ? null : digest.getBytes(StandardCharsets.US_ASCII), true)
                                        : new SidecarAnswer(resp.statusCode(), null, true, errorHeaders(resp));
                            })));
                        }))
                .recover(err -> Future.succeededFuture(SidecarAnswer.UNREACHABLE));
    }

    /** The registry confirmed the caller's manifest at {@code digest}: serve the copy of it if there is one. */
    private void serveConfirmedManifest(HttpServerRequest clientReq, long started, String domain, String name,
                                        String reference, Path tag, String ref, String digest) {
        if (digest == null) {
            fetchManifest(clientReq, started, domain, name, reference, tag, ref);
            return;
        }
        vertx.executeBlocking(() -> {
            var type = ManifestCache.storedType(digest);
            if (type != null) {
                if (tag != null) ManifestCache.recordTag(tag, digest);
            }
            return type;
        }, false).onComplete(ar -> {
            var type = ar.succeeded() ? ar.result() : null;
            if (type == null) {
                fetchManifest(clientReq, started, domain, name, reference, tag, ref);
            } else {
                ProxyLog.trace("Registry manifest cache hit: " + ref + " (" + digest.substring(0, 19) + "..., confirmed)");
                serveManifestCopy(clientReq, digest, type);
                // Hits must renew it too: a manifest that never moves is never fetched again
                checkPublicInBackground(domain, name, digest, type);
            }
        });
    }

    /**
     * The registry could not confirm the manifest: the copy of {@code digest} (the one asked for, or
     * the one the tag last resolved to) is served when the anonymous check found that digest public
     * in that repository, as the Maven cache serves an artifact upstream cannot be asked about. Any
     * other would go to a caller the registry may refuse, so it gets the registry's own answer: a
     * private image's, a digest pushed since a repository was made private, and one the check has
     * not answered for yet.
     */
    private void serveManifestUnconfirmed(HttpServerRequest clientReq, long started, String domain, String name,
                                          String digest, String ref, SidecarAnswer answer) {
        vertx.executeBlocking(() -> {
            if (!ManifestCache.isPublic(domain, name, digest)) return null;
            var type = ManifestCache.storedType(digest);
            return type == null ? null : new String[] {digest, type};
        }, false).onComplete(ar -> {
            var copy = ar.succeeded() ? ar.result() : null;
            if (copy == null) {
                upstream.relayRequest(clientReq, domain, started);
                return;
            }
            var why = answer == SidecarAnswer.UNREACHABLE ? "could not be reached" : "answered " + answer.status();
            ProxyLog.info("Registry " + why + ": serving " + ref + " unconfirmed, at " + copy[0]);
            upstream.throttles.servedUnconfirmed(domain, answer.status());
            serveManifestCopy(clientReq, copy[0], copy[1]);
        });
    }

    private void serveManifestCopy(HttpServerRequest clientReq, String digest, String type) {
        var resp = clientReq.response();
        resp.setStatusCode(200);
        resp.putHeader("Content-Type", type);
        resp.putHeader("Docker-Content-Digest", digest);
        resp.sendFile(ManifestCache.copy(digest).toString()).onFailure(err -> {
            ProxyLog.trace("Failed to serve cached manifest: " + err.getMessage());
            if (!resp.ended() && !resp.closed()) upstream.sendError(resp, 500, "Cache read error");
        });
    }

    /**
     * Fetch the client's manifest with its own headers and serve it, caching it when the answer
     * names a digest the bytes then match: the reference itself when it is one, else the answer's
     * {@code Docker-Content-Digest}. A tag ({@code tag} not null) is recorded as resolving to it.
     */
    private void fetchManifest(HttpServerRequest clientReq, long started, String domain, String name,
                               String reference, Path tag, String ref) {
        fetchManifest(clientReq, started, domain, name, reference, tag, ref, Upstream.mayResend(clientReq, false));
    }

    private void fetchManifest(HttpServerRequest clientReq, long started, String domain, String name,
                               String reference, Path tag, String ref, boolean resend) {
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(domain)
                .setPort(443)
                .setURI(clientReq.uri())
                .setConnectTimeout(Math.min(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS, upstream.timeoutLeftMillis(started)));
        upstream.requestWithAsyncDns(options).onSuccess(upReq -> {
            upReq.idleTimeout(upstream.timeoutLeftMillis(started));
            upstream.copyRequestHeaders(clientReq, upReq, domain);
            // The copy is served as stored, so it is stored as sent
            upReq.headers().remove("Accept-Encoding");
            upReq.send().onSuccess(upResp -> {
                upstream.throttles.answered(domain, upResp.statusCode());
                var answered = ManifestCache.usableDigest(upResp.getHeader("Docker-Content-Digest"));
                var digest = tag == null ? reference : answered;
                var type = upResp.getHeader("Content-Type");
                if (upResp.statusCode() != 200 || digest == null || type == null || type.isBlank()
                        || (answered != null && !answered.equals(digest))) {
                    upstream.relayResponse(clientReq, domain, upResp, started);
                    return;
                }
                new CachingDownload(clientReq, domain, started, upResp, ManifestCache.copy(digest), ref,
                        Verification.ofManifest(digest, type)).start();
                if (tag != null) {
                    vertx.executeBlocking(() -> {
                        ManifestCache.recordTag(tag, digest);
                        return null;
                    }, false).onFailure(err -> ProxyLog.trace("Could not record " + ref + ": " + err.getMessage()));
                }
                checkPublicInBackground(domain, name, digest, type);
            }).onFailure(err -> {
                if (resend && !clientReq.response().closed() && Upstream.closedUnanswered(err)) {
                    ProxyLog.trace("Upstream closed fetching " + ref + ", sending again: " + err.getMessage());
                    fetchManifest(clientReq, started, domain, name, reference, tag, ref, false);
                    return;
                }
                ProxyLog.warn("Upstream error fetching " + ref + ": " + err.getMessage());
                upstream.sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            ProxyLog.warn("Connect error fetching " + ref + ": " + err.getMessage());
            upstream.sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    /**
     * Find out, at most every {@link #PUBLIC_CHECK_INTERVAL_NANOS}, whether anyone may pull
     * {@code digest} from {@code domain/name}, and record it: only then is its copy served without
     * the registry's confirmation. Per digest, so a digest new to the repository is checked at once,
     * and stays unserved during a throttle until its check got an answer. It asks as an anonymous
     * client would, with none of the caller's headers: a HEAD for the manifest, and on a Bearer
     * challenge a pull token from its realm and the HEAD again with it. None of these is a pull Docker Hub counts. A failed exchange, a 5xx or a 429 changes
     * nothing, so a flaky registry does not drop the marker it is needed for; any other answer but
     * a 200 finds it not public. The token request follows no redirect.
     */
    private void checkPublicInBackground(String domain, String name, String digest, String type) {
        var key = domain + "/" + name + "@" + digest;
        var now = System.nanoTime();
        var due = new boolean[1];
        publicChecks.compute(key, (k, last) -> {
            due[0] = last == null || now - last >= PUBLIC_CHECK_INTERVAL_NANOS;
            return due[0] ? now : last;
        });
        if (!due[0]) return;
        var path = "/v2/" + name + "/manifests/" + digest;
        anonymousManifestHead(domain, path, type, null).compose(ArtifactCacheHandler::answered).compose(first -> {
            if (first.status() == 200) return Future.succeededFuture(true);
            var tokenUri = first.status() == 401
                    ? ManifestCache.tokenUri(first.header("WWW-Authenticate"), name, REGISTRY_DOMAINS) : null;
            if (tokenUri == null) return Future.succeededFuture(false);
            // Never redirected: the realm was checked, a redirect's target would not be
            var tokenPath = tokenUri.getRawPath() + "?" + tokenUri.getRawQuery();
            return retryOnceAfterConnect(() -> fetchSmallBody(tokenUri.getHost(), 443, tokenPath, FOLLOW_NO_REDIRECT,
                            ManifestCache.MAX_TOKEN_BYTES))
                    .compose(ArtifactCacheHandler::answered).compose(answer -> {
                        var token = answer.status() == 200 ? ManifestCache.token(answer.body()) : null;
                        if (token == null) return Future.succeededFuture(false);
                        return anonymousManifestHead(domain, path, type, token).compose(ArtifactCacheHandler::answered)
                                .map(second -> second.status() == 200);
                    });
        }).onSuccess(isPublic -> vertx.executeBlocking(() -> {
            ManifestCache.recordPublic(domain, name, digest, isPublic);
            return null;
        }, false)).onFailure(err -> publicChecks.remove(key));
    }

    /** {@code answer}, or a failure when upstream failed or throttled it: that says nothing about the repository. */
    private static Future<SidecarAnswer> answered(SidecarAnswer answer) {
        return answer.unreachable()
                ? Future.failedFuture(new IOException("upstream answered " + answer.status()))
                : Future.succeededFuture(answer);
    }

    /**
     * A HEAD for a manifest with no credential but an anonymous pull {@code token}, if any. A 401
     * keeps its {@code WWW-Authenticate}. Fails when upstream could not be asked.
     */
    private Future<SidecarAnswer> anonymousManifestHead(String domain, String path, String type, String token) {
        var options = probeOptions(HttpMethod.HEAD, domain, 443, path).putHeader("Accept", type);
        if (token != null) options.putHeader("Authorization", "Bearer " + token);
        return retryOnceAfterConnect(() -> upstream.requestWithAsyncDns(upstream.probeClient, options)
                .compose(req -> afterConnect(upstream.sendProbe(req, (resp, read) -> resp.end().map(v -> {
                    var challenge = resp.getHeader("WWW-Authenticate");
                    return new SidecarAnswer(resp.statusCode(), null, true,
                            challenge == null ? List.of() : List.of(Map.entry("WWW-Authenticate", challenge)));
                })))));
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
            ProxyLog.trace("Failed to serve cached file: " + err.getMessage());
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
        fetchCacheAndServe(clientReq, started, domain, cacheFile, ref, null, verification);
    }

    /** {@code credential}: a private repository's, sent in place of the client's own (#2312). */
    private void fetchCacheAndServe(HttpServerRequest clientReq, long started, String domain,
                                    Path cacheFile, String ref, Credential credential, Verification verification) {
        // Never a body: the fetch sends none, whatever the client did
        fetchCacheAndServe(clientReq, started, domain, cacheFile, ref, credential, verification,
                Upstream.mayResend(clientReq, false), 0);
    }

    /** One attempt at the fetch; {@code resend} lets it make one more on a new request; {@code throttled} counts the 429s it has already waited out. */
    private void fetchCacheAndServe(HttpServerRequest clientReq, long started, String domain,
                                    Path cacheFile, String ref, Credential credential, Verification verification,
                                    boolean resend, int throttled) {
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
            if (credential != null) upReq.putHeader(credential.header(), credential.value());
            // Never Connection: close, here or on a redirect or resume, so the connection is pooled
            // again (#2082). A server that closes anyway loses nothing: ResponseHold (#2088)

            // Don't let upstream gzip the response — we cache raw bytes
            // and serve them directly via sendFile on cache hits.
            upReq.headers().remove("Accept-Encoding");

            upReq.send().onSuccess(upResp -> {
                var statusCode = upResp.statusCode();
                // Every answer, a redirect included: the domain answered, whatever its target says
                upstream.throttles.answered(domain, statusCode);

                if (statusCode == 200) {
                    new CachingDownload(clientReq, domain, started, upResp, cacheFile, ref, verification).start();
                } else if (statusCode == 429 && !REGISTRY_DOMAINS.contains(domain)
                        && waitOutThrottle(clientReq, started, domain, upResp, ref, throttled,
                                () -> fetchCacheAndServe(clientReq, started, domain, cacheFile, ref,
                                        credential, verification, resend, throttled + 1))) {
                    // Asked again when the domain's schedule says, within the client's budget (#2302)
                } else if (statusCode >= 300 && statusCode < 400 && credential != null) {
                    // A private repository's redirect goes to the client, whose next request is proven
                    // and credentialed again: followed here, it would go out without the credential
                    upstream.relayResponse(clientReq, domain, upResp, started);
                } else if (statusCode >= 300 && statusCode < 400) {
                    // Follow redirect manually — Vert.x setFollowRedirects carries
                    // the original Host header, which breaks cross-domain redirects
                    // (e.g. plugins.gradle.org -> plugins-artifacts.gradle.org).
                    followRedirect(clientReq, domain, upResp, cacheFile, ref, verification, 0, started);
                } else {
                    upstream.relayResponse(clientReq, domain, upResp, started);
                }
            }).onFailure(err -> {
                // Not for a guest that has left: nobody would read the answer
                if (resend && !clientReq.response().closed() && Upstream.closedUnanswered(err)) {
                    // A pooled connection upstream closed as the fetch went out on it (#1923)
                    ProxyLog.trace("Upstream closed fetching " + ref + ", sending again: " + err.getMessage());
                    fetchCacheAndServe(clientReq, started, domain, cacheFile, ref, credential, verification, false, throttled);
                    return;
                }
                ProxyLog.warn("Upstream error fetching " + ref + ": " + err.getMessage());
                upstream.sendError(clientReq.response(), 502, "Upstream error");
            });
        }).onFailure(err -> {
            ProxyLog.warn("Connect error fetching " + ref + ": " + err.getMessage());
            upstream.sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    // The wait before asking again after a 429 with no Retry-After: this, doubled for each earlier 429 of the fetch
    private static final long THROTTLE_BASE_MILLIS = 2_000;
    // Never less: "Retry-After: 0" would otherwise ask again every slot, until the budget ends
    private static final long THROTTLE_MIN_WAIT_MILLIS = 1_000;
    // Between two fetches of a domain waiting out the same throttling, so the wait's end is not a burst
    private static final long THROTTLE_SPACING_MILLIS = 250;
    // The shortest read timeout of a client we serve: Gradle's socket timeout, 30s by default
    // (org.gradle.internal.http.socketTimeout); Maven's and npm's are minutes
    static final long CLIENT_READ_TIMEOUT_MILLIS = 30_000;
    // What a 429's wait leaves the client at least, for the ask after it
    private static final long THROTTLE_MIN_LEFT_MILLIS = 5_000;
    /** When each domain's next fetch waiting out a 429 may ask again (nanoTime), one schedule per domain. */
    private final ConcurrentHashMap<String, Long> throttleSchedule = new ConcurrentHashMap<>();

    /**
     * Upstream answered a cache miss 429: waits, then runs {@code again}, and returns true; or returns
     * false when {@link #throttleDelay} finds no wait that fits, or the client has left, so the 429 is
     * relayed as it is, its {@code Retry-After} with it.
     */
    private boolean waitOutThrottle(HttpServerRequest clientReq, long started, String domain,
                                    HttpClientResponse upResp, String ref, int throttled, Runnable again) {
        if (clientReq.response().closed()) return false;
        var delay = throttleDelay(domain, started, upResp.getHeader("Retry-After"), throttled);
        if (delay < 0) return false;
        // Over h2 the reset reaches this response as an error; it is the one we asked for
        upResp.exceptionHandler(ignored -> {});
        upResp.request().reset();
        ProxyLog.trace("Upstream throttled " + ref + " (429), asking again in " + delay + "ms");
        vertx.setTimer(delay, t -> {
            if (clientReq.response().closed()) return;
            again.run();
        });
        return true;
    }

    /**
     * How long a fetch that upstream answered 429 waits before asking again, its slot on the domain's
     * schedule reserved; or -1, nothing reserved, when the wait does not end in time. The wait is
     * upstream's {@code Retry-After}, else a backoff, and at least the domain's schedule, which spaces
     * the fetches waiting on one domain instead of letting them ask at once. It ends in time when it
     * leaves {@link #THROTTLE_MIN_LEFT_MILLIS} before the client's silence budget and before
     * {@link #CLIENT_READ_TIMEOUT_MILLIS}, both counted from the client's request ({@code started}).
     */
    long throttleDelay(String domain, long started, String retryAfter, int throttled) {
        var now = System.nanoTime();
        var advised = retryAfterMillis(retryAfter);
        var wait = Math.max(THROTTLE_MIN_WAIT_MILLIS,
                advised >= 0 ? advised : THROTTLE_BASE_MILLIS << Math.min(throttled, 4));
        var limit = Math.min(upstream.silenceLeftMillis(started),
                CLIENT_READ_TIMEOUT_MILLIS - TimeUnit.NANOSECONDS.toMillis(now - started)) - THROTTLE_MIN_LEFT_MILLIS;
        // Checked before the schedule too: a huge Retry-After would overflow the nanoTime arithmetic below
        if (wait > limit) return -1;
        var slot = new long[] {-1};
        throttleSchedule.compute(domain, (d, next) -> {
            var at = next == null ? now + TimeUnit.MILLISECONDS.toNanos(wait)
                    : Math.max(now + TimeUnit.MILLISECONDS.toNanos(wait), next);
            var delay = TimeUnit.NANOSECONDS.toMillis(at - now);
            // A slot that does not fit is not reserved, so it does not delay the fetches that can
            if (delay > limit) return next;
            slot[0] = delay;
            return at + TimeUnit.MILLISECONDS.toNanos(THROTTLE_SPACING_MILLIS);
        });
        return slot[0];
    }

    /** A {@code Retry-After} in milliseconds, whole seconds or an HTTP date; -1 when absent or unreadable. */
    static long retryAfterMillis(String value) {
        if (value == null) return -1;
        var text = value.trim();
        try {
            if (!text.isEmpty() && text.chars().allMatch(Character::isDigit)) {
                return text.length() > 9 ? Long.MAX_VALUE / 4 : Long.parseLong(text) * 1000;
            }
            var date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, date.toInstant().toEpochMilli() - System.currentTimeMillis());
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    private static final int MAX_REDIRECTS = 10;
    // fetchSmallBody's depth when no redirect may be followed: it follows one only below MAX_REDIRECTS
    private static final int FOLLOW_NO_REDIRECT = MAX_REDIRECTS;

    /**
     * Follow a 3xx redirect from upstream, making a new request to the Location URL.
     * Uses the redirect target's host for both the connection and Host header.
     * Handles multi-hop cross-domain redirects manually because Vert.x's built-in
     * setFollowRedirects carries the original Host header across domains.
     */
    private void followRedirect(HttpServerRequest clientReq, String domain, HttpClientResponse upResp,
                                Path cacheFile, String ref,
                                Verification verification, int depth, long started) {
        if (depth >= MAX_REDIRECTS) {
            ProxyLog.trace("Too many redirects for " + ref);
            upstream.sendError(clientReq.response(), 502, "Too many redirects");
            return;
        }

        var location = upResp.getHeader("Location");
        if (location == null) {
            ProxyLog.trace("Redirect with no Location header for " + ref);
            upstream.sendError(clientReq.response(), 502, "Redirect with no Location");
            return;
        }

        var from = upResp.request();
        var redirectUri = redirectTarget(from.authority().host(), from.authority().port(), from.getURI(), location);
        if (redirectUri == null) {
            ProxyLog.trace("Invalid redirect Location for " + ref + ": " + location);
            upstream.sendError(clientReq.response(), 502, "Invalid redirect Location");
            return;
        }

        fetchRedirect(clientReq, domain, redirectUri, location, cacheFile, ref, verification, depth, started, true);
    }

    /** One attempt at a redirect's GET; {@code resend} lets it make one more on a new request. */
    private void fetchRedirect(HttpServerRequest clientReq, String domain, URI redirectUri, String location,
                               Path cacheFile, String ref, Verification verification, int depth, long started,
                               boolean resend) {
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

            redReq.send().onSuccess(redResp -> {
                var statusCode = redResp.statusCode();
                // The target's own limit, under its own name, not the domain that redirected there
                upstream.throttles.answered(redirectHost, statusCode);
                if (statusCode == 200) {
                    new CachingDownload(clientReq, domain, started, redResp, cacheFile, ref, verification).start();
                } else if (statusCode >= 300 && statusCode < 400) {
                    followRedirect(clientReq, domain, redResp, cacheFile, ref, verification, depth + 1, started);
                } else {
                    ProxyLog.warn("Redirect target " + redirectHost + " returned " +
                            statusCode + " for " + ref + " (Location: " + location + ")");
                    upstream.relayResponse(clientReq, domain, redResp, started);
                }
            }).onFailure(err -> {
                if (resend && !clientReq.response().closed() && Upstream.closedUnanswered(err)) {
                    ProxyLog.trace("Upstream closed following redirect for " + ref + ", sending again: " + err.getMessage());
                    fetchRedirect(clientReq, domain, redirectUri, location, cacheFile, ref, verification, depth, started, false);
                    return;
                }
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
        private final HttpServerRequest clientReq;
        private final String domain;
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
        private ResponseHold current;
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

        CachingDownload(HttpServerRequest clientReq, String domain, long started, HttpClientResponse first,
                        Path cacheFile, String ref, Verification verification) {
            this.clientReq = clientReq;
            this.domain = domain;
            this.clientResp = clientReq.response();
            this.clientActive = started;
            this.first = first;
            this.cacheFile = cacheFile;
            this.ref = ref;
            this.verification = verification;
            var contentEncoding = first.getHeader("Content-Encoding");
            this.isGzip = contentEncoding != null && contentEncoding.toLowerCase().contains("gzip");
            this.length = HttpValidator.contentLength(first::getHeader);
            // A strong ETag, or with no ETag a Last-Modified a second older than Date: a server
            // ignores a weak ETag in If-Range, and without a validator there is no resume
            this.validator = HttpValidator.strong(first::getHeader);
            // Compressed on the fly, the same file need not give the same bytes twice
            this.neverResumable = contentEncoding != null ? "it is " + contentEncoding + "-encoded"
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
                    upstream.relayBody(clientReq, domain, current, clientActive);
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

        private void attach(HttpClientResponse response) {
            var upResp = new ResponseHold(response);
            current = upResp;
            upstreamActive = System.nanoTime();
            upResp.hold();
            upResp.handler(chunk -> {
                if (upResp != current) return;
                received += chunk.length();
                resumes = 0;
                clientActive = upstreamActive = System.nanoTime();
                if (!clientGone) clientResp.write(chunk);
                file.write(chunk);
                if (clientBackedUp() || file.writeQueueFull()) {
                    upResp.hold();
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
                current.release();
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
            resume(true);
        }

        /** One attempt at the Range request; {@code resend} lets it make one more on a new request. */
        private void resume(boolean resend) {
            if (failed) return;
            var from = first.request();
            var options = new RequestOptions()
                    .setMethod(HttpMethod.GET)
                    .setHost(from.authority().host())
                    .setPort(from.authority().port())
                    .setURI(from.getURI())
                    .setConnectTimeout(resumeTimeoutMillis(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS));
            var headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap().setAll(from.headers());
            var resumeAt = received;
            upstream.requestWithAsyncDns(options).onSuccess(req -> {
                req.idleTimeout(resumeTimeoutMillis(idleMillis()));
                req.headers().setAll(headers);
                req.putHeader("Range", "bytes=" + resumeAt + "-");
                // Only the same bytes may continue: a changed file comes back whole (200)
                req.putHeader("If-Range", validator);
                req.send().onSuccess(resp -> resumed(resp, resumeAt)).onFailure(err -> {
                    if (resend && !failed && Upstream.closedUnanswered(err)) {
                        // A pooled connection upstream closed as the resume went out on it: not a
                        // failed resume, so neither counted nor backed off (#2094, as #1923)
                        ProxyLog.trace("Upstream closed resuming " + ref + ", sending again: " + err.getMessage());
                        resume(false);
                        return;
                    }
                    broken(err, true);
                });
            }).onFailure(err -> broken(err, true));
        }

        /** What the Range request was answered: the rest of this file, or why not. */
        private void resumed(HttpClientResponse resp, long resumeAt) {
            if (failed) {
                resp.request().reset();
                return;
            }
            // Under the host the resume asked, a redirect's target included, not the domain the client named
            upstream.throttles.answered(resp.request().authority().host(), resp.statusCode());
            if (resp.statusCode() == 206 && resumesAt(resp, resumeAt)) {
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
                        + (resp.statusCode() == 206 ? " with another validator or range" : "")
                        + " to a Range request");
                fail("Upstream stream error");
            }
        }

        /** Detached first: its reset reports back to its handlers, which must not take it as a new break. */
        private void abandonCurrent() {
            var upResp = current;
            current = null;
            if (upResp != null) upResp.reset();
        }

        /** Whether a 206 carries the rest of this file, from {@code offset} to its end, whatever If-Range got. */
        private boolean resumesAt(HttpClientResponse resp, long offset) {
            return HttpValidator.continues(validator, length, offset, resp::getHeader);
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
                ProxyLog.trace("No usable " + checksum.extension + " for " + ref + ", not caching");
                Files.deleteIfExists(tempFile);
            } else switch (VerifiedArtifactStore.verifyAndCommit(tempFile, cacheFile, checksum, expected,
                    verification.storeSidecar(), verification.alongside(), verification.lookedUp())) {
                case COMMITTED -> ProxyLog.trace("Cached: " + ref + " (" + checksum.extension + " verified, " +
                        formatSize(Files.size(cacheFile)) + ")");
                case SUPERSEDED -> ProxyLog.trace("Not caching " + ref
                        + ": upstream confirmed the cached copy after this download's lookup");
                case REJECTED -> ProxyLog.trace("Cache: " + checksum.extension + " mismatch for " +
                        ref + ", not caching");
            }
        } catch (Exception e) {
            ProxyLog.trace("Failed to finalize cache for " + ref + ": " + e.getMessage());
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
     *       the package's ETag hasn't changed since the tarball was verified and that
     *       confirmation is still trusted ({@link #npmTiers}). Otherwise the
     *       tarball's digest is re-verified against per-version metadata. Cache misses
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
            upstream.relayRequest(clientReq, domain, started);
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
                        upstream.relayRequest(clientReq, domain, started);
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
            relayNpmPackument(clientReq, started, domain, packumentMatcher.group(1));
            return;
        }

        upstream.relayRequest(clientReq, domain, started);
    }

    /**
     * Relay a packument request to upstream and store the response ETag.
     * The ETag is used by tarball cache hits to skip re-verification when
     * the packument hasn't changed.
     */
    private void relayNpmPackument(HttpServerRequest clientReq, long started, String domain,
                                    String packageName) {
        upstream.relayRequest(clientReq, domain, started, upResp -> {
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
            ProxyLog.trace("npm: failed to store ETag for " + packageName +
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

    /**
     * {@code confirmAgain}: a hit served on an aging confirmation, to be confirmed in the background.
     * A miss carries the digest to verify the download on, the package ETag to store with it,
     * and when upstream was asked for that digest, which orders its commit (#1538).
     */
    record NpmVerifyResult(boolean cacheHit, long size, String digest, String packageEtag, Instant lookedUp,
                           boolean confirmAgain) {
        static NpmVerifyResult hit(long size, boolean confirmAgain) {
            return new NpmVerifyResult(true, size, null, null, null, confirmAgain);
        }

        static NpmVerifyResult miss(String digest, String packageEtag, Instant lookedUp) {
            return new NpmVerifyResult(false, 0, digest, packageEtag, lookedUp, false);
        }
    }

    /** Upstream's answer for a version document, and when it was asked for: the ask that answered, after a retry. */
    record NpmLookup(SidecarAnswer answer, Instant askedAt) {
        /** No ask at all (a backoff): the answer is as of now. */
        static NpmLookup unreachable() {
            return new NpmLookup(SidecarAnswer.UNREACHABLE, Instant.now());
        }
    }

    /**
     * Serve an npm tarball from cache or fetch fresh.
     * <p>
     * <b>Cache hit + ETag unchanged</b>: serve directly while the tarball's last
     * confirmation is within {@link #npmTiers}' fresh window, and confirm it again
     * in the background within max-stale. The ETag alone is not enough: a lockfile
     * install ({@code npm ci}) fetches no packument, so it never changes, and a version
     * removed upstream would be served forever (#1466).
     * <p>
     * <b>Cache hit + ETag changed/missing, or confirmation too old</b>: fetch per-version metadata, compare
     * the upstream digest with the stored sidecar. Same digest → update the tarball's
     * ETag marker and serve. Different digest → evict and re-fetch. No digest: see
     * {@link #checkNpmTarballCache}; only an unreachable registry serves the copy.
     * <p>
     * <b>Cache miss</b>: fetch the per-version digest, download with digest verification,
     * and commit the tarball with its digest + ETag sidecar files ({@link Verification#ofNpmTarball}).
     */
    private void fetchNpmTarballAndServe(HttpServerRequest clientReq, long started, String domain,
                                          Path cacheFile, String ref,
                                          String packageName, String version) {
        // The disk work stays ordered, behind the packument's ETag write (relayNpmPackument) and
        // other tarballs' settling. That queue is the one every request on this server
        // shares, so the digest lookup waits outside it: inside, a cold install's lookups ran
        // one at a time (#960).
        vertx.executeBlocking(() -> {
            var cacheDir = npmCacheDir();
            var etagFile = cacheDir.resolve(packageName).resolve(".etag").normalize();
            var packageEtag = etagFile.startsWith(cacheDir)
                    ? readFileOrNull(etagFile) : null;
            return npmCacheState(cacheFile, packageEtag, npmTiers(), Instant.now());
        }).compose(state -> {
            if (state.hit() != null) return Future.succeededFuture(state.hit());
            // A copy to fall back on is served at once during a backoff, as for Maven/Gradle; with none, the registry is still asked
            var lookup = state.cached() && inBackoff(domain)
                    ? Future.succeededFuture(NpmLookup.unreachable())
                    : fetchNpmVersion(domain, packageName, version, started,
                            state.cached() ? () -> false : () -> !clientReq.response().closed());
            // Ordered by when upstream was asked, not by when the request came in: the queue above can be long
            return lookup.compose(l -> vertx.executeBlocking(
                    () -> checkNpmTarballCache(cacheFile, state.packageEtag(), ref, l.answer(), l.askedAt()))
                    .onSuccess(r -> {
                        // Served unconfirmed: a lookup that could not answer, or none made during a backoff
                        if (r != null && r.cacheHit() && l.answer().unreachable()) {
                            upstream.throttles.servedUnconfirmed(domain, l.answer().status());
                        }
                    }));
        }).onSuccess(result -> {
            if (result == null) {
                // Said here, so a relay that fails points at the lookup that sent it there
                ProxyLog.warn("No npm digest for " + ref + "; relaying it uncached");
                upstream.relayRequest(clientReq, domain, started);
            } else if (result.cacheHit()) {
                ProxyLog.trace("npm cache hit: " + ref +
                        " (" + formatSize(result.size()) + ")");
                serveCachedFile(clientReq.response(), cacheFile, null);
                if (result.confirmAgain()) {
                    confirmNpmInBackground(domain, cacheFile, ref, packageName, version);
                }
            } else {
                fetchCacheAndServe(clientReq, started, domain, cacheFile, ref,
                        Verification.ofNpmTarball(result.digest(), result.packageEtag(), result.lookedUp()));
            }
        }).onFailure(err -> {
            ProxyLog.trace("npm integrity check error for " + ref +
                    ": " + err.getMessage());
            upstream.relayRequest(clientReq, domain, started);
        });
    }

    /**
     * The package's stored ETag, whether the tarball is cached, and the cached tarball when
     * that ETag and its last confirmation vouch for it without a lookup first.
     */
    record NpmCacheState(String packageEtag, boolean cached, NpmVerifyResult hit) {}

    /**
     * A hit on the cached tarball when the package's ETag is the one it was verified under
     * and {@code tiers} still trusts its last confirmation (the stored shasum's mtime), as
     * for Maven/Gradle; else no hit, and the tarball is confirmed before it is served.
     */
    static NpmCacheState npmCacheState(Path cacheFile, String packageEtag, ArtifactCacheTiers tiers, Instant now)
            throws IOException {
        var tarball = VerifiedArtifactStore.attributesOrNull(cacheFile);
        var cached = tarball != null && tarball.isRegularFile();
        if (packageEtag == null || packageEtag.isEmpty() || !cached
                || !packageEtag.equals(readFileOrNull(Path.of(cacheFile + ".etag")))) {
            return new NpmCacheState(packageEtag, cached, null);
        }
        var shasum = VerifiedArtifactStore.attributesOrNull(Path.of(cacheFile + ".shasum"));
        var tier = tiers.tierOf(shasum == null ? null : shasum.lastModifiedTime(), now);
        return new NpmCacheState(packageEtag, true, tier == ArtifactCacheTiers.Tier.EXPIRED ? null
                : NpmVerifyResult.hit(tarball.size(), tier == ArtifactCacheTiers.Tier.STALE));
    }

    /**
     * Confirm a tarball just served on an aging confirmation, as for Maven/Gradle: a match
     * renews it, a change or removal evicts it so the next request gets upstream's answer,
     * and an answer that settles neither expires the confirmation so the next hit is
     * confirmed first. An unreachable registry, or one in backoff, leaves it as it is.
     * The tarball's ETag marker already matches the package's, or it would not have been a hit.
     */
    private void confirmNpmInBackground(String domain, Path cacheFile, String ref, String packageName,
                                        String version) {
        // Checked when it runs, not when it is queued: past the cap it may start after a backoff began.
        // Timed from then too, not from the request it followed: no client waits on it.
        inBackground(cacheFile, () -> inBackoff(domain) ? Future.succeededFuture()
                : fetchNpmVersion(domain, packageName, version, System.nanoTime(), () -> false)
                        .compose(l -> l.answer().unreachable() ? Future.succeededFuture() : vertx.executeBlocking(() -> {
                            reconcileNpmCopy(cacheFile, ref, l.answer(), l.askedAt());
                            return null;
                        }))
                        .onFailure(err -> ProxyLog.trace("npm background confirmation error for " + ref
                                + ": " + err.getMessage())));
    }

    /**
     * Reconcile a cached tarball with upstream's (reachable) answer, for a background
     * confirmation. It only ever renews, expires or evicts the copy there is, and never
     * writes a sidecar: a foreground request may have evicted the copy meanwhile and be
     * fetching it again, and its sidecars are not this one's to touch. The check and what
     * follows hold {@link VerifiedArtifactStore}'s per-artifact lock, which the foreground's
     * settling ({@link #checkNpmTarballCache}) and a download's commit with its sidecars hold
     * too, so neither runs in the middle of the other. The answer was fetched before the lock, though, so it
     * is dropped when upstream confirmed the copy after {@code lookedUp}, the answer's lookup: a copy the
     * foreground replaced and committed meanwhile is not evicted, renewed or expired on an older answer.
     */
    static void reconcileNpmCopy(Path cacheFile, String ref, SidecarAnswer answer, Instant lookedUp) throws IOException {
        var digest = answer.status() == 200 ? npmDigest(answer.body()) : null;
        // Under the artifact's lock, as is the foreground's evict-and-write (checkNpmTarballCache)
        synchronized (VerifiedArtifactStore.lockFor(cacheFile)) {
            var storedDigest = Path.of(cacheFile + ".shasum");
            // Confirmed after this lookup, by a commit or another check: the answer has nothing newer to say (#1538)
            if (!Files.isRegularFile(cacheFile) || VerifiedArtifactStore.confirmedAfter(storedDigest, lookedUp)) return;
            var stored = storedNpmDigest(storedDigest);
            if (digest != null && digest.equals(stored)) {
                VerifiedArtifactStore.renewConfirmation(cacheFile, storedDigest, lookedUp);
            } else if (digest != null || answer.gone()) {
                ProxyLog.trace("npm cache: " + ref + (digest != null ? npmMismatch(stored, digest)
                        : " is gone upstream (" + answer.status() + ")") + ", evicting");
                evictNpmTarball(cacheFile);
            } else {
                VerifiedArtifactStore.expireConfirmation(cacheFile, storedDigest);
            }
        }
    }

    /**
     * Settle a tarball that {@link #npmCacheState} could not serve, given upstream's
     * {@code answer} for its version document. Null means relay it uncached.
     * <p>
     * When upstream answers, its answer wins, as for Maven/Gradle (#555): a cached copy is
     * served only on a matching digest, and a version that is gone (404/410: unpublished,
     * or taken down as malware) is evicted so the client gets upstream's answer. Any other
     * answer without a digest confirms nothing and relays. Only an unreachable registry
     * (no connection, 5xx, 429) serves the copy unconfirmed (#787). An answer from a lookup made
     * before upstream last confirmed the copy ({@code lookedUp}) is older news, and serves the copy (#1538).
     */
    static NpmVerifyResult checkNpmTarballCache(Path cacheFile, String packageEtag,
                                                 String ref, SidecarAnswer answer, Instant lookedUp)
            throws IOException {
        // Parsed before taking the lock, which other artifacts share
        var digest = answer.status() == 200 ? npmDigest(answer.body()) : null;
        synchronized (VerifiedArtifactStore.lockFor(cacheFile)) {
            var cached = Files.isRegularFile(cacheFile);
            var storedDigest = Path.of(cacheFile + ".shasum");
            if (cached && VerifiedArtifactStore.confirmedAfter(storedDigest, lookedUp)) {
                // Upstream confirmed the copy after this lookup, which a slow answer can predate (#1538)
                return NpmVerifyResult.hit(Files.size(cacheFile), false);
            }

            if (digest == null) {
                if (!cached) return null;
                if (answer.unreachable()) {
                    ProxyLog.trace("npm: serving " + ref + " unconfirmed, the registry "
                            + (answer.status() > 0 ? "answered " + answer.status() : "is unreachable"));
                    return NpmVerifyResult.hit(Files.size(cacheFile), false);
                }
                if (answer.gone()) {
                    ProxyLog.trace("npm cache: " + ref + " is gone upstream (" + answer.status() + "), evicting");
                    evictNpmTarball(cacheFile);
                }
                return null;
            }

            if (cached) {
                var stored = storedNpmDigest(storedDigest);
                if (digest.equals(stored)) {
                    VerifiedArtifactStore.renewConfirmation(cacheFile, storedDigest, lookedUp);
                    if (packageEtag != null) {
                        Files.writeString(Path.of(cacheFile + ".etag"), packageEtag);
                    }
                    return NpmVerifyResult.hit(Files.size(cacheFile), false);
                }
                ProxyLog.trace("npm cache: " + ref + npmMismatch(stored, digest) + ", evicting");
                evictNpmTarball(cacheFile);
            }

            // Its sidecars are committed with the download (Verification.ofNpmTarball)
            return NpmVerifyResult.miss(digest, packageEtag, lookedUp);
        }
    }

    private static void evictNpmTarball(Path cacheFile) throws IOException {
        Files.deleteIfExists(cacheFile);
        Files.deleteIfExists(Path.of(cacheFile + ".shasum"));
        Files.deleteIfExists(Path.of(cacheFile + ".etag"));
    }

    /**
     * The {@link #npmDigest} a tarball was verified against, from its {@code .shasum} sidecar.
     * Before #1465 that held a bare SHA-1, read here as {@code sha1:<hex>}: it still matches a
     * version that has no SHA-512 integrity, and a version that has one is downloaded once
     * more and verified on it.
     */
    static String storedNpmDigest(Path storedDigest) {
        var stored = readFileOrNull(storedDigest);
        return stored == null || stored.indexOf(':') >= 0 ? stored : "sha1:" + stored;
    }

    /** Why a cached tarball stored under {@code stored} does not match upstream's {@code digest}. */
    private static String npmMismatch(String stored, String digest) {
        var was = stored == null ? null : stored.substring(0, Math.max(stored.indexOf(':'), 0));
        var now = digest.substring(0, digest.indexOf(':'));
        return was == null || was.equals(now) ? " changed upstream"
                : " was verified on " + was + ", re-verifying on " + now;
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SHA1_HEX = Pattern.compile("[a-f0-9]{40}");

    // A version document is a few KB; old ones can carry their readme
    private static final int MAX_NPM_VERSION_BYTES = 1024 * 1024;

    /**
     * Fetch an npm package version's metadata document ({@code /<package>/<version>}),
     * which carries its digests, through {@code probeClient} like every other checksum, so
     * lookups run concurrently on shared connections. Never fails; see {@link SidecarAnswer}
     * for what comes back instead. {@link #npmDigest} parses it, on a worker thread.
     * <p>
     * A 5xx is asked once more while over half the client's silence budget is left, so the
     * tarball's head still has time: without a digest the tarball is relayed uncached, and a
     * relayed body that stalls cannot be resumed (#925, #929). A 429 is not asked again at once,
     * where it would only be refused again: while {@code clientWaits} (no copy to serve meanwhile),
     * it is asked again after the wait {@link #throttleDelay} gives, on the domain's schedule (#2302).
     * <p>
     * Each ask joins one of the same version already in flight (#1567), but the retry is each
     * caller's own, decided on its own budget, so a joined caller does not depend on the first one's client.
     */
    Future<NpmLookup> fetchNpmVersion(String domain, String packageName, String version, long started,
                                      BooleanSupplier clientWaits) {
        return fetchNpmVersion(domain, packageName, version, started, clientWaits, 0);
    }

    private Future<NpmLookup> fetchNpmVersion(String domain, String packageName, String version, long started,
                                              BooleanSupplier clientWaits, int throttled) {
        var path = "/" + packageName.replace("/", "%2F") + "/" + version;
        Supplier<Future<NpmLookup>> ask = () -> npmLookups.join(domain + path, () -> {
            var asked = Instant.now();
            return fetchSmallBody(domain, path, MAX_NPM_VERSION_BYTES).map(answer -> new NpmLookup(answer, asked));
        });
        return ask.get().compose(lookup -> {
            var status = lookup.answer().status();
            if (status >= 500
                    && upstream.silenceLeftMillis(started) > TimeUnit.SECONDS.toMillis(upstream.clientSilenceBudgetSeconds) / 2) {
                return ask.get();
            }
            var delay = status == 429 && clientWaits.getAsBoolean()
                    ? throttleDelay(domain, started, lookup.answer().header("Retry-After"), throttled) : -1;
            if (delay < 0) return Future.succeededFuture(lookup);
            ProxyLog.trace("Upstream throttled " + domain + path + " (429), asking again in " + delay + "ms");
            var again = Promise.<NpmLookup>promise();
            vertx.setTimer(delay, t -> {
                // Not asked again for a client that left meanwhile
                if (!clientWaits.getAsBoolean()) again.complete(lookup);
                else fetchNpmVersion(domain, packageName, version, started, clientWaits, throttled + 1).onComplete(again);
            });
            return again.future();
        });
    }

    /**
     * The digest a version document vouches for its tarball with: {@code sha512:<hex>} from
     * {@code dist.integrity}, else {@code sha1:<hex>} from its {@code sha1-} hash (which npm
     * checks), else from {@code dist.shasum}; or null when it has none we can use. SHA-1 is
     * used only when the integrity is absent or every hash it lists is SHA-1: any other
     * integrity that gives no SHA-512 we can read (malformed, not a string, or only another
     * algorithm) gives null rather than SHA-1 (#1465).
     */
    static String npmDigest(byte[] body) {
        if (body == null) return null;
        try {
            var dist = Jjq.parseForeign(body, "the npm registry's metadata").getField("dist");
            var integrity = dist.getField("integrity");
            if (!integrity.isNull()) {
                if (!integrity.isString()) return null;
                // An SRI string: whitespace-separated <algorithm>-<base64>[?options]
                var sha1Only = true;
                String sha1 = null;
                var text = integrity.asText().trim();
                for (var entry : text.isEmpty() ? new String[0] : WHITESPACE.split(text)) {
                    var dash = entry.indexOf('-');
                    var algorithm = dash < 0 ? "" : entry.substring(0, dash).toLowerCase(Locale.ROOT);
                    if (algorithm.equals("sha512")) {
                        var hash = sriHash(entry.substring(dash + 1), 64);
                        if (hash != null) return "sha512:" + HexFormat.of().formatHex(hash);
                    } else if (algorithm.equals("sha1") && sha1 == null) {
                        var hash = sriHash(entry.substring(dash + 1), 20);
                        if (hash != null) sha1 = "sha1:" + HexFormat.of().formatHex(hash);
                    }
                    sha1Only &= algorithm.equals("sha1");
                }
                if (!sha1Only) return null;
                if (sha1 != null) return sha1;
            }
            var shasum = dist.getField("shasum");
            if (shasum.isString()) {
                var hex = shasum.asText().trim().toLowerCase(Locale.ROOT);
                if (SHA1_HEX.matcher(hex).matches()) return "sha1:" + hex;
            }
        } catch (Exception e) { /* JSON parse error */ }
        return null;
    }

    /** The {@code length}-byte hash an SRI entry's {@code <base64>[?options]} carries, or null if it is not one. */
    private static byte[] sriHash(String value, int length) {
        var options = value.indexOf('?');
        try {
            var hash = Base64.getDecoder().decode(options < 0 ? value : value.substring(0, options));
            return hash.length == length ? hash : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Maven/Gradle artifact caching ---
    //
    // Serve a cached artifact only when a direct fetch would return the same bytes:
    // - an artifact is stored only after matching a checksum from upstream, and that
    //   checksum is stored with it as a sidecar (VerifiedArtifactStore);
    // - a hit is served on its last confirmation with upstream while its repository's
    //   tiers trust it (trustTier, Revalidation.tiersOr; confirmed again in the background once it ages), and confirmed before
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
     * server-computed header rather than a separately uploaded sidecar. {@code headers}: those of
     * upstream's own that go out with its status when the client gets it (#2309), on an error alone.
     */
    record SidecarAnswer(int status, byte[] body, boolean fromHeader, List<Map.Entry<String, String>> headers) {
        static final SidecarAnswer UNREACHABLE = new SidecarAnswer(-1, null);
        static final SidecarAnswer UNUSABLE = new SidecarAnswer(0, null);

        SidecarAnswer(int status, byte[] body) {
            this(status, body, false);
        }

        SidecarAnswer(int status, byte[] body, boolean fromHeader) {
            this(status, body, fromHeader, List.of());
        }

        /**
         * No connection, or upstream failing (5xx) or throttling (429): without the
         * cache the client would get an error.
         */
        boolean unreachable() {
            return this == UNREACHABLE || status >= 500 || status == 429;
        }

        /** Upstream says the document does not exist (any more). */
        boolean gone() {
            return status == 404 || status == 410;
        }

        /** The first of upstream's {@link #headers} named {@code name}, or null. */
        String header(String name) {
            return headers.stream().filter(h -> h.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
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
     * sidecar, and {@code alongside} is committed with it (see
     * {@link VerifiedArtifactStore#verifyAndCommit(Path, Path, Sidecar, byte[], boolean, SequencedMap, Instant)}).
     * {@code lookedUp} is no later than the moment upstream was asked for the checksum.
     * {@code responseHeaders} adds what this kind of cache tells the client.
     */
    record Verification(Sidecar checksum,
                        Function<HttpClientResponse, Future<byte[]>> expected,
                        boolean storeSidecar,
                        SequencedMap<String, byte[]> alongside,
                        Instant lookedUp,
                        BiConsumer<HttpServerResponse, HttpClientResponse> responseHeaders) {

        /**
         * A content digest known up front, {@code sha256:<hex>} (OCI blobs). Content-addressed,
         * so nothing beside the blob vouches for it, and when it was looked up does not matter.
         */
        static Verification ofDigest(String digest) {
            return ofDigest(digest, VerifiedArtifactStore.NOTHING_ALONGSIDE, Instant.now());
        }

        /**
         * An npm tarball, committed with its sidecars: the {@link #npmDigest} it is verified
         * against as {@code .shasum}, and the package ETag it was looked up under as {@code .etag}.
         * Written with the tarball, under its lock, so a concurrent miss for the same tarball
         * whose lookup found another digest cannot leave its digest beside these bytes (#1464),
         * and not over a copy confirmed after {@code lookedUp}, the digest's lookup (#1538).
         */
        static Verification ofNpmTarball(String digest, String packageEtag, Instant lookedUp) {
            var alongside = new LinkedHashMap<String, byte[]>();
            // Without one, a previous copy's is still dropped: it vouched for other bytes
            alongside.put(".etag", packageEtag == null ? null : packageEtag.getBytes(StandardCharsets.UTF_8));
            alongside.put(".shasum", digest.getBytes(StandardCharsets.US_ASCII));
            return ofDigest(digest, alongside, lookedUp);
        }

        /**
         * A container image manifest of digest {@code digest} (#2306), committed with its media type
         * beside it, which goes to the client in place of a blob's {@code application/octet-stream}.
         */
        static Verification ofManifest(String digest, String type) {
            var blob = ofDigest(digest, ManifestCache.alongside(type), Instant.now());
            return new Verification(blob.checksum(), blob.expected(), false, blob.alongside(), blob.lookedUp(),
                    (clientResp, upResp) -> {
                        clientResp.putHeader("Content-Type", type);
                        blob.responseHeaders().accept(clientResp, upResp);
                    });
        }

        private static Verification ofDigest(String digest, SequencedMap<String, byte[]> alongside, Instant lookedUp) {
            var checksum = digestAlgorithm(digest);
            // An algorithm we cannot check is never cached
            byte[] expected = checksum == null ? null : digestHex(digest).getBytes(StandardCharsets.US_ASCII);
            return new Verification(checksum == null ? Sidecar.SHA256 : checksum,
                    resp -> Future.succeededFuture(expected), false, alongside, lookedUp,
                    (clientResp, upResp) -> clientResp.putHeader("Docker-Content-Digest", digest));
        }

        /**
         * A Maven/Gradle artifact, committed with the checksum it was verified against. That
         * checksum comes with or after the download, so the download's start is its lookup.
         */
        static Verification ofArtifact(Sidecar checksum, Function<HttpClientResponse, Future<byte[]>> expected) {
            return new Verification(checksum, expected, true, VerifiedArtifactStore.NOTHING_ALONGSIDE, Instant.now(),
                    (clientResp, upResp) -> {
                        // Pass on the checksums upstream sent with these bytes (Maven smart checksums)
                        for (var header : new String[] {"X-Checksum-SHA1", "X-Checksum-MD5"}) {
                            var value = upResp.getHeader(header);
                            if (value != null) clientResp.putHeader(header, value);
                        }
                    });
        }

        /** The checksum type a {@code sha256:}/{@code sha512:}/{@code sha1:} digest names, or null for any other. */
        static Sidecar digestAlgorithm(String digest) {
            var colon = digest.indexOf(':');
            return colon < 0 ? null : switch (digest.substring(0, colon)) {
                case "sha256" -> Sidecar.SHA256;
                case "sha1" -> Sidecar.SHA1;
                case "sha512" -> Sidecar.SHA512;
                default -> null;
            };
        }

        static String digestHex(String digest) {
            return digest.substring(digest.indexOf(':') + 1);
        }
    }

    /**
     * Where a request's artifact is cached, the checksum describing it, any copy in the host's
     * ~/.m2, and where its repository's mirror holds it (#2304), or null.
     */
    record ArtifactTarget(Path artifact, Sidecar checksum, Path hostCopy, Credential credential,
                          CachedRepository.MirrorSource mirror) {
        ArtifactTarget(Path artifact, Sidecar checksum, Path hostCopy) {
            this(artifact, checksum, hostCopy, null, null);
        }

        ArtifactTarget(Path artifact, Sidecar checksum, Path hostCopy, Credential credential) {
            this(artifact, checksum, hostCopy, credential, null);
        }
    }

    /**
     * The credential a private repository's request carries upstream (#2312): the header the proxy
     * injected for the caller's proven account, in the namespace the repository names. Never a
     * {@code RequestContext}: the cache sees the one header it sends and nothing it could choose by.
     * Every confirmation and download of that request sends it, so upstream decides each serve.
     */
    record Credential(String namespace, String header, String value) {
        @Override
        public String toString() {
            return "Credential[" + namespace + ", " + header + "]";
        }
    }

    private static final int MAX_SIDECAR_BYTES = 64 * 1024;

    /**
     * Handle a GET for an artifact or one of its sidecars on a domain whose
     * repositories are declared for caching (see {@link Revalidation} for what a
     * declaration says): the repository whose prefix holds the path maps it to
     * where it is cached, or to null for a path that must not be. Everything else
     * is relayed, including a request with a query string: the cache is keyed by
     * path, and a query may select something else.
     */
    private void handleArtifactRequest(HttpServerRequest clientReq, long started, String domain,
                                       List<CachedRepository> cached) {
        var path = clientReq.path();
        var repository = path == null ? null : repositoryOf(cached, path);

        if (clientReq.method() == HttpMethod.GET && clientReq.query() == null && repository != null
                && !carriesOwnCredential(clientReq, repository)) {
            var revalidation = repository.revalidation();
            var sidecar = Sidecar.of(path);
            var artifactPath = sidecar == null ? path : sidecar.artifactPath(path);
            // A sidecar of a sidecar (foo.jar.asc.sha1) has no artifact to cache
            var target = Sidecar.of(artifactPath) == null ? targetOf(repository, artifactPath) : null;
            if (target != null && revalidation.fits(target.checksum())
                    // Its stored sidecars would be served on a past confirmation: upstream answers for them
                    && !(sidecar != null && revalidation.servesOnlyConfirmed())) {
                if (sidecar != null) {
                    serveSidecar(clientReq, domain, revalidation, sidecar, target);
                } else {
                    serveArtifact(clientReq, started, domain, revalidation, target);
                }
                return;
            }
        }

        upstream.relayRequest(clientReq, domain, started);
    }

    /**
     * Whether a request to a public repository config.yaml declared carries a credential of the
     * client's own. It is relayed, never cached: the cache is shared by every instance, and the
     * repository answering a credential may be a private one declared public by mistake. The
     * built-in ones are public whatever a client sends.
     */
    private static boolean carriesOwnCredential(HttpServerRequest clientReq, CachedRepository repository) {
        if (CachedRepository.BUILT_IN_DOMAINS.contains(repository.domain())) return false;
        for (var header : CLIENT_CREDENTIAL_HEADERS) {
            if (clientReq.headers().contains(header)) return true;
        }
        return false;
    }

    /**
     * The headers a client carries a credential of its own in: the standard ones, and the token
     * headers repository managers take (GitLab's, Artifactory's), so none of them fills the shared cache.
     */
    private static final List<String> CLIENT_CREDENTIAL_HEADERS = List.of("Authorization", "Cookie",
            "Private-Token", "Job-Token", "Deploy-Token", "X-JFrog-Art-Api", "X-Api-Key");

    // Declared repositories already warned about for answering without their checksum header
    final Set<String> warnedHeaderless = ConcurrentHashMap.newKeySet();

    /**
     * Say once per domain that a repository config.yaml declared {@code confirm: checksum-header}
     * answered an artifact without that header: its copies are then confirmed by the sidecar, or,
     * where that is unsound, never cached, and nothing else would tell the user why.
     */
    private void warnHeaderless(String domain, Revalidation revalidation) {
        if (CachedRepository.BUILT_IN_DOMAINS.contains(domain) || !warnedHeaderless.add(domain)) return;
        ProxyLog.warn(domain + " answered an artifact without " + revalidation.header()
                + ", which maven-repositories: in config.yaml declares it sends (confirm: checksum-header); "
                + (revalidation.servesOnlyConfirmed() ? "its artifacts are not cached"
                        : "its artifacts are confirmed by their .sha1 instead")
                + ". Check the declaration against what the server sends.");
    }

    /**
     * Relay a request the cache could not serve. Not a private repository's: the relay sends no
     * credential of its own, so it would only carry the guest's proof upstream.
     */
    private void relayUncached(HttpServerRequest clientReq, String domain, long started, ArtifactTarget target) {
        if (target.credential() != null) {
            upstream.sendError(clientReq.response(), 502, "Artifact cache error");
        } else {
            upstream.relayRequest(clientReq, domain, started);
        }
    }

    /** The repository among a domain's {@code cached} ones whose prefix holds {@code path}, or null. */
    static CachedRepository repositoryOf(List<CachedRepository> cached, String path) {
        for (var repository : cached) {
            if (repository.holds(path)) return repository;
        }
        return null;
    }

    /** Where {@code repository} caches the artifact at {@code path}, or null when it must not be cached. */
    private static ArtifactTarget targetOf(CachedRepository repository, String path) {
        return switch (repository.layout()) {
            case MAVEN -> mavenTarget(repository, path);
            case GRADLE_DISTRIBUTIONS -> gradleTarget(path);
        };
    }

    /** A Maven-layout repository: metadata and SNAPSHOTs are not cached ({@link #isMavenCacheable}). */
    private static ArtifactTarget mavenTarget(CachedRepository repository, String path) {
        if (!isMavenCacheable(path)) return null;
        var artifact = mavenCacheFile(repository.domain(), path);
        return artifact == null ? null
                : new ArtifactTarget(artifact, Sidecar.SHA1, resolveM2Path(repository.pathPrefix(), path), null,
                        repository.mirrorOf(path));
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
     * as its repository's tiers ask ({@link #trustTier}), or download, verify and store it.
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
                var tier = trustTier(revalidation, cached);
                if (tier == ArtifactCacheTiers.Tier.EXPIRED) {
                    revalidateAndServe(clientReq, started, domain, revalidation, target, ref, cached.size());
                } else {
                    serveTrusted(clientReq, started, domain, revalidation, target, ref, cached, tier);
                }
            } else if (local.hostCopy() && !inBackoff(domain)) {
                // The checksum is needed before deciding whether to download at all
                hostImports.join(target.artifact(), () -> importHostCopy(domain, clientReq.path(), revalidation, target, ref))
                        .onSuccess(imported -> importOrDownload(clientReq, started, domain, revalidation, target, ref, imported))
                        // Fails only once upstream answered with a checksum, so never from the mirror
                        .onFailure(err -> download(clientReq, started, domain, revalidation, target, ref));
            } else {
                // No copy to fall back on, so even in a backoff upstream is asked, after its mirror
                downloadMissing(clientReq, started, domain, revalidation, target, ref);
            }
        }).onFailure(err -> {
            ProxyLog.trace("Artifact cache check error for " + ref + ": " + err.getMessage());
            relayUncached(clientReq, domain, started, target);
        });
    }

    /**
     * How far the artifact's last confirmation is trusted: its repository's own tiers, else
     * the configured ones ({@link Revalidation#tiersOr}).
     */
    private ArtifactCacheTiers.Tier trustTier(Revalidation revalidation, VerifiedArtifactStore.CachedCopy cached) {
        return revalidation.tiersOr(artifactCacheTiers).tierOf(cached.confirmedAt(), Instant.now());
    }

    /** The tiers npm tarballs are judged by: config.yaml's, else the default. */
    private ArtifactCacheTiers npmTiers() {
        return java.util.Objects.requireNonNullElse(artifactCacheTiers, ArtifactCacheTiers.DEFAULT);
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
        if (revalidation.header() != null) {
            var hex = target.checksum().hex(cached.checksum());
            if (hex != null) clientReq.response().putHeader(revalidation.header(), hex);
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
        if (summary != null) ProxyLog.trace(summary);
    }

    // Artifacts with a background confirmation queued or in flight, so overlapping hits queue one; the
    // confirmation itself, once it runs, goes through confirmations below like a foreground one
    final SingleFlight<Path, Void> backgroundConfirmations = new SingleFlight<>();
    // Confirmations asking upstream, foreground and background alike, so overlapping ones share one (#1567)
    final SingleFlight<Path, Confirmed> confirmations = new SingleFlight<>();
    // npm version lookups in flight, by domain and path, so overlapping ones share one (#1567)
    final SingleFlight<String, NpmLookup> npmLookups = new SingleFlight<>();
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
        inBackground(artifact, () -> confirm(domain, path, revalidation, target).onComplete(ar -> {
            if (ar.failed()) {
                ProxyLog.trace("Background confirmation error for " + ref + ": " + ar.cause().getMessage());
            } else {
                var outcome = ar.result().outcome();
                logEviction(outcome, ref, target.checksum(), ar.result().answer());
                if (outcome != null && outcome != VerifiedArtifactStore.Outcome.MATCHED
                        && outcome != VerifiedArtifactStore.Outcome.EVICTED
                        && outcome != VerifiedArtifactStore.Outcome.SUPERSEDED) {
                    vertx.executeBlocking(() -> {
                        VerifiedArtifactStore.expireConfirmation(artifact, target.checksum());
                        return null;
                    }, false);
                }
            }
        }));
    }

    /**
     * Run a background confirmation of the cached file {@code key}, unless one is already
     * queued or in flight for it; past {@link #maxBackgroundConfirmations} it waits its turn.
     */
    private void inBackground(Path key, Supplier<Future<?>> confirmation) {
        backgroundConfirmations.join(key, () -> {
            var done = Promise.<Void>promise();
            Runnable run = () -> {
                Future<?> running;
                try {
                    running = confirmation.get();
                } catch (RuntimeException e) {
                    running = Future.failedFuture(e);
                }
                running.onComplete(ar -> {
                    done.complete();
                    startNextConfirmation();
                });
            };
            boolean queued;
            synchronized (queuedConfirmations) {
                queued = confirmationsInFlight >= maxBackgroundConfirmations;
                if (queued) queuedConfirmations.add(run);
                else confirmationsInFlight++;
            }
            if (!queued) run.run();
            return done.future();
        });
    }

    /** For tests: background confirmations in flight, and those waiting their turn. */
    String backgroundQueue() {
        synchronized (queuedConfirmations) {
            return confirmationsInFlight + " in flight, " + queuedConfirmations.size() + " queued";
        }
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
        // On a later turn of the event loop, not this stack: a confirmation can complete at once (in a
        // backoff), and a long queue drained that way would nest a run per entry.
        vertx.runOnContext(v -> next.run());
    }

    /** Ask upstream for the artifact's current checksum, the way the domain allows. */
    private Future<SidecarAnswer> probe(String domain, String path, Revalidation revalidation, ArtifactTarget target) {
        return revalidation.header() != null
                ? fetchChecksumHeader(domain, path, revalidation, target.credential())
                : fetchSidecar(domain, path + target.checksum().extension);
    }

    /** Upstream's answer, and what it did to the cached copy: no outcome when upstream was unreachable. */
    private record Confirmed(SidecarAnswer answer, VerifiedArtifactStore.Outcome outcome) {}

    /**
     * Ask upstream about a cached artifact and reconcile the copy with the answer, or join a
     * confirmation of it still in flight when this caller asked (#1567): its probe may have been
     * answered just before, but its writes precede the reply, as with a lookup of the caller's own.
     */
    private Future<Confirmed> confirm(String domain, String path, Revalidation revalidation, ArtifactTarget target) {
        return confirmations.join(target.artifact(), () -> confirmNow(domain, path, revalidation, target));
    }

    private Future<Confirmed> confirmNow(String domain, String path, Revalidation revalidation, ArtifactTarget target) {
        var lookedUp = Instant.now();
        var probed = inBackoff(domain)
                ? Future.succeededFuture(SidecarAnswer.UNREACHABLE)
                : probe(domain, path, revalidation, target);
        return probed.compose(answer -> answer.unreachable()
                ? Future.succeededFuture(new Confirmed(answer, null))
                : vertx.executeBlocking(() -> new Confirmed(answer, VerifiedArtifactStore.reconcile(
                        target.artifact(), target.checksum(), answer.status(), answer.body(),
                        answer.mayEvict(revalidation), lookedUp)), false));
    }

    private void revalidateAndServe(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                                    ArtifactTarget target, String ref, long size) {
        confirm(domain, clientReq.path(), revalidation, target).onSuccess(confirmed -> {
            if (confirmed.outcome() == null && revalidation.servesOnlyConfirmed()) {
                // Never served on a past confirmation, however unreachable upstream is: it answers for itself
                download(clientReq, started, domain, revalidation, target, ref);
            } else if (confirmed.outcome() == null) {
                upstream.throttles.servedUnconfirmed(domain, confirmed.answer().status());
                recordHit(ArtifactCacheStats.Hit.UNCONFIRMED, size);
                serveCachedFile(clientReq.response(), target.artifact(), null);
            } else if (confirmed.outcome() == VerifiedArtifactStore.Outcome.SUPERSEDED) {
                // Confirmed after this probe, which a slow answer can predate (#1542); the answer's
                // checksum may describe other bytes, so none goes out with the copy
                recordHit(ArtifactCacheStats.Hit.CONFIRMED, size);
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
            ProxyLog.trace("Artifact revalidation error for " + ref + ": " + err.getMessage());
            relayUncached(clientReq, domain, started, target);
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
        if (revalidation.header() != null) {
            var hex = revalidation.headerChecksum().hex(answer.body());
            if (hex != null) clientReq.response().putHeader(revalidation.header(), hex);
        }
        serveCachedFile(clientReq.response(), artifact, null);
    }

    // Host-copy imports probing upstream, by artifact, so overlapping misses share one (#1567)
    final SingleFlight<Path, HostImport> hostImports = new SingleFlight<>();

    /** Upstream's checksum for an artifact with a host copy, and the size imported: -1 when it was not. */
    record HostImport(SidecarAnswer answer, long importedSize) {}

    /** Probe upstream, then import the host copy if it matches the answer. */
    private Future<HostImport> importHostCopy(String domain, String path, Revalidation revalidation, ArtifactTarget target,
                                              String ref) {
        var checksum = target.checksum();
        var lookedUp = Instant.now();
        return probe(domain, path, revalidation, target).compose(answer -> {
            if (answer == SidecarAnswer.UNREACHABLE || answer.status() != 200 || checksum.hex(answer.body()) == null) {
                return Future.succeededFuture(new HostImport(answer, -1));
            }
            // Superseded too goes to upstream: the copy confirmed meanwhile may not be what this answer describes
            return vertx.executeBlocking(() -> {
                if (VerifiedArtifactStore.importCopy(target.hostCopy(), target.artifact(), checksum, answer.body(), lookedUp)
                        == VerifiedArtifactStore.Commit.COMMITTED) {
                    return new HostImport(answer, Files.size(target.artifact()));
                }
                ProxyLog.trace("Maven .m2 copy not imported (differs from upstream, or superseded): " + ref);
                return new HostImport(answer, -1);
            }, false);
        }).onFailure(err -> ProxyLog.trace("Maven .m2 import error for " + ref + ": " + err.getMessage()));
    }

    private void importOrDownload(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                                  ArtifactTarget target, String ref, HostImport imported) {
        var answer = imported.answer();
        if (answer == SidecarAnswer.UNREACHABLE && target.mirror() == null) {
            // Downloading would only wait out the same failed connection
            upstream.sendError(clientReq.response(), 502, "Upstream unreachable");
        } else if (imported.importedSize() >= 0) {
            recordHit(ArtifactCacheStats.Hit.HOST_COPY, imported.importedSize());
            serveConfirmed(clientReq, target.artifact(), revalidation, answer);
        } else if (answer.unreachable()) {
            // Unreachable, throttled or failing, upstream said nothing about the artifact: its mirror may serve it
            downloadMissing(clientReq, started, domain, revalidation, target, ref);
        } else {
            download(clientReq, started, domain, revalidation, target, ref);
        }
    }

    /**
     * Download an artifact upstream has said nothing about: from its repository's mirror when it
     * has one (#2304), else, or when the mirror does not serve it, from upstream ({@link #download}).
     * Never after upstream answered for the artifact: a copy it withdrew or changed would come
     * back from a mirror that still has the old one. The mirror's checksum is asked first, so it
     * goes out with the artifact where the repository sends one (Central's {@code X-Checksum-SHA1}),
     * and Maven does not then ask the repository, maybe throttling, for the {@code .sha1}; a mirror
     * without it is not asked for the artifact. Each of the two requests gets half of what the
     * client has left ({@link #mirrorWaitMillis}), so a mirror that hangs leaves upstream the rest,
     * and one that failed or throttled is not asked again for {@link Upstream#UNREACHABLE_BACKOFF_SECONDS}.
     */
    private void downloadMissing(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                                 ArtifactTarget target, String ref) {
        var mirror = target.mirror();
        if (mirror == null || mirrorResting(mirror.host())) {
            download(clientReq, started, domain, revalidation, target, ref);
            return;
        }
        var checksum = target.checksum();
        // The probe client's own timeouts outlast Gradle's read timeout: the mirror gets its share
        var settled = new java.util.concurrent.atomic.AtomicBoolean();
        var timer = vertx.setTimer(mirrorWaitMillis(started), t -> {
            if (!settled.compareAndSet(false, true)) return;
            restMirror(mirror.host());
            ProxyLog.trace("Mirror " + mirror.host() + " did not answer for " + ref + " in time, asking " + domain);
            if (!clientReq.response().closed()) download(clientReq, started, domain, revalidation, target, ref);
        });
        fetchSidecar(mirror.host(), mirror.path() + checksum.extension).onSuccess(answer -> {
            if (!settled.compareAndSet(false, true)) return;
            vertx.cancelTimer(timer);
            if (clientReq.response().closed()) return;
            var hex = answer.status() == 200 ? checksum.hex(answer.body()) : null;
            if (hex == null) {
                if (answer.unreachable()) restMirror(mirror.host());
                ProxyLog.trace("Mirror " + mirror.host() + " has no usable " + checksum.extension + " for " + ref
                        + " (" + (answer == SidecarAnswer.UNREACHABLE ? "unreachable" : "HTTP " + answer.status())
                        + "), asking " + domain);
                download(clientReq, started, domain, revalidation, target, ref);
            } else {
                downloadFromMirror(clientReq, started, domain, revalidation, target, ref, mirror, hex);
            }
        });
    }

    /** The mirror's GET for an artifact whose checksum it gave as {@code hex}. */
    private void downloadFromMirror(HttpServerRequest clientReq, long started, String domain, Revalidation revalidation,
                                    ArtifactTarget target, String ref, CachedRepository.MirrorSource mirror, String hex) {
        var wait = mirrorWaitMillis(started);
        Runnable fromUpstream = () -> {
            if (!clientReq.response().closed()) download(clientReq, started, domain, revalidation, target, ref);
        };
        // Only what the mirror needs to find the file: nothing of the client's, a credential
        // included, goes to a third party, and a resume repeats these same headers
        var options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(mirror.host())
                .setPort(443)
                .setURI(mirror.path())
                .setConnectTimeout(Math.min(Upstream.UPSTREAM_CONNECT_TIMEOUT_MILLIS, wait));
        upstream.requestWithAsyncDns(options).onSuccess(req -> {
            req.idleTimeout(wait);
            req.putHeader("Host", mirror.host());
            req.send().onSuccess(resp -> {
                var status = resp.statusCode();
                // Under the mirror's own name: its limit is not the repository's
                upstream.throttles.answered(mirror.host(), status);
                if (status == 200) {
                    ProxyLog.info("Fetching " + ref + " from its mirror " + mirror.host()
                            + "; " + domain + " confirms the copy at its next use");
                    new CachingDownload(clientReq, domain, started, resp, target.artifact(), ref,
                            mirroredArtifact(revalidation, target.checksum(), hex)).start();
                } else {
                    if (status == 429 || status >= 500) restMirror(mirror.host());
                    ProxyLog.trace("Mirror " + mirror.host() + " answered " + status + " for " + ref
                            + ", asking " + domain);
                    // Over h2 the reset reaches this response as an error; it is the one we asked for
                    resp.exceptionHandler(ignored -> {});
                    resp.request().reset();
                    fromUpstream.run();
                }
            }).onFailure(err -> {
                restMirror(mirror.host());
                ProxyLog.trace("Mirror " + mirror.host() + " failed for " + ref + " (" + err.getMessage()
                        + "), asking " + domain);
                fromUpstream.run();
            });
        }).onFailure(err -> {
            restMirror(mirror.host());
            ProxyLog.trace("Mirror " + mirror.host() + " unreachable for " + ref + " (" + err.getMessage()
                    + "), asking " + domain);
            fromUpstream.run();
        });
    }

    /**
     * How long the mirror may take, for each of its two requests: half of what the client has
     * left, of its silence budget and of {@link #CLIENT_READ_TIMEOUT_MILLIS} alike, so a mirror
     * that hangs leaves the repository at least a quarter of it.
     */
    private long mirrorWaitMillis(long started) {
        var readLeft = CLIENT_READ_TIMEOUT_MILLIS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return Math.max(1, Math.min(upstream.timeoutLeftMillis(started), readLeft) / 2);
    }

    // Mirrors that failed, hung or throttled, by host, and the System.nanoTime until which they are not asked
    final Map<String, Long> mirrorBackoffs = new ConcurrentHashMap<>();

    private boolean mirrorResting(String host) {
        var until = mirrorBackoffs.get(host);
        if (until == null) return false;
        if (until - System.nanoTime() > 0) return true;
        mirrorBackoffs.remove(host, until);
        return false;
    }

    private void restMirror(String host) {
        mirrorBackoffs.put(host, System.nanoTime() + TimeUnit.SECONDS.toNanos(Upstream.UNREACHABLE_BACKOFF_SECONDS));
    }

    /**
     * What a mirrored download must match: the checksum the mirror gave, {@code hex}, which goes
     * out to the client as the repository's checksum header where it sends one. That vouches only
     * for the transfer, so the copy is committed unconfirmed ({@link Instant#EPOCH}): its first hit
     * is confirmed with the repository itself, which evicts it if the repository disagrees.
     */
    private static Verification mirroredArtifact(Revalidation revalidation, Sidecar checksum, String hex) {
        var expected = hex.getBytes(StandardCharsets.US_ASCII);
        var header = revalidation.header() != null && revalidation.headerChecksum() == checksum ? revalidation.header() : null;
        return new Verification(checksum, resp -> Future.succeededFuture(expected), true,
                VerifiedArtifactStore.NOTHING_ALONGSIDE, Instant.EPOCH, (clientResp, upResp) -> {
                    if (header != null) clientResp.putHeader(header, hex);
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
        fetchCacheAndServe(clientReq, started, domain, target.artifact(), ref, target.credential(),
                Verification.ofArtifact(target.checksum(), upResp -> {
                    var hex = revalidation.checksumFrom(upResp);
                    if (hex != null) return Future.succeededFuture(hex.getBytes(StandardCharsets.US_ASCII));
                    // Served, not cached: a sidecar would be no sound check of these bytes
                    if (revalidation.header() != null) warnHeaderless(domain, revalidation);
                    if (revalidation.servesOnlyConfirmed() && revalidation.header() != null) return Future.succeededFuture(null);
                    return fetchSidecar(domain, sidecarPath).map(answer -> answer.status() == 200 ? answer.body() : null);
                }));
    }

    /**
     * Serve a sidecar. While the artifact's last confirmation is trusted
     * ({@link #trustTier}) its stored copy is served, as the artifact
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
            var tier = stored == null ? ArtifactCacheTiers.Tier.EXPIRED : trustTier(revalidation, cached);
            if (tier != ArtifactCacheTiers.Tier.EXPIRED
                    || (stored != null && inBackoff(domain) && !revalidation.servesOnlyConfirmed())) {
                sendSidecar(clientReq.response(), 200, stored);
                // An expired copy served in a backoff was not asked about upstream
                if (tier == ArtifactCacheTiers.Tier.EXPIRED) upstream.throttles.servedUnconfirmed(domain, 0);
                if (tier == ArtifactCacheTiers.Tier.STALE) {
                    var artifactPath = sidecar.artifactPath(clientReq.path());
                    confirmInBackground(domain, artifactPath, revalidation, target, domain + artifactPath);
                }
            } else {
                // Without a stored copy, upstream may still answer even in a backoff
                fetchAndServeSidecar(clientReq, domain, revalidation, sidecar, target, stored);
            }
        });
    }

    // Sidecar requests settling with upstream, by domain and path, so overlapping ones share one (#1567)
    final SingleFlight<String, SidecarReply> sidecarSettles = new SingleFlight<>();

    /**
     * What a sidecar request is answered with: a body, or an error with its message. An unreachable
     * upstream on the first ask is left to each caller, which serves its own stored copy if it has one.
     */
    record SidecarReply(int status, byte[] body, List<Map.Entry<String, String>> headers, String error,
                        boolean unreachable) {
        static SidecarReply of(int status, byte[] body) {
            return new SidecarReply(status, body, List.of(), null, false);
        }

        static SidecarReply error(int status, String message) {
            return new SidecarReply(status, null, List.of(), message, false);
        }
    }

    /**
     * Answer a sidecar request as upstream has it now, joining a request for the same sidecar
     * that is already settling (#1567): two answers looked up at about the same time could only
     * disagree, and the one settled last overwrote or dropped what the other stored.
     * {@code stored}: the stored copy, served in upstream's place when it cannot be reached.
     */
    private void fetchAndServeSidecar(HttpServerRequest clientReq, String domain, Revalidation revalidation,
                                      Sidecar sidecar, ArtifactTarget target, byte[] stored) {
        var path = clientReq.path();
        sidecarSettles.join(domain + path, () -> settleSidecar(domain, path, revalidation, sidecar, target, false)
                        .onFailure(err -> ProxyLog.trace("Sidecar check error for " + domain + path + ": " + err.getMessage())))
                .otherwise(err -> SidecarReply.error(502, "Sidecar check failed"))
                .onSuccess(reply -> {
                    var resp = clientReq.response();
                    if (reply.unreachable() && stored != null && !revalidation.servesOnlyConfirmed()) {
                        upstream.throttles.servedUnconfirmed(domain, reply.status());
                        sendSidecar(resp, 200, stored);
                    } else if (reply.error() != null) {
                        upstream.sendError(resp, reply.status(), reply.error());
                    } else {
                        sendSidecar(resp, reply.status(), reply.body(), reply.headers());
                    }
                });
    }

    /**
     * Ask upstream for a sidecar, reconcile the cached copy with the answer, and decide the reply.
     * It touches no client response, since every request that joined it is answered with the result.
     * {@code askedAgain}: this is the second ask, after the first answer predated the copy's confirmation;
     * unreachable then, it is an error, since a caller's stored copy may describe the bytes replaced.
     */
    private Future<SidecarReply> settleSidecar(String domain, String path, Revalidation revalidation, Sidecar sidecar,
                                               ArtifactTarget target, boolean askedAgain) {
        var artifact = target.artifact();
        var lookedUp = Instant.now();
        return fetchSidecar(domain, path).compose(answer -> {
            if (answer == SidecarAnswer.UNREACHABLE) {
                return Future.succeededFuture(new SidecarReply(502, null, List.of(), "Upstream unreachable", !askedAgain));
            }
            if (answer.unreachable()) {
                // Throttled or failing: what the client gets, when no stored copy stands in, is upstream's own answer
                return Future.succeededFuture(new SidecarReply(answer.status(), answer.body(), answer.headers(), null, !askedAgain));
            }
            if (answer == SidecarAnswer.UNUSABLE) {
                return Future.succeededFuture(SidecarReply.error(502, "Unusable upstream answer"));
            }
            var artifactPath = sidecar.artifactPath(path);
            // Reconcile before answering, so the client's next request sees any eviction
            return vertx.executeBlocking(() -> VerifiedArtifactStore.reconcileCopy(artifact, target.checksum(), sidecar,
                    answer.status(), answer.body(), revalidation.sidecarsAuthoritative(), lookedUp), false
            ).compose(reconciled -> reconciled.outcome() == VerifiedArtifactStore.Outcome.DISAGREES
                    ? settleWithHeader(domain, artifactPath, artifact, reconciled.copy(), revalidation, sidecar, answer)
                    : Future.succeededFuture(reconciled.outcome())
            ).transform(ar -> {
                if (ar.failed()) {
                    ProxyLog.trace("Sidecar check error for " + domain + path + ": " + ar.cause().getMessage());
                } else if (ar.result() == VerifiedArtifactStore.Outcome.SUPERSEDED) {
                    // Upstream confirmed the copy after this lookup (#1542): the copy's own sidecar
                    // describes the bytes served, where the answer may describe others
                    return keptSidecar(domain, path, revalidation, sidecar, target, askedAgain);
                } else {
                    logEviction(ar.result(), domain + artifactPath, sidecar, answer);
                }
                return Future.succeededFuture(SidecarReply.of(answer.status(), answer.body()));
            });
        });
    }

    /**
     * The reply to a sidecar request whose upstream answer predates the cached copy's confirmation, and may
     * describe other bytes: the copy's stored sidecar, else upstream's answer to one more ask, now
     * after that confirmation. Never the older answer.
     */
    private Future<SidecarReply> keptSidecar(String domain, String path, Revalidation revalidation,
                                             Sidecar sidecar, ArtifactTarget target, boolean askedAgain) {
        return vertx.executeBlocking(() -> VerifiedArtifactStore.cachedCopy(target.artifact(), target.checksum(), sidecar), false)
                .transform(ar -> {
                    if (ar.failed()) {
                        ProxyLog.trace("Stored sidecar read error for " + domain + path + ": "
                                + ar.cause().getMessage());
                    }
                    var kept = ar.succeeded() && ar.result() != null ? ar.result().sidecar() : null;
                    if (kept != null) return Future.succeededFuture(SidecarReply.of(200, kept));
                    // Asked again within this settle, not joined to it: it is still in flight
                    if (!askedAgain) return settleSidecar(domain, path, revalidation, sidecar, target, true);
                    // Superseded twice: the copy was committed or confirmed again during each ask, so
                    // neither answer is known to describe it
                    return Future.succeededFuture(SidecarReply.error(502, "Upstream answers predate the cached copy"));
                });
    }

    /**
     * A sidecar disagreed with the stored copy on a domain whose artifacts carry a
     * server-computed checksum: let that checksum decide, and nothing else (a
     * header-less answer falls back to the very sidecar that disagreed). If it
     * still confirms the artifact, the stored sidecar follows upstream: a fresh
     * signature is kept, and one upstream no longer has is dropped. A checksum
     * sidecar that contradicts the artifact is not stored, since stored checksums
     * describe the artifact beside them. The sidecar answer changes nothing beside
     * a copy other than the {@code answered} one it was reconciled against
     * ({@link VerifiedArtifactStore.Outcome#SUPERSEDED}): the header may then have
     * matched bytes committed after the sidecar was asked for (#1555).
     */
    private Future<VerifiedArtifactStore.Outcome> settleWithHeader(String domain, String artifactPath, Path artifact,
                                                                   VerifiedArtifactStore.FileState answered,
                                                                   Revalidation revalidation, Sidecar sidecar,
                                                                   SidecarAnswer sidecarAnswer) {
        var lookedUp = Instant.now();
        return fetchChecksumHeader(domain, artifactPath, revalidation).compose(header -> {
            if (!header.fromHeader()) {
                return Future.succeededFuture(VerifiedArtifactStore.Outcome.UNCHANGED);
            }
            return vertx.executeBlocking(() -> {
                var outcome = VerifiedArtifactStore.reconcile(
                        artifact, revalidation.headerChecksum(), header.status(), header.body(), true, lookedUp);
                if (outcome != VerifiedArtifactStore.Outcome.MATCHED) return outcome;
                boolean answeredCopy;
                if (sidecarAnswer.gone()) {
                    answeredCopy = VerifiedArtifactStore.dropSidecar(artifact, answered, sidecar);
                } else if (sidecarAnswer.status() == 200 && !sidecar.isChecksum()) {
                    answeredCopy = VerifiedArtifactStore.storeSidecar(artifact, answered, sidecar, sidecarAnswer.body());
                } else {
                    answeredCopy = VerifiedArtifactStore.isCopy(artifact, answered);
                }
                return answeredCopy ? outcome : VerifiedArtifactStore.Outcome.SUPERSEDED;
            }, false);
        });
    }

    private static void logEviction(VerifiedArtifactStore.Outcome outcome, String ref,
                                    Sidecar sidecar, SidecarAnswer answer) {
        if (outcome != VerifiedArtifactStore.Outcome.EVICTED) return;
        ProxyLog.trace("Evicted " + ref + ": upstream " + sidecar.extension +
                (answer.status() == 200 ? " no longer matches" : " is gone (HTTP " + answer.status() + ")"));
    }

    private static void sendSidecar(HttpServerResponse resp, int status, byte[] body) {
        sendSidecar(resp, status, body, List.of());
    }

    /** {@code headers}: upstream's, kept by {@link #readSmallBody} for an error; a Content-Type among them replaces ours. */
    private static void sendSidecar(HttpServerResponse resp, int status, byte[] body,
                                    List<Map.Entry<String, String>> headers) {
        if (resp.ended() || resp.closed()) return;
        resp.setStatusCode(status);
        if (headers.stream().noneMatch(header -> header.getKey().equalsIgnoreCase("Content-Type"))) {
            resp.putHeader("Content-Type", "text/plain");
        }
        // Added, not set: a header upstream repeats (RateLimit-Policy, say) keeps every value
        headers.forEach(header -> resp.headers().add(header.getKey(), header.getValue()));
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
        return fetchChecksumHeader(domain, path, revalidation, null);
    }

    /** As above, sending {@code credential} when it is not null: a private repository's HEAD. */
    Future<SidecarAnswer> fetchChecksumHeader(String domain, String path, Revalidation revalidation, Credential credential) {
        var options = probeOptions(HttpMethod.HEAD, domain, 443, path);
        if (credential != null) options.putHeader(credential.header(), credential.value());
        return retryOnceAfterConnect(() -> upstream.requestWithAsyncDns(upstream.probeClient, options)
                        .compose(req -> afterConnect(upstream.sendProbe(req, (resp, read) -> resp.end().map(v -> {
                            var status = resp.statusCode();
                            if (status != 200) return new SidecarAnswer(status, null, true);
                            var hex = revalidation.checksumFrom(resp);
                            if (hex == null) warnHeaderless(domain, revalidation);
                            return hex == null ? null : new SidecarAnswer(200, hex.getBytes(StandardCharsets.US_ASCII), true);
                        })))))
                .recover(err -> Future.succeededFuture(SidecarAnswer.UNREACHABLE))
                .compose(answer -> answer != null ? Future.succeededFuture(answer)
                        // A sidecar is uploaded apart from its artifact: no confirmation where releases are replaced
                        : revalidation.servesOnlyConfirmed() ? Future.succeededFuture(SidecarAnswer.UNUSABLE)
                        : fetchSidecar(domain, path + revalidation.headerChecksum().extension));
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
        return upstream.inBackoff(domain);
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
                .compose(req -> afterConnect(upstream.sendProbe(req, (resp, read) -> {
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
                        // Draining the redirect's body is a read too
                        resp.handler(chunk -> read.run());
                        return resp.end().compose(v -> fetchSmallBody(target.getHost(),
                                target.getPort() > 0 ? target.getPort() : 443, rawPathAndQuery(target), depth + 1,
                                maxBytes));
                    }
                    return readSmallBody(resp, maxBytes, read);
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

    private static Future<SidecarAnswer> readSmallBody(HttpClientResponse resp, int maxBytes, Runnable read) {
        var promise = Promise.<SidecarAnswer>promise();
        var body = Buffer.buffer();
        var status = resp.statusCode();
        // An error goes to the client with these when nothing stands in for it: when to retry, and what its body is
        var headers = status >= 500 || status == 429 ? errorHeaders(resp) : List.<Map.Entry<String, String>>of();
        resp.handler(chunk -> {
            read.run();
            if (body.length() + chunk.length() > maxBytes) {
                resp.handler(null);
                // An oversized sidecar is unusable; an oversized error page is still that error, without
                // its body, so without the type that described it
                promise.tryComplete(status == 200
                        ? SidecarAnswer.UNUSABLE : new SidecarAnswer(status, null, false, withoutType(headers)));
                resp.request().reset();
            } else {
                body.appendBuffer(chunk);
            }
        });
        resp.endHandler(v -> promise.tryComplete(new SidecarAnswer(status, body.getBytes(), false, headers)));
        resp.exceptionHandler(promise::tryFail);
        return promise.future();
    }

    /** {@link Upstream#retryHeaders}, and the type of the body they come with. */
    private static List<Map.Entry<String, String>> errorHeaders(HttpClientResponse resp) {
        var type = resp.getHeader("Content-Type");
        var retry = Upstream.retryHeaders(resp.headers());
        if (type == null) return retry;
        var headers = new ArrayList<Map.Entry<String, String>>(retry.size() + 1);
        headers.add(Map.entry("Content-Type", type));
        headers.addAll(retry);
        return List.copyOf(headers);
    }

    private static List<Map.Entry<String, String>> withoutType(List<Map.Entry<String, String>> headers) {
        return headers.stream().filter(header -> !header.getKey().equalsIgnoreCase("Content-Type")).toList();
    }

    private void deleteUnverifiedLegacyCaches() {
        vertx.executeBlocking(() -> {
            for (var dir : Environment.unverifiedLegacyCacheDirs()) {
                if (Files.isDirectory(dir)) {
                    ProxyLog.info("Deleting cache stored without verification: " + dir);
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
     *   <li>SNAPSHOTs: Central and the Plugin Portal reject them, but a declared
     *       repository may serve them. Their content is replaced on every deploy, so
     *       sidecar confirmation would be unsound (see {@link Revalidation#SIDECAR}),
     *       offline serves would rarely be what a fetch returns, and confirming each
     *       one would cost what fetching it costs.</li>
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
     * Map a Maven repository URL path to the corresponding path in ~/.m2/repository, where
     * {@code prefix} is the path before the coordinates. Returns null if the path doesn't match.
     */
    static Path resolveM2Path(String prefix, String urlPath) {
        if (!urlPath.startsWith(prefix)) return null;
        var relativePath = urlPath.substring(prefix.length());
        if (relativePath.contains("..")) return null;
        return m2Repository().resolve(relativePath);
    }

    /** Where a Maven repository URL path is cached, or null if it would land outside the domain's cache. */
    static Path mavenCacheFile(String domain, String urlPath) {
        return cacheFileUnder(mavenCacheDir(), domain, urlPath);
    }

    /** Where a URL path of {@code domain} is cached under {@code root}, or null if it would land outside the domain's directory. */
    private static Path cacheFileUnder(Path root, String domain, String urlPath) {
        var domainRoot = root.resolve(domain);
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
