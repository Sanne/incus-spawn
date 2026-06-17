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

    private static final String INCLUDE_LINE = "Include ~/.config/incus-spawn/ssh/config";
    private static final String CONFIG_HEADER = "# Auto-managed by incus-spawn -- do not edit\n";
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
     * Idempotently prepend an Include directive to ~/.ssh/config pointing
     * to the incus-spawn managed SSH config.
     *
     * @return true if the Include is present (already existed or was added), false on failure
     */
    public static boolean ensureSshConfigInclude() {
        try {
            var sshDir = Environment.home().resolve(".ssh");
            Files.createDirectories(sshDir);
            Files.setPosixFilePermissions(sshDir, PosixFilePermissions.fromString("rwx------"));

            var sshConfig = sshDir.resolve("config");
            // Resolve symlinks so dotfile-managed configs are updated in place
            var resolvedConfig = Files.exists(sshConfig)
                    ? sshConfig.toRealPath()
                    : sshConfig;
            String content = "";
            if (Files.exists(resolvedConfig)) {
                content = Files.readString(resolvedConfig);
                // Check if the Include is already at the top (before any Host block)
                for (var line : content.lines().toList()) {
                    var trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    if (trimmed.equals(INCLUDE_LINE)) return true;
                    break; // first non-blank, non-comment line is not our Include
                }
            }

            // Prepend Include line — must come before Host blocks to take effect
            var newContent = INCLUDE_LINE + "\n\n" + content;
            writeAtomically(resolvedConfig, newContent);
            return true;
        } catch (IOException e) {
            System.err.println("  Warning: failed to update ~/.ssh/config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensure the managed SSH config file exists (creates it with the header if missing).
     * Also creates the ssh directory if needed.
     */
    static void ensureManagedConfigExists() throws IOException {
        Files.createDirectories(Environment.sshDir());
        if (!Files.exists(Environment.sshConfigFile())) {
            Files.writeString(Environment.sshConfigFile(), CONFIG_HEADER);
        }
    }

    /**
     * Add or replace a Host block in the managed SSH config for the given instance.
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
            ensureManagedConfigExists();
            var content = Files.readString(Environment.sshConfigFile());
            var blocks = parseWithoutHostBlocks(content, instanceName);

            var isxPath = resolveIsxPath();

            blocks.add("");
            blocks.add("Host " + instanceName);
            if (hostname != null && !hostname.isEmpty()) {
                blocks.add("    Hostname " + hostname);
            }
            blocks.add("    ProxyCommand \"" + isxPath + "\" ssh-proxy " + instanceName);
            blocks.add("    User agentuser");
            blocks.add("    IdentityFile ~/.config/incus-spawn/ssh/id_ed25519");
            blocks.add("    IdentitiesOnly yes");
            blocks.add("    StrictHostKeyChecking no");
            blocks.add("    UserKnownHostsFile /dev/null");
            blocks.add("");

            writeAtomically(Environment.sshConfigFile(), String.join("\n", blocks));
            return true;
        } catch (IOException e) {
            System.err.println("  Warning: failed to update SSH config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove a Host block from the managed SSH config.
     */
    public static void removeHostEntry(String instanceName) {
        if (!Files.exists(Environment.sshConfigFile())) return;
        try {
            var content = Files.readString(Environment.sshConfigFile());
            var blocks = parseWithoutHostBlocks(content, instanceName);
            writeAtomically(Environment.sshConfigFile(), String.join("\n", blocks));
        } catch (IOException e) {
            System.err.println("  Warning: failed to update SSH config: " + e.getMessage());
        }
    }

    /**
     * Clean up SSH config for a destroyed instance.
     */
    public static void cleanupInstance(String instanceName) {
        removeHostEntry(instanceName);
    }

    /**
     * Parse the managed SSH config, returning all lines except those belonging to
     * the named Host block.
     */
    private static List<String> parseWithoutHostBlocks(String content, String instanceName) {
        var result = new ArrayList<String>();
        var lines = content.lines().toList();
        boolean skipping = false;

        for (var line : lines) {
            var trimmed = line.strip();
            if (trimmed.startsWith("Host ")) {
                skipping = trimmed.substring(5).strip().equals(instanceName);
                if (!skipping) result.add(line);
            } else if (skipping) {
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !Character.isWhitespace(line.charAt(0))) {
                    skipping = false;
                    result.add(line);
                }
            } else {
                result.add(line);
            }
        }

        // Trim trailing empty lines
        while (!result.isEmpty() && result.get(result.size() - 1).isBlank()) {
            result.remove(result.size() - 1);
        }
        return result;
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
