package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.Metadata;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Stable credential proofs, independent of the rotating MCP/host-service secret. */
public final class CredentialProof {
    public static final String GUEST_PATH = "/run/isx/credential-secret";
    public static final String FILE_ENV_VAR = "ISX_CREDENTIAL_SECRET_FILE";
    public static final String DELIVERY_ENV = "ISX_CREDENTIAL_SECRET";

    private CredentialProof() {}

    /** Public generation identifiers are safe in metadata; only the host key can derive a seed. */
    public record Seed(String generation, String secret) {
        @Override public String toString() { return "CredentialProof.Seed[redacted]"; }
    }

    public static Seed fresh() { return derive(InstanceSecret.generate(), true); }

    /** Empty means an upgrade or a revoked build; a lost host key never silently rotates an existing grant. */
    public static Seed retain(String generation) {
        return generation == null || generation.isBlank() ? fresh() : derive(generation, false);
    }

    /** A replaced host key must not silently rotate every other instance when it next starts. */
    public static Seed retain(String generation, String verifierStamp) {
        var seed = retain(generation);
        for (var verifier : ProofVerifier.parse(verifierStamp).entrySet()) {
            if (!ProofVerifier.matches(ProofToken.derive(seed.secret(), verifier.getKey()), verifier.getValue())) {
                throw new IllegalStateException("Credential proof host key does not match this instance; restore it or run 'isx account reset-proofs <instance>'");
            }
        }
        return seed;
    }

    public static void stampInto(Map<String, String> updates, Seed seed, List<ProofToken.Placeholder> placeholders) {
        updates.put(Metadata.INSTANCE_PROOF_ID, seed.generation());
        updates.put(Metadata.INSTANCE_PROOF_SHA256, ProofVerifier.stamp(seed.secret(), placeholders));
    }

    public static Path keyFile() { return Environment.configDir().resolve("credential-proofs/host.key"); }

    public static dev.incusspawn.util.HostLock lock(String instance) {
        return dev.incusspawn.util.HostLock.acquire(Environment.lockDir().resolve("credential-proofs")
                .resolve(dev.incusspawn.tool.ToolDef.sha256hex(instance) + ".lock"),
                "updating credential proofs for " + instance, dev.incusspawn.Warnings::warn);
    }

    private static Seed derive(String generation, boolean createKey) {
        if (!generation.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid credential proof generation; reset the instance's proofs");
        try {
            var configDir = Environment.configDir();
            if (Files.isSymbolicLink(configDir)) throw new IllegalStateException("Credential proof config directory must not be a symbolic link");
            var uid = HostRouteSecret.ownUid(configDir);
            var file = keyFile();
            var dir = HostRouteSecret.ownerOnlyDirectory(configDir, file.getParent(), uid, uid);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!createKey) throw new IllegalStateException("Credential proof host key is missing; restore it or run 'isx account reset-proofs <instance>'");
                HostRouteSecret.create(dir, file);
            }
            var key = HostRouteSecret.read(file, uid);
            if (!key.matches("[0-9a-f]{64}")) throw new IllegalStateException("Credential proof host key is invalid");
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(key), "HmacSHA256"));
            return new Seed(generation, HexFormat.of().formatHex(mac.doFinal(
                    ("isx-credential-proof-seed:" + generation).getBytes(StandardCharsets.US_ASCII))));
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot use credential proof host key: " + e.getMessage(), e);
        }
    }
}
