package dev.incusspawn;

import java.nio.file.Path;

// WARNING: This class is configured with --initialize-at-run-time for native image.
// Do NOT reference its non-constant fields from static field initializers in other classes —
// that forces this class to initialize at build time (where user.home=/), silently baking
// wrong paths into the native binary. Access these fields from methods or constructors only.
public final class RuntimeConstants {
    private RuntimeConstants() {}

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    public static final Path CONFIG_DIR = HOME.resolve(".config/incus-spawn");
    public static final Path DOWNLOAD_CACHE_DIR = HOME.resolve(".cache/incus-spawn/downloads");
    public static final Path SKILLS_CACHE_DIR = HOME.resolve(".cache/incus-spawn/skills");
    public static final Path REGISTRY_CACHE_DIR = HOME.resolve(".cache/incus-spawn/registry");
    public static final Path MAVEN_CACHE_DIR = HOME.resolve(".cache/incus-spawn/maven");
    public static final Path DNF_CACHE_DIR = HOME.resolve(".cache/incus-spawn/dnf");
    public static final Path M2_REPOSITORY = HOME.resolve(".m2/repository");
    public static final Path SYSTEMD_USER_DIR = HOME.resolve(".config/systemd/user");
    public static final Path LOCAL_BIN_ISX = HOME.resolve(".local/bin/isx");
    public static final Path PROXY_LOG_FILE = HOME.resolve(".local/state/incus-spawn/proxy.log");
    public static final String PROXY_SERVICE_NAME = "incus-spawn-proxy";
    public static final Path PROXY_SERVICE_FILE = SYSTEMD_USER_DIR.resolve(PROXY_SERVICE_NAME + ".service");

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
