package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProxyService {

    private static final String SERVICE_NAME = Environment.PROXY_SERVICE_NAME;

    private ProxyService() {}

    public static boolean isInstalled() {
        return Files.exists(Environment.proxyServiceFile());
    }

    public static boolean isActive() {
        try {
            var pb = new ProcessBuilder("systemctl", "--user", "is-active", SERVICE_NAME);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 && "active".equals(output);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean install() {
        var isxPath = resolveIsxPath();
        if (isxPath == null) {
            System.err.println("Could not find 'isx' in PATH.");
            return false;
        }

        var serviceContent = """
                [Unit]
                Description=incus-spawn MITM authentication proxy
                After=incus.service

                [Service]
                Type=simple
                ExecStart=%s proxy start
                Restart=on-failure
                RestartSec=5

                [Install]
                WantedBy=default.target
                """.formatted(isxPath);

        try {
            Files.createDirectories(Environment.proxyServiceFile().getParent());
            Files.writeString(Environment.proxyServiceFile(), serviceContent);
        } catch (IOException e) {
            System.err.println("Failed to write service file: " + e.getMessage());
            return false;
        }

        System.out.println("Service file written to " + Environment.proxyServiceFile());
        System.out.println("Enabling and starting proxy service...");
        runQuiet("systemctl", "--user", "daemon-reload");
        runQuiet("systemctl", "--user", "enable", "--now", SERVICE_NAME);

        System.out.println("Enabling lingering for user (sudo required)...");
        runQuiet("sudo", "loginctl", "enable-linger", System.getProperty("user.name"));

        if (isActive()) {
            System.out.println("Proxy service is running.");
            return true;
        } else {
            System.err.println("Warning: service did not start.");
            System.err.println("Check logs with: journalctl --user -u " + SERVICE_NAME);
            return false;
        }
    }

    public static boolean uninstall() {
        if (!isInstalled()) {
            System.err.println("Proxy service is not installed.");
            return false;
        }

        System.out.println("Stopping and disabling proxy service...");
        runQuiet("systemctl", "--user", "stop", SERVICE_NAME);
        runQuiet("systemctl", "--user", "disable", SERVICE_NAME);

        try {
            Files.deleteIfExists(Environment.proxyServiceFile());
        } catch (IOException e) {
            System.err.println("Failed to remove service file: " + e.getMessage());
            return false;
        }

        runQuiet("systemctl", "--user", "daemon-reload");
        System.out.println("Proxy service uninstalled.");
        return true;
    }

    public static boolean restart() {
        System.err.println("Restarting proxy service...");
        runQuiet("systemctl", "--user", "restart", SERVICE_NAME);
        if (isActive()) {
            System.err.println("Proxy service restarted.");
            return true;
        }
        System.err.println("Warning: proxy service did not restart.");
        return false;
    }

    public static void stop() {
        if (isActive()) {
            System.out.println("Stopping proxy service...");
            runQuiet("systemctl", "--user", "stop", SERVICE_NAME);
            System.out.println("Proxy service stopped.");
            return;
        }

        var pid = findProxyPid();
        if (pid != -1) {
            System.out.println("Stopping proxy (PID " + pid + ")...");
            runQuiet("kill", String.valueOf(pid));
            System.out.println("Proxy stopped.");
            return;
        }

        System.out.println("Proxy is not running.");
    }

    /**
     * Check whether the installed service needs updating (binary path or version)
     * and restart if so. Returns true if a restart was performed.
     */
    public static boolean reinstallIfChanged(IncusClient incus) {
        if (!Files.exists(Environment.proxyServiceFile())) return false;
        var isxPath = resolveIsxPath();
        if (isxPath == null) return false;

        boolean needsRestart = false;
        try {
            var content = Files.readString(Environment.proxyServiceFile());
            var expected = "ExecStart=" + isxPath + " proxy start";
            if (!content.contains(expected)) {
                var updated = content.replaceFirst(
                        "ExecStart=\\S+ proxy start",
                        "ExecStart=" + isxPath + " proxy start");
                if (!updated.equals(content)) {
                    Files.writeString(Environment.proxyServiceFile(), updated);
                    runQuiet("systemctl", "--user", "daemon-reload");
                    needsRestart = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not update proxy service file: " + e.getMessage());
        }

        if (!needsRestart) {
            var gatewayIp = MitmProxy.resolveGatewayIp(incus);
            var info = ProxyHealthCheck.fetchProxyInfo(gatewayIp);
            var drift = ProxyHealthCheck.checkVersionDrift(info);
            needsRestart = !drift.isEmpty();
        }

        if (needsRestart) {
            restart();
            return true;
        }
        return false;
    }

    public static void upgradeIfNeeded() {
        if (!Files.exists(Environment.proxyServiceFile())) return;
        try {
            var content = Files.readString(Environment.proxyServiceFile());
            if (content.contains("proxy start")) return;
            if (!content.contains("ExecStart=")) return;
            var updated = content.replaceFirst(
                    "ExecStart=(\\S+)\\s+proxy\\b(?!\\s+start)",
                    "ExecStart=$1 proxy start");
            if (updated.equals(content)) return;
            Files.writeString(Environment.proxyServiceFile(), updated);
            System.out.println("Updated proxy service to use 'isx proxy start'.");
            runQuiet("systemctl", "--user", "daemon-reload");
            runQuiet("systemctl", "--user", "restart", SERVICE_NAME);
        } catch (IOException e) {
            System.err.println("Warning: could not check proxy service file: " + e.getMessage());
        }
    }

    private static long findProxyPid() {
        try {
            var pb = new ProcessBuilder("fuser", MitmProxy.DEFAULT_HEALTH_PORT + "/tcp");
            pb.redirectErrorStream(false);
            var process = pb.start();
            // fuser sends port label to stderr, PIDs to stdout
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            process.getErrorStream().readAllBytes();
            if (process.waitFor() == 0 && !stdout.isBlank()) {
                return Long.parseLong(stdout.split("\\s+")[0]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static String resolveIsxPath() {
        try {
            var pb = new ProcessBuilder("which", "isx");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return output;
            }
        } catch (Exception ignored) {}
        var fallback = Environment.localBinIsx();
        if (java.nio.file.Files.isExecutable(fallback)) {
            return fallback.toString();
        }
        return null;
    }

    private static void runQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception ignored) {}
    }
}
