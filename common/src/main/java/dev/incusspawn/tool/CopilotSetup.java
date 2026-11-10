package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CopilotSetup implements ToolSetup {

    private static final String PLACEHOLDER_TOKEN = "gho_placeholder";

    static final NpmGlobalInstall.PlatformSplitCli NPM_CLI = new NpmGlobalInstall.PlatformSplitCli(
            "GitHub Copilot CLI", "@github/copilot", "copilot", "@github/copilot-");

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
        // These have two labels ahead of githubcopilot.com, which a single-label *.githubcopilot.com
        // wildcard cert can't match under RFC 6125. The proxy mints a cert for any depth on
        // demand (#783); listing each per-tier host exactly has its cert ready at start.
        bearerAuth.setDomains(List.of("*.githubcopilot.com",
                "api.individual.githubcopilot.com", "api.business.githubcopilot.com",
                "api.enterprise.githubcopilot.com"));
        bearerAuth.setType("bearer");
        bearerAuth.setToken("${token}");

        var proxy = new ToolDef.ProxyDef();
        proxy.setConfiguration(Map.of("token", token));
        proxy.setAuth(List.of(bearerAuth));
        proxy.setPlaceholders(List.of(new ToolDef.PlaceholderDef("COPILOT_GITHUB_TOKEN", "gho_")));
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
    public Map<String, ToolDef.ParameterDef> parameters() {
        var params = new LinkedHashMap<String, ToolDef.ParameterDef>();

        // None declares a default: Copilot resolves them from a server-side catalog that moves,
        // so see settingsJson().
        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Copilot model ID (e.g. claude-opus-5.5, gpt-5.6-sol, auto)");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        params.put("model", model);

        var effort = new ToolDef.ParameterDef();
        effort.setType("string");
        effort.setDescription("Reasoning effort (none, minimal, low, medium, high, xhigh, max)");
        effort.setPattern("^(none|minimal|low|medium|high|xhigh|max)$");
        effort.setOptional(true);
        effort.setReconfigurable(true);
        params.put("effort", effort);

        var context = new ToolDef.ParameterDef();
        context.setType("enum");
        context.setDescription("Context window tier: default or long_context");
        context.setOptions(List.of("default", "long_context"));
        context.setOptional(true);
        context.setReconfigurable(true);
        params.put("context", context);

        return params;
    }

    @Override
    public List<EnvEntry> envEntries(Map<String, String> resolvedParams) {
        return List.of(
                EnvEntry.set("COPILOT_GITHUB_TOKEN", PLACEHOLDER_TOKEN),
                // Isx containers are already the isolation boundary (see ClaudeSetup's
                // bypassPermissions, CodexSetup's full-access permission profile): auto-approve every
                // tool/path/URL for non-interactive `copilot -p` runs, same as --yolo.
                EnvEntry.set("COPILOT_ALLOW_ALL", "true"));
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing GitHub Copilot CLI");
        installCopilotCli(c);
        writeSettings(c, resolvedParams);
        BuildOutput.stepDone();
    }

    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        writeSettings(c, resolvedParams);
    }

    private void installCopilotCli(Container c) {
        // `gh copilot`/the standalone `copilot` launcher exec whatever `copilot` binary is on
        // PATH, falling back to an interactive-only downloader otherwise (no non-interactive
        // flag or env var -- confirmed against gh 2.97.0's confirm prompt, which declines when
        // stdin isn't a real TTY regardless of GH_PROMPT_DISABLED). Installing the npm package
        // directly, as CodexSetup does for @openai/codex, sidesteps that prompt entirely.
        // The real binary is an optional platform package, which NpmGlobalInstall verifies.
        NpmGlobalInstall.install(c, NPM_CLI);
    }

    private static final String COPILOT_SETTINGS_PATH = "/home/agentuser/.copilot/settings.json";

    /**
     * The whole file is rewritten every time, so a child that sets none of the parameters drops
     * what its parent pinned while keeping the permission mode.
     */
    private void writeSettings(Container c, Map<String, String> resolvedParams) {
        c.writeFile(COPILOT_SETTINGS_PATH, settingsJson(resolvedParams));
        c.chown("/home/agentuser/.copilot", "agentuser:agentuser");
    }

    /**
     * A parent built by an older isx wrote the permission mode to config.json, where Copilot does
     * not honour it, and settings.json only when a parameter was set (#2066). The parent's
     * parameters are not known here, so its settings.json is kept and only the key is added.
     */
    @Override
    public void refreshInherited(Container c) {
        var settings = c.sh("if [ -f " + COPILOT_SETTINGS_PATH + " ]; then cat " + COPILOT_SETTINGS_PATH + "; fi")
                .assertSuccess("Failed to read " + COPILOT_SETTINGS_PATH).stdout();
        c.writeFile(COPILOT_SETTINGS_PATH, withAllowAll(settings));
        c.chown("/home/agentuser/.copilot", "agentuser:agentuser");
    }

    /** The parent's settings with the permission mode set, every other key kept as it was. */
    static String withAllowAll(String json) {
        var root = json.isBlank() ? JqObject.EMPTY : Jjq.parseForeign(json, COPILOT_SETTINGS_PATH);
        if (!(root instanceof JqObject settings)) {
            throw new IllegalStateException(COPILOT_SETTINGS_PATH + " must hold a JSON object");
        }
        return JqValues.toPrettyJsonString(
                settings.with("defaultPermissionMode", JqString.of("allow-all"))) + "\n";
    }

    /**
     * Copilot's user settings. COPILOT_ALLOW_ALL (envEntries) covers non-interactive `-p` runs;
     * defaultPermissionMode is the interactive-session equivalent, so both are needed for full
     * "--yolo" parity. It belongs here: config.json is state the CLI manages itself, and the key
     * there leaves a session in manual approval (Copilot CLI 1.0.108, #2066).
     * <p>
     * Each other key appears only when set: Copilot picks the model, effort and context tier
     * from a catalog that moves, so a value baked in here would silently override whatever it
     * currently promotes. The parameter patterns keep the values free of characters that need
     * JSON escaping.
     */
    static String settingsJson(Map<String, String> resolvedParams) {
        var entries = new java.util.ArrayList<String>();
        entries.add("  \"defaultPermissionMode\": \"allow-all\"");
        addSetting(entries, resolvedParams, "model", "model");
        addSetting(entries, resolvedParams, "effort", "effortLevel");
        addSetting(entries, resolvedParams, "context", "contextTier");
        return "{\n" + String.join(",\n", entries) + "\n}\n";
    }

    private static void addSetting(List<String> entries, Map<String, String> params,
                                   String paramKey, String jsonKey) {
        var value = params.get(paramKey);
        if (value != null) {
            entries.add("  \"" + jsonKey + "\": \"" + value + "\"");
        }
    }
}
