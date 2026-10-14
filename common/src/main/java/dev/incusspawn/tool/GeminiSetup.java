package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.util.BuildOutput;

import java.util.List;
import java.util.Map;

/** Installs and configures Gemini CLI; login waits for proxy support (#1409). */
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
    static final String UNSUPPORTED_NOTICE_PATH = "/etc/profile.d/isx-gemini-login.sh";
    static final String LOGIN_ISSUE_URL = "https://github.com/Sanne/incus-spawn/issues/1409";

    /**
     * Shadows the CLI in every login and interactive shell, so it never reaches its own login
     * prompt, which asks for a key or token to keep in the box: the proxy cannot inject one yet,
     * and none may enter the box. Remove once #1409 lands.
     */
    static final String UNSUPPORTED_NOTICE_SCRIPT = """
            # Written by isx: Gemini login through isx is not supported yet (%1$s)
            gemini() {
              echo "Gemini login through isx is not supported yet: credentials never enter the box, and the proxy cannot supply a Gemini key yet." >&2
              echo "See %1$s" >&2
              return 1
            }
            # Exported, so a non-login bash an agent starts finds it too; a failed export ends a POSIX sh
            if [ -n "${BASH_VERSION:-}" ]; then export -f gemini; fi
            """.formatted(LOGIN_ISSUE_URL);

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
        // A login shell, so the notice in /etc/profile.d also answers when zmx starts the command.
        action.setCommand("bash -lc gemini");
        action.setAutoReturn(true);
        return List.of(action);
    }

    @Override
    public void install(Container c, Map<String, String> resolvedParams) {
        BuildOutput.stepStart("Installing Gemini CLI...");
        NpmGlobalInstall.install(c, "Gemini CLI", "@google/gemini-cli");
        c.writeFile(UNSUPPORTED_NOTICE_PATH, UNSUPPORTED_NOTICE_SCRIPT);
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
