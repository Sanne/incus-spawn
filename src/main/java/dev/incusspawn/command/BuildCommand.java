package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(
        name = "build",
        description = "Build or rebuild a base golden image (e.g. golden-minimal, golden-java)",
        mixinStandardHelpOptions = true
)
public class BuildCommand implements Runnable {

    @Parameters(index = "0", description = "Name of the golden image (e.g. golden-minimal, golden-java)")
    String name;

    @Option(names = "--profile", description = "Profile to use: minimal, java (default: auto-detected from name)")
    String profile;

    @Option(names = "--vm", description = "Build as a VM instead of a container")
    boolean vm;

    @Option(names = "--image", description = "Base OS image", defaultValue = "images:fedora/43")
    String image;

    @Inject
    IncusClient incus;

    /**
     * Known image definitions. Each entry maps a profile name to its definition:
     * the parent profile (null = build from scratch using the OS image) and
     * the setup steps to run on top of the parent.
     *
     * Future: these will be discovered from definition files in the working directory.
     */
    private static final Map<String, ImageDef> IMAGE_DEFS = new LinkedHashMap<>();

    static {
        IMAGE_DEFS.put("minimal", new ImageDef(null, "golden-minimal"));
        IMAGE_DEFS.put("java", new ImageDef("minimal", "golden-java"));
    }

    private record ImageDef(String parentProfile, String defaultName) {}

    @Override
    public void run() {
        if (profile == null) {
            profile = detectProfile(name);
        }

        build(name, profile);
    }

    /**
     * Build an image. If the image's profile has a parent, ensure the parent
     * is built first (recursively).
     */
    private void build(String targetName, String targetProfile) {
        var def = IMAGE_DEFS.get(targetProfile);

        // If this profile has a parent, ensure it exists
        if (def != null && def.parentProfile() != null) {
            var parentDef = IMAGE_DEFS.get(def.parentProfile());
            var parentName = parentDef != null ? parentDef.defaultName() : "golden-" + def.parentProfile();
            if (!incus.exists(parentName)) {
                System.out.println("Parent image '" + parentName + "' not found, building it first...\n");
                build(parentName, def.parentProfile());
                System.out.println();
            }
        }

        System.out.println("Building image: " + targetName + " (profile: " + targetProfile + ")");

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

        if (def != null && def.parentProfile() != null) {
            buildFromParent(targetName, targetProfile, def);
        } else {
            buildFromScratch(targetName, targetProfile);
        }
    }

