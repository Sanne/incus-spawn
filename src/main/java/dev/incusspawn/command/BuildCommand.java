package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Map;

@Command(
        name = "build",
        description = "Build or rebuild a base golden image (e.g. golden-minimal, golden-java)",
        mixinStandardHelpOptions = true
)
public class BuildCommand implements Runnable {

    @Parameters(index = "0", description = "Name of the golden image (e.g. golden-minimal, golden-java)")
    String name;

    @Option(names = "--vm", description = "Build as a VM instead of a container")
    boolean vm;

    @Inject
    IncusClient incus;

    @Inject
    ToolDefLoader toolDefLoader;

    @Inject
    Instance<ToolSetup> toolSetups;

    @Override
    public void run() {
        var defs = ImageDef.loadBuiltins();
        var imageDef = defs.get(name);
        if (imageDef == null) {
            System.err.println("Unknown image: " + name);
            System.err.println("Available images: " + String.join(", ", defs.keySet()));
            System.exit(1);
        }
        build(imageDef, defs);
    }

    /**
     * Build an image. If the image has a parent, ensure the parent
     * is built first (recursively).
     */
    private void build(ImageDef imageDef, Map<String, ImageDef> defs) {
        var targetName = imageDef.getName();

        // If this image has a parent, ensure it exists
        if (!imageDef.isRoot()) {
            var parentName = imageDef.getParent();
            if (!incus.exists(parentName)) {
                var parentDef = defs.get(parentName);
                if (parentDef == null) {
                    System.err.println("Parent image '" + parentName + "' not found in definitions.");
                    System.exit(1);
                }
                System.out.println("Parent image '" + parentName + "' not found, building it first...\n");
                build(parentDef, defs);
                System.out.println();
            }
        }

        System.out.println("Building image: " + targetName);

        if (incus.exists(targetName)) {
            System.out.println("Image '" + targetName + "' already exists.");
            System.out.println("Rebuilding will destroy the existing image and any changes made to it.");
            var console = System.console();
            if (console != null) {
                System.out.print("Delete and rebuild? (y/N): ");
                var answer = console.readLine().strip();
                if (!answer.equalsIgnoreCase("y")) {
                    System.out.println("Aborted.");
                    return;
                }
            }
            incus.delete(targetName, true);
        }

        if (imageDef.isRoot()) {
            buildFromScratch(imageDef);
        } else {
            buildFromParent(imageDef);
        }
    }

