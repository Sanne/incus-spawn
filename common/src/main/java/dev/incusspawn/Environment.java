package dev.incusspawn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// WARNING: This class is configured with --initialize-at-run-time for native image.
// Do NOT reference its non-constant fields from static field initializers in other classes —
// that forces this class to initialize at build time (where user.home=/), silently baking
// wrong paths into the native binary. Access these fields from methods or constructors only.
/**
 * Runtime environment paths and constants.
 * <p>
 * This class resolves user.home dynamically to support testing with temporary directories.
 */
public final class Environment {
    private Environment() {}

    /**
     * An environment variable, stripped. Credentials arrive from shells, CI files and
     * copy-paste with stray whitespace attached; a padded token concatenated into an
     * {@code Authorization} header is rejected as if it were the wrong credential.
     * Returns "" when unset, so callers need only an {@code isBlank()} check.
     */
    public static String strippedEnv(String name) {
        var value = System.getenv(name);
        return value == null ? "" : value.strip();
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    public static Path configDir() {
        return home().resolve(".config/incus-spawn");
    }

    public static Path initCompleteMarker() {
        return configDir().resolve(".init-complete");
    }

    // v4: install a scoped NOPASSWD sudoers rule (Linux) so the non-root TUI can read btrfs
    // referenced sizes for per-template disk accounting (see InitCommand.configureBtrfsUsageAccess).
    public static final int INIT_VERSION = 4;

    public static boolean hasBeenInitialized() {
        var marker = initCompleteMarker();
        if (!Files.exists(marker)) return false;
        try {
            return Integer.parseInt(Files.readString(marker).strip()) >= INIT_VERSION;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    public static void markInitComplete() {
        try {
            Files.writeString(initCompleteMarker(), String.valueOf(INIT_VERSION));
        } catch (IOException e) {
            System.err.println("Warning: could not write init marker: " + e.getMessage());
        }
    }

    public static Path sshDir() {
        return configDir().resolve("ssh");
    }

    public static Path sshKeyFile() {
        return sshDir().resolve("id_ed25519");
    }

    public static Path sshPubKeyFile() {
        return sshDir().resolve("id_ed25519.pub");
    }

    public static Path sshConfigFile() {
        return sshDir().resolve("config");
    }

    public static Path cacheDir() {
        return home().resolve(".cache/incus-spawn");
    }

    public static Path downloadCacheDir() {
        return home().resolve(".cache/incus-spawn/downloads");
    }

    public static Path skillsCacheDir() {
        return home().resolve(".cache/incus-spawn/skills");
    }

    public static Path registryCacheDir() {
        return home().resolve(".cache/incus-spawn/registry");
    }

    public static Path mavenCacheDir() {
        return home().resolve(".cache/incus-spawn/maven");
    }

    public static Path gradleCacheDir() {
        return home().resolve(".cache/incus-spawn/gradle");
    }

    public static Path npmCacheDir() {
        return home().resolve(".cache/incus-spawn/npm");
    }

    public static Path lockDir() {
        return home().resolve(".cache/incus-spawn/locks");
    }

    public static Path m2Repository() {
        return home().resolve(".m2/repository");
    }

    public static Path systemdUserDir() {
        return home().resolve(".config/systemd/user");
    }

    public static Path localBinIsx() {
        return home().resolve(".local/bin/isx");
    }

    public static Path proxyLogFile() {
        return home().resolve(".local/state/incus-spawn/proxy.log");
    }

    public static Path proxyLifecycleLogFile() {
        return home().resolve(".local/state/incus-spawn/proxy-lifecycle.log");
    }

    /**
     * Client-side diagnostic log. Written to a file only (never stdout/stderr) so it is safe
     * to emit from inside the TUI, which owns the terminal.
     */
    public static Path clientLogFile() {
        return home().resolve(".local/state/incus-spawn/client.log");
    }

    public static final String PROXY_SERVICE_NAME = "incus-spawn-proxy";

    public static Path proxyServiceFile() {
        return systemdUserDir().resolve(PROXY_SERVICE_NAME + ".service");
    }

    public static Path apiDebugDir() {
        return home().resolve(".local/state/incus-spawn/api-debug");
    }

    // --- VM state paths (under ~/.local/state/incus-spawn/) ---

    public static Path vmStateDir() {
        return home().resolve(".local/state/incus-spawn");
    }

    public static Path vmPidFile() {
        return vmStateDir().resolve("vm.pid");
    }

    public static Path vmLogFile() {
        return vmStateDir().resolve("vm.log");
    }

    public static Path vmRestUriFile() {
        return vmStateDir().resolve("vm.rest-uri");
    }

    public static Path vmVsockSocket() {
        return vmStateDir().resolve("vm.incus.sock");
    }

    /** Host-side Unix socket for the in-VM control agent (introspection/recovery). */
    public static Path vmAgentSocket() {
        return vmStateDir().resolve("vm.agent.sock");
    }

    public static Path vmDiskImage() {
        return vmStateDir().resolve("disk.img");
    }

    public static Path vmDataImage() {
        return vmStateDir().resolve("data.img");
    }

    public static Path vmDiskVersion() {
        return vmStateDir().resolve("disk.version");
    }

    public static Path vmSwapImage() {
        return vmStateDir().resolve("swap.img");
    }

    public static Path vmDummyInitrd() {
        return vmStateDir().resolve("empty-initrd");
    }

    public static Path vfkitAppBundle() {
        return vmStateDir().resolve("incus-spawn-vm.app");
    }

    // --- VM appliance artifact paths ---

    public static Path dataDir() {
        return home().resolve(".local/share/incus-spawn");
    }

    public static Path applianceDir() {
        var envDir = System.getenv("ISX_APPLIANCE_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Path.of(envDir);
        }
        return dataDir().resolve("appliance");
    }

    public static Path applianceKernel() {
        return applianceDir().resolve("vmlinuz");
    }

    public static Path applianceRootfs() {
        return applianceDir().resolve("rootfs.tar.zst");
    }

    public static Path applianceDiskImage() {
        return applianceDir().resolve("disk.img.gz");
    }

    // --- Incus client config paths ---

    /** Candidate paths for reading the Incus client config, in priority order. */
    public static List<Path> incusConfigCandidates() {
        return List.of(
                home().resolve(".config/incus/config.yml"),
                home().resolve(".local/share/incus/config.yml")
        );
    }

    private static volatile String incusServer;

    public static String incusClient() {
        return "REST API";
    }

    public static String incusServer() {
        var cached = incusServer;
        if (cached != null) return cached;
        cached = dev.incusspawn.incus.IncusClient.daemonVersion();
        incusServer = cached;
        return cached;
    }

    public static String kernelInfo() {
        return dev.incusspawn.incus.IncusClient.daemonKernelInfo();
    }
}