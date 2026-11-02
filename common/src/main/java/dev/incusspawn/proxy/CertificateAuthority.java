package dev.incusspawn.proxy;

import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import dev.incusspawn.Warnings;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceNames;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.MacKeychainTrust;
import dev.incusspawn.util.SystemTrust;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static dev.incusspawn.DerEncoder.*;

/**
 * Manages a self-signed CA for the MITM TLS proxy.
 * <p>
 * The CA key and certificate are stored at {@code ~/.config/incus-spawn/ca.key}
 * and {@code ~/.config/incus-spawn/ca.crt} with owner-only permissions.
 * <p>
 * Per-domain certificates are generated on-the-fly using pure Java DER encoding,
 * signed by this CA, and held in memory only.
 */
public class CertificateAuthority {
    private static final Pattern PEM_ARMOR = Pattern.compile("-----[A-Z ]+-----");
    private static final Pattern WHITESPACE_CHAR = Pattern.compile("\\s");

    /**
     * How far back {@code notBefore} is set on generated certs, to tolerate clock
     * skew between the host that mints a cert and a (possibly lagging) container
     * that validates it. Leaf certs are persisted and reused across proxy restarts
     * (see {@link CertStore}), so this margin only matters at the rare moments a
     * cert is freshly minted: first install, CA rotation, and near-expiry renewal.
     */
    private static final long BACKDATE_MS = 2L * 24 * 60 * 60 * 1000;

    private static Path caKeyFile() { return SpawnConfig.configDir().resolve("ca.key"); }
    private static Path caCertFile() { return SpawnConfig.configDir().resolve("ca.crt"); }

    /**
     * The CA certificate that {@link #reissueWithSki} replaced, kept so that images
     * built against it can be recognised as carrying a superseded — not a rotated — CA.
     */
    private static Path caSupersededCertFile() {
        return SpawnConfig.configDir().resolve("ca-superseded.crt");
    }

    /** Set once the SKI upgrade has failed, so it is attempted at most once per process. */
    private static volatile boolean reissueFailed;

    private final PrivateKey caKey;
    private final X509Certificate caCert;

    private CertificateAuthority(PrivateKey caKey, X509Certificate caCert) {
        this.caKey = caKey;
        this.caCert = caCert;
    }

    /**
     * Load existing CA from disk, or generate a new one if none exists.
     */
    public static CertificateAuthority loadOrCreate() {
        try {
            if (Files.exists(caKeyFile()) && Files.exists(caCertFile())) {
                return load();
            }
            return generate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load or create CA: " + e.getMessage(), e);
        }
    }

    /**
     * Check whether a CA certificate already exists on disk.
     */
    public static boolean exists() {
        return Files.exists(caKeyFile()) && Files.exists(caCertFile());
    }

