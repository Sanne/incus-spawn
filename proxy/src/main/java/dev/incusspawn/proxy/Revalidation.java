package dev.incusspawn.proxy;

import io.vertx.core.http.HttpClientResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * How a cached Maven/Gradle artifact is confirmed with upstream before it is
 * served. Every hit is confirmed; only an unreachable upstream skips it.
 * <p>
 * This is also the allowlist of domains whose artifacts are cached at all: a
 * domain with no entry is relayed. Confirming every hit means an online serve no
 * longer depends on what a repository lets publishers do, so an entry is not a
 * claim that its content is immutable. A domain still has to be vetted, and
 * each entry below was, for three reasons:
 * <ol>
 *   <li><b>Offline serves are unconfirmed.</b> When upstream is unreachable a
 *       cached copy is served as is, which is only what a fetch would have
 *       returned if the repository does not change or withdraw what it has
 *       published. Central forbids both; Gradle does not withdraw distributions;
 *       the Plugin Portal can delete a version (7 days for authors, later via
 *       support), an accepted risk while offline.</li>
 *   <li><b>The confirmation must be sound for that repository.</b> A
 *       server-computed checksum header describes exactly the bytes the server
 *       would send, so {@link #HEAD_CHECKSUM} is sound even for content that
 *       changes. A sidecar is uploaded separately from its artifact
 *       ({@code mvn deploy} PUTs them one by one), so {@link #SIDECAR} is only
 *       sound where the two are published together and never replaced, as on the
 *       Portal and for Gradle distributions.</li>
 *   <li><b>Public repositories only.</b> The cache is shared by every instance and
 *       confirmation carries no client credentials, so a private repository's
 *       artifact cached for one instance would be served to others with no access
 *       to it, and offline serving would bypass its authorization entirely.</li>
 * </ol>
 */
enum Revalidation {
    /**
     * The repository sends {@code X-Checksum-SHA1} with each artifact (Maven
     * Central). A hit costs one HEAD; a miss is verified against the header of
     * the GET that downloads it. The fresh header is passed on to the client, so
     * Maven Resolver's smart checksums skip their own {@code .sha1} request.
     * A response without the header falls back to fetching the {@code .sha1}.
     */
    HEAD_CHECKSUM(Sidecar.SHA1, "X-Checksum-SHA1"),

    /**
     * No checksum headers (Gradle Plugin Portal, Gradle distributions): a hit
     * fetches the checksum sidecar, and a miss fetches it once downloaded.
     */
    SIDECAR(null, null);

    private static final Map<String, Revalidation> BY_DOMAIN = Map.of(
            "repo.maven.apache.org", HEAD_CHECKSUM,
            "repo1.maven.org", HEAD_CHECKSUM,
            "plugins.gradle.org", SIDECAR,
            "services.gradle.org", SIDECAR);

    /** The checksum the repository sends as a header with each artifact, or null. */
    final Sidecar headerChecksum;
    /** That header's name, or null. */
    final String header;

    Revalidation(Sidecar headerChecksum, String header) {
        this.headerChecksum = headerChecksum;
        this.header = header;
    }

    /** How to confirm a cached artifact from this domain, or null when it must not be cached. */
    static Revalidation forDomain(String domain) {
        return BY_DOMAIN.get(domain);
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
