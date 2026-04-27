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

    public static Path dnfCacheDir() {
        return home().resolve(".cache/incus-spawn/dnf");
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

    public static final String INCUS_CLIENT;
    public static final String INCUS_SERVER;
    static {
        String client = "unknown", server = "unknown";
        try {
            var pb = new ProcessBuilder("incus", "version");
            pb.redirectErrorStream(true);
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor() == 0 && !output.isEmpty()) {
                for (var line : output.lines().toList()) {
                    if (line.startsWith("Client version:"))
                        client = line.substring("Client version:".length()).strip();
                    else if (line.startsWith("Server version:"))
                        server = line.substring("Server version:".length()).strip();
                }
            }
        } catch (Exception ignored) {}
        INCUS_CLIENT = client;
        INCUS_SERVER = server;
    }
}