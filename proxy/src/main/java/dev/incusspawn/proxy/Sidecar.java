package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A file a Maven-layout repository publishes next to an artifact to describe it:
 * a checksum in one of four algorithms, or a detached PGP signature.
 */
enum Sidecar {
    SHA1(".sha1", "SHA-1", 40),
    MD5(".md5", "MD5", 32),
    SHA256(".sha256", "SHA-256", 64),
    SHA512(".sha512", "SHA-512", 128),
    ASC(".asc", null, 0);

    private static final int BUFFER_SIZE = 64 * 1024;

    final String extension;
    private final String algorithm;
    private final Pattern hexPattern;

    Sidecar(String extension, String algorithm, int hexLength) {
        this.extension = extension;
        this.algorithm = algorithm;
        this.hexPattern = algorithm == null ? null : Pattern.compile("[a-f0-9]{" + hexLength + "}");
    }

    /** The sidecar type a request path names, or null for anything else (an artifact). */
    static Sidecar of(String path) {
        for (var s : values()) {
            if (path.endsWith(s.extension)) return s;
        }
        return null;
    }

    boolean isChecksum() {
        return algorithm != null;
    }

    /** The artifact path this sidecar path describes. */
    String artifactPath(String sidecarPath) {
        return sidecarPath.substring(0, sidecarPath.length() - extension.length());
    }

    /** Where the stored copy of this sidecar lives, next to the cached artifact. */
    Path storedFile(Path artifact) {
        return artifact.resolveSibling(artifact.getFileName() + extension);
    }

    /**
     * The checksum a sidecar body states, as lowercase hex, or null if no
     * well-formed checksum of this algorithm is found. Repositories publish the bare
     * hex, the {@code sha1sum} form {@code "<hex>  <filename>"} (hex first) and the
     * BSD/OpenSSL forms {@code "SHA1(<filename>)= <hex>"} and
     * {@code "MD5 (<filename>) = <hex>"} (hex last), all of which Maven accepts.
     */
    String hex(byte[] body) {
        if (!isChecksum() || body == null) return null;
        var tokens = new String(body, java.nio.charset.StandardCharsets.US_ASCII).trim().split("\\s+");
        for (var token : new String[] {tokens[0], tokens[tokens.length - 1]}) {
            var hex = token.toLowerCase(Locale.ROOT);
            if (hexPattern.matcher(hex).matches()) return hex;
        }
        return null;
    }

    /**
     * Whether two bodies of this sidecar say the same thing: the same checksum for
     * checksum types (formatting may differ), byte-identical for signatures.
     */
    boolean sameContent(byte[] a, byte[] b) {
        if (!isChecksum()) return Arrays.equals(a, b);
        var hexA = hex(a);
        return hexA != null && hexA.equals(hex(b));
    }

    /** Hash a file with this sidecar's algorithm, as lowercase hex. */
    String hashOf(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
