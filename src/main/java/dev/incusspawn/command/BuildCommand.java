package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;

import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


@Command(
        name = "build",
        description = "Build or rebuild a base golden image (e.g. golden-minimal, golden-java)",
        mixinStandardHelpOptions = true
)
public class BuildCommand implements Runnable {

    @Parameters(index = "0", description = "Name of the golden image (e.g. golden-minimal, golden-java)",
            arity = "0..1")
    String name;

    @Option(names = "--all", description = "Rebuild all defined golden images")
    boolean all;

    @Option(names = "--vm", description = "Build as a VM instead of a container")
    boolean vm;

    @Inject
    IncusClient incus;

    @Inject
    ToolDefLoader toolDefLoader;

    @Inject
    Instance<ToolSetup> toolSetups;

    @Inject
    picocli.CommandLine.IFactory factory;

    // Host-side DNF cache shared across builds to avoid redundant metadata downloads
    private static final Path DNF_CACHE_DIR = Path.of(System.getProperty("user.home"),
            ".cache", "incus-spawn", "dnf");
    private static final String DNF_CACHE_DEVICE = "dnf-cache";

    @Override
    public void run() {
        if (!InitCommand.requireInit(factory)) return;
        var defs = ImageDef.loadAll();

        if (all) {
            buildAll(defs);
            return;
        }

        if (name == null) {
            System.err.println("Usage: isx build <image-name>  or  isx build --all");
            System.err.println("Available images: " + String.join(", ", defs.keySet()));
            System.exit(1);
        }

        var imageDef = defs.get(name);
        if (imageDef == null) {
            System.err.println("Unknown image: " + name);
            System.err.println("Available images: " + String.join(", ", defs.keySet()));
            System.exit(1);
        }
        build(imageDef, defs);
    }

    /**
     * Rebuild all defined golden images. Deletes existing images in reverse
     * dependency order (children first), then builds leaf images (parents
     * are built automatically by the recursive build).
     */
    private void buildAll(Map<String, ImageDef> defs) {
        // Identify which images are parents of other images
        var parentNames = defs.values().stream()
                .filter(d -> !d.isRoot())
                .map(ImageDef::getParent)
                .collect(java.util.stream.Collectors.toSet());

        // Leaf images = images that no other image references as parent
        var leaves = defs.values().stream()
                .filter(d -> !parentNames.contains(d.getName()))
                .toList();

        var allNames = new java.util.ArrayList<>(defs.keySet());
        System.out.println("This will rebuild all golden images: " + String.join(", ", allNames));
        var console = System.console();
        if (console != null) {
            System.out.print("Continue? (y/N): ");
            var answer = console.readLine().strip();
            if (!answer.equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
        }

        // Delete in reverse order (children before parents)
        java.util.Collections.reverse(allNames);
        System.out.println("\nDeleting existing images...");
        for (var imgName : allNames) {
            if (incus.exists(imgName)) {
                System.out.println("  Deleting " + imgName + "...");
                incus.delete(imgName, true);
            }
        }

        // Build each leaf — parents are built recursively
        System.out.println();
        for (var leaf : leaves) {
            build(leaf, defs);
            System.out.println();
        }
    }

    /**
     * Build an image. If the image has a parent, ensure the parent
     * is built first (recursively).
     */
    private void build(ImageDef imageDef, Map<String, ImageDef> defs) {
        var targetName = imageDef.getName();

        // Check that required credentials are configured before starting a potentially long build
        var credentialError = SpawnConfig.checkCredentials(imageDef, defs, incus::exists);
        if (!credentialError.isEmpty()) {
            System.err.println("Error: " + credentialError);
            System.exit(1);
        }

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

        mountDnfCache(targetName);
        var container = new Container(incus, targetName);
        installPackages(container, imageDef);
        installTools(container, imageDef);
        unmountDnfCache(targetName);

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

        // Point DNS at the Incus bridge gateway (dnsmasq) so the container can
        // resolve names during the build. systemd-resolved is disabled after dnf
        // upgrade to prevent the upgrade from re-enabling it.
        System.out.println("Configuring DNS...");
        var gatewayRaw = incus.exec("network", "get", "incusbr0", "ipv4.address")
                .assertSuccess("Failed to get bridge IP").stdout().strip();
        var gatewayIp = gatewayRaw.contains("/")
                ? gatewayRaw.substring(0, gatewayRaw.indexOf('/'))
                : gatewayRaw;
        incus.shellExec(targetName, "sh", "-c",
                "rm -f /etc/resolv.conf; " +
                "echo 'nameserver " + gatewayIp + "' > /etc/resolv.conf");

        // Install MITM CA certificate so containers trust the proxy's TLS certs.
        // DNS interception is handled at the bridge level via dnsmasq (configured by isx proxy).
        System.out.println("Installing MITM proxy CA certificate...");
        var ca = CertificateAuthority.loadOrCreate();
        incus.shellExec(targetName, "sh", "-c",
                "cat > /etc/pki/ca-trust/source/anchors/incus-spawn-mitm.crt << 'CERTEOF'\n" +
                ca.caCertPem() +
                "CERTEOF");
        incus.shellExec(targetName, "update-ca-trust");

        waitForNetwork(targetName);

        // Mount host-side DNF cache so metadata and packages are shared across builds
        mountDnfCache(targetName);

        // Update all packages to latest security patches
        System.out.println("Updating system packages...");
        requireSuccess(incus.shellExecInteractive(targetName, "dnf", "-y", "upgrade", "--refresh"),
                "Failed to update system packages");

        // Disable systemd-resolved AFTER dnf upgrade — the upgrade can re-enable it.
        // Also remove 'resolve' from nsswitch.conf so .local domains go through
        // regular DNS (dnsmasq) instead of mDNS.
        System.out.println("Finalizing DNS configuration...");
        incus.shellExec(targetName, "sh", "-c",
                "systemctl disable --now systemd-resolved 2>/dev/null; " +
                "systemctl mask systemd-resolved 2>/dev/null; " +
                "sed -i 's/resolve \\[!UNAVAIL=return\\] //' /etc/nsswitch.conf; " +
                "chattr +i /etc/resolv.conf");

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

        // Unmount host-side DNF cache before cleanup — keeps images clean
        unmountDnfCache(targetName);

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

    /**
     * Mount a host-side DNF cache directory into the container. This shares
     * metadata and downloaded packages across builds, avoiding redundant
     * downloads when building a parent→child image chain.
     */
    private void mountDnfCache(String container) {
        try {
            Files.createDirectories(DNF_CACHE_DIR);
        } catch (IOException e) {
            System.err.println("Warning: could not create DNF cache directory: " + e.getMessage());
            return;
        }
        incus.deviceAdd(container, DNF_CACHE_DEVICE, "disk",
                "source=" + DNF_CACHE_DIR,
                "path=/var/cache/dnf",
                "shift=true");
    }

    private void unmountDnfCache(String container) {
        incus.deviceRemove(container, DNF_CACHE_DEVICE);
    }

}
