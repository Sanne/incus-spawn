package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.List;
import java.util.Map;

/** Installs and configures Gemini CLI; authentication is deliberately left to a separate integration. */
public class GeminiSetup implements ToolSetup {

    static final String CONFIG_PATH = "/home/agentuser/.gemini/settings.json";
    public static final String MEMORY_PATH = "/home/agentuser/.gemini/GEMINI.md";
    static final String POLICY_PATH = "/home/agentuser/.gemini/policies/isx-auto-approve.toml";
    static final String AUTO_APPROVE_POLICY = """
            # isx provides the isolation boundary. Higher-priority user rules can override this default.
            [[rule]]
            toolName = "*"
            decision = "allow"
            allowRedirection = true
            priority = 0
            modes = ["default", "autoEdit", "yolo"]
            """;

    @Override
    public String name() { return "gemini-cli"; }

    @Override
    public String description() { return "Gemini CLI — Google coding assistant"; }

    @Override
    public List<String> packages() { return List.of("nodejs", "ripgrep"); }

    @Override
    public Map<String, ToolDef.ParameterDef> parameters() {
        var model = new ToolDef.ParameterDef();
        model.setType("string");
        model.setDescription("Gemini model ID (e.g. gemini-4-argon); omitted to use the CLI default");
        model.setPattern("^[a-zA-Z0-9][-a-zA-Z0-9._@:]*$");
        model.setOptional(true);
        model.setReconfigurable(true);
        return Map.of("model", model);
    }

    @Override
    public List<ToolDef.ActionEntry> actions() {
        var action = new ToolDef.ActionEntry();
        action.setLabel("Gemini CLI");
        action.setType("shell");
        action.setCommand("gemini");
        action.setAutoReturn(true);
        return List.of(action);
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing Gemini CLI...");
        NpmGlobalInstall.install(c, "Gemini CLI", "@google/gemini-cli");
        BuildOutput.stepDone();
        configureSettings(c, resolvedParams);
    }

    @Override
    public void reconfigure(Container c, Map<String, String> resolvedParams) {
        configureSettings(c, resolvedParams);
    }

    /** Called after all template layers are built, so inherited notes and repositories are included. */
    public static void writeEnvironmentContext(Container c, String content) {
        c.sh("mkdir -p /home/agentuser/.gemini");
        c.writeFile(MEMORY_PATH, content);
        c.chown(MEMORY_PATH, "agentuser:agentuser");
    }

    private void configureSettings(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Configuring Gemini CLI...");
        c.sh("mkdir -p /home/agentuser/.gemini/policies");
        c.writeFile(CONFIG_PATH, settingsJson(resolvedParams));
        c.writeFile(POLICY_PATH, AUTO_APPROVE_POLICY);
        c.chown("/home/agentuser/.gemini", "agentuser:agentuser");
        BuildOutput.stepDone();
    }

    static String settingsJson(Map<String, String> resolvedParams) {
        // As with Claude and Codex, let upstream choose its default model unless explicitly pinned.
        var model = resolvedParams.get("model");
        var modelSetting = model == null ? "" : "  \"model\": {\"name\": \"" + model + "\"},\n";
        return "{\n" + modelSetting + """
                  "general": {
                    "enableAutoUpdate": false,
                    "enableAutoUpdateNotification": false
                  },
                  "ui": {"hideTips": true, "hideBanner": true, "showHomeDirectoryWarning": false},
                  "tools": {"sandbox": false},
                  "security": {"folderTrust": {"enabled": false}, "toolSandboxing": false},
                  "privacy": {"usageStatisticsEnabled": false},
                  "telemetry": {"enabled": false}
                }
                """;
    }
}
