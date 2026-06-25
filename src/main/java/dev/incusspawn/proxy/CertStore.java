package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.CertificateAuthority.CertEntry;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain-keyed persistent cache of MITM leaf certificates.
 * <p>
 * Leaf certs are stored under {@code ~/.config/incus-spawn/certs/} as
 * {@code <domain>.crt} / {@code <domain>.key} (wildcards encoded as
 * {@code _wildcard.<domain>}), signed by the {@link CertificateAuthority}.
 * <p>
 * <b>Why persist instead of minting per proxy start.</b> A leaf cert's
 * {@code notBefore} is stamped from the host clock at the moment it is minted.
 * The macOS proxy runs under launchd with {@code KeepAlive=true}, so it is
 * relaunched (and previously re-minted every cert) whenever it exits — including
 * right after the Mac wakes, when the host clock has already jumped forward to
 * real time but the Incus VM's clock is still lagging. A freshly minted cert
 * then has a {@code notBefore} in the container's future and fails validation
 * with "certificate is not yet valid". Persisting the leaf and reusing it across
 * restarts keeps the original {@code notBefore} (stamped while the clocks were in
 * sync), so the container's lagging-but-monotonic clock always accepts it.
 * <p>
 * A cert is re-minted only when it is missing, signed by a rotated CA, or close
 * to expiry — all rare events for which {@code CertificateAuthority}'s backdate
 * is the safety margin.
 * <p>
 * Certs are keyed by domain, never by container: a leaf is a function of
 * {@code (domain, CA)} and is identical for every container that intercepts that
 * domain. Per-container interception (a future feature) is a routing/DNS concern
 * — it decides which domains reach the proxy for a given container — and does not
 * change cert identity, so this store stays domain-keyed.
 */
public class CertStore {

    /** Re-mint a stored cert once it falls within this window of its expiry. */
    private static final long RENEW_BEFORE_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000;

    private final CertificateAuthority ca;
    private final Path dir;
    private final ConcurrentHashMap<String, CertEntry> memory = new ConcurrentHashMap<>();

    public CertStore(CertificateAuthority ca) {
        this.ca = ca;
        this.dir = SpawnConfig.configDir().resolve("certs");
    }

    /**
     * Return a leaf cert for {@code domain}, reusing the persisted one when it is
     * still usable, otherwise minting, persisting, and caching a fresh one.
     * Safe to call concurrently for distinct domains.
     */
    public CertEntry get(String domain) {
        return memory.computeIfAbsent(domain, this::loadOrMint);
    }

    private CertEntry loadOrMint(String domain) {
        var existing = tryLoad(domain);
        if (existing != null && isUsable(existing)) {
            return existing;
        }
        var fresh = ca.generateDomainCert(domain);
        persist(domain, fresh);
        return fresh;
    }

    private boolean isUsable(CertEntry entry) {
        var cert = entry.cert();
        try {
            // Re-mint if the stored cert was signed by a different (rotated) CA.
            cert.verify(ca.caCert().getPublicKey());
        } catch (Exception e) {
            return false;
        }
        // Re-mint if expired or close to expiry.
        return cert.getNotAfter().after(new Date(System.currentTimeMillis() + RENEW_BEFORE_EXPIRY_MS));
    }

    private CertEntry tryLoad(String domain) {
        var certFile = certPath(domain);
        var keyFile = keyPath(domain);
        if (!Files.exists(certFile) || !Files.exists(keyFile)) return null;
        try {
            var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile)));
            var key = loadPrivateKey(Files.readString(keyFile));
            return new CertEntry(key, cert);
        } catch (Exception e) {
            // Corrupt/unreadable on-disk cert: treat as absent and re-mint.
            return null;
        }
    }

    private void persist(String domain, CertEntry entry) {
        try {
            Files.createDirectories(dir);
            trySetPerms(dir, "rwx------");
            Files.writeString(certPath(domain),
                    DerEncoder.toPem("CERTIFICATE", entry.cert().getEncoded()));
            Files.writeString(keyPath(domain),
                    DerEncoder.toPem("PRIVATE KEY", entry.key().getEncoded()));
            trySetPerms(keyPath(domain), "rw-------");
        } catch (Exception e) {
            // Best-effort: an in-memory cert still serves this run; it just won't
            // have a stable notBefore across restarts until persistence succeeds.
            System.err.println("Warning: could not persist cert for " + domain + ": " + e.getMessage());
        }
    }

    private static void trySetPerms(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (Exception ignored) {
            // Non-POSIX filesystem: skip.
        }
    }

    private Path certPath(String domain) { return dir.resolve(fileName(domain) + ".crt"); }
    private Path keyPath(String domain)  { return dir.resolve(fileName(domain) + ".key"); }

    /** Encode a domain into a filesystem-safe stem ({@code *.foo} → {@code _wildcard.foo}). */
    static String fileName(String domain) {
        return domain.startsWith("*.") ? "_wildcard." + domain.substring(2) : domain;
    }

    private static PrivateKey loadPrivateKey(String pem) throws Exception {
        var base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        var der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
