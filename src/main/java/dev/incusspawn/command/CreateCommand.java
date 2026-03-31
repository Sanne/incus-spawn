package dev.incusspawn.command;

import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
        name = "create",
        description = "Spawn an ephemeral clone from a golden image",
        mixinStandardHelpOptions = true
)
public class CreateCommand implements Runnable {

    @Parameters(index = "0", description = "Name for the clone (like a git branch name)")
    String name;

    @Option(names = "--project", description = "Project golden image to clone from (auto-detected from cwd if omitted)")
    String project;

    @Option(names = "--vm", description = "Create a VM instead of a container")
    boolean vm;

    @Option(names = "--gui", description = "Enable Wayland + GPU passthrough for graphical applications")
    boolean gui;

    @Option(names = "--airgapped", description = "Disable network access")
    boolean airgapped;

    @Option(names = "--inbox", description = "Host directory to mount read-only at /home/agentuser/inbox")
    Path inbox;

    @Option(names = "--cpu", description = "CPU core limit (default: adaptive)")
    Integer cpuLimit;

    @Option(names = "--memory", description = "Memory limit, e.g. '8GB' (default: adaptive)")
    String memoryLimit;

    @Option(names = "--disk", description = "Disk size limit (default: 20GB)")
    String diskLimit;

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        var source = resolveSource();
        if (source == null) return;

        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            System.err.println("Use 'incus-spawn shell " + name + "' to reconnect, or 'incus-spawn destroy " + name + "' first.");
            return;
        }

        System.out.println("Creating clone '" + name + "' from '" + source + "'...");

        // Clone from golden image
        incus.copy(source, name);

        // Apply resource limits
        applyResourceLimits();

        // Configure GUI if requested
        if (gui) {
            configureGui();
        }

        // Configure airgapped mode
        if (airgapped) {
            // Remove network devices
            System.out.println("Configuring airgapped mode (no network)...");
            incus.exec("config", "device", "remove", name, "eth0");
        }

        // Start the clone
        incus.start(name);
        waitForReady(name);

        // Mount inbox if specified
        if (inbox != null) {
            System.out.println("Mounting inbox: " + inbox + " -> /home/agentuser/inbox (read-only)");
            incus.deviceAdd(name, "inbox", "disk",
                    "source=" + inbox.toAbsolutePath(),
                    "path=/home/agentuser/inbox",
                    "readonly=true");
        }

        // Mount gcloud credentials for Vertex AI auth (read-only)
        var config = dev.incusspawn.config.SpawnConfig.load();
        if (config.getClaude().isUseVertex()) {
            var gcloudDir = java.nio.file.Path.of(System.getProperty("user.home"), ".config", "gcloud");
            if (java.nio.file.Files.isDirectory(gcloudDir)) {
                System.out.println("Mounting gcloud credentials (read-only) for Vertex AI auth...");
                incus.deviceAdd(name, "gcloud", "disk",
                        "source=" + gcloudDir.toAbsolutePath(),
                        "path=/home/agentuser/.config/gcloud",
                        "readonly=true");
            }
        }

        // Tag with metadata
        incus.configSet(name, Metadata.TYPE, Metadata.TYPE_CLONE);
        incus.configSet(name, Metadata.PROJECT, source);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        System.out.println("Clone '" + name + "' is ready.\n");

        // Show welcome info
        var claudeVersion = incus.execInContainer(name, "agentuser", "claude", "--version");
        if (claudeVersion.success()) {
            System.out.println("  Claude Code: " + claudeVersion.stdout().strip());
        }
        var ghStatus = incus.execInContainer(name, "agentuser", "gh", "auth", "status");
        if (ghStatus.success()) {
            // Extract just the "Logged in to" line
            ghStatus.stdout().lines()
                    .filter(l -> l.contains("Logged in") || l.contains("account"))
                    .findFirst()
                    .ifPresent(l -> System.out.println("  GitHub: " + l.strip()));
        }
        if (inbox != null) {
            System.out.println("  Inbox: ~/inbox (read-only mount from " + inbox.toAbsolutePath() + ")");
        }
        System.out.println();

        // Open interactive shell
        incus.interactiveShell(name, "agentuser");
    }

    private String resolveSource() {
        if (project != null) {
            if (!incus.exists(project)) {
                System.err.println("Error: golden image '" + project + "' does not exist.");
                return null;
            }
            return project;
        }

        // Try to auto-detect from cwd
        var projectConfig = ProjectConfig.findInDirectory(Path.of("."));
        if (projectConfig != null && projectConfig.getName() != null) {
            var detected = projectConfig.getName();
            if (incus.exists(detected)) {
                System.out.println("Auto-detected project: " + detected);
                return detected;
            }
            System.err.println("Error: auto-detected project '" + detected + "' does not exist. Run 'incus-spawn project create " + detected + "' first.");
            return null;
        }

        System.err.println("Error: no --project specified and no incus-spawn.yaml found in current directory.");
        System.err.println("Usage: incus-spawn create <name> --project <golden-image>");
        return null;
    }

    private void applyResourceLimits() {
        var cpu = cpuLimit != null ? cpuLimit : ResourceLimits.adaptiveCpuLimit();
        var memory = memoryLimit != null ? memoryLimit : ResourceLimits.adaptiveMemoryLimit();
        var disk = diskLimit != null ? diskLimit : ResourceLimits.defaultDiskLimit();

        System.out.println("Applying resource limits: " + cpu + " CPUs, " + memory + " memory, " + disk + " disk");

        incus.configSet(name, "limits.cpu", String.valueOf(cpu));
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);
    }

    private void configureGui() {
        System.out.println("Configuring GUI (Wayland + GPU passthrough)...");
        incus.deviceAdd(name, "mygpu", "gpu");
        incus.deviceAdd(name, "wayland", "disk",
                "source=/run/user/1000/wayland-0",
                "path=/mnt/wayland-0");

        // Set up Wayland env vars for agentuser
        incus.shellExec(name, "sh", "-c",
                "mkdir -p /home/agentuser/.run && chmod 0700 /home/agentuser/.run && chown agentuser:agentuser /home/agentuser/.run");
        incus.execInContainer(name, "agentuser", "sh", "-c",
                "echo 'export XDG_RUNTIME_DIR=~/.run' >> ~/.bashrc && " +
                "echo 'export WAYLAND_DISPLAY=wayland-0' >> ~/.bashrc && " +
                "echo 'ln -sf /mnt/wayland-0 ~/.run/wayland-0 2>/dev/null' >> ~/.bashrc");
    }

    private void waitForReady(String container) {
        for (int i = 0; i < 30; i++) {
            var result = incus.shellExec(container, "true");
            if (result.success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        System.err.println("Warning: container " + container + " may not be fully ready.");
    }
}