    /**
     * Build an image by copying its parent and applying layers from the image definition.
     */
    private void buildFromParent(ImageDef imageDef) {
        var targetName = imageDef.getName();
        var parentName = imageDef.getParent();

        System.out.println("Deriving from parent image '" + parentName + "'...");
        incus.copy(parentName, targetName);
        incus.start(targetName);
        waitForReady(targetName);
        waitForNetwork(targetName);

        var container = new Container(incus, targetName);
        installPackages(container, imageDef);
        installTools(container, imageDef);

        // Clean up caches to minimize image size (important for CoW clones)
        cleanCaches(targetName);

        // Tag with metadata
        incus.configSet(targetName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(targetName, Metadata.PROFILE, targetName);
        incus.configSet(targetName, Metadata.PARENT, parentName);
        incus.configSet(targetName, Metadata.CREATED, Metadata.today());

        System.out.println("Stopping image...");
        incus.stop(targetName);

        System.out.println("Image " + targetName + " built successfully.");
    }

    /**
     * Build an image from scratch using the base OS image.
     * This is the full setup path: DNS, user, packages, tools.
     */
    private void buildFromScratch(ImageDef imageDef) {
        var targetName = imageDef.getName();
        var image = imageDef.getImage();

        // Launch base image
        System.out.println("Launching " + image + "...");
        incus.launch(image, targetName, vm);

        waitForReady(targetName);

        // Set ID mapping for UID 1000 (needed for Wayland passthrough) and enable
        // nested containers with syscall interception for container runtimes.
        // Don't drop any capabilities since the container is the security boundary;
        // this ensures tools like ping, traceroute, tcpdump, strace, etc. work.
        incus.configSet(targetName, "raw.idmap", "both 1000 1000");
        incus.configSet(targetName, "security.nesting", "true");
        incus.configSet(targetName, "security.syscalls.intercept.mknod", "true");
        incus.configSet(targetName, "security.syscalls.intercept.setxattr", "true");
        incus.configSet(targetName, "raw.lxc", "lxc.cap.drop =");
        incus.restart(targetName);
        waitForReady(targetName);

        // Relax kernel restrictions that default to paranoid values inside containers.
        // On bare metal these are often already relaxed by the distro; in a container
        // they reset to kernel defaults. Since the container is the security boundary,
        // restore bare-metal-like behaviour for common developer tools.
        incus.shellExec(targetName, "sh", "-c",
                "printf '%s\\n' " +
                "'net.ipv4.ping_group_range = 0 2147483647' " +
                "'kernel.dmesg_restrict = 0' " +
                "'kernel.perf_event_paranoid = 1' " +
                "'kernel.yama.ptrace_scope = 0' " +
                "> /etc/sysctl.d/99-dev-container.conf && " +
                "sysctl -p /etc/sysctl.d/99-dev-container.conf");

        // systemd-resolved (127.0.0.53) doesn't work reliably inside containers.
        // Disable it and point DNS directly at the Incus bridge gateway (dnsmasq).
        System.out.println("Configuring DNS...");
        var gatewayIp = incus.exec("network", "get", "incusbr0", "ipv4.address")
                .assertSuccess("Failed to get bridge IP").stdout().strip();
        if (gatewayIp.contains("/")) {
            gatewayIp = gatewayIp.substring(0, gatewayIp.indexOf('/'));
        }
        incus.shellExec(targetName, "sh", "-c",
                "systemctl disable --now systemd-resolved 2>/dev/null; " +
                "systemctl mask systemd-resolved 2>/dev/null; " +
                "rm -f /etc/resolv.conf; " +
                "echo 'nameserver " + gatewayIp + "' > /etc/resolv.conf; " +
                "chattr +i /etc/resolv.conf");
        waitForNetwork(targetName);

        // Update all packages to latest security patches
        System.out.println("Updating system packages...");
        requireSuccess(incus.shellExecInteractive(targetName, "dnf", "-y", "upgrade", "--refresh"),
                "Failed to update system packages");

        // Create agentuser with passwordless sudo (container is the security boundary)
        System.out.println("Creating agentuser...");
        incus.shellExec(targetName, "useradd", "-m", "-u", "1000", "agentuser");
        incus.shellExec(targetName, "mkdir", "-p", "/home/agentuser/inbox");
        incus.shellExec(targetName, "chown", "agentuser:agentuser", "/home/agentuser/inbox");
        incus.shellExec(targetName, "sh", "-c",
                "echo 'agentuser ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/agentuser");

        // Install base packages needed by most tools
        System.out.println("Installing base packages...");
        requireSuccess(incus.shellExecInteractive(targetName, "dnf", "install", "-y",
                "git", "curl", "which", "procps-ng", "findutils"),
                "Failed to install base packages");

        // Install packages and tools from image definition
        var container = new Container(incus, targetName);
        installPackages(container, imageDef);
        installTools(container, imageDef);

        // Clean up caches to minimize image size (important for CoW clones)
        cleanCaches(targetName);

        // Tag with metadata
        incus.configSet(targetName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(targetName, Metadata.PROFILE, targetName);
        incus.configSet(targetName, Metadata.CREATED, Metadata.today());

        // Stop the golden image (it's a template, not a running instance)
        System.out.println("Stopping image...");
        incus.stop(targetName);

        System.out.println("Image " + targetName + " built successfully.");
    }

    private void installPackages(Container container, ImageDef imageDef) {
        var packages = imageDef.getPackages();
        if (packages.isEmpty()) return;
        System.out.println("Installing packages: " + String.join(", ", packages) + "...");
        var args = new java.util.ArrayList<String>();
        args.addAll(java.util.List.of("dnf", "install", "-y"));
        args.addAll(packages);
        container.runInteractive("Failed to install packages", args.toArray(String[]::new));
    }

    private void installTools(Container container, ImageDef imageDef) {
        var toolNames = imageDef.getTools();
        if (toolNames.isEmpty()) return;
        for (var toolName : toolNames) {
            var tool = findTool(toolName);
            if (tool == null) {
                System.err.println("Warning: unknown tool '" + toolName + "', skipping.");
                continue;
            }
            tool.install(container);
        }
    }

    private ToolSetup findTool(String name) {
        // YAML tools (user-defined, then built-in) take priority
        var yamlTool = toolDefLoader.find(name);
        if (yamlTool != null) return yamlTool;
        // Fall back to Java CDI implementations
        for (var tool : toolSetups) {
            if (tool.name().equals(name)) return tool;
        }
        return null;
    }

    private void cleanCaches(String container) {
        System.out.println("Cleaning up caches...");
        incus.shellExec(container, "sh", "-c",
                "dnf -y clean all && rm -rf /var/cache/dnf /tmp/* /var/tmp/*");
    }

    private void waitForNetwork(String container) {
        System.out.println("Verifying DNS resolution...");
        for (int attempt = 0; attempt < 10; attempt++) {
            var dnsCheck = incus.shellExec(container, "sh", "-c",
                    "curl -4 -s -o /dev/null -w '%{http_code}' https://mirrors.fedoraproject.org");
            if (dnsCheck.success() && dnsCheck.stdout().strip().contains("302")) {
                System.out.println("  DNS working.");
                return;
            }
            if (attempt == 9) {
                System.err.println("  DNS resolution is not working. Check your network setup.");
                System.err.println("  The container '" + container + "' has been left running for inspection.");
                System.exit(1);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            System.out.println("  Waiting for DNS... (attempt " + (attempt + 2) + "/10)");
        }
    }

    private void waitForReady(String container) {
        for (int i = 0; i < 30; i++) {
            var result = incus.shellExec(container, "true");
            if (result.success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        System.err.println("Warning: container " + container + " may not be fully ready.");
    }

    private void requireSuccess(int exitCode, String message) {
        if (exitCode != 0) {
            System.err.println("\nBuild failed: " + message + " (exit code " + exitCode + ")");
            System.err.println("The container '" + name + "' has been left running for inspection.");
            System.err.println("To clean up: incus-spawn destroy " + name + " --force");
            System.exit(1);
        }
    }

}
