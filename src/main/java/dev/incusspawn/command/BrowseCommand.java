package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.SpawnConfig;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@CommandDefinition(
        name = "browse",
        description = "Browse container files on the host via WebDAV mount",
        generateHelp = true
)
public class BrowseCommand extends BaseCommand {

    @Argument(description = "Name of the instance to browse", required = true)
    String name;

    @Option(name = "stop", hasValue = false, description = "Unmount and stop browsing")
    boolean stop;

    @Option(name = "port", description = "WebDAV port (default: 8888)",
            defaultValue = {"8888"})
    int port;

    private static final int HOST_OPEN_PORT = 9999;
    private static final String AGENTUSER_HOME = "/home/agentuser";

    private Process sshTunnel;
    private ServerSocket hostOpenServer;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = RuntimeServices.incus();

        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            return CommandResult.valueOf(1);
        }

        var mountPath = mountPath();

        if (stop) {
            return doStop(mountPath);
        }

        if (!"Running".equalsIgnoreCase(incus.getInstanceStatus(name))) {
            System.err.println("Error: instance '" + name + "' is not running.");
            return CommandResult.valueOf(1);
        }

        var wsgidavCheck = incus.execInContainer(name, "agentuser", "test", "-x",
                AGENTUSER_HOME + "/.local/bin/wsgidav");
        if (!wsgidavCheck.success()) {
            System.err.println("Error: wsgidav is not installed in '" + name + "'.");
            System.err.println("Add the 'webdav' tool to your image definition, or install manually:");
            System.err.println("  ssh " + name + " \"python3 -m pip install --user wsgidav cheroot\"");
            return CommandResult.valueOf(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanup(mountPath)));

        System.out.println("Starting WebDAV server on " + name + ":" + port + "...");
        incus.execInContainer(name, "agentuser", "pkill", "-f", "wsgidav");
        incus.execInContainer(name, "agentuser",
                "sh", "-c", "nohup " + AGENTUSER_HOME + "/.local/bin/wsgidav"
                        + " --host 0.0.0.0 --port " + port
                        + " --root " + AGENTUSER_HOME
                        + " --auth anonymous"
                        + " > /tmp/wsgidav.log 2>&1 &");

        Thread.sleep(2000);

        System.out.println("Setting up SSH tunnel...");
        sshTunnel = new ProcessBuilder(
                "ssh", "-f", "-N",
                "-L", port + ":localhost:" + port,
                "-R", HOST_OPEN_PORT + ":localhost:" + HOST_OPEN_PORT,
                name
        ).start();
        sshTunnel.waitFor();

        Thread.sleep(1000);

        Files.createDirectories(mountPath);

        if (Environment.isMacOS()) {
            System.out.println("Mounting at " + mountPath + "...");
            var mount = new ProcessBuilder(
                    "mount_webdav", "-s", "-v", name,
                    "http://localhost:" + port + "/",
                    mountPath.toString()
            ).inheritIO().start();
            int exitCode = mount.waitFor();
            if (exitCode != 0) {
                System.err.println("Error: mount_webdav failed (exit " + exitCode + ").");
                return CommandResult.valueOf(1);
            }
        } else {
            System.out.println("WebDAV available at http://localhost:" + port + "/");
            System.out.println("Mount with: sudo mount -t davfs http://localhost:" + port + "/ " + mountPath);
        }

        var config = SpawnConfig.load();
        var listenerThread = new Thread(() -> runHostOpenListener(config.getHostApps(), mountPath),
                "host-open-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        System.out.println();
        System.out.println("Browsing " + name + " at " + mountPath);
        System.out.println("  host-open available inside container");
        System.out.println("  Press Ctrl+C to unmount and stop.");

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return CommandResult.SUCCESS;
    }

    private CommandResult doStop(Path mountPath) {
        cleanup(mountPath);
        System.out.println("Stopped.");
        return CommandResult.SUCCESS;
    }

    private void cleanup(Path mountPath) {
        try {
            if (Environment.isMacOS()) {
                new ProcessBuilder("umount", mountPath.toString())
                        .inheritIO().start().waitFor();
            }
        } catch (Exception ignored) {}

        try {
            new ProcessBuilder("pkill", "-f",
                    "ssh.*-L " + port + ":localhost:" + port + ".*" + name)
                    .start().waitFor();
        } catch (Exception ignored) {}

        if (hostOpenServer != null) {
            try {
                hostOpenServer.close();
            } catch (Exception ignored) {}
        }

        try {
            var incus = RuntimeServices.incus();
            if (incus.exists(name)) {
                incus.execInContainer(name, "agentuser", "pkill", "-f", "wsgidav");
            }
        } catch (Exception ignored) {}
    }

    private void runHostOpenListener(Map<String, String> hostApps, Path mountPath) {
        try {
            hostOpenServer = new ServerSocket(HOST_OPEN_PORT);
            while (!Thread.currentThread().isInterrupted()) {
                try (var socket = hostOpenServer.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    var line = reader.readLine();
                    if (line == null || line.isBlank()) continue;
                    line = line.strip();

                    String app = null;
                    String filepath;
                    if (line.contains("|")) {
                        app = line.substring(0, line.indexOf('|'));
                        filepath = line.substring(line.indexOf('|') + 1);
                    } else {
                        filepath = line;
                    }

                    // Strip container home prefix
                    if (filepath.startsWith(AGENTUSER_HOME + "/")) {
                        filepath = filepath.substring(AGENTUSER_HOME.length() + 1);
                    } else if (filepath.startsWith("~/")) {
                        filepath = filepath.substring(2);
                    }

                    var localPath = mountPath.resolve(filepath);
                    if (!Files.exists(localPath)) {
                        System.err.println("  host-open: not found: " + localPath);
                        continue;
                    }

                    // Resolve app: explicit > config by extension > system default
                    if (app == null || app.isBlank()) {
                        var ext = extensionOf(filepath);
                        app = hostApps.getOrDefault(ext, null);
                    } else {
                        app = hostApps.getOrDefault(app, app);
                    }

                    if (app != null && !app.isBlank()) {
                        System.out.println("  Opening in " + app + ": " + localPath);
                        if (Environment.isMacOS()) {
                            new ProcessBuilder("open", "-a", app, localPath.toString()).start();
                        } else {
                            new ProcessBuilder(app, localPath.toString()).start();
                        }
                    } else {
                        System.out.println("  Opening: " + localPath);
                        if (Environment.isMacOS()) {
                            new ProcessBuilder("open", localPath.toString()).start();
                        } else {
                            new ProcessBuilder("xdg-open", localPath.toString()).start();
                        }
                    }

                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        // Connection reset — client disconnected, normal for ncat
                    }
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.err.println("  host-open listener failed: " + e.getMessage());
            }
        }
    }

    private Path mountPath() {
        return Path.of(System.getProperty("user.home"), "mnt", name);
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase();
    }
}
