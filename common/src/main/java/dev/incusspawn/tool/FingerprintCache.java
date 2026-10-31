package dev.incusspawn.tool;

import dev.incusspawn.config.MappedDefinition;

/**
 * The {@link ToolDef#contentFingerprint()} once computed.
 *
 * <p>A superclass because the generated mapping counts every field the class itself declares as
 * a known key, {@code transient} or not: declared in {@link ToolDef}, a key named
 * {@code cachedFingerprint} in a tool would be dropped without the unknown-key warning (#1889).
 * An inherited field is not counted.
 */
abstract class FingerprintCache extends MappedDefinition {
    private transient volatile String cachedFingerprint;

    String cachedFingerprint() { return cachedFingerprint; }
    void cacheFingerprint(String fingerprint) { cachedFingerprint = fingerprint; }
}
