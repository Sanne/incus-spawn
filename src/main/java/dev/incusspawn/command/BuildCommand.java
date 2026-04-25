package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyHealthCheck;

import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Command(
        name = "build",
        description = "Build or rebuild a template image (e.g. tpl-minimal, tpl-java)",
        mixinStandardHelpOptions = true
)
public class BuildCommand implements java.util.concurrent.Callable<Integer> {

    @Parameters(index = "0", description = "Name of the template (e.g. tpl-minimal, tpl-java)",
            arity = "0..1")
    String name;

    @Option(names = "--all", description = "Rebuild all defined templates")
    boolean all;

    @Option(names = "--missing", description = "Build only templates that don't exist yet")
    boolean missing;

    @Option(names = "--vm", description = "Build as a VM instead of a container")
    boolean vm;

    @Option(names = "--yes", description = "Skip interactive confirmations (for TUI integration)")
    boolean yes;

    @Inject
    IncusClient incus;

    @Inject
    ToolDefLoader toolDefLoader;

    @Inject
    Instance<ToolSetup> toolSetups;

    @Inject
    picocli.CommandLine.IFactory factory;

    private static Path dnfCacheDir() { return RuntimeConstants.DNF_CACHE_DIR; }
    private static final String DNF_CACHE_DEVICE = "dnf-cache";

    @Override
    public Integer call() {
        if (!InitCommand.requireInit(factory)) return 0;
        var defs = ImageDef.loadAll();

        try {
            if (missing) {
                buildMissing(defs);
                return 0;
            }
            if (all) {
                buildAll(defs);
                return 0;
            }

            if (name == null) {
                System.err.println("Usage: isx build <image-name>  or  isx build --all");
                System.err.println("Available images: " + String.join(", ", defs.keySet()));
                return 1;
            }

            var imageDef = defs.get(name);
            if (imageDef == null) {
                System.err.println("Unknown image: " + name);
                System.err.println("Available images: " + String.join(", ", defs.keySet()));
                return 1;
            }
            build(imageDef, defs);
            return 0;
        } catch (BuildFailedException e) {
            return 1;
        }
    }

    /**
     * Rebuild all defined templates. Deletes existing images in reverse
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
        System.out.println("This will rebuild all templates: " + String.join(", ", allNames));
        if (!yes) {
            var console = System.console();
            if (console != null) {
                System.out.print("Continue? (y/N): ");
                var answer = console.readLine().strip();
                if (!answer.equalsIgnoreCase("y")) {
                    System.out.println("Aborted.");
                    return;
                }
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
     * Build only templates that don't exist yet. Skips already-built
     * images without deleting them. Parents are built recursively if missing.
     */
    private void buildMissing(Map<String, ImageDef> defs) {
        var parentNames = defs.values().stream()
                .filter(d -> !d.isRoot())
                .map(ImageDef::getParent)
                .collect(java.util.stream.Collectors.toSet());
        var leaves = defs.values().stream()
                .filter(d -> !parentNames.contains(d.getName()))
                .toList();
        for (var leaf : leaves) {
            if (!incus.exists(leaf.getName())) {
                build(leaf, defs);
                System.out.println();
            }
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

        ProxyHealthCheck.requireProxy(incus);

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
            if (!yes) {
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
            }
            incus.delete(targetName, true);
        }

        try {
            if (imageDef.isRoot()) {
                buildFromScratch(imageDef, defs);
            } else {
                buildFromParent(imageDef, defs);
            }
        } catch (Exception e) {
            System.err.println("\n\033[33m" + "─".repeat(60) + "\033[0m");
            System.err.println("\033[1mBuild failed for " + targetName + ": " + e.getMessage() + "\033[0m");
            promoteToFailedInstance(targetName);
            throw new BuildFailedException(targetName);
        }
    }

    private void promoteToFailedInstance(String containerName) {
        var promotedName = containerName + "-failed-build";
        try {
            if (incus.exists(promotedName)) {
                incus.delete(promotedName, true);
            }
            try { unmountDnfCache(containerName); } catch (Exception ignored) {}
            incus.stop(containerName);
            incus.rename(containerName, promotedName);
            incus.configSet(promotedName, Metadata.TYPE, Metadata.TYPE_FAILED_BUILD);
            incus.configSet(promotedName, Metadata.PARENT, containerName);
            incus.configSet(promotedName, Metadata.CREATED, Metadata.now());
            System.err.println("\033[1mContainer promoted to instance '" + promotedName + "' for inspection.\033[0m");
        } catch (Exception promoteError) {
            System.err.println("Failed to promote container: " + promoteError.getMessage());
            System.err.println("Container '" + containerName + "' may still exist for manual cleanup.");
        }
    }

