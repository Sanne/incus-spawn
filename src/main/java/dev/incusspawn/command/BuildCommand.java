package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

    @Override
    public void run() {
        if (profile == null) {
            profile = detectProfile(name);
        }

        System.out.println("Building base golden image: " + name + " (profile: " + profile + ")");

        if (incus.exists(name)) {
            System.out.println("Image '" + name + "' already exists.");
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
            incus.delete(name, true);
        }

        // Launch base image
        System.out.println("Launching " + image + "...");
        incus.launch(image, name, vm);

        // Wait for container to be ready
        waitForReady(name);

        // Set ID mapping for UID 1000 (needed for Wayland passthrough option)
        incus.configSet(name, "raw.idmap", "both 1000 1000");
        incus.restart(name);
        waitForReady(name);

        // systemd-resolved (127.0.0.53) doesn't work reliably inside containers.
        // Disable it and point DNS directly at the Incus bridge gateway (dnsmasq).
        System.out.println("Configuring DNS...");
        var gatewayIp = incus.exec("network", "get", "incusbr0", "ipv4.address")
                .assertSuccess("Failed to get bridge IP").stdout().strip();
        if (gatewayIp.contains("/")) {
            gatewayIp = gatewayIp.substring(0, gatewayIp.indexOf('/'));
        }
        // Unlink NSS from systemd-resolved and write a static resolv.conf
        incus.shellExec(name, "sh", "-c",
                "systemctl disable --now systemd-resolved 2>/dev/null; " +
                "systemctl mask systemd-resolved 2>/dev/null; " +
                "rm -f /etc/resolv.conf; " +
                "echo 'nameserver " + gatewayIp + "' > /etc/resolv.conf; " +
                "chattr +i /etc/resolv.conf");
        // Verify DNS works before proceeding
        System.out.println("Verifying DNS resolution...");
        for (int attempt = 0; attempt < 10; attempt++) {
            var dnsCheck = incus.shellExec(name, "sh", "-c",
                    "curl -4 -s -o /dev/null -w '%{http_code}' https://mirrors.fedoraproject.org");
            if (dnsCheck.success() && dnsCheck.stdout().strip().contains("302")) {
                System.out.println("  DNS working.");
                break;
            }
            if (attempt == 9) {
                System.err.println("  DNS resolution is not working. Check your network setup.");
                System.err.println("  The container '" + name + "' has been left running for inspection.");
                System.exit(1);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            System.out.println("  Waiting for DNS... (attempt " + (attempt + 2) + "/10)");
        }

        // Create agentuser
        System.out.println("Creating agentuser...");
        incus.shellExec(name, "useradd", "-m", "-u", "1000", "agentuser");
        incus.shellExec(name, "mkdir", "-p", "/home/agentuser/inbox");
        incus.shellExec(name, "chown", "agentuser:agentuser", "/home/agentuser/inbox");

        // Install common tools
        System.out.println("Installing common tools (git, gh CLI)...");
        requireSuccess(incus.shellExecInteractive(name, "dnf", "install", "-y", "git", "which", "procps-ng", "findutils"),
                "Failed to install common tools");
        installGitHubCli();
        installClaudeCode();

        // Profile-specific setup
        switch (profile) {
            case "java" -> installJavaToolkit();
            case "minimal" -> System.out.println("Minimal profile - no additional tools.");
            default -> System.out.println("Unknown profile: " + profile + " - treating as minimal.");
        }

        // Set up auth forwarding
        setupAuthForwarding();

        // Configure Claude Code settings for agent use
        setupClaudeConfig();

        // Tag with metadata
        incus.configSet(name, Metadata.TYPE, Metadata.TYPE_BASE);
        incus.configSet(name, Metadata.PROFILE, profile);
        incus.configSet(name, Metadata.CREATED, Metadata.today());

        // Stop the golden image (it's a template, not a running instance)
        System.out.println("Stopping golden image...");
        incus.stop(name);

        System.out.println("Golden image " + name + " built successfully.");
    }

    private void installJavaToolkit() {
        System.out.println("Installing Java development toolkit...");
        requireSuccess(incus.shellExecInteractive(name, "dnf", "install", "-y",
                "java-latest-openjdk", "java-latest-openjdk-devel"),
                "Failed to install Java toolkit");

        // Install latest Maven from Apache (auto-detect latest version)
        System.out.println("Installing latest Maven from Apache...");
        requireSuccess(incus.shellExecInteractive(name, "sh", "-c",
                "MAVEN_VERSION=$(curl -s https://dlcdn.apache.org/maven/maven-3/ " +
                "| grep -oP '3\\.\\d+\\.\\d+' | sort -V | tail -1) && " +
                "echo \"Downloading Maven $MAVEN_VERSION...\" && " +
                "curl -sL https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz " +
                "| tar xz -C /opt && " +
                "ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn"),
                "Failed to install Maven");

        // Verify
        var result = incus.shellExec(name, "mvn", "--version");
        if (result.success()) {
            System.out.println("  Maven installed: " + result.stdout().lines().findFirst().orElse(""));
        } else {
            System.err.println("  Warning: Maven installation may have failed.");
        }
    }

    private void installGitHubCli() {
        System.out.println("Installing GitHub CLI...");
        requireSuccess(incus.shellExecInteractive(name, "dnf", "install", "-y", "gh"),
                "Failed to install GitHub CLI");
    }

    private void installClaudeCode() {
        System.out.println("Installing Claude Code...");
        // Install Node.js (required for Claude Code)
        requireSuccess(incus.shellExecInteractive(name, "dnf", "install", "-y", "nodejs", "npm"),
                "Failed to install Node.js");
        System.out.println("Installing Claude Code via npm...");
        requireSuccess(incus.shellExecInteractive(name, "npm", "install", "-g", "@anthropic-ai/claude-code"),
                "Failed to install Claude Code");
    }

    private void setupAuthForwarding() {
        var config = SpawnConfig.load();

        // Set up Claude env vars in agentuser's profile
        if (config.getClaude().isUseVertex()) {
            appendToProfile("export CLAUDE_CODE_USE_VERTEX=1");
            if (!config.getClaude().getCloudMlRegion().isBlank()) {
                appendToProfile("export CLOUD_ML_REGION=" + config.getClaude().getCloudMlRegion());
            }
            if (!config.getClaude().getVertexProjectId().isBlank()) {
                appendToProfile("export ANTHROPIC_VERTEX_PROJECT_ID=" + config.getClaude().getVertexProjectId());
            }
        } else if (config.getClaude().getApiKey() != null && !config.getClaude().getApiKey().isBlank()) {
            appendToProfile("export ANTHROPIC_API_KEY=" + config.getClaude().getApiKey());
        }

        // Set up GitHub token
        if (config.getGithub().getToken() != null && !config.getGithub().getToken().isBlank()) {
            appendToProfile("export GH_TOKEN=" + config.getGithub().getToken());
        }
    }

    private void setupClaudeConfig() {
        System.out.println("Configuring Claude Code for agent use...");
        // The container is the security boundary, so Claude Code can run fully permissive.
        // Settings are placed in user-level config so they apply to all projects.
        var settingsJson = """
                {
                  "permissions": {
                    "defaultMode": "bypassPermissions",
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
        incus.shellExec(name, "sh", "-c",
                "mkdir -p /home/agentuser/.claude && " +
                "cat > /home/agentuser/.claude/settings.json << 'SETTINGS'\n" +
                settingsJson.strip() + "\nSETTINGS");
        incus.shellExec(name, "chown", "-R", "agentuser:agentuser", "/home/agentuser/.claude");
    }

    private void appendToProfile(String line) {
        incus.shellExec(name, "sh", "-c",
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