    /**
     * Build an image by copying its parent and applying profile-specific layers.
     */
    private void buildFromParent(String targetName, String targetProfile, ImageDef def) {
        var parentDef = IMAGE_DEFS.get(def.parentProfile());
        var parentName = parentDef != null ? parentDef.defaultName() : "golden-" + def.parentProfile();

        System.out.println("Deriving from parent image '" + parentName + "'...");
        incus.copy(parentName, targetName);
        incus.start(targetName);
        waitForReady(targetName);

        // Apply profile-specific setup on top of parent
        switch (targetProfile) {
            case "java" -> installJavaToolkit(targetName);
            default -> System.out.println("No additional setup for profile: " + targetProfile);
        }

        // Tag with metadata
        incus.configSet(targetName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(targetName, Metadata.PROFILE, targetProfile);
        incus.configSet(targetName, Metadata.PARENT, parentName);
        incus.configSet(targetName, Metadata.CREATED, Metadata.today());

        System.out.println("Stopping image...");
        incus.stop(targetName);

        System.out.println("Image " + targetName + " built successfully.");
    }

    /**
     * Build an image from scratch using the base OS image.
     * This is the full setup path: DNS, user, tools, auth, Claude Code.
     */
    private void buildFromScratch(String targetName, String targetProfile) {
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
        // Verify DNS works before proceeding
        System.out.println("Verifying DNS resolution...");
        for (int attempt = 0; attempt < 10; attempt++) {
            var dnsCheck = incus.shellExec(targetName, "sh", "-c",
                    "curl -4 -s -o /dev/null -w '%{http_code}' https://mirrors.fedoraproject.org");
            if (dnsCheck.success() && dnsCheck.stdout().strip().contains("302")) {
                System.out.println("  DNS working.");
                break;
            }
            if (attempt == 9) {
                System.err.println("  DNS resolution is not working. Check your network setup.");
                System.err.println("  The container '" + targetName + "' has been left running for inspection.");
                System.exit(1);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            System.out.println("  Waiting for DNS... (attempt " + (attempt + 2) + "/10)");
        }

        // Create agentuser with passwordless sudo (container is the security boundary)
        System.out.println("Creating agentuser...");
        incus.shellExec(targetName, "useradd", "-m", "-u", "1000", "agentuser");
        incus.shellExec(targetName, "mkdir", "-p", "/home/agentuser/inbox");
        incus.shellExec(targetName, "chown", "agentuser:agentuser", "/home/agentuser/inbox");
        incus.shellExec(targetName, "sh", "-c",
                "echo 'agentuser ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/agentuser");

        // Install common tools
        System.out.println("Installing common tools (git, gh CLI, podman)...");
        requireSuccess(incus.shellExecInteractive(targetName, "dnf", "install", "-y",
                "git", "curl", "which", "procps-ng", "findutils", "podman"),
                "Failed to install common tools");

        // Rootless podman requires nested user namespaces, which need more host
        // subordinate UIDs than typical Incus installations provide (65536 is not
        // enough for both the container's own namespace and podman's nested one).
        // Since the container IS the security boundary, we run podman rootful via
        // a wrapper script. This is transparent to all callers including Testcontainers.
        incus.shellExec(targetName, "sh", "-c",
                "printf '#!/bin/sh\\nexec sudo /usr/bin/podman \"$@\"\\n' > /usr/local/bin/podman && " +
                "chmod +x /usr/local/bin/podman && " +
                "ln -sf /usr/local/bin/podman /usr/local/bin/docker");
        installGitHubCli(targetName);
        installClaudeCode(targetName);

        // Profile-specific setup (for profiles that build from scratch)
        switch (targetProfile) {
            case "java" -> installJavaToolkit(targetName);
            case "minimal" -> System.out.println("Minimal profile - no additional tools.");
            default -> System.out.println("Unknown profile: " + targetProfile + " - treating as minimal.");
        }

        // Set up auth forwarding
        setupAuthForwarding(targetName);

        // Configure Claude Code settings for agent use
        setupClaudeConfig(targetName);

        // Tag with metadata
        incus.configSet(targetName, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(targetName, Metadata.PROFILE, targetProfile);
        incus.configSet(targetName, Metadata.CREATED, Metadata.today());

        // Stop the golden image (it's a template, not a running instance)
        System.out.println("Stopping image...");
        incus.stop(targetName);

        System.out.println("Image " + targetName + " built successfully.");
    }

    private void installJavaToolkit(String container) {
        System.out.println("Installing Java development toolkit...");
        requireSuccess(incus.shellExecInteractive(container, "dnf", "install", "-y",
                "java-latest-openjdk", "java-latest-openjdk-devel"),
                "Failed to install Java toolkit");

        // Install latest Maven from Apache (auto-detect latest version)
        System.out.println("Installing latest Maven from Apache...");
        requireSuccess(incus.shellExecInteractive(container, "sh", "-c",
                "MAVEN_VERSION=$(curl -s https://dlcdn.apache.org/maven/maven-3/ " +
                "| grep -oP '3\\.\\d+\\.\\d+' | sort -V | tail -1) && " +
                "echo \"Downloading Maven $MAVEN_VERSION...\" && " +
                "curl -sL https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz " +
                "| tar xz -C /opt && " +
                "ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn"),
                "Failed to install Maven");

        // Verify
        var result = incus.shellExec(container, "mvn", "--version");
        if (result.success()) {
            System.out.println("  Maven installed: " + result.stdout().lines().findFirst().orElse(""));
        } else {
            System.err.println("  Warning: Maven installation may have failed.");
        }
    }

    private void installGitHubCli(String container) {
        System.out.println("Installing GitHub CLI...");
        requireSuccess(incus.shellExecInteractive(container, "dnf", "install", "-y", "gh"),
                "Failed to install GitHub CLI");
    }

    private void installClaudeCode(String container) {
        System.out.println("Installing Claude Code...");
        // Ensure ~/.local/bin is on agentuser's PATH before installing, so the
        // installer doesn't warn about it and claude is immediately available.
        incus.shellExec(container, "sh", "-c",
                "mkdir -p /home/agentuser/.local/bin && " +
                "chown -R agentuser:agentuser /home/agentuser/.local && " +
                "grep -q '.local/bin' /home/agentuser/.bashrc 2>/dev/null || " +
                "echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> /home/agentuser/.bashrc");
        // Install as agentuser so it lands in /home/agentuser/.local/bin
        requireSuccess(incus.shellExecInteractive(container, "su", "-l", "agentuser", "-c",
                "curl -fsSL https://claude.ai/install.sh | sh"),
                "Failed to install Claude Code");
    }

    private void setupAuthForwarding(String container) {
        var config = SpawnConfig.load();

        if (config.getClaude().isUseVertex()) {
            appendToProfile(container, "export CLAUDE_CODE_USE_VERTEX=1");
            if (!config.getClaude().getCloudMlRegion().isBlank()) {
                appendToProfile(container, "export CLOUD_ML_REGION=" + config.getClaude().getCloudMlRegion());
            }
            if (!config.getClaude().getVertexProjectId().isBlank()) {
                appendToProfile(container, "export ANTHROPIC_VERTEX_PROJECT_ID=" + config.getClaude().getVertexProjectId());
            }
        } else if (config.getClaude().getApiKey() != null && !config.getClaude().getApiKey().isBlank()) {
            appendToProfile(container, "export ANTHROPIC_API_KEY=" + config.getClaude().getApiKey());
        }

        if (config.getGithub().getToken() != null && !config.getGithub().getToken().isBlank()) {
            appendToProfile(container, "export GH_TOKEN=" + config.getGithub().getToken());
        }
    }

    private void setupClaudeConfig(String container) {
        System.out.println("Configuring Claude Code for agent use...");
        var settingsJson = """
                {
                  "permissions": {
                    "defaultMode": "bypassPermissions",
                    "skipDangerousModePermissionPrompt": true,
                    "allow": [
                      "Bash(*)",
                      "Read(**)",
                      "Edit(**)",
                      "Write(**)",
                      "Glob(**)",
                      "Grep(**)",
                      "WebFetch",
                      "WebSearch",
                      "Agent(*)"
                    ]
                  }
                }
                """;
        var claudeJson = """
                {
                  "hasCompletedOnboarding": true,
                  "hasSeenTasksHint": true,
                  "numStartups": 1,
                  "autoUpdates": false
                }
                """;
        incus.shellExec(container, "sh", "-c",
                "mkdir -p /home/agentuser/.claude && " +
                "cat > /home/agentuser/.claude/settings.json << 'SETTINGS'\n" +
                settingsJson.strip() + "\nSETTINGS");
        incus.shellExec(container, "sh", "-c",
                "cat > /home/agentuser/.claude.json << 'CLAUDEJSON'\n" +
                claudeJson.strip() + "\nCLAUDEJSON");
        incus.shellExec(container, "chown", "-R", "agentuser:agentuser", "/home/agentuser/.claude");
        incus.shellExec(container, "chown", "agentuser:agentuser", "/home/agentuser/.claude.json");

        appendToProfile(container, "export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1");
    }

    private void appendToProfile(String container, String line) {
        incus.shellExec(container, "sh", "-c",
                "echo '" + line + "' >> /home/agentuser/.bashrc");
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

    private String detectProfile(String name) {
        if (name.contains("java")) return "java";
        return "minimal";
    }
}