    /**
     * Build an image by copying its parent and applying layers from the image definition.
     */
    private void buildFromParent(ImageDef imageDef, Map<String, ImageDef> defs) {
        var targetName = imageDef.getName();
        var parentName = imageDef.getParent();

        System.out.println("Deriving from parent image '" + parentName + "'...");
        incus.copy(parentName, targetName);
        incus.start(targetName);
        waitForReady(targetName);
        waitForNetwork(targetName);

        mountDnfCache(targetName);
        var container = new Container(incus, targetName);

        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        if (!hostResources.isEmpty()) {
            System.out.println("Applying host-resources...");
            HostResourceSetup.applyForBuild(incus, container, hostResources);
        }

        var tools = resolveTools(imageDef);
        installAllPackages(container, imageDef, tools, defs);
        runToolSetup(container, tools);
        installSkills(container, imageDef, defs);
        cloneRepos(container, imageDef);
        updateClaudeJsonTrust(container, imageDef);

        HostResourceSetup.removeBuildDevices(incus, targetName, hostResources);
        unmountDnfCache(targetName);

        // Clean up caches to minimize image size (important for CoW clones)
        cleanCaches(targetName);

        // Tag with metadata
        incus.configSet(targetName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(targetName, Metadata.PROFILE, targetName);
        incus.configSet(targetName, Metadata.PARENT, parentName);
        incus.configSet(targetName, Metadata.CREATED, Metadata.today());
        stampBuildVersion(targetName, imageDef);
        if (!hostResources.isEmpty()) {
            incus.configSet(targetName, Metadata.HOST_RESOURCES,
                    HostResourceSetup.serialize(hostResources));
        }

        System.out.println("Stopping image...");
        incus.stop(targetName);

        System.out.println("Image " + targetName + " built successfully.");
    }

    /**
     * Build an image from scratch using the base OS image.
     * This is the full setup path: DNS, user, packages, tools.
     */
    private void buildFromScratch(ImageDef imageDef, Map<String, ImageDef> defs) {
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

        // The base Fedora image uses systemd-resolved (127.0.0.53) which doesn't
        // work reliably inside Incus containers. Replace it with a direct resolv.conf
        // pointing at the bridge gateway's dnsmasq — this is how the container gets
        // basic DNS resolution (package mirrors, etc.), unrelated to MITM proxy
        // domain interception. systemd-resolved is disabled permanently after dnf
        // upgrade (which can re-enable it).
        System.out.println("Replacing systemd-resolved with direct DNS...");
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
        requireSuccess(incus.shellExecInteractive(targetName, "dnf", "-y", "--setopt=keepcache=true",
                "upgrade", "--refresh"),
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
                "--setopt=keepcache=true", "git", "curl", "which", "procps-ng", "findutils"),
                "Failed to install base packages");

        // Install packages and tools from image definition
        var container = new Container(incus, targetName);

        var hostResources = HostResourceSetup.collectEffective(imageDef, defs);
        if (!hostResources.isEmpty()) {
            System.out.println("Applying host-resources...");
            HostResourceSetup.applyForBuild(incus, container, hostResources);
        }

        var tools = resolveTools(imageDef);
        installAllPackages(container, imageDef, tools, defs);
        runToolSetup(container, tools);
        installSkills(container, imageDef, defs);
        cloneRepos(container, imageDef);
        updateClaudeJsonTrust(container, imageDef);

        HostResourceSetup.removeBuildDevices(incus, targetName, hostResources);
        // Unmount host-side DNF cache before cleanup — keeps images clean
        unmountDnfCache(targetName);

        // Clean up caches to minimize image size (important for CoW clones)
        cleanCaches(targetName);

        // Tag with metadata
        incus.configSet(targetName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(targetName, Metadata.PROFILE, targetName);
        incus.configSet(targetName, Metadata.CREATED, Metadata.today());
        stampBuildVersion(targetName, imageDef);
        if (!hostResources.isEmpty()) {
            incus.configSet(targetName, Metadata.HOST_RESOURCES,
                    HostResourceSetup.serialize(hostResources));
        }

        // Stop the template (it's a stopped snapshot you branch from, not a running instance)
        System.out.println("Stopping image...");
        incus.stop(targetName);

        System.out.println("Image " + targetName + " built successfully.");
    }

    /**
     * Resolve all tools referenced by the image definition, including
     * transitive dependencies declared via {@code requires}.
     */
    private java.util.List<ToolSetup> resolveTools(ImageDef imageDef) {
        var explicit = new java.util.LinkedHashSet<String>(imageDef.getTools());
        var resolved = new java.util.LinkedHashMap<String, ToolSetup>();

        for (var toolName : imageDef.getTools()) {
            resolveWithDeps(toolName, resolved, new java.util.LinkedHashSet<>(), explicit);
        }
        return new java.util.ArrayList<>(resolved.values());
    }

    private void resolveWithDeps(String name, java.util.LinkedHashMap<String, ToolSetup> resolved,
                                  java.util.LinkedHashSet<String> visiting, java.util.Set<String> explicit) {
        if (resolved.containsKey(name)) return;
        if (!visiting.add(name)) {
            System.err.println("Warning: dependency cycle detected: " +
                    String.join(" -> ", visiting) + " -> " + name + ", skipping.");
            return;
        }
        var tool = findTool(name);
        if (tool == null) {
            System.err.println("Warning: unknown tool '" + name + "', skipping.");
            visiting.remove(name);
            return;
        }
        for (var dep : tool.requires()) {
            if (!explicit.contains(dep)) {
                System.out.println("  Auto-adding dependency: " + dep + " (required by " + name + ")");
            }
            resolveWithDeps(dep, resolved, visiting, explicit);
        }
        resolved.put(name, tool);
        visiting.remove(name);
    }

    /**
     * Collect all packages from the image definition and its tools,
     * subtract those already installed by ancestor images, and install
     * only the remaining packages.
     */
    private void installAllPackages(Container container, ImageDef imageDef,
                                    java.util.List<ToolSetup> tools,
                                    Map<String, ImageDef> defs) {
        var allPackages = new java.util.LinkedHashSet<>(imageDef.getPackages());
        for (var tool : tools) {
            allPackages.addAll(tool.packages());
        }
        if (allPackages.isEmpty()) return;

        // Collect packages already installed by ancestor images
        var ancestorPackages = new java.util.LinkedHashSet<String>();
        var parentName = imageDef.getParent();
        while (parentName != null && !parentName.isBlank()) {
            var parentDef = defs.get(parentName);
            if (parentDef == null) break;
            ancestorPackages.addAll(parentDef.getPackages());
            for (var tool : resolveTools(parentDef)) {
                ancestorPackages.addAll(tool.packages());
            }
            parentName = parentDef.getParent();
        }

        var totalCount = allPackages.size();
        allPackages.removeAll(ancestorPackages);

        if (allPackages.isEmpty()) {
            System.out.println("All " + totalCount + " packages already installed.");
            return;
        }

        System.out.println("Installing " + allPackages.size() + " packages (" +
                (totalCount - allPackages.size()) + " already installed): " +
                String.join(", ", allPackages) + "...");
        var args = new java.util.ArrayList<String>();
        args.addAll(java.util.List.of("dnf", "install", "-y", "--setopt=keepcache=true"));
        args.addAll(allPackages);
        container.runInteractive("Failed to install packages", args.toArray(String[]::new));
    }

    /**
     * Run the non-package setup steps for each tool (scripts, files, env, verify).
     */
    private void runToolSetup(Container container, java.util.List<ToolSetup> tools) {
        for (var tool : tools) {
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
        // DNF cache is on the host mount (unmounted before this call) — only
        // clean container-local leftovers and temp files to minimize image size.
        System.out.println("Cleaning up caches...");
        incus.shellExec(container, "sh", "-c",
                "rm -rf /var/cache/libdnf5 /tmp/* /var/tmp/*");
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
                throw new RuntimeException(
                        "DNS resolution is not working. Check your network setup.");
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

    private void stampBuildVersion(String container, dev.incusspawn.config.ImageDef imageDef) {
        var info = BuildInfo.instance();
        incus.configSet(container, Metadata.BUILD_VERSION, info.version());
        incus.configSet(container, Metadata.BUILD_SHA, info.gitSha());
        incus.configSet(container, Metadata.CA_FINGERPRINT, CertificateAuthority.currentCaFingerprint());
        incus.configSet(container, Metadata.DEFINITION_SHA,
                imageDef.contentFingerprint(computeToolFingerprints(imageDef)));
    }

    private java.util.Map<String, String> computeToolFingerprints(dev.incusspawn.config.ImageDef imageDef) {
        var rawFps = new java.util.TreeMap<String, String>();
        var depMap = new java.util.TreeMap<String, java.util.List<String>>();
        for (var tool : resolveTools(imageDef)) {
            if (tool instanceof YamlToolSetup yts) {
                rawFps.put(yts.toolDef().getName(), yts.toolDef().contentFingerprint());
                depMap.put(yts.toolDef().getName(), yts.toolDef().getRequires());
            }
        }
        return dev.incusspawn.tool.ToolDef.compositeFingerprints(rawFps, depMap);
    }

    private void requireSuccess(int exitCode, String message) {
        if (exitCode != 0) {
            throw new RuntimeException(
                    message + " (exit code " + exitCode + ")");
        }
    }

    static class BuildFailedException extends RuntimeException {
        final String containerName;

        BuildFailedException() {
            this(null);
        }

        BuildFailedException(String containerName) {
            super(null, null, true, false);
            this.containerName = containerName;
        }
    }

    /**
     * Mount a host-side DNF cache directory into the container. This shares
     * metadata and downloaded packages across builds, avoiding redundant
     * downloads when building a parent→child image chain.
     */
    private void mountDnfCache(String container) {
        try {
            Files.createDirectories(dnfCacheDir());
        } catch (IOException e) {
            System.err.println("Warning: could not create DNF cache directory: " + e.getMessage());
            return;
        }
        incus.deviceAdd(container, DNF_CACHE_DEVICE, "disk",
                "source=" + dnfCacheDir(),
                "path=/var/cache/libdnf5",
                "shift=true");
    }

    private void unmountDnfCache(String container) {
        incus.deviceRemove(container, DNF_CACHE_DEVICE);
    }

    /** Global skills directory for Claude Code inside the container. */
    private static final String SKILLS_DIR = "/home/agentuser/.claude/skills";

    /**
     * Install Claude Code skills declared in the image definition.
     * Fetches SKILL.md files on the host and writes them directly into the container.
     * Deduplicates against skills already declared by ancestor images.
     */
    void installSkills(Container container, ImageDef imageDef, Map<String, ImageDef> defs) {
        var skillSources = collectEffectiveSkills(imageDef, defs);
        if (skillSources.isEmpty()) return;

        var repo = imageDef.getSkills().getRepo();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        var cache = new dev.incusspawn.tool.SkillsCache();

        container.exec("mkdir", "-p", SKILLS_DIR);

        for (var entry : skillSources) {
            String resolved;
            try {
                resolved = resolveSkillSource(entry, repo);
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.err.println("Use the fully qualified form 'owner/repo@skill-name', or set 'skills.repo' in your image definition.");
                throw new BuildFailedException();
            }
            System.out.println("Installing skill: " + resolved + "...");
            try {
                var skills = fetchSkills(resolved, http, cache);
                for (var skill : skills) {
                    var skillDir = SKILLS_DIR + "/" + skill.name();
                    container.exec("mkdir", "-p", skillDir);
                    container.writeFile(skillDir + "/SKILL.md", skill.content());
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Error: Failed to fetch skill '" + resolved + "': " + e.getMessage());
                throw new BuildFailedException();
            }
        }

        // Fix ownership so agentuser owns the skills directory
        container.exec("chown", "-R", "agentuser:agentuser", SKILLS_DIR);
    }

    /** A fetched skill ready to be written into the container. */
    record SkillFile(String name, String content) {}

    /**
     * Fetch one or more SKILL.md files for the given resolved source.
     * GitHub skills are cached on the host at {@code ~/.cache/incus-spawn/skills/}.
     * Supports:
     * <ul>
     *   <li>{@code owner/repo@skill-name} — single skill from a GitHub repo</li>
     *   <li>{@code owner/repo} — all skills from a GitHub repo (via Trees API)</li>
     *   <li>{@code https://github.com/owner/repo} — same as owner/repo</li>
     *   <li>{@code ./local/path} or {@code /absolute/path} — local directory</li>
     * </ul>
     */
    static List<SkillFile> fetchSkills(String source, HttpClient http,
            dev.incusspawn.tool.SkillsCache cache)
            throws IOException, InterruptedException {
        // Local path
        if (source.startsWith("./") || source.startsWith("/")) {
            return fetchLocalSkills(Path.of(source));
        }

        // Normalise GitHub URL to owner/repo[@skill]
        var normalised = source;
        if (normalised.startsWith("https://github.com/")) {
            normalised = normalised.substring("https://github.com/".length()).replaceAll("\\.git$", "");
        }

        // owner/repo@skill-name
        var atIdx = normalised.indexOf('@');
        if (atIdx >= 0) {
            var ownerRepo = normalised.substring(0, atIdx);
            var skillName = normalised.substring(atIdx + 1);
            return List.of(new SkillFile(skillName, cache.fetchSkillMd(ownerRepo, skillName, http)));
        }

        // owner/repo — fetch all skills via Trees API
        return fetchAllGitHubSkills(normalised, http, cache);
    }

    private static List<SkillFile> fetchAllGitHubSkills(String ownerRepo, HttpClient http,
            dev.incusspawn.tool.SkillsCache cache)
            throws IOException, InterruptedException {
        // Use GitHub Trees API to find all SKILL.md files
        for (var branch : List.of("main", "master")) {
            var treeUrl = "https://api.github.com/repos/" + ownerRepo + "/git/trees/"
                    + branch + "?recursive=1";
            var token = System.getenv("GITHUB_TOKEN");
            var reqBuilder = HttpRequest.newBuilder(URI.create(treeUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json");
            if (token != null && !token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }
            var response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) continue;

            var mapper = new ObjectMapper();
            var tree = mapper.readTree(response.body()).path("tree");
            var skills = new ArrayList<SkillFile>();
            for (var node : tree) {
                var path = node.path("path").asText();
                // Match <skill-name>/SKILL.md at the top level only
                if (path.matches("[^/]+/SKILL\\.md")) {
                    var skillName = path.substring(0, path.indexOf('/'));
                    skills.add(new SkillFile(skillName, cache.fetchSkillMd(ownerRepo, skillName, http)));
                }
            }
            if (!skills.isEmpty()) return skills;
        }
        throw new IOException("No SKILL.md files found in " + ownerRepo);
    }

    private static List<SkillFile> fetchLocalSkills(Path localPath) throws IOException {
        if (!Files.isDirectory(localPath)) {
            throw new IOException("Local skill path is not a directory: " + localPath);
        }
        // If there's a SKILL.md directly in this dir, treat it as a single skill
        var directSkill = localPath.resolve("SKILL.md");
        if (Files.exists(directSkill)) {
            return List.of(new SkillFile(localPath.getFileName().toString(),
                    Files.readString(directSkill)));
        }
        // Otherwise scan subdirectories for SKILL.md files
        var skills = new ArrayList<SkillFile>();
        try (var entries = Files.list(localPath)) {
            for (var entry : entries.toList()) {
                var skillMd = entry.resolve("SKILL.md");
                if (Files.isDirectory(entry) && Files.exists(skillMd)) {
                    skills.add(new SkillFile(entry.getFileName().toString(),
                            Files.readString(skillMd)));
                }
            }
        }
        if (skills.isEmpty()) {
            throw new IOException("No SKILL.md files found in " + localPath);
        }
        return skills;
    }

    /**
     * Collect skills declared in this image, minus any already declared by ancestor images.
     */
    List<String> collectEffectiveSkills(ImageDef imageDef, Map<String, ImageDef> defs) {
        var skills = new java.util.LinkedHashSet<>(imageDef.getSkills().getList());
        if (skills.isEmpty()) return List.of();

        var ancestorSkills = new java.util.LinkedHashSet<String>();
        var parentName = imageDef.getParent();
        while (parentName != null && !parentName.isBlank()) {
            var parentDef = defs.get(parentName);
            if (parentDef == null) break;
            ancestorSkills.addAll(parentDef.getSkills().getList());
            parentName = parentDef.getParent();
        }
        skills.removeAll(ancestorSkills);
        return new ArrayList<>(skills);
    }

    /**
     * Resolve a skill entry to a fully-qualified source string.
     * <ul>
     *   <li>Contains {@code ://} or starts with {@code .} or {@code /} → local/URL, pass through</li>
     *   <li>Contains {@code /} → owner/repo or owner/repo@skill, pass through</li>
     *   <li>Plain name → prepend {@code skillsRepo@}; throws if no skillsRepo set</li>
     * </ul>
     */
    static String resolveSkillSource(String skill, String skillsRepo) {
        if (skill.contains("://") || skill.startsWith(".") || skill.startsWith("/")) {
            return skill;
        }
        if (skill.contains("/")) {
            return skill;
        }
        if (skillsRepo == null || skillsRepo.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill '" + skill + "' is a short name but no skills.repo is defined in the image definition.");
        }
        return skillsRepo + "@" + skill;
    }

    /**
     * Clone git repos declared in the image definition as agentuser.
     */
    void cloneRepos(Container container, ImageDef imageDef) {
        for (var repo : imageDef.getRepos()) {
            System.out.println("Cloning " + repo.getUrl() + "...");
            var cmd = new StringBuilder("git clone");
            if (repo.getBranch() != null && !repo.getBranch().isBlank()) {
                cmd.append(" --branch ").append(repo.getBranch());
            }
            cmd.append(" ").append(repo.getUrl());
            cmd.append(" ").append(repo.getPath());
            container.runAsUser("agentuser", cmd.toString(),
                    "Failed to clone " + repo.getUrl());
            if (repo.getPrime() != null && !repo.getPrime().isBlank()) {
                System.out.println("Priming " + repo.getPath() + "...");
                var expanded = expandHome(repo.getPath());
                container.runAsUser("agentuser",
                        "cd " + expanded + " && " + repo.getPrime(),
                        "Failed to prime " + repo.getPath());
            }
        }
    }

    private static final String CLAUDE_JSON_PATH = "/home/agentuser/.claude.json";
    private static final String AGENTUSER_HOME = "/home/agentuser";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Update .claude.json to pre-trust cloned repo directories and register GitHub repo paths.
     */
    void updateClaudeJsonTrust(Container container, ImageDef imageDef) {
        if (imageDef.getRepos().isEmpty()) return;

        var checkResult = container.exec("test", "-f", CLAUDE_JSON_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CLAUDE_JSON_PATH);
        if (!catResult.success()) {
            System.err.println("Warning: could not read " + CLAUDE_JSON_PATH);
            return;
        }

        try {
            var root = (ObjectNode) JSON.readTree(catResult.stdout());

            var projects = root.has("projects")
                    ? (ObjectNode) root.get("projects")
                    : root.putObject("projects");

            var githubRepoPaths = root.has("githubRepoPaths")
                    ? (ObjectNode) root.get("githubRepoPaths")
                    : root.putObject("githubRepoPaths");

            for (var repo : imageDef.getRepos()) {
                var expandedPath = expandHome(repo.getPath());

                if (!projects.has(expandedPath)) {
                    var projectEntry = projects.putObject(expandedPath);
                    projectEntry.putArray("allowedTools");
                    projectEntry.put("hasTrustDialogAccepted", true);
                }

                var ownerRepo = parseGitHubOwnerRepo(repo.getUrl());
                if (ownerRepo != null) {
                    ArrayNode paths;
                    if (githubRepoPaths.has(ownerRepo)) {
                        paths = (ArrayNode) githubRepoPaths.get(ownerRepo);
                    } else {
                        paths = githubRepoPaths.putArray(ownerRepo);
                    }
                    boolean found = false;
                    for (var node : paths) {
                        if (node.asText().equals(expandedPath)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        paths.add(expandedPath);
                    }
                }
            }

            var updatedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            container.writeFile(CLAUDE_JSON_PATH, updatedJson);
            container.chown(CLAUDE_JSON_PATH, "agentuser:agentuser");
        } catch (Exception e) {
            System.err.println("Warning: failed to update " + CLAUDE_JSON_PATH + ": " + e.getMessage());
        }
    }

    static String expandHome(String path) {
        if (path.startsWith("~/")) {
            return AGENTUSER_HOME + path.substring(1);
        }
        if (path.equals("~")) {
            return AGENTUSER_HOME;
        }
        return path;
    }

    static String parseGitHubOwnerRepo(String url) {
        if (url == null) return null;
        var prefix = "https://github.com/";
        if (!url.startsWith(prefix)) return null;
        var rest = url.substring(prefix.length());
        if (rest.endsWith(".git")) {
            rest = rest.substring(0, rest.length() - 4);
        }
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        var parts = rest.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }

}
