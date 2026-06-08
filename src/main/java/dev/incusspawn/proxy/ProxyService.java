package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.IncusClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;

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

    private static final int REQUIRED_JAVA_MAJOR = 25;

    public static boolean install() {
        if (Environment.isMacOS()) {
            return installMacOs();
        }

        var isxPath = resolveIsxPath();
        if (isxPath == null) {
            System.err.println("Could not find 'isx' in PATH.");
            return false;
        }

        var javaIssue = checkJvmWrapper(isxPath);
        if (javaIssue != null) {
            System.err.println(javaIssue);
            System.err.println("Reinstall with: install.sh --native   (builds a standalone binary that does not need Java)");
            return false;
        }

        var serviceContent = """
                [Unit]
                Description=incus-spawn MITM authentication proxy
                After=incus.service

                [Service]
                Type=simple
                %s
                Restart=on-failure
                RestartSec=5

                [Install]
                WantedBy=default.target
                """.formatted(execStartLine(isxPath));

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
            printServiceLogs();
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
            var expected = execStartLine(isxPath);
            if (!content.contains(expected)) {
                var updated = content.replaceFirst(
                        "ExecStart=.*proxy start.*",
                        Matcher.quoteReplacement(expected));
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
            var info = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(incus));
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
        var isxPath = resolveIsxPath();
        if (isxPath == null) return;
        try {
            var content = Files.readString(Environment.proxyServiceFile());
            if (!content.contains("ExecStart=")) return;
            var expected = execStartLine(isxPath);
            if (content.contains(expected)) return;
            var updated = content.replaceFirst("ExecStart=.*proxy.*", Matcher.quoteReplacement(expected));
            if (updated.equals(content)) return;
            Files.writeString(Environment.proxyServiceFile(), updated);
            System.out.println("Updated proxy service ExecStart.");
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

    /**
     * If isx is a JVM wrapper script, validate that the embedded Java binary exists
     * and is the required version. Returns a diagnostic message on failure, null if OK.
     */
    static String checkJvmWrapper(String isxPath) {
        try {
            var content = Files.readString(Path.of(isxPath));
            if (!content.startsWith("#!/bin/bash")) return null; // native binary
            var matcher = java.util.regex.Pattern.compile("exec\\s+\"?([^\"\\s]+)\"?\\s+.*-jar")
                    .matcher(content);
            if (!matcher.find()) return null; // not a java wrapper
            var javaBin = matcher.group(1);

            if (!Files.isExecutable(Path.of(javaBin))) {
                return "Java binary not found: " + javaBin + "\n"
                        + "The installed 'isx' is a JVM wrapper that requires Java " + REQUIRED_JAVA_MAJOR + "+.";
            }

            var pb = new ProcessBuilder(javaBin, "-version");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0) {
                return "Could not determine Java version for " + javaBin;
            }
            var vmatcher = java.util.regex.Pattern.compile("\"(\\d+)(?:\\.(\\d+))?")
                    .matcher(output.lines().findFirst().orElse(""));
            if (!vmatcher.find()) {
                return "Could not determine Java version for " + javaBin;
            }
            int major = Integer.parseInt(vmatcher.group(1));
            if (major == 1 && vmatcher.group(2) != null) {
                major = Integer.parseInt(vmatcher.group(2));
            }
            if (major < REQUIRED_JAVA_MAJOR) {
                return "Java " + REQUIRED_JAVA_MAJOR + "+ is required, but " + javaBin
                        + " is version " + major + ".";
            }
            return null;
        } catch (Exception e) {
            return null; // if we can't check, let it proceed and fail naturally
        }
    }

    private static void printServiceLogs() {
        try {
            var pb = new ProcessBuilder(
                    "journalctl", "--user", "-u", SERVICE_NAME, "--no-pager", "-n", "10");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (!output.isBlank()) {
                System.err.println("Recent logs:");
                System.err.println(output);
                if (output.contains("status=127")) {
                    System.err.println();
                    System.err.println("Exit code 127 usually means the Java binary was not found.");
                    System.err.println("If isx was installed as a JVM wrapper, ensure Java "
                            + REQUIRED_JAVA_MAJOR + "+ is available at the path embedded in the wrapper.");
                    System.err.println("Alternatively, reinstall with: install.sh --native");
                }
            } else {
                System.err.println("Check logs with: journalctl --user -u " + SERVICE_NAME);
            }
        } catch (Exception ignored) {
            System.err.println("Check logs with: journalctl --user -u " + SERVICE_NAME);
        }
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

    private static String execStartLine(String isxPath) {
        var quoted = isxPath.replace("'", "'\\''");
        return "ExecStart=/usr/bin/sg incus-admin -c \"exec '" + quoted + "' proxy start\"";
    }

    // --- macOS launchd support ---

    private static final String VM_LABEL = "dev.incusspawn.vm";
    private static final String PROXY_LABEL = "dev.incusspawn.proxy";

    private static Path launchAgentsDir() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
    }

    private static Path vmPlistFile() {
        return launchAgentsDir().resolve(VM_LABEL + ".plist");
    }

    private static Path proxyPlistFile() {
        return launchAgentsDir().resolve(PROXY_LABEL + ".plist");
    }

    public static boolean isMacOsServiceInstalled() {
        return Files.exists(proxyPlistFile());
    }

    public static boolean isMacOsServiceActive() {
        try {
            var pb = new ProcessBuilder("launchctl", "print", "gui/" + getUid() + "/" + PROXY_LABEL);
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean installMacOs() {
        var isxPath = resolveIsxPath();
        if (isxPath == null) {
            System.err.println("Could not find 'isx' in PATH.");
            return false;
        }

        var logDir = Environment.vmStateDir();
        try {
            Files.createDirectories(launchAgentsDir());

            var path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                path = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";
            }

            // VM agent — starts the VM on login
            var vmPlist = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Label</key><string>%s</string>
                        <key>ProgramArguments</key>
                        <array>
                            <string>%s</string>
                            <string>vm</string>
                            <string>start</string>
                        </array>
                        <key>RunAtLoad</key><true/>
                        <key>EnvironmentVariables</key>
                        <dict>
                            <key>PATH</key><string>%s</string>
                        </dict>
                        <key>StandardOutPath</key><string>%s/vm-service.log</string>
                        <key>StandardErrorPath</key><string>%s/vm-service.log</string>
                    </dict>
                    </plist>
                    """.formatted(VM_LABEL, isxPath, path, logDir, logDir);
            Files.writeString(vmPlistFile(), vmPlist);

            // Proxy agent — starts the proxy on login
            var proxyPlist = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Label</key><string>%s</string>
                        <key>ProgramArguments</key>
                        <array>
                            <string>%s</string>
                            <string>proxy</string>
                            <string>start</string>
                        </array>
                        <key>RunAtLoad</key><true/>
                        <key>KeepAlive</key><true/>
                        <key>ThrottleInterval</key><integer>10</integer>
                        <key>EnvironmentVariables</key>
                        <dict>
                            <key>PATH</key><string>%s</string>
                        </dict>
                        <key>StandardOutPath</key><string>%s/proxy-service.log</string>
                        <key>StandardErrorPath</key><string>%s/proxy-service.log</string>
                    </dict>
                    </plist>
                    """.formatted(PROXY_LABEL, isxPath, path, logDir, logDir);
            Files.writeString(proxyPlistFile(), proxyPlist);
        } catch (IOException e) {
            System.err.println("Failed to write launchd plist: " + e.getMessage());
            return false;
        }

        System.out.println("  Installing VM service...");
        runQuiet("launchctl", "bootout", "gui/" + getUid(), vmPlistFile().toString());
        runQuiet("launchctl", "bootstrap", "gui/" + getUid(), vmPlistFile().toString());

        System.out.println("  Installing proxy service...");
        runQuiet("launchctl", "bootout", "gui/" + getUid(), proxyPlistFile().toString());
        runQuiet("launchctl", "bootstrap", "gui/" + getUid(), proxyPlistFile().toString());

        if (isMacOsServiceActive()) {
            System.out.println("  Services installed and running.");
            return true;
        } else {
            System.out.println("  Services installed (proxy will start when VM is ready).");
            return true;
        }
    }

    public static void uninstallMacOs() {
        runQuiet("launchctl", "bootout", "gui/" + getUid(), proxyPlistFile().toString());
        runQuiet("launchctl", "bootout", "gui/" + getUid(), vmPlistFile().toString());
        try { Files.deleteIfExists(proxyPlistFile()); } catch (IOException ignored) {}
        try { Files.deleteIfExists(vmPlistFile()); } catch (IOException ignored) {}
        System.out.println("  macOS services uninstalled.");
    }

    private static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            pb.redirectErrorStream(true);
            var process = pb.start();
            var uid = new String(process.getInputStream().readAllBytes()).strip();
            if (uid.isEmpty() || !uid.chars().allMatch(Character::isDigit)) {
                throw new RuntimeException("unexpected id -u output: " + uid);
            }
            return uid;
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine current UID — launchd service install/uninstall requires a valid UID", e);
        }
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
