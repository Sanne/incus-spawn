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
        name = "branch",
        description = "Create a new instance from an existing one",
        mixinStandardHelpOptions = true
)
public class BranchCommand implements Runnable {

    @Parameters(index = "0", description = "Name for the new instance")
    String name;

    @Option(names = "--from", description = "Source instance to branch from (auto-detected from cwd if omitted)")
    String source;

    @Option(names = "--gui", description = "Enable GUI passthrough (Wayland + GPU + audio)")
    boolean gui;

    @Option(names = "--airgap", description = "Disable network access")
    boolean airgap;

    @Option(names = "--inbox", description = "Host directory to mount read-only at /home/agentuser/inbox")
    Path inbox;

    @Option(names = "--cpu", description = "CPU core limit (default: adaptive)")
    Integer cpuLimit;

    @Option(names = "--memory", description = "Memory limit, e.g. '8GB' (default: adaptive)")
    String memoryLimit;

    @Option(names = "--disk", description = "Disk size limit (default: adaptive)")
    String diskLimit;

    @Option(names = "--no-start", description = "Don't start the instance after creation")
    boolean noStart;

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        var resolvedSource = resolveSource();
        if (resolvedSource == null) return;

        if (incus.exists(name)) {
            System.err.println("Error: an instance named '" + name + "' already exists.");
            return;
        }

        System.out.println("Branching '" + name + "' from '" + resolvedSource + "'...");
        incus.copy(resolvedSource, name);

        // Apply resource limits
        var cpu = cpuLimit != null ? cpuLimit : ResourceLimits.adaptiveCpuLimit();
        var memory = memoryLimit != null ? memoryLimit : ResourceLimits.adaptiveMemoryLimit();
        var disk = diskLimit != null ? diskLimit : ResourceLimits.defaultDiskLimit();

        System.out.println("Applying resource limits: " + cpu + " CPUs, " + memory + " memory, " + disk + " disk");
        incus.configSet(name, "limits.cpu", String.valueOf(cpu));
        incus.configSet(name, "limits.memory", memory);
        incus.exec("config", "device", "set", name, "root", "size=" + disk);

        if (airgap) {
            configureAirgap();
        }

        // Tag with metadata
        incus.configSet(name, Metadata.PARENT, resolvedSource);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        if (noStart) {
            System.out.println("Branch '" + name + "' created (not started).");
            return;
        }

        incus.start(name);
        waitForReady(name);

        if (gui) {
            configureGui();
        }

        if (inbox != null) {
            if (java.nio.file.Files.isDirectory(inbox)) {
                System.out.println("Mounting inbox: " + inbox.toAbsolutePath() + " -> /home/agentuser/inbox (read-only)");
                incus.deviceAdd(name, "inbox", "disk",
                        "source=" + inbox.toAbsolutePath(),
                        "path=/home/agentuser/inbox",
                        "readonly=true");
            } else {
                System.err.println("Warning: inbox path '" + inbox + "' is not a directory, skipping.");
            }
        }

        // Mount gcloud credentials for Vertex AI auth
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

        // Fix home dir ownership after all device mounts
        incus.shellExec(name, "chown", "-R", getUid() + ":" + getUid(), "/home/agentuser");

        System.out.println("Branch '" + name + "' is ready.\n");
        incus.interactiveShell(name, "agentuser");
    }

    private String resolveSource() {
        if (source != null) {
            if (!incus.exists(source)) {
                System.err.println("Error: source instance '" + source + "' does not exist.");
                return null;
            }
            return source;
        }

        // Try to auto-detect from cwd
        var projectConfig = ProjectConfig.findInDirectory(Path.of("."));
        if (projectConfig != null && projectConfig.getName() != null) {
            var detected = projectConfig.getName();
            if (incus.exists(detected)) {
                System.out.println("Auto-detected source: " + detected);
                return detected;
            }
            System.err.println("Error: auto-detected source '" + detected + "' does not exist.");
            return null;
        }

        System.err.println("Error: no --from specified and no incus-spawn.yaml found in current directory.");
        System.err.println("Usage: isx branch <name> --from <source-instance>");
        return null;
    }

    private void configureGui() {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Warning: WAYLAND_DISPLAY or XDG_RUNTIME_DIR not set, skipping GUI passthrough.");
            return;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Warning: Wayland socket not found at " + hostSocket + ", skipping.");
            return;
        }

        System.out.println("Enabling GUI passthrough...");
        var uid = getUid();
        incus.deviceAdd(name, "gpu", "gpu");
        incus.deviceAdd(name, "xdg-runtime", "disk",
                "source=" + xdgRuntimeDir,
                "path=/run/user/" + uid);
        incus.shellExec(name, "sh", "-c",
                "cat > /etc/profile.d/wayland.sh << 'ENVEOF'\n" +
                "export WAYLAND_DISPLAY=" + waylandDisplay + "\n" +
                "export XDG_RUNTIME_DIR=/run/user/" + uid + "\n" +
                "export GDK_BACKEND=wayland\n" +
                "export QT_QPA_PLATFORM=wayland\n" +
                "export SDL_VIDEODRIVER=wayland\n" +
                "export MOZ_ENABLE_WAYLAND=1\n" +
                "export ELECTRON_OZONE_PLATFORM_HINT=wayland\n" +
                "ENVEOF\n" +
                "chmod 644 /etc/profile.d/wayland.sh");
    }

    private void configureAirgap() {
        System.out.println("Enabling network airgap...");
        var result = incus.exec("network", "detach", "incusbr0", name);
        if (!result.success()) {
            incus.exec("config", "device", "override", name, "eth0");
            incus.exec("config", "device", "remove", name, "eth0");
        }
    }

    private static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            p.waitFor();
            return output;
        } catch (Exception e) {
            return "1000";
        }
    }

    private void waitForReady(String container) {
        for (int i = 0; i < 30; i++) {
            var result = incus.shellExec(container, "true");
            if (result.success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        System.err.println("Warning: instance " + container + " may not be fully ready.");
    }
}