    /**
     * Generate a TLS certificate for the given domain, signed by this CA.
     * The certificate includes the domain as both CN and a SAN DNS entry.
     * Uses pure Java DER encoding — no openssl processes, no JDK internal APIs.
     */
    public CertEntry generateDomainCert(String domain) {
        try {
            var keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            var keyPair = keyGen.generateKeyPair();

            var notBefore = new Date(System.currentTimeMillis() - BACKDATE_MS);
            var expiry = new Date(notBefore.getTime() + 366L * 24 * 60 * 60 * 1000);
            var serial = new BigInteger(128, new SecureRandom());

            var algId = sha256WithRsaAid();
            var tbsCert = derSequence(concat(
                    derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                    derInteger(serial),
                    algId,
                    caCert.getSubjectX500Principal().getEncoded(),      // issuer
                    derSequence(concat(derUtcTime(notBefore), derUtcTime(expiry))),
                    derDistinguishedName(domain),                       // subject
                    keyPair.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                    derExplicit(3, derSequence(concat(                  // extensions
                            derExtension(oidBasicConstraints(), true,
                                    derSequence(new byte[]{0x01, 0x01, 0x00})),
                            derExtension(oidSubjectAltName(), false,
                                    derSequence(derDnsName(domain))),
                            derExtension(oidSubjectKeyIdentifier(), false,
                                    derOctetString(computeKeyIdentifier(keyPair.getPublic()))),
                            derExtension(oidAuthorityKeyIdentifier(), false,
                                    derAuthorityKeyIdentifier(computeKeyIdentifier(caCert.getPublicKey())))
                    )))
            ));

            var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(signAndWrap(tbsCert, caKey)));
            return new CertEntry(keyPair.getPrivate(), cert);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate cert for " + domain + ": " + e.getMessage(), e);
        }
    }

    /**
     * Return the CA certificate in PEM format, suitable for installing
     * in a container's trust store.
     */
    public String caCertPem() {
        try {
            return Files.readString(caCertFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CA cert: " + e.getMessage(), e);
        }
    }

    public String caFingerprint() {
        try {
            return fingerprintOf(caCert);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute CA fingerprint", e);
        }
    }

    public static String currentCaFingerprint() {
        if (!exists()) return "";
        return loadOrCreate().caFingerprint();
    }

    /** Where {@code host-ca.propagate} reads the host's anchors from. Tests point it elsewhere. */
    static Path hostAnchorsDir = Path.of("/etc/pki/ca-trust/source/anchors");
    /** What {@code host-ca.propagate} reads from the macOS Keychain; none elsewhere. Tests replace it. */
    static Supplier<MacKeychainTrust.Trust> hostKeychain = SystemTrust::keychain;
    private static final String GUEST_ANCHORS_DIR = "/etc/pki/ca-trust/source/anchors/";

    /**
     * The trust anchors an instance should hold: the MITM proxy CA, plus host CA anchors when
     * {@code host-ca.propagate} is enabled: the host's anchor files and, on macOS, what an
     * administrator trusts for SSL in the Keychain, less what the Keychain denies (#1530). {@code files} is keyed by {@code isx-<sha256>.crt}
     * (the SHA-256 of the X.509 DER), with PEM content as the value. Content-addressable naming
     * eliminates filename collisions and shell-injection via filenames.
     */
    public record TrustAnchors(String caFingerprint, SortedMap<String, String> files) {

        /**
         * What an instance holding this set is stamped with: the CA's fingerprint, then
         * {@code +} and a fingerprint over the host anchors when there are any. The CA's part
         * stays readable on its own, so a stamp still says which CA an image was built against
         * after the host anchors change ({@link CaTrust}); without host anchors it is the CA's
         * fingerprint alone, as every image was stamped before host anchors existed.
         */
        public String stamp() {
            var hostFiles = new TreeMap<>(files);
            hostFiles.remove(anchorFile(caFingerprint));
            return hostFiles.isEmpty() ? caFingerprint : caFingerprint + "+" + digest(hostFiles);
        }
    }

    /**
     * The trust set for this host. Creates the CA if there is none yet: every caller is about
     * to install the set into an instance, and an instance must trust the CA its proxy signs with.
     * A host anchor file that cannot be read as certificates goes to {@link Warnings}, once per
     * channel: the set is read again on every use, and the same file would be reported each time.
     */
    public static TrustAnchors collectTrustAnchors() {
        var ca = loadOrCreate();
        var caFingerprint = ca.caFingerprint();
        var files = new TreeMap<String, String>();
        files.put(anchorFile(caFingerprint), ca.caCertPem().strip());
        if (SpawnConfig.load().hostCa().propagate()) {
            addHostAnchors(files, hostKeychain.get());
        }
        return new TrustAnchors(caFingerprint, files);
    }

    private static String anchorFile(String fingerprint) {
        return "isx-" + fingerprint + ".crt";
    }

    /**
     * Adds the certificates in the host's anchor files, each keyed by its own fingerprint and
     * re-encoded as PEM from its DER: nothing else in a file (a private key, comments, bag
     * attributes, its line endings) reaches a guest or the stamp. A file that does not parse as
     * certificates throws, so it is refused whole. A host copy of the local CA lands on the CA's
     * own entry and is skipped.
     * <p>
     * Then the {@code keychain}'s admin-trusted certificates, the same that isx itself trusts on
     * macOS ({@link SystemTrust}): a TLS-inspection CA is there, not in an anchors directory.
     * The Keychain trusts them for SSL alone, so they are written as an OpenSSL
     * {@code TRUSTED CERTIFICATE} for TLS server and client authentication, which keeps them out of
     * the guest's email and code-signing bundles; a plain certificate in the anchors directory is
     * trusted for every purpose.
     * Whatever the Keychain denies is left out, from either source, as isx leaves it out of its
     * own trust. A Keychain not fully read adds nothing and is reported: guests lose its CAs until
     * a later use reads it, narrower rather than a set nobody could check.
     */
    private static void addHostAnchors(SortedMap<String, String> anchors, MacKeychainTrust.Trust keychain) {
        for (var cert : hostAnchorFiles()) {
            if (!keychain.denies(cert)) addAnchor(anchors, cert, "CERTIFICATE", new byte[0]);
        }
        if (keychain.problem() != null) {
            // Without the problem, which ClientLog has: one message however a read fails, as the TUI re-collects
            Warnings.warn("host-ca: the Keychain could not be fully read, so its CAs are left out of instances"
                    + " (`isx doctor` says why)");
            return;
        }
        for (var cert : keychain.trusted()) {
            if (!keychain.denies(cert)) addAnchor(anchors, cert, "TRUSTED CERTIFICATE", TLS_TRUST);
        }
    }

    /**
     * OpenSSL's auxiliary trust ({@code X509_CERT_AUX}) that follows a {@code TRUSTED CERTIFICATE}'s
     * DER: trusted for serverAuth and clientAuth, as {@code openssl x509 -addtrust serverAuth
     * -addtrust clientAuth -trustout} writes it, and nothing else. p11-kit reads it from the anchors
     * directory and extracts such a CA into the TLS bundle alone.
     */
    private static final byte[] TLS_TRUST = derSequence(derSequence(concat(
            new byte[] {0x06, 0x08, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01},     // id-kp-serverAuth
            new byte[] {0x06, 0x08, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02})));  // id-kp-clientAuth

    /** Keyed by the certificate's own fingerprint, whatever trust follows it; the first source to name it wins. */
    private static void addAnchor(SortedMap<String, String> anchors, X509Certificate cert, String label, byte[] trust) {
        try {
            anchors.putIfAbsent(anchorFile(fingerprintOf(cert)), toPem(label, concat(cert.getEncoded(), trust)).strip());
        } catch (Exception e) {
            Warnings.warn("host-ca: skipped " + cert.getSubjectX500Principal() + ", which cannot be encoded: " + e.getMessage());
        }
    }

    /** The certificates of the {@code .crt} and {@code .pem} files in {@link #hostAnchorsDir}, a file that does not parse skipped. */
    private static List<X509Certificate> hostAnchorFiles() {
        if (!Files.isDirectory(hostAnchorsDir)) return List.of();
        var certs = new ArrayList<X509Certificate>();
        try (var stream = Files.list(hostAnchorsDir)) {
            for (var path : stream.toList()) {
                var name = path.getFileName().toString();
                if (!name.endsWith(".crt") && !name.endsWith(".pem")) continue;
                if (!Files.isRegularFile(path)) continue;
                try (var in = Files.newInputStream(path)) {
                    certs.addAll(SystemTrust.certificates(in));
                } catch (Exception e) {
                    Warnings.warn("host-ca: skipped " + name + ", which must hold only X.509 certificates"
                            + " (a private key or anything else in it refuses the whole file): " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Warnings.warn("host-ca: cannot list " + hostAnchorsDir + ": " + e.getMessage());
        }
        return certs;
    }

    /** A fingerprint over a set of anchors: changes when any is added, removed or modified. */
    private static String digest(SortedMap<String, String> anchors) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var entry : anchors.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Matches exactly the names {@link #anchorFile} writes: {@code isx-}, 64 lowercase hex digits
     * (spelled out rather than a range, which a locale's collation can widen), {@code .crt}.
     * Anything else in the guest's anchors directory, such as {@code isx-company.crt}, is not
     * isx's to remove. A {@code case} pattern, never a path: matching nothing, a glob reaches
     * {@code rm} as itself, longer than a file name may be, and fails the script.
     */
    private static final String MANAGED_ANCHOR_PATTERN = anchorFile("[0123456789abcdef]".repeat(64));

    /**
     * Build a shell script that removes all isx-managed anchors (both the old
     * {@code incus-spawn-mitm.crt} and the current {@code isx-<sha256>.crt}), writes
     * the given trust set, and runs {@code update-ca-trust}. Uses
     * {@link Container#heredoc} so content cannot escape the heredoc.
     */
    static String buildReconcileScript(SortedMap<String, String> anchors) {
        // set -e: the exit status is the first failure's, so a write that fails is not stamped
        // as done because update-ca-trust, last, succeeded
        var sb = new StringBuilder("set -e\n");
        sb.append("rm -f ").append(GUEST_ANCHORS_DIR).append("incus-spawn-mitm.crt\n");
        sb.append("for f in ").append(GUEST_ANCHORS_DIR).append(anchorFile("*")).append("; do\n")
                .append("  case \"${f##*/}\" in ").append(MANAGED_ANCHOR_PATTERN).append(") rm -f \"$f\" ;; esac\n")
                .append("done\n");
        for (var entry : anchors.entrySet()) {
            sb.append(Container.heredoc(
                    "cat > " + GUEST_ANCHORS_DIR + entry.getKey(),
                    entry.getValue().strip()));
            sb.append('\n');
        }
        sb.append("update-ca-trust");
        return sb.toString();
    }

    /** Replaces the isx-managed anchors in {@code container} with {@code trust}, in one exec. */
    public static IncusClient.ExecResult installTrustAnchors(IncusClient incus, String container, TrustAnchors trust) {
        return incus.shellExec(container, "sh", "-c", buildReconcileScript(trust.files()));
    }

    /** How an image's stored CA fingerprint relates to the CA on this host. */
    public enum CaStatus {
        /** Built against the current CA cert. */
        CURRENT,
        /** Built against a cert this host re-issued over the same key: stale, but repairable. */
        REPAIRABLE,
        /** Built against a CA this host has no record of — a different key, or another machine. */
        FOREIGN,
        /** No fingerprint stored, or no CA on this host, so nothing can be concluded. */
        UNKNOWN
    }

    /**
     * The fingerprints an image can be compared against, read once so callers that
     * classify many images do not re-read and re-hash the CA per image.
     * <p>
     * Only a stamp's CA part ({@link TrustAnchors#stamp}) is compared: it says which CA an image
     * was built against. Host anchors that changed since are reconciled on first use, as
     * a superseded CA is, and do not make an image foreign.
     */
    public record CaTrust(String current, String superseded) {

        public static CaTrust snapshot() {
            return new CaTrust(currentCaFingerprint(), supersededCaFingerprint());
        }

        public CaStatus classify(String storedFingerprint) {
            if (storedFingerprint == null || storedFingerprint.isEmpty() || current.isEmpty()) {
                return CaStatus.UNKNOWN;
            }
            var plus = storedFingerprint.indexOf('+');
            var ca = plus < 0 ? storedFingerprint : storedFingerprint.substring(0, plus);
            if (ca.equals(current)) return CaStatus.CURRENT;
            if (ca.equals(superseded)) return CaStatus.REPAIRABLE;
            return CaStatus.FOREIGN;
        }
    }

    /**
     * Fingerprint of the CA certificate replaced by {@link #reissueWithSki}, or {@code ""}
     * if this CA was never re-issued.
     * <p>
     * Single-slot on purpose: it bridges images built before key identifiers existed, and a
     * second key-preserving re-issue would overwrite it. That is acceptable only because
     * there is exactly one such upgrade; a recurring need would call for stamping the CA's
     * key identifier onto images instead of comparing certificate fingerprints.
     */
    static String supersededCaFingerprint() {
        try {
            if (!Files.exists(caSupersededCertFile())) return "";
            return fingerprintOf(loadCertificate(caSupersededCertFile()));
        } catch (Exception e) {
            return "";
        }
    }

    /** SHA-256 of the certificate's encoding, lower-case hex: what names an {@code isx-<fp>.crt} anchor. */
    public static String fingerprintOf(X509Certificate cert) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reconcile a container's trust anchors (MITM CA + host CAs) against the current
     * trust set. If mismatched: marks the stamp pending, removes all isx-managed anchors,
     * writes the current set via heredoc, and re-stamps on success. The stamp is
     * marked before cleanup so a failed install retries next time.
     * <p>
     * Serialized per instance across isx processes on this host ({@link #lockTrust}): two
     * reconciles that overlap, each from its own snapshot of the set, would both clean before
     * either writes, so a CA one snapshot had and the other dropped survives under the newer
     * stamp, and every later use skips the repair (#1381). Under the lock the stamp is read again
     * and the set collected afresh, so whoever goes last installs, and stamps, what it saw.
     * <p>
     * Containers without a stored fingerprint (pre-versioning) are skipped.
     * Returns true if a fix was applied, false if none was needed or it failed; a failure is
     * reported to {@code warn}, since anchors it was to revoke may still be trusted.
     */
    public static boolean fixContainerCaIfNeeded(IncusClient incus, String container, JqValue instance,
                                                 Consumer<String> warn) {
        // Unlocked, and from the caller's read when it has one: a stamp is written only once the
        // guest holds its set, and marked pending before anything there changes, so a match means
        // the guest holds the current set and costs nothing more
        var storedFp = instance != null
                ? instance.getField("config").pathText(Metadata.CA_FINGERPRINT, "")
                : incus.configGet(container, Metadata.CA_FINGERPRINT);
        if (storedFp.isEmpty()) return false;
        if (collectTrustAnchors().stamp().equals(storedFp)) return false;

        try (var lock = lockTrust(container)) {
            // The read above may predate another process's reconcile: read the stamp again
            storedFp = incus.configGet(container, Metadata.CA_FINGERPRINT);
            if (storedFp.isEmpty()) return false;
            var trust = collectTrustAnchors();
            var stamp = trust.stamp();
            if (stamp.equals(storedFp)) return false;

            // Until the guest holds the new set its stamp matches no set: a reconcile that fails
            // after removing the old anchors is retried, even once the set changes back. Not
            // empty, which would read as "built before stamps" and never be checked again.
            incus.configSet(container, Metadata.CA_FINGERPRINT, trust.caFingerprint() + "+pending");
            var result = installTrustAnchors(incus, container, trust);
            if (result.exitCode() != 0) {
                var why = result.stderr().strip().lines().reduce((first, last) -> last).orElse("exit " + result.exitCode());
                warn.accept(notUpdated(container, why.length() > 200 ? why.substring(0, 200) + "..." : why));
                return false;
            }
            incus.configSet(container, Metadata.CA_FINGERPRINT, stamp);
            return true;
        } catch (HostLock.HostLockException e) {
            // Nothing changed: the stamp still differs, so the next use tries again
            warn.accept(notUpdated(container, e.getMessage()));
            return false;
        }
    }

    private static String notUpdated(String container, String why) {
        return "could not update the trusted CAs in " + container + " (retried on next use): " + why;
    }

    /** A {@link #lockTrust} hold, released by closing it. */
    public interface TrustLock extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Hold the right to change {@code container}'s trust anchors and their stamp, against every
     * isx process on this host: whatever installs a set and stamps it takes this first. Waits at
     * most {@link HostLock#TIMEOUT}; a lock file that cannot be used at all (a home on NFS without
     * lockd) degrades, with a warning once, to the in-process lock rather than to no repair. A name
     * no instance can have is held by nothing.
     *
     * @throws HostLock.HostLockException if another process holds it past the timeout
     */
    public static TrustLock lockTrust(String container) {
        if (!InstanceNames.isValid(container)) return () -> {};
        return HostLock.acquireOrDegrade(trustLockFile(container), "updating the trusted CAs in " + container,
                ClientLog::debug, Warnings::warn)::close;
    }

    /** One file per instance name, kept: deleting it would let two processes lock different files. */
    static Path trustLockFile(String container) {
        return Environment.lockDir().resolve("trust").resolve(container + ".lock");
    }

    public PrivateKey caKey() {
        return caKey;
    }

    public X509Certificate caCert() {
        return caCert;
    }

    // --- Private helpers ---

    /**
     * Compute the key identifier per RFC 5280 §4.2.1.2 method 1: the SHA-1 of the
     * subjectPublicKey BIT STRING contents from SubjectPublicKeyInfo.
     * <p>
     * For RSA those contents are the PKCS#1 {@code RSAPublicKey} sequence, which is
     * cheaper to re-encode from the key than to locate by parsing the DER. This class
     * mints RSA keys throughout, so the cast holds — and fails loudly if that changes.
     */
    static byte[] computeKeyIdentifier(PublicKey key) throws Exception {
        var rsa = (RSAPublicKey) key;
        var subjectPublicKey = derSequence(concat(
                derInteger(rsa.getModulus()), derInteger(rsa.getPublicExponent())));
        return MessageDigest.getInstance("SHA-1").digest(subjectPublicKey);
    }

    private static CertificateAuthority load() throws Exception {
        var key = loadPrivateKey(caKeyFile());
        var cert = loadCertificate(caCertFile());
        if (cert.getExtensionValue(SKI_OID) == null && !reissueFailed) {
            cert = reissueWithSki(key, cert);
        }
        return new CertificateAuthority(key, cert);
    }

    /**
     * Re-issue a pre-SKI CA certificate over its existing key pair, adding the
     * Subject Key Identifier extension.
     * <p>
     * Strict TLS validators (OpenSSL 3.5, and so Python 3.13+, which enables
     * {@code VERIFY_X509_STRICT} by default) reject a chain whose CA certificate has
     * no SKI — adding AKI/SKI to the leaves alone is not enough, because the trust
     * anchor itself is checked. CAs generated before that fix therefore have to be
     * re-issued, and only the certificate can change: the key pair is reused, so the
     * subject, the public key, and hence every leaf's Authority Key Identifier stay
     * valid. Already-issued leaf certs keep verifying and are not re-minted.
     * <p>
     * The re-issued certificate is a pure function of the old one and the key — same
     * subject, serial, and validity window, and RSA PKCS#1 v1.5 signatures are
     * deterministic — so two processes migrating concurrently write identical bytes.
     * Reusing the serial leaves the old and new certificates sharing an issuer/serial
     * pair, which is harmless here: the old one is superseded, and determinism is
     * worth more than uniqueness for a private self-signed root.
     * <p>
     * Best-effort: if the new certificate cannot be persisted, the old one is kept, so
     * the in-memory CA never disagrees with what containers are handed by
     * {@link #caCertPem()}. A failure is not retried — {@code loadOrCreate()} runs several
     * times per command, and re-signing and re-warning on each would only add noise.
     */
    private static X509Certificate reissueWithSki(PrivateKey key, X509Certificate old) {
        try {
            var der = selfSignedCaCertDer(old.getPublicKey(), key,
                    old.getSubjectX500Principal().getEncoded(),
                    old.getNotBefore(), old.getNotAfter(), old.getSerialNumber());

            writeAtomically(caSupersededCertFile(), toPem("CERTIFICATE", old.getEncoded()));
            writeAtomically(caCertFile(), toPem("CERTIFICATE", der));

            // File-only: this runs under the TUI too, where stderr lands mid-render.
            ClientLog.warn("Upgraded the MITM CA certificate to include a Subject Key Identifier "
                    + "(required by strict TLS validators). The CA key is unchanged.");
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            reissueFailed = true;
            ClientLog.warn("Could not upgrade the MITM CA certificate: " + e.getMessage());
            return old;
        }
    }

    /** Write {@code content} to {@code path} via a temp file, so readers never see a partial cert. */
    private static void writeAtomically(Path path, String content) throws IOException {
        var tmp = Files.createTempFile(path.getParent(), ".isx-ca-", ".tmp");
        try {
            Files.writeString(tmp, content);
            CertStore.trySetPerms(tmp, "rw-------");
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * Build the DER of a self-signed CA certificate: v3, basicConstraints CA:TRUE,
     * keyCertSign + cRLSign, and a Subject Key Identifier. Shared by first-time
     * generation and by {@link #reissueWithSki}, so both produce the same shape.
     */
    private static byte[] selfSignedCaCertDer(PublicKey pub, PrivateKey signingKey, byte[] subject,
                                              Date notBefore, Date notAfter, BigInteger serial)
            throws Exception {
        // keyCertSign (bit 5) | cRLSign (bit 6) → byte value 0x06, with 1 unused trailing bit
        var keyUsageBits = new byte[]{0x03, 0x02, 0x01, 0x06};

        var algId = sha256WithRsaAid();
        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),   // v3
                derInteger(serial),
                algId,
                subject,                                            // issuer = subject (self-signed)
                derSequence(concat(derUtcTime(notBefore), derUtcTime(notAfter))),
                subject,
                pub.getEncoded(),                                   // SubjectPublicKeyInfo
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, (byte) 0xff})),
                        derExtension(oidKeyUsage(), true, keyUsageBits),
                        // No AKI: RFC 5280 §4.2.1.1 exempts self-signed certs.
                        derExtension(oidSubjectKeyIdentifier(), false,
                                derOctetString(computeKeyIdentifier(pub)))
                )))
        ));

        return signAndWrap(tbsCert, signingKey);
    }

    /** Sign a TBSCertificate and wrap it into the outer Certificate SEQUENCE. */
    private static byte[] signAndWrap(byte[] tbsCert, PrivateKey signingKey) throws Exception {
        var sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(signingKey);
        sig.update(tbsCert);
        return derSequence(concat(tbsCert, sha256WithRsaAid(), derBitString(sig.sign())));
    }

    private static CertificateAuthority generate() throws Exception {
        BuildOutput.stepStart("Generating MITM CA certificate");
        try {
            var ca = generateUnreported();
            BuildOutput.stepDone();
            return ca;
        } catch (Exception e) {
            BuildOutput.abandonStep();
            throw e;
        }
    }

    private static CertificateAuthority generateUnreported() throws Exception {
        Files.createDirectories(caKeyFile().getParent());
        // A new key pair is a real rotation, not an SKI upgrade: drop any record of a
        // superseded cert so images built against it are not mistaken for repairable.
        Files.deleteIfExists(caSupersededCertFile());

        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();

        var notBefore = new Date(System.currentTimeMillis() - BACKDATE_MS);
        var expiry = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000);
        var serial = new BigInteger(128, new SecureRandom());
        var subject = derDistinguishedName("incus-spawn MITM CA");

        var certDer = selfSignedCaCertDer(keyPair.getPublic(), keyPair.getPrivate(),
                subject, notBefore, expiry, serial);

        Files.writeString(caKeyFile(), toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.setPosixFilePermissions(caKeyFile(),
                PosixFilePermissions.fromString("rw-------"));

        Files.writeString(caCertFile(), toPem("CERTIFICATE", certDer));
        Files.setPosixFilePermissions(caCertFile(),
                PosixFilePermissions.fromString("rw-------"));

        BuildOutput.stepNote("CA certificate saved to " + caCertFile());
        BuildOutput.stepNote("CA private key saved to " + caKeyFile());

        var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        return new CertificateAuthority(keyPair.getPrivate(), cert);
    }

    private static PrivateKey loadPrivateKey(Path path) throws Exception {
        var pem = Files.readString(path);
        var base64 = WHITESPACE_CHAR.matcher(PEM_ARMOR.matcher(pem).replaceAll("")).replaceAll("");
        var der = Base64.getDecoder().decode(base64);

        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 (legacy openssl genrsa output) — wrap in PKCS#8 envelope
            der = wrapPkcs1InPkcs8(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // Backward compat: existing CAs generated by older versions used openssl genrsa (PKCS#1)
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] rsaOid = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};
        byte[] algId = derSequence(concat(rsaOid, new byte[]{0x05, 0x00}));
        return derSequence(concat(derInteger(BigInteger.ZERO), algId, derOctetString(pkcs1)));
    }

    private static X509Certificate loadCertificate(Path path) throws Exception {
        var certPem = Files.readAllBytes(path);
        var certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(certPem));
    }

    /**
     * A generated domain certificate and its private key.
     */
    public record CertEntry(PrivateKey key, X509Certificate cert) {}
}
