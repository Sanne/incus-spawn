package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Names the private Maven cache's partitions (#2312): one directory per credential the proxy
 * injects, so two accounts never share a cached copy, a confirmation or a single-flight key, and a
 * token replaced in an account starts a partition of its own. The name is an HMAC of the
 * namespace, header and value under a random key kept owner-only beside the partitions, so the
 * directory names do not let anyone who can list them test guesses at a weak password.
 */
final class PrivatePartitions {

    private static final String KEY_FILE = ".key";
    private static final int KEY_BYTES = 32;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    private PrivatePartitions() {}

    /** The partition directory's name for {@code credential}: 32 hex characters. */
    static String of(byte[] key, ArtifactCacheHandler.Credential credential) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            for (var part : new String[] {credential.namespace(), credential.header(), credential.value()}) {
                mac.update(part.getBytes(StandardCharsets.UTF_8));
                mac.update((byte) 0);
            }
            return HexFormat.of().formatHex(mac.doFinal(), 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * The key in {@code dir}, created owner-only when there is none; null, said through {@code warn},
     * when it cannot be used. <strong>Blocks</strong> on the file system.
     */
    static byte[] loadOrCreateKey(Path dir, Consumer<String> warn) {
        var file = dir.resolve(KEY_FILE);
        try {
            Files.createDirectories(dir);
            if (Files.isSymbolicLink(dir)) throw new IOException(dir + " is a symbolic link");
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                var key = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(key);
                var temporary = Files.createTempFile(dir, KEY_FILE, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                try {
                    Files.write(temporary, key);
                    Files.createLink(file, temporary);
                } catch (FileAlreadyExistsException e) {
                    // Another proxy made it first: read theirs
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(file + " is not a regular file");
            if (!OWNER_ONLY.equals(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException(file + " is readable by others");
            }
            var key = Files.readAllBytes(file);
            if (key.length != KEY_BYTES) throw new IOException(file + " does not hold a key");
            return key;
        } catch (IOException | UnsupportedOperationException e) {
            warn.accept("Cannot use the private Maven cache's key: " + e.getMessage());
            return null;
        }
    }
}
