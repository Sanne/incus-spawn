package dev.incusspawn.proxy;

import io.vertx.core.http.HttpClientResponse;

import java.nio.charset.StandardCharsets;

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
 *       even for content that changes. A sidecar is uploaded separately from its artifact
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
 * @param header         that header's name, or null: then a hit fetches the checksum sidecar
 * @param tiers          how long a confirmation is trusted; null for config.yaml's {@code artifact-cache:}
 * @param servesOnlyConfirmed never served on a past confirmation, offline or during a backoff
 *                       either: the repository may replace what it published, or is private
 */
record Revalidation(Sidecar headerChecksum, String header, ArtifactCacheTiers tiers, boolean servesOnlyConfirmed) {

    /** The header Maven Central and Artifactory send the artifact's SHA-1 in (Nexus 3 does not: its ETag is the SHA-1). */
    static final String CHECKSUM_HEADER = "X-Checksum-SHA1";

    /**
     * The repository sends {@code X-Checksum-SHA1} with each artifact (Maven
     * Central). A hit costs one HEAD; a miss is verified against the header of
     * the GET that downloads it. The fresh header is passed on to the client, so
     * Maven Resolver's smart checksums skip their own {@code .sha1} request.
     * A response without the header falls back to fetching the {@code .sha1}.
     */
    static final Revalidation HEAD_CHECKSUM = new Revalidation(Sidecar.SHA1, CHECKSUM_HEADER, null, false);

    /**
     * No checksum headers (Gradle Plugin Portal, Gradle distributions): a hit
     * fetches the checksum sidecar, and a miss fetches it once downloaded.
     */
    static final Revalidation SIDECAR = new Revalidation(null, null, null, false);

    Revalidation {
        if (servesOnlyConfirmed) tiers = ArtifactCacheTiers.CONFIRM_EVERY_HIT;
    }

    /** This confirmation, its confirmation trusted for {@code tiers}. */
    Revalidation trustedFor(ArtifactCacheTiers tiers) {
        return new Revalidation(headerChecksum, header, tiers, servesOnlyConfirmed);
    }

    /** This confirmation, made before every serve and never skipped. */
    Revalidation onlyConfirmed() {
        return new Revalidation(headerChecksum, header, tiers, true);
    }

    /** The tiers a hit is judged by: this repository's own, else {@code configured}. */
    ArtifactCacheTiers tiersOr(ArtifactCacheTiers configured) {
        return tiers != null ? tiers : configured;
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
        return value == null ? null : headerChecksum.hex(value.getBytes(StandardCharsets.US_ASCII));
    }
}
