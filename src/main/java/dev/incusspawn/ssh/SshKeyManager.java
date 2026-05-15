package dev.incusspawn.ssh;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;

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
    private static final String KNOWN_HOST_TAG = "# incus-spawn";

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
     * @return true if the entry was written successfully
     */
    public static boolean addHostEntry(String instanceName, String ip) {
        try {
            ensureManagedConfigExists();
            var content = Files.readString(Environment.sshConfigFile());
            var blocks = parseWithoutHostBlocks(content, instanceName);

            blocks.add("");
            blocks.add("Host " + instanceName);
            blocks.add("    HostName " + ip);
            blocks.add("    User agentuser");
            blocks.add("    IdentityFile ~/.config/incus-spawn/ssh/id_ed25519");
            blocks.add("    IdentitiesOnly yes");
            blocks.add("    UserKnownHostsFile ~/.config/incus-spawn/ssh/known_hosts");
            blocks.add("    StrictHostKeyChecking yes");
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
     * Look up the HostName (IP) for a given instance from the managed SSH config.
     */
    public static String findIpForInstance(String instanceName) {
        if (!Files.exists(Environment.sshConfigFile())) return null;
        try {
            var lines = Files.readAllLines(Environment.sshConfigFile());
            boolean inBlock = false;
            for (var line : lines) {
                var trimmed = line.strip();
                if (trimmed.startsWith("Host ")) {
                    inBlock = trimmed.substring(5).strip().equals(instanceName);
                } else if (inBlock && trimmed.startsWith("HostName ")) {
                    return trimmed.substring(9).strip();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Add or replace a known_hosts entry for the given instance.
     * Stores entries as {@code instanceName,ip keytype key} so OpenSSH matches
     * both the alias used on the command line and the resolved IP.
     *
     * @return true if the entry was written successfully
     */
    public static boolean addKnownHost(String instanceName, String ip, String hostKeyLine) {
        try {
            Files.createDirectories(Environment.sshDir());
            var knownHosts = Environment.sshKnownHostsFile();

            List<String> lines = new ArrayList<>();
            if (Files.exists(knownHosts)) {
                for (var line : Files.readAllLines(knownHosts)) {
                    if (!knownHostLineMatchesHost(line, ip)
                            && !knownHostLineMatchesHost(line, instanceName)) {
                        lines.add(line);
                    }
                }
            }
            lines.add(instanceName + "," + ip + " " + hostKeyLine + " " + KNOWN_HOST_TAG);

            writeAtomically(knownHosts, String.join("\n", lines) + "\n");
            addToStandardKnownHosts(instanceName, ip, hostKeyLine);
            return true;
        } catch (IOException e) {
            System.err.println("  Warning: failed to update known_hosts: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove known_hosts entries for the given IP.
     * The managed file is cleaned unconditionally; the standard ~/.ssh/known_hosts
     * only has entries removed that are in our {@code instanceName,ip} format.
     */
    public static void removeKnownHost(String ip) {
        removeFromKnownHostsFile(Environment.sshKnownHostsFile(), ip, false);
        try {
            var stdKnownHosts = Environment.home().resolve(".ssh/known_hosts");
            if (Files.exists(stdKnownHosts)) {
                removeFromKnownHostsFile(stdKnownHosts.toRealPath(), ip, true);
            }
        } catch (IOException ignored) {}
    }

    /**
     * Harvest the container's SSH host key and register it in known_hosts and SSH config.
     *
     * @return true if the host entry was successfully written, false otherwise
     */
    public static boolean harvestHostKey(IncusClient incus, String instanceName) {
        var ipResult = incus.shellExec(instanceName, "hostname", "-I");
        if (!ipResult.success()) return false;
        var ip = ipResult.stdout().strip().split("\\s+")[0];
        if (ip.isEmpty()) return false;

        // Regenerate host keys so CoW-branched instances get unique keys
        var regenResult = incus.shellExec(instanceName, "sh", "-c",
                "rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A");
        if (!regenResult.success()) return false;
        var restartResult = incus.shellExec(instanceName, "systemctl", "restart", "sshd");
        if (!restartResult.success()) return false;

        String hostKeyLine = null;
        for (var keyFile : List.of(
                "/etc/ssh/ssh_host_ed25519_key.pub",
                "/etc/ssh/ssh_host_ecdsa_key.pub",
                "/etc/ssh/ssh_host_rsa_key.pub")) {
            var result = incus.shellExec(instanceName, "cat", keyFile);
            if (result.success() && !result.stdout().isBlank()) {
                var parts = result.stdout().strip().split("\\s+");
                if (parts.length >= 2) {
                    hostKeyLine = parts[0] + " " + parts[1];
                    break;
                }
            }
        }

        if (hostKeyLine == null) return false;

        return addKnownHost(instanceName, ip, hostKeyLine) && addHostEntry(instanceName, ip);
    }

    /**
     * Clean up SSH config and known_hosts entries for a destroyed instance.
     */
    public static void cleanupInstance(String instanceName) {
        var ip = findIpForInstance(instanceName);
        removeHostEntry(instanceName);
        if (ip != null) {
            removeKnownHost(ip);
        }
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

    /**
     * Write the host key to ~/.ssh/known_hosts for IntelliJ compatibility
     * (its SSH client ignores UserKnownHostsFile).
     *
     * Safety: only writes if there is no pre-existing entry for this IP,
     * or the existing entry is one of ours (identifiable by the
     * {@code instanceName,ip} hostname format). Never overwrites entries
     * belonging to real hosts.
     */
    private static void addToStandardKnownHosts(String instanceName, String ip, String hostKeyLine) {
        try {
            var stdKnownHosts = Environment.home().resolve(".ssh/known_hosts");
            Files.createDirectories(stdKnownHosts.getParent());

            List<String> lines = new ArrayList<>();
            boolean foreignEntryExists = false;
            if (Files.exists(stdKnownHosts)) {
                for (var line : Files.readAllLines(stdKnownHosts)) {
                    if (knownHostLineMatchesHost(line, ip)
                            || knownHostLineMatchesHost(line, instanceName)) {
                        if (isOurKnownHostLine(line)) {
                            continue; // drop our old entry — will be re-added below
                        }
                        foreignEntryExists = true;
                    }
                    lines.add(line);
                }
            }

            if (foreignEntryExists) return;

            lines.add(instanceName + "," + ip + " " + hostKeyLine + " " + KNOWN_HOST_TAG);
            writeAtomically(stdKnownHosts, String.join("\n", lines) + "\n");
        } catch (IOException ignored) {}
    }

    private static void removeFromKnownHostsFile(Path knownHosts, String ip, boolean onlyOurs) {
        if (!Files.exists(knownHosts)) return;
        try {
            var lines = Files.readAllLines(knownHosts);
            var filtered = lines.stream()
                    .filter(line -> !knownHostLineMatchesHost(line, ip)
                            || (onlyOurs && !isOurKnownHostLine(line)))
                    .toList();
            if (filtered.size() != lines.size()) {
                writeAtomically(knownHosts, String.join("\n", filtered) + "\n");
            }
        } catch (IOException e) {
            System.err.println("  Warning: failed to update known_hosts: " + e.getMessage());
        }
    }

    static boolean isOurKnownHostLine(String line) {
        return line.endsWith(KNOWN_HOST_TAG);
    }

    private static boolean knownHostLineMatchesHost(String line, String host) {
        int space = line.indexOf(' ');
        if (space < 0) return false;
        for (String h : line.substring(0, space).split(",")) {
            if (h.equals(host)) return true;
        }
        return false;
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
