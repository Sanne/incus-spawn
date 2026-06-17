package dev.incusspawn.ssh;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages a dedicated SSH key pair and per-instance SSH configuration
 * for seamless container access without passphrase prompts or host key warnings.
 */
public final class SshKeyManager {

    private static final String MARKER_BEGIN = "# isx:begin:";
    private static final String MARKER_END = "# isx:end:";
    private SshKeyManager() {}

    public static boolean exists() {
        return Files.exists(Environment.sshKeyFile()) && Files.exists(Environment.sshPubKeyFile());
    }

    /**
     * Generate an ed25519 key pair if one does not already exist.
     */
    public static void ensureKeyPairExists() {
        if (exists()) return;

        try {
            if (!isSshKeygenAvailable()) {
                throw new RuntimeException(
                        "ssh-keygen not found. Install openssh-clients (Fedora/RHEL) " +
                        "or openssh-client (Debian/Ubuntu) and re-run 'isx init'.");
            }
            Files.createDirectories(Environment.sshDir());

            if (Files.exists(Environment.sshKeyFile()) && !Files.exists(Environment.sshPubKeyFile())) {
                // Private key exists but public key is missing — derive it rather than
                // regenerating, because containers already have the old public key
                if (derivePublicKey()) return;
                // Derivation failed (corrupt/incompatible key) — remove so fresh generation works
                Files.deleteIfExists(Environment.sshKeyFile());
            }

            // No usable key pair — generate fresh
            Files.deleteIfExists(Environment.sshPubKeyFile());

            var pb = new ProcessBuilder(
                    "ssh-keygen", "-t", "ed25519",
                    "-f", Environment.sshKeyFile().toString(),
                    "-N", "",
                    "-C", "incus-spawn managed key");
            pb.redirectErrorStream(true);
            var process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("ssh-keygen timed out");
            }
            var output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                throw new RuntimeException("ssh-keygen failed: " + output);
            }

            Files.setPosixFilePermissions(Environment.sshKeyFile(),
                    PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(Environment.sshPubKeyFile(),
                    PosixFilePermissions.fromString("rw-r--r--"));

            System.out.println("  SSH key pair generated at " + Environment.sshDir());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to generate SSH key pair: " + e.getMessage(), e);
        }
    }

    /**
     * Derive the public key from an existing private key.
     * @return true if successful
     */
    private static boolean derivePublicKey() {
        try {
            var pb = new ProcessBuilder(
                    "ssh-keygen", "-y", "-f", Environment.sshKeyFile().toString());
            pb.redirectErrorStream(true);
            var process = pb.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            var pubKey = new String(process.getInputStream().readAllBytes()).strip();
            if (process.exitValue() != 0 || pubKey.isEmpty()) return false;

            Files.writeString(Environment.sshPubKeyFile(), pubKey + "\n");
            Files.setPosixFilePermissions(Environment.sshPubKeyFile(),
                    PosixFilePermissions.fromString("rw-r--r--"));
            System.out.println("  SSH public key recovered from existing private key.");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String publicKeyContent() {
        try {
            return Files.readString(Environment.sshPubKeyFile()).strip();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SSH public key: " + e.getMessage(), e);
        }
    }

    /**
     * Add or replace a Host block in ~/.ssh/config for the given instance.
     * Uses a ProxyCommand that tunnels through the Incus exec API, so no direct
     * IP connectivity to the container is required.
     * @return true if the entry was written successfully
     */
    public static boolean addHostEntry(String instanceName) {
        return addHostEntry(instanceName, null);
    }

    /**
     * @param hostname optional IP/hostname for clients that don't support ProxyCommand
     */
    public static boolean addHostEntry(String instanceName, String hostname) {
        try {
            var sshConfig = resolveUserSshConfig();

            var isxPath = resolveIsxPath();

            var block = new ArrayList<String>();
            block.add(MARKER_BEGIN + instanceName);
            block.add("Host " + instanceName);
            if (hostname != null && !hostname.isEmpty()) {
                block.add("    Hostname " + hostname);
            }
            block.add("    ProxyCommand \"" + isxPath + "\" ssh-proxy " + instanceName);
            block.add("    User agentuser");
            block.add("    IdentityFile ~/.config/incus-spawn/ssh/id_ed25519");
            block.add("    IdentitiesOnly yes");
            block.add("    StrictHostKeyChecking no");
            block.add("    UserKnownHostsFile /dev/null");
            block.add(MARKER_END + instanceName);

            var content = Files.exists(sshConfig) ? Files.readString(sshConfig) : "";
            var cleaned = removeMarkerBlock(content, instanceName);
            var newContent = cleaned.stripTrailing().isEmpty()
                    ? String.join("\n", block) + "\n"
                    : cleaned.stripTrailing() + "\n\n" + String.join("\n", block) + "\n";
            writeAtomically(sshConfig, newContent);
            return true;
        } catch (IOException e) {
            System.err.println("  Warning: failed to update SSH config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a Host block from ~/.ssh/config.
     */
    public static void removeHostEntry(String instanceName) {
        try {
            var sshConfig = resolveUserSshConfig();
            if (!Files.exists(sshConfig)) return;
            var content = Files.readString(sshConfig);
            var cleaned = removeMarkerBlock(content, instanceName);
            writeAtomically(sshConfig, cleaned);
        } catch (IOException e) {
            System.err.println("  Warning: failed to update SSH config: " + e.getMessage());
        }
    }

    /**
     * Remove a marker-delimited block for the given instance from SSH config content.
     */
    private static String removeMarkerBlock(String content, String instanceName) {
        var beginMarker = MARKER_BEGIN + instanceName;
        var endMarker = MARKER_END + instanceName;
        var result = new ArrayList<String>();
        boolean skipping = false;

        for (var line : content.lines().toList()) {
            if (line.strip().equals(beginMarker)) {
                skipping = true;
            } else if (line.strip().equals(endMarker)) {
                skipping = false;
            } else if (!skipping) {
                result.add(line);
            }
        }

        // Trim trailing empty lines
        while (!result.isEmpty() && result.get(result.size() - 1).isBlank()) {
            result.remove(result.size() - 1);
        }
        return result.isEmpty() ? "" : String.join("\n", result) + "\n";
    }

    private static Path resolveUserSshConfig() throws IOException {
        var sshDir = Environment.home().resolve(".ssh");
        Files.createDirectories(sshDir);
        Files.setPosixFilePermissions(sshDir, PosixFilePermissions.fromString("rwx------"));
        var sshConfig = sshDir.resolve("config");
        return Files.exists(sshConfig) ? sshConfig.toRealPath() : sshConfig;
    }

    /**
     * Clean up SSH config for a destroyed instance.
     */
    public static void cleanupInstance(String instanceName) {
        removeHostEntry(instanceName);
    }

    private static String resolveIsxPath() {
        try {
            var pb = new ProcessBuilder("which", "isx");
            pb.redirectErrorStream(true);
            var p = pb.start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                var path = new String(p.getInputStream().readAllBytes()).strip();
                if (!path.isEmpty()) return path;
            }
        } catch (Exception ignored) {}
        return Environment.localBinIsx().toString();
    }

    private static boolean isSshKeygenAvailable() {
        try {
            var p = new ProcessBuilder("which", "ssh-keygen").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        var tmp = Files.createTempFile(target.getParent(), ".isx-ssh-", ".tmp");
        try {
            Files.writeString(tmp, content);
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
