package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.List;
import java.util.Map;

public class CopilotSetup implements ToolSetup {

    private static final String PLACEHOLDER_TOKEN = "gho_placeholder";

    @Override
    public String name() {
        return "copilot";
    }

    @Override
    public String description() {
        return "GitHub Copilot CLI — AI coding assistant";
    }

    @Override
    public ToolDef.ProxyDef proxy() {
        var token = new ToolDef.ConfigEntry();
        // Reuses the gh tool's PAT: Copilot access is tied to the same GitHub account/token,
        // so this tool works standalone (without gh installed) but never prompts separately.
        token.setConfigPath("github.token");
        token.setDescription("GitHub personal access token (shared with the gh tool)");
        token.setSecret(true);

        var bearerAuth = new ToolDef.AuthDef();
        // These have two labels ahead of githubcopilot.com, so a single-label *.githubcopilot.com
        // wildcard cert can't match them under RFC 6125 SNI rules even though the proxy's own
        // suffix-based routing would; list each exact per-tier host so it gets its own leaf cert.
        bearerAuth.setDomains(List.of("*.githubcopilot.com",
                "api.individual.githubcopilot.com", "api.business.githubcopilot.com",
                "api.enterprise.githubcopilot.com"));
        bearerAuth.setType("bearer");
        bearerAuth.setToken("${token}");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfiguration(Map.of("token", token));
        proxy.setAuth(List.of(bearerAuth));
        return proxy;
    }

    @Override
    public boolean hasOwnCredentials() {
        return false;
    }

    @Override
    public List<String> packages() {
        return List.of("nodejs");
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        return List.of(
                EnvEntry.set("COPILOT_GITHUB_TOKEN", PLACEHOLDER_TOKEN),
                // Isx containers are already the isolation boundary (see ClaudeSetup's
                // bypassPermissions, CodexSetup's danger-full-access): auto-approve every
                // tool/path/URL for non-interactive `copilot -p` runs, same as --yolo.
                EnvEntry.set("COPILOT_ALLOW_ALL", "true"));
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing GitHub Copilot CLI...");
        installCopilotCli(c);
        configureCopilotSettings(c);
        BuildOutput.stepDone();
    }

    private void installCopilotCli(Container c) {
        // `gh copilot`/the standalone `copilot` launcher exec whatever `copilot` binary is on
        // PATH, falling back to an interactive-only downloader otherwise (no non-interactive
        // flag or env var -- confirmed against gh 2.97.0's confirm prompt, which declines when
        // stdin isn't a real TTY regardless of GH_PROMPT_DISABLED). Installing the npm package
        // directly, as CodexSetup does for @openai/codex, sidesteps that prompt entirely.
        c.runQuiet("Failed to install GitHub Copilot CLI",
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", "@github/copilot");
    }

    private static final String COPILOT_CONFIG_PATH = "/home/agentuser/.copilot/config.json";

    private void configureCopilotSettings(Container c) {
        // COPILOT_ALLOW_ALL (envEntries) covers non-interactive `-p` runs; defaultPermissionMode
        // is the interactive-session equivalent -- it's ignored outside interactive runs, so both
        // are needed for full "--yolo" parity regardless of how the CLI is invoked.
        c.writeFile(COPILOT_CONFIG_PATH, """
                {
                  "defaultPermissionMode": "allow-all"
                }
                """);
        c.chown("/home/agentuser/.copilot", "agentuser:agentuser");
    }
}
