package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.config.Jjq;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where the proxy keeps container image manifests (#2306), and the rules for reading the
 * requests that ask for them. Files only: the requests themselves are made by
 * {@link ArtifactCacheHandler}.
 * <p>
 * A manifest is stored by its digest, verified on store, with its media type beside it. A tag is
 * a name that can move: what it last resolved to is kept per repository, tag and {@code Accept}.
 * Every copy is served on a registry's confirmation for the caller, and without one only for a
 * repository found public that served that digest: a manifest digest is often published (an
 * image pinned in a chart or a CI file), so knowing one is no proof of access.
 */
final class ManifestCache {

    private ManifestCache() {}

    /**
     * A manifest request: {@code /v2/<name>/manifests/<reference>}, the name as the OCI
     * distribution spec spells it, the reference a tag or a {@code sha256} digest.
     */
    static final Pattern MANIFEST_PATTERN = Pattern.compile(
            "/v2/([a-z0-9]+(?:(?:\\.|_|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:\\.|_|__|-+)[a-z0-9]+)*)*)"
                    + "/manifests/([A-Za-z0-9_][A-Za-z0-9._-]{0,127}|sha256:[a-f0-9]{64})");

    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");

    // A Bearer challenge's parameters: key="value"
    private static final Pattern CHALLENGE_PARAM = Pattern.compile("([A-Za-z]+)=\"([^\"]*)\"");

    // The media type is read into memory: anything longer is not one
    private static final int MAX_TYPE_BYTES = 256;

    // A token endpoint's answer: a JWT or an opaque token, never this long
    static final int MAX_TOKEN_BYTES = 64 * 1024;

    static Path dir() {
        return Environment.registryCacheDir().resolve("manifests");
    }

    /** Whether {@code reference} is a digest rather than a tag. */
    static boolean isDigest(String reference) {
        return DIGEST.matcher(reference).matches();
    }

    /** A {@code Docker-Content-Digest} the cache can verify and store under, or null. */
    static String usableDigest(String header) {
        return header != null && DIGEST.matcher(header.trim()).matches() ? header.trim() : null;
    }

    /** Where the manifest whose digest is {@code digest} is stored; its media type is beside it. */
    static Path copy(String digest) {
        return dir().resolve(digest.replace(':', '-'));
    }

    /** The suffix of the file beside a copy that holds its media type. */
    static final String TYPE = ".type";

    /** The media type stored with the copy of {@code digest}, or null when there is no whole copy. */
    static String storedType(String digest) {
        var copy = copy(digest);
        var type = copy.resolveSibling(copy.getFileName() + TYPE);
        try {
            if (!Files.isRegularFile(copy) || Files.size(type) > MAX_TYPE_BYTES) return null;
            var text = Files.readString(type, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Where the digest a tag last resolved to is kept: one file per registry, repository, tag and
     * {@code Accept}, since one tag gives a different manifest to a client that accepts other types.
     * Named by a hash, so no part of a request becomes a path.
     */
    static Path tag(String domain, String name, String tag, String accept) {
        return dir().resolve("tags").resolve(hash(domain + "\n" + name + "\n" + tag + "\n" + (accept == null ? "" : accept)));
    }

    /** The digest a tag last resolved to, or null. */
    static String resolvedDigest(Path tag) {
        try {
            if (!Files.isRegularFile(tag) || Files.size(tag) > MAX_TYPE_BYTES) return null;
            return usableDigest(Files.readString(tag, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return null;
        }
    }

    /** Records that a tag resolved to {@code digest}. */
    static void recordTag(Path tag, String digest) throws IOException {
        if (digest.equals(resolvedDigest(tag))) return;
        Files.createDirectories(tag.getParent());
        var temp = Files.createTempFile(tag.getParent(), "tag-", ".tmp");
        try {
            Files.writeString(temp, digest, StandardCharsets.US_ASCII);
            Files.move(temp, tag, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * The marker that {@code domain/name} served {@code digest}: copies are shared by digest, and one
     * is served unconfirmed only under a repository that served it.
     */
    static Path inRepositoryMarker(String domain, String name, String digest) {
        return dir().resolve("repositories").resolve(hash(domain + "\n" + name + "\n" + digest));
    }

    static boolean inRepository(String domain, String name, String digest) {
        return Files.isRegularFile(inRepositoryMarker(domain, name, digest));
    }

    static void recordInRepository(String domain, String name, String digest) throws IOException {
        var marker = inRepositoryMarker(domain, name, digest);
        if (Files.exists(marker)) return;
        Files.createDirectories(marker.getParent());
        try {
            Files.createFile(marker);
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            // Another request recorded it first
        }
    }

    /**
     * The marker a repository has while it was last found public: anyone may then pull it, so a
     * tag of it may be served from the copy when the registry cannot be asked.
     */
    static Path publicMarker(String domain, String name) {
        return dir().resolve("public").resolve(hash(domain + "\n" + name));
    }

    static boolean isPublic(String domain, String name) {
        return Files.isRegularFile(publicMarker(domain, name));
    }

    /** Records what an anonymous check found: the marker is there exactly while the repository is public. */
    static void recordPublic(String domain, String name, boolean isPublic) throws IOException {
        var marker = publicMarker(domain, name);
        if (isPublic) {
            Files.createDirectories(marker.getParent());
            if (!Files.exists(marker)) Files.writeString(marker, domain + "/" + name, StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(marker);
        }
    }

    /**
     * Where an anonymous client gets a token for pulling {@code name}, read from a 401's
     * {@code WWW-Authenticate: Bearer realm=...,service=...}, or null. The realm must be an https
     * URL on one of {@code registries}, so a registry cannot send the proxy anywhere else. The scope
     * is the proxy's own, pull only: the challenge's is not taken.
     */
    static URI tokenUri(String challenge, String name, Set<String> registries) {
        if (challenge == null) return null;
        var text = challenge.strip();
        if (text.length() < 7 || !text.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        var params = new LinkedHashMap<String, String>();
        var matcher = CHALLENGE_PARAM.matcher(text.substring(7));
        while (matcher.find()) params.putIfAbsent(matcher.group(1).toLowerCase(java.util.Locale.ROOT), matcher.group(2));
        var realm = params.get("realm");
        if (realm == null) return null;
        URI uri;
        try {
            uri = URI.create(realm);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() != null
                || uri.getPort() != -1 || uri.getRawUserInfo() != null || !registries.contains(uri.getHost())) {
            return null;
        }
        var query = new StringBuilder();
        var service = params.get("service");
        if (service != null) query.append("service=").append(URLEncoder.encode(service, StandardCharsets.UTF_8)).append('&');
        query.append("scope=").append(URLEncoder.encode("repository:" + name + ":pull", StandardCharsets.UTF_8));
        var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return URI.create("https://" + uri.getHost() + path + "?" + query);
    }

    /** The token in a token endpoint's answer ({@code token}, or OAuth2's {@code access_token}), or null. */
    static String token(byte[] body) {
        if (body == null) return null;
        try {
            var answer = Jjq.parseForeign(body, "a registry's token endpoint");
            for (var field : new String[] {"token", "access_token"}) {
                var value = answer.getField(field);
                if (value.isString() && !value.asText().isBlank() && value.asText().chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
                    return value.asText();
                }
            }
        } catch (RuntimeException unreadable) {
            // Not JSON, or not an object: no token
        }
        return null;
    }

    /** What the cache stores beside a manifest it caches: its media type. */
    static SequencedMap<String, byte[]> alongside(String type) {
        var alongside = new LinkedHashMap<String, byte[]>();
        alongside.put(TYPE, type.getBytes(StandardCharsets.UTF_8));
        return alongside;
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
