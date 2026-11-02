package dev.incusspawn.ssh;

import dev.incusspawn.Environment;
import dev.incusspawn.Warnings;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;

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

    /** Test seam: runs between reading the managed config and writing it back. */
    static volatile Runnable afterConfigRead = () -> {};

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

            BuildOutput.note("SSH key pair generated at " + Environment.sshDir());
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
            BuildOutput.note("SSH public key recovered from existing private key.");
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
                if (includedIn(content)) return true;
            }

            // Prepend Include line — must come before Host blocks to take effect
            var newContent = INCLUDE_LINE + "\n\n" + content;
            writeAtomically(resolvedConfig, newContent);
            return true;
        } catch (IOException e) {
            Warnings.warn("Failed to update ~/.ssh/config: " + e.getMessage());
            return false;
        }
    }

    /** Whether {@code content} starts, before any Host block, with the Include of isx's managed config. */
    static boolean includedIn(String content) {
        for (var line : content.lines().toList()) {
            var trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            return trimmed.equals(INCLUDE_LINE); // the first other line decides
        }
        return false;
    }

    /**
     * Whether ~/.ssh/config already includes isx's managed config, read only: so {@code ssh <name>}
     * reads an instance's Host block (#1720, the herdr integration checks it before naming one).
     */
    public static boolean hasSshConfigInclude() {
        try {
            var sshConfig = Environment.home().resolve(".ssh/config");
            return Files.exists(sshConfig) && includedIn(Files.readString(sshConfig.toRealPath()));
        } catch (IOException e) {
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
     * @param hostname optional address for the Hostname line, which Gateway resolves before it
     *     runs the ProxyCommand but never dials (design/instances.md "`Hostname` as a label")
     */
    public static boolean addHostEntry(String instanceName, String hostname) {
        var isxPath = resolveIsxPath(); // a subprocess: not under the lock
        try (var lock = lockManagedConfig()) {
            ensureManagedConfigExists();
            var content = Files.readString(Environment.sshConfigFile());
            afterConfigRead.run();
            var blocks = parseWithoutHostBlocks(content, instanceName);

            blocks.add("");
            blocks.add("Host " + instanceName);
            if (hostname != null && !hostname.isEmpty()) {
                blocks.add("    Hostname " + hostname);
            }
            blocks.add("    ProxyCommand " + proxyCommand(isxPath, instanceName, false));
            blocks.add("    User " + USER);
            blocks.add("    IdentityFile ~/.config/incus-spawn/ssh/id_ed25519");
            blocks.add("    IdentitiesOnly yes");
            blocks.add("    StrictHostKeyChecking no");
            blocks.add("    UserKnownHostsFile /dev/null");
            blocks.add("");

            writeIfChanged(content, String.join("\n", blocks));
            return true;
        } catch (IOException | HostLock.HostLockException e) {
            Warnings.warn("Failed to update SSH config: " + e.getMessage());
            return false;
        }
    }

    /**
     * Whether the managed SSH config has a Host block for the instance, with {@code hostname} as
     * its Hostname when one is given: a block for an address the instance no longer has is none.
     */
    public static boolean hasHostEntry(String instanceName, String hostname) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Environment.sshConfigFile());
        } catch (IOException e) {
            // None yet, or unreadable: addHostEntry reports the latter
            return false;
        }
        boolean in = false;
        for (var line : lines) {
            var trimmed = line.strip();
            if (trimmed.startsWith("Host ")) {
                in = isHostFor(trimmed, instanceName);
                if (in && (hostname == null || hostname.isEmpty())) return true;
            } else if (in && !trimmed.isEmpty() && !trimmed.startsWith("#") && !Character.isWhitespace(line.charAt(0))) {
                // An unindented line ends the block, as parseWithoutHostBlocks reads it
                in = false;
            } else if (in && trimmed.equals("Hostname " + hostname)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a Host block from the managed SSH config.
     */
    public static void removeHostEntry(String instanceName) {
        if (!Files.exists(Environment.sshConfigFile())) return;
        try (var lock = lockManagedConfig()) {
            var content = Files.readString(Environment.sshConfigFile());
            afterConfigRead.run();
            var blocks = parseWithoutHostBlocks(content, instanceName);
            writeIfChanged(content, String.join("\n", blocks));
        } catch (IOException | HostLock.HostLockException e) {
            Warnings.warn("Failed to update SSH config: " + e.getMessage());
        }
    }

    /**
     * Write the managed config only when it changes: destroying an instance without a Host block,
     * or re-adding the last block unchanged, leaves it alone. An earlier block moves to the end.
     */
    private static void writeIfChanged(String content, String updated) throws IOException {
        // Removing drops the trailing newline adding writes, which is no change to ssh.
        if (!updated.stripTrailing().equals(content.stripTrailing())) {
            writeAtomically(Environment.sshConfigFile(), updated);
        }
    }

    /**
     * Clean up SSH config for a destroyed instance.
     */
    public static void cleanupInstance(String instanceName) {
        removeHostEntry(instanceName);
    }

    /**
     * Held from reading the managed config to writing it back, so that two isx processes -- the
     * CLI starting one instance while the TUI clones another -- cannot each rewrite it from the
     * same old copy and silently drop the other's Host block.
     */
    private static HostLock lockManagedConfig() {
        return HostLock.acquireOrDegrade(Environment.lockDir().resolve("ssh-config.lock"),
                "updating the SSH config", Warnings::warn, Warnings::warn);
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
                skipping = isHostFor(trimmed, instanceName);
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

    /** @param hostLine a stripped line starting with {@code "Host "} */
    private static boolean isHostFor(String hostLine, String instanceName) {
        return hostLine.substring(5).strip().equals(instanceName);
    }

    /** Who an SSH connection to an instance logs in as. */
    public static final String USER = "agentuser";

    /**
     * The ProxyCommand that reaches the instance's sshd through the Incus exec API, for a program
     * handed the connection rather than the alias, such as an orchestrator ({@code isx branch
     * --format=json}, #1720). With {@code startIfStopped} it starts a stopped instance first, as
     * {@code isx shell} would; the Host block a plain {@code ssh} reads never does.
     */
    public static String proxyCommand(String instanceName, boolean startIfStopped) {
        return proxyCommand(resolveIsxPath(), instanceName, startIfStopped);
    }

    static String proxyCommand(String isxPath, String instanceName, boolean startIfStopped) {
        return "\"" + isxPath + "\" ssh-proxy " + (startIfStopped ? "--start " : "") + instanceName;
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
