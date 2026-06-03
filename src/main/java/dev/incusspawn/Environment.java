package dev.incusspawn;

import java.nio.file.Path;

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

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    public static Path configDir() {
        return home().resolve(".config/incus-spawn");
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

    public static Path dnfCacheDir() {
        return home().resolve(".cache/incus-spawn/dnf");
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

    public static final String PROXY_SERVICE_NAME = "incus-spawn-proxy";

    public static Path proxyServiceFile() {
        return systemdUserDir().resolve(PROXY_SERVICE_NAME + ".service");
    }

    public static Path apiDebugDir() {
        return home().resolve(".local/state/incus-spawn/api-debug");
    }

    public static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    public static final String INCUS_CLIENT;
    public static final String INCUS_SERVER;
    static {
        // Probe the Incus daemon via REST API to get the server version.
        // INCUS_CLIENT is not meaningful without the CLI binary; we report the API version instead.
        String client = "REST API";
        String server = dev.incusspawn.incus.IncusClient.daemonVersion();
        INCUS_CLIENT = client;
        INCUS_SERVER = server;
    }
}