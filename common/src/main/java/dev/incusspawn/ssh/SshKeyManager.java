package dev.incusspawn.ssh;

import dev.incusspawn.Environment;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.Warnings;
import dev.incusspawn.incus.CidrUtils;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.HostPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
            OwnDirectory.create(Environment.sshDir());

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
            OwnDirectory.create(sshDir);
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
        OwnDirectory.create(Environment.sshDir());
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
        return writeHostBlock(instanceName, instanceName, hostname, false);
    }

    /**
     * Set to {@code 1} in the environment of every command a host integration runs, and so of the
     * ssh it may run: {@code isx ssh-proxy --start} then starts nothing. Such a command runs from a
     * start that is preparing the instance, and would otherwise wait for that start or prepare it
     * again, as herdr's {@code machine add} through {@link #startAlias} would (#2038).
     */
    public static final String NO_START_ENV = "ISX_SSH_PROXY_NO_START";

    /** What ends the Host alias of {@link #startAlias}; no instance name holds a dot. */
    static final String START_SUFFIX = ".start.isx";

    /**
     * The SSH target that starts the instance when it is stopped, for a program that keeps a target
     * and reconnects through it, such as herdr (#2038). {@code ssh <name>} never starts one (#1720).
     */
    public static String startAlias(String instanceName) {
        return instanceName + START_SUFFIX;
    }

    /**
     * Add or replace the Host block of {@link #startAlias}: the instance's own block, but with
     * {@code isx ssh-proxy --start} and no {@code Hostname}, which only Gateway reads. Its host
     * key is the instance's, under the same {@code HostKeyAlias}. Removed with the instance's block.
     * @return true if the block is written, or already was
     */
    public static boolean addStartEntry(String instanceName) {
        return writeHostBlock(startAlias(instanceName), instanceName, null, true);
    }

    /** Whether the managed SSH config has the current Host block of {@link #startAlias}. */
    public static boolean hasStartEntry(String instanceName) {
        try {
            var block = blockOf(Files.readAllLines(Environment.sshConfigFile()), startAlias(instanceName));
            return block != null && block.contains(knownHostsOption())
                    && block.stream().anyMatch(line -> line.startsWith("ProxyCommand ")
                            && line.endsWith(" ssh-proxy --start " + instanceName));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean writeHostBlock(String host, String instanceName, String hostname, boolean startIfStopped) {
        var isxPath = resolveIsxPath(); // a subprocess: not under the lock
        try (var lock = lockManagedConfig()) {
            ensureManagedConfigExists();
            var content = Files.readString(Environment.sshConfigFile());
            afterConfigRead.run();
            var blocks = parseWithoutHostBlocks(content, host);

            blocks.add("");
            blocks.add("Host " + host);
            if (hostname != null && !hostname.isEmpty()) {
                blocks.add("    Hostname " + hostname);
            }
            blocks.add("    ProxyCommand " + proxyCommand(isxPath, instanceName, startIfStopped));
            blocks.add("    User " + USER);
            blocks.add("    IdentityFile ~/.config/incus-spawn/ssh/id_ed25519");
            blocks.add("    IdentitiesOnly yes");
            // The key is looked up by the instance's name, which routing goes by, never only by
            // an address isx recycles (#1863); its pin also names the address, for a client that
            // ignores the alias. Unhashed, so isx can find and replace an instance's lines.
            blocks.add("    HostKeyAlias " + instanceName);
            blocks.add("    HashKnownHosts no");
            blocks.add("    StrictHostKeyChecking no");
            blocks.add("    " + knownHostsOption());
            blocks.add("");

            writeIfChanged(content, String.join("\n", blocks));
            return true;
        } catch (IOException | HostLock.HostLockException e) {
            Warnings.warn("Failed to update SSH config: " + e.getMessage());
            return false;
        }
    }

    /**
     * The block's {@code UserKnownHostsFile}: absolute, since a {@code ~} ssh expands to another
     * home than the one isx wrote would hide every pin, quoted, and with ssh's {@code %} escaped.
     */
    static String knownHostsOption() {
        var path = Environment.sshKnownHostsFile().toAbsolutePath().toString().replace("%", "%%");
        return "UserKnownHostsFile \"" + path + "\"";
    }

    /**
     * Whether the managed SSH config has a current Host block for the instance, with
     * {@code hostname} as its Hostname when one is given: a block for an address the instance no
     * longer has is none, and so is one written before isx pinned host keys (#1863).
     */
    public static boolean hasHostEntry(String instanceName, String hostname) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Environment.sshConfigFile());
        } catch (IOException e) {
            // None yet, or unreadable: addHostEntry reports the latter
            return false;
        }
        var block = blockOf(lines, instanceName);
        return block != null && block.contains(knownHostsOption())
                && (hostname == null || hostname.isEmpty() || block.contains("Hostname " + hostname));
    }

    /** The stripped lines of the instance's first Host block, or null when there is none. */
    private static List<String> blockOf(List<String> lines, String instanceName) {
        List<String> block = null;
        for (var line : lines) {
            var trimmed = line.strip();
            if (trimmed.startsWith("Host ")) {
                if (block != null) return block;
                if (isHostFor(trimmed, instanceName)) block = new ArrayList<>();
            } else if (block != null && !trimmed.isEmpty() && !trimmed.startsWith("#")
                    && !Character.isWhitespace(line.charAt(0))) {
                // An unindented line ends the block, as parseWithoutHostBlocks reads it
                return block;
            } else if (block != null) {
                block.add(trimmed);
            }
        }
        return block;
    }

    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\s+");
    private static final Pattern HOST_SEPARATOR = Pattern.compile(",");

    /**
     * Pin the instance's host keys in the managed {@code known_hosts} (#1863), under its name and
     * its address: the name is what its Host block's {@code HostKeyAlias} looks up, the address
     * what a client ignoring the alias dials. Replaces the instance's earlier lines where they
     * stood, and takes the address off another instance's line, whose earlier tenant it was. A
     * pin already current costs a file read and no lock.
     *
     * @param address the instance's static address, or "" for none
     * @return whether the keys are pinned; false for no keys, or a write that failed (reported)
     */
    public static boolean pinHostKeys(String instanceName, String address, List<HostKeys.HostKey> keys) {
        if (keys.isEmpty()) return false;
        var ip = normalizedAddress(address);
        var file = Environment.sshKnownHostsFile();
        try {
            var content = Files.exists(file) ? Files.readString(file) : "";
            if (pinned(content, instanceName, ip, keys).equals(content)) return true;
        } catch (IOException e) {
            // Taken again under the lock, which reports it
        }
        try (var lock = lockManagedConfig()) {
            OwnDirectory.create(Environment.sshDir());
            var content = Files.exists(file) ? Files.readString(file) : "";
            afterConfigRead.run();
            writeIfChanged(file, content, pinned(content, instanceName, ip, keys));
            return true;
        } catch (IOException | HostLock.HostLockException e) {
            Warnings.warn("Failed to pin the SSH host key of " + instanceName + ": " + e.getMessage());
            return false;
        }
    }

    /** Whether the managed {@code known_hosts} holds a key for the instance's name: what herdr checks. */
    public static boolean hasHostKeys(String instanceName) {
        try {
            for (var line : Files.readAllLines(Environment.sshKnownHostsFile())) {
                var hosts = hostsOf(line);
                if (hosts != null && hosts.contains(instanceName)) return true;
            }
        } catch (IOException e) {
            // None yet, or unreadable: no pin
        }
        return false;
    }

    /** {@code content} with the instance's lines replaced by {@code keys}, at the first one's place. */
    static String pinned(String content, String instanceName, String ip, List<HostKeys.HostKey> keys) {
        var result = new ArrayList<String>();
        int at = -1;
        for (var line : content.lines().toList()) {
            var hosts = hostsOf(line);
            if (hosts == null) {
                result.add(line);
            } else if (hosts.contains(instanceName)) {
                if (at < 0) at = result.size();
            } else if (ip != null && hosts.remove(ip)) {
                // The address was another instance's before isx gave it to this one
                if (!hosts.isEmpty()) result.add(String.join(",", hosts) + " " + FIELD_SEPARATOR.split(line.strip(), 2)[1]);
            } else {
                result.add(line);
            }
        }
        var lines = keys.stream().map(key -> (ip == null ? instanceName : instanceName + "," + ip)
                + " " + key.type() + " " + key.blob() + " isx:" + instanceName).toList();
        result.addAll(at < 0 ? result.size() : at, lines);
        return String.join("\n", result) + "\n";
    }

    /** {@code content} without the instance's lines. */
    static String unpinned(String content, String instanceName) {
        var result = new ArrayList<String>();
        for (var line : content.lines().toList()) {
            var hosts = hostsOf(line);
            if (hosts == null || !hosts.contains(instanceName)) result.add(line);
        }
        return result.isEmpty() ? "" : String.join("\n", result) + "\n";
    }

    /**
     * The hosts a {@code known_hosts} line names, or null for a line that names none isx manages:
     * blank, a comment, a marker ({@code @revoked}) or a hashed host, all left as they are.
     */
    private static List<String> hostsOf(String line) {
        var trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("@") || trimmed.startsWith("|")) return null;
        var fields = FIELD_SEPARATOR.split(trimmed, 2);
        if (fields.length < 2) return null;
        return new ArrayList<>(List.of(HOST_SEPARATOR.split(fields[0])));
    }

    /** The address in dotted form, or null for none or one that is not an IPv4 address. */
    private static String normalizedAddress(String address) {
        if (address == null || address.isBlank()) return null;
        try {
            return CidrUtils.longToIp(CidrUtils.ipToLong(address.strip()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Remove the instance's Host block, and the one of its {@link #startAlias}, from the managed SSH config.
     */
    public static void removeHostEntry(String instanceName) {
        var config = Environment.sshConfigFile();
        var knownHosts = Environment.sshKnownHostsFile();
        if (!Files.exists(config) && !Files.exists(knownHosts)) return;
        try (var lock = lockManagedConfig()) {
            if (Files.exists(config)) {
                var content = Files.readString(config);
                afterConfigRead.run();
                var blocks = parseWithoutHostBlocks(content, instanceName);
                blocks = parseWithoutHostBlocks(String.join("\n", blocks), startAlias(instanceName));
                writeIfChanged(content, String.join("\n", blocks));
            }
            // Its pinned keys go with it: a later instance of that name has its own (#1863)
            if (Files.exists(knownHosts)) {
                var content = Files.readString(knownHosts);
                writeIfChanged(knownHosts, content, unpinned(content, instanceName));
            }
        } catch (IOException | HostLock.HostLockException e) {
            Warnings.warn("Failed to update SSH config: " + e.getMessage());
        }
    }

    /**
     * Write the managed config only when it changes: destroying an instance without a Host block,
     * or re-adding the last block unchanged, leaves it alone. An earlier block moves to the end.
     */
    private static void writeIfChanged(String content, String updated) throws IOException {
        writeIfChanged(Environment.sshConfigFile(), content, updated);
    }

    private static void writeIfChanged(Path file, String content, String updated) throws IOException {
        // Removing drops the trailing newline adding writes, which is no change to ssh.
        if (!updated.stripTrailing().equals(content.stripTrailing())) {
            writeAtomically(file, updated);
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
        var path = HostPath.find("isx");
        return path != null ? path : Environment.localBinIsx().toString();
    }

    private static boolean isSshKeygenAvailable() {
        return HostPath.isOnPath("ssh-keygen");
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
