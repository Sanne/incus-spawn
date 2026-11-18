package dev.incusspawn.proxy;

import io.vertx.core.http.HttpClientResponse;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a repository's cached Maven/Gradle artifacts are confirmed with upstream before they are
 * served, and how long a confirmation is trusted: one per {@link CachedRepository}.
 * <p>
 * Confirming hits means an online serve does not depend on what a repository lets publishers do,
 * so a repository's entry is not a claim that its content is immutable. What an entry does
 * declare, built in or written in config.yaml, is three things the proxy cannot find out by
 * itself (design/proxy.md "Why a repository is declared"):
 * <ol>
 *   <li><b>How long a withdrawal may go unnoticed.</b> Every repository can withdraw a release:
 *       Central removes one on occasion (copyright, malware), the Plugin Portal lets authors
 *       delete versions. A hit served on a past confirmation ({@link #tiers}), and an offline
 *       serve, is only what a fetch would return while upstream has not withdrawn or replaced it.
 *       The tiers bound how long that lasts, and a repository that replaces the bytes at a release
 *       path is never served on a past confirmation at all ({@link #servesOnlyConfirmed}).</li>
 *   <li><b>The confirmation must be sound for that repository.</b> A server-computed checksum
 *       header describes exactly the bytes the server would send, so a {@link #header} is sound
 *       even for content that changes. Nexus 3 sends it as its {@code ETag} instead
 *       ({@link #HEAD_ETAG}), and a copy is committed on it only where that value is the SHA-1
 *       of the bytes downloaded, so a server whose {@code ETag} is something else never has one
 *       confirmed by it. A sidecar is uploaded separately from its artifact
 *       ({@code mvn deploy} PUTs them one by one), so sidecar confirmation is only sound where the
 *       two are published together and never replaced, as on the Portal and for Gradle
 *       distributions.</li>
 *   <li><b>Whether it is public.</b> The cache is shared by every instance and a public
 *       repository's confirmation carries no client credentials, so a private repository's
 *       artifact is cached apart per credential and confirmed with that credential before every
 *       serve ({@link CachedRepository#credentials}).</li>
 * </ol>
 *
 * @param headerChecksum the checksum the repository sends as a header with each artifact, or null
 * @param header         that header's name, or null: then a hit fetches the checksum sidecar;
 *                       {@link #ETAG} is read as a strong, quoted SHA-1 alone
 * @param tiers          how long a confirmation is trusted; null for config.yaml's {@code artifact-cache:}
 * @param servesOnlyConfirmed never served on a past confirmation, offline or during a backoff
 *                       either: the repository may replace what it published, or is private
 * @param defaultTiers   where {@code tiers} is null, the tiers used while config.yaml has no
 *                       {@code artifact-cache:}; null for {@link ArtifactCacheTiers#DEFAULT}
 */
record Revalidation(Sidecar headerChecksum, String header, ArtifactCacheTiers tiers, boolean servesOnlyConfirmed,
                    ArtifactCacheTiers defaultTiers) {

    /** The header Maven Central and Artifactory send the artifact's SHA-1 in (Nexus 3 does not: its ETag is the SHA-1). */
    static final String CHECKSUM_HEADER = "X-Checksum-SHA1";

    /** The header Nexus 3 sends the artifact's SHA-1 in, quoted: {@code "c19307cc..."}. */
    static final String ETAG = "ETag";

    // A strong entity tag holding a SHA-1 and nothing else: a weak one (W/) does not promise the
    // bytes, and Nexus's merged-metadata {SHA1{...}} or a proxied upstream's MD5 is not this
    private static final Pattern ETAG_SHA1 = Pattern.compile("\"([0-9A-Fa-f]{40})\"");

    /**
     * The repository sends {@code X-Checksum-SHA1} with each artifact (Maven
     * Central). A hit costs one HEAD; a miss is verified against the header of
     * the GET that downloads it. The fresh header is passed on to the client, so
     * Maven Resolver's smart checksums skip their own {@code .sha1} request.
     * A response without the header falls back to fetching the {@code .sha1}.
     */
    static final Revalidation HEAD_CHECKSUM = new Revalidation(Sidecar.SHA1, CHECKSUM_HEADER, null, false, null);

    /**
     * The repository sends the artifact's SHA-1 as its strong, quoted {@code ETag} (Nexus 3's
     * hosted repositories, #2336), confirmed as {@link #HEAD_CHECKSUM} is. Nexus 3 makes that
     * {@code ETag} the blob's SHA-1, except where a proxy repository keeps its upstream's (Central's
     * is an MD5): a download whose {@code ETag} is not the SHA-1 of its bytes is never committed on
     * it, so no repository is trusted to send one. One with no such {@code ETag} is handled as a
     * header-less {@link #HEAD_CHECKSUM} answer: confirmed by its sidecar where that is sound, else
     * served and never cached. A copy served gets its SHA-1 as
     * {@code X-Checksum-SHA1} ({@link #clientHeader}), never as an {@code ETag} made up from the store;
     * a miss gets none, since its {@code ETag} is not yet known to be the SHA-1 of its bytes.
     */
    static final Revalidation HEAD_ETAG = new Revalidation(Sidecar.SHA1, ETAG, null, false, null);

    /**
     * No checksum headers (Gradle Plugin Portal, Gradle distributions): a hit
     * fetches the checksum sidecar, and a miss fetches it once downloaded.
     */
    static final Revalidation SIDECAR = new Revalidation(null, null, null, false, null);

    Revalidation {
        if (servesOnlyConfirmed) tiers = ArtifactCacheTiers.CONFIRM_EVERY_HIT;
    }

    /** This confirmation, its confirmation trusted for {@code tiers}. */
    Revalidation trustedFor(ArtifactCacheTiers tiers) {
        return new Revalidation(headerChecksum, header, tiers, servesOnlyConfirmed, defaultTiers);
    }

    /** This confirmation, made before every serve and never skipped. */
    Revalidation onlyConfirmed() {
        return new Revalidation(headerChecksum, header, tiers, true, defaultTiers);
    }

    /**
     * This confirmation, its confirmation trusted for {@code defaults} unless config.yaml's
     * {@code artifact-cache:} says otherwise.
     */
    Revalidation trustedByDefaultFor(ArtifactCacheTiers defaults) {
        return new Revalidation(headerChecksum, header, tiers, servesOnlyConfirmed, defaults);
    }

    /**
     * The tiers a hit is judged by: this repository's own, else {@code configured}, the
     * {@code artifact-cache:} section, else this repository's default when there is none. Any
     * {@code artifact-cache:} at all, even one key, replaces the default whole, so a default never
     * loosens what a user wrote (a {@code max-stale} below the default's {@code fresh} would be
     * raised to it).
     */
    ArtifactCacheTiers tiersOr(ArtifactCacheTiers configured) {
        if (tiers != null) return tiers;
        if (configured != null) return configured;
        return defaultTiers != null ? defaultTiers : ArtifactCacheTiers.DEFAULT;
    }

    /** Whether artifacts described by {@code checksum} can be confirmed this way. */
    boolean fits(Sidecar checksum) {
        return headerChecksum == null || headerChecksum == checksum;
    }

    /**
     * Whether a sidecar may evict an artifact on its own. Not where a
     * server-computed header outranks it: a separately uploaded {@code .sha1} can
     * be wrong where the header is right (old Central artifacts).
     */
    boolean sidecarsAuthoritative() {
        return header == null;
    }

    /** The checksum this repository sent with a response, as lowercase hex, or null. */
    String checksumFrom(HttpClientResponse resp) {
        if (header == null) return null;
        var value = resp.getHeader(header);
        if (value == null) return null;
        if (header.equals(ETAG)) {
            var m = ETAG_SHA1.matcher(value.strip());
            return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
        }
        return headerChecksum.hex(value.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The header a checksum from upstream, or from the store, goes out to the client in, or null:
     * {@code X-Checksum-SHA1}, whichever header upstream sent it in, since Maven reads that one and
     * then skips its own {@code .sha1} request. Never an {@code ETag}, which the client would send
     * back as a validator the proxy did not make.
     */
    String clientHeader() {
        return header == null ? null : CHECKSUM_HEADER;
    }
}
