package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Hashes of namespace digests, never the secret or a token a guest can present. */
public final class ProofVerifier {
    private ProofVerifier() {}

    /** One replaceable stamp: removed declarations cannot leave inherited verifiers behind. */
    public static String stamp(String secret, Collection<ProofToken.Placeholder> placeholders) {
        var hashes = new TreeMap<String, String>();
        for (var placeholder : placeholders) {
            if (placeholder.problem().isEmpty()) {
                hashes.put(placeholder.namespace(), ToolDef.sha256hex(ProofToken.derive(secret, placeholder.namespace())));
            }
        }
        return hashes.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static Map<String, String> parse(String stamp) {
        var hashes = new LinkedHashMap<String, String>();
        for (var line : stamp.split("\n")) {
            var parts = line.split(":", -1);
            if (parts.length != 2 || !ToolDefValidator.isNamespace(parts[0])
                    || !parts[1].matches("[0-9a-f]{64}") || hashes.putIfAbsent(parts[0], parts[1]) != null) {
                return Map.of();
            }
        }
        return Map.copyOf(hashes);
    }

    public static boolean matches(String digest, String hash) {
        return digest != null && hash != null && MessageDigest.isEqual(
                ToolDef.sha256hex(digest).getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII));
    }
}
