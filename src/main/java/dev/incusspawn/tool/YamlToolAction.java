package dev.incusspawn.tool;

import java.io.IOException;

/**
 * Adapts a YAML-declared action entry into a ToolAction.
 * Handles template variable interpolation and execution for url, command, and copy-to-clipboard types.
 */
public class YamlToolAction implements ToolAction {

    private static final String TYPE_URL = "url";
    private static final String TYPE_COMMAND = "command";
    private static final String TYPE_SHELL = "shell";
    private static final String TYPE_COPY_TO_CLIPBOARD = "copy-to-clipboard";
    public static final String EXPAND_REPOS = "repos";

    private final String toolName;
    private final ToolDef.ActionEntry entry;
    private final ActionContext.RepoInfo repo; // null if not expanded

    public YamlToolAction(String toolName, ToolDef.ActionEntry entry) {
        this(toolName, entry, null);
    }

    public YamlToolAction(String toolName, ToolDef.ActionEntry entry, ActionContext.RepoInfo repo) {
        this.toolName = toolName;
        this.entry = entry;
        this.repo = repo;
    }

    @Override
    public String toolName() {
        return toolName;
    }

    @Override
    public java.util.Optional<String> id() {
        var baseId = entry.getId();
        if (baseId == null) return java.util.Optional.empty();
        if (repo != null) return java.util.Optional.of(baseId + "/" + repo.name());
        return java.util.Optional.of(baseId);
    }

    @Override
    public String label() {
        return interpolate(entry.getLabel(), null);
    }

    @Override
    public boolean requiresRunning() {
        return entry.isRequiresRunning();
    }

    public boolean isUrl() {
        return TYPE_URL.equals(entry.getType());
    }

    public String resolveUrl(ActionContext context) {
        return isUrl() ? interpolate(entry.getUrl(), context) : null;
    }

    @Override
    public java.util.Optional<String> shellCommand(ActionContext context) {
        if (!TYPE_SHELL.equals(entry.getType())) return java.util.Optional.empty();
        var cmd = interpolate(entry.getCommand(), context);
        return (cmd == null || cmd.isBlank()) ? java.util.Optional.empty() : java.util.Optional.of(cmd);
    }

    @Override
    public boolean needsDeferredExecution() {
        return TYPE_COMMAND.equals(entry.getType());
    }

    public boolean shouldAutoReturn() {
        return entry.isAutoReturn();
    }

    @Override
    public ActionResult execute(ActionContext context) {
        var type = entry.getType();
        if (type == null || type.isBlank()) {
            return ActionResult.error("Missing action type for tool: " + toolName);
        }

        return switch (type) {
            case TYPE_URL -> executeUrl(context);
            case TYPE_COMMAND -> executeCommand(context);
            case TYPE_SHELL -> ActionResult.error("Missing command for shell action: " + entry.getLabel());
            case TYPE_COPY_TO_CLIPBOARD -> executeCopyToClipboard(context);
            default -> ActionResult.error("Unknown action type: " + type);
        };
    }

    private ActionResult executeUrl(ActionContext context) {
        var url = interpolate(entry.getUrl(), context);
        if (url == null || url.isBlank()) {
            return ActionResult.error("Missing URL for action: " + entry.getLabel());
        }

        var prereq = checkUrlPrerequisites(url);
        if (prereq != null) {
            return prereq;
        }

        if (openInBrowser(url)) {
            return ActionResult.ok("Opened " + url);
        } else {
            return ActionResult.error("Could not open URL (no handler found): " + url);
        }
    }

    private ActionResult executeCommand(ActionContext context) {
        var cmd = interpolate(entry.getCommand(), context);
        if (cmd == null || cmd.isBlank()) {
            return ActionResult.error("Missing command for action: " + entry.getLabel());
        }
        try {
            var process = new ProcessBuilder("sh", "-c", cmd).inheritIO().start();
            var exitCode = process.waitFor();
            if (exitCode == 0) {
                return ActionResult.ok("Command completed successfully");
            } else {
                return ActionResult.error("Command failed with exit code " + exitCode + ": " + cmd);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error("Command execution interrupted: " + cmd);
        } catch (IOException e) {
            return ActionResult.error("Failed to execute command '" + cmd + "': " + e.getMessage());
        }
    }

    private ActionResult executeCopyToClipboard(ActionContext context) {
        var text = interpolate(entry.getText(), context);
        if (text == null || text.isBlank()) {
            return ActionResult.error("Missing text for action: " + entry.getLabel());
        }
        if (copyToClipboard(text)) {
            return ActionResult.ok("Copied to clipboard");
        } else {
            return ActionResult.ok("Text: " + text);
        }
    }

    private String interpolate(String template, ActionContext context) {
        if (template == null) return "";
        var result = template;

        // Repo-specific variables (from expansion)
        if (repo != null) {
            result = result.replace("${repo_name}", repo.name());
            result = result.replace("${repo_path}", repo.path());
            result = result.replace("${repo_url}", repo.url());
        }

        // Instance variables (available when executing, not when generating label)
        if (context != null) {
            result = result.replace("${ip}", context.ipv4());
            result = result.replace("${name}", context.instanceName());
            result = result.replace("${parent}", context.parent());
        }

        return result;
    }

    private static boolean openInBrowser(String url) {
        try {
            Process process;
            if (dev.incusspawn.Environment.OS_NAME.contains("linux")) {
                process = new ProcessBuilder("xdg-open", url).start();
            } else if (dev.incusspawn.Environment.OS_NAME.contains("mac")) {
                process = new ProcessBuilder("open", url).start();
            } else {
                return false;
            }
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean copyToClipboard(String text) {
        try {
            var pb = new ProcessBuilder("xclip", "-selection", "clipboard");
            var process = pb.start();
            try (var out = process.getOutputStream()) {
                out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            var exitCode = process.waitFor();
            return exitCode == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException ignored) {
            return false;
        }
    }

    // --- URL scheme prerequisite checks ---

    static ActionResult checkUrlPrerequisites(String url) {
        var scheme = extractScheme(url);
        if (scheme == null) return null;

        return switch (scheme) {
            case "vscode" -> checkVscodePrerequisites(url);
            case "jetbrains-gateway" -> checkGatewayPrerequisites();
            default -> null;
        };
    }

    static String extractScheme(String url) {
        int idx = url.indexOf("://");
        return idx > 0 ? url.substring(0, idx).toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static ActionResult checkVscodePrerequisites(String url) {
        var codePath = findVscodeCli();
        if (codePath == null && !isVscodeInstalled()) {
            return ActionResult.error(
                    "VS Code does not appear to be installed.\n" +
                    "Install it from: https://code.visualstudio.com/");
        }

        if (codePath != null && url.contains("vscode-remote/ssh-remote")) {
            if (!isVscodeExtensionInstalled(codePath, "ms-vscode-remote.remote-ssh")) {
                System.out.println(
                        "The VS Code 'Remote - SSH' extension is required for this action but is not installed.\n" +
                        "\nPress Enter to open VS Code and install it...");
                try { System.in.read(); } catch (java.io.IOException ignored) {}
                openInBrowser("vscode:extension/ms-vscode-remote.remote-ssh");
                return ActionResult.error(
                        "Please retry this action after the extension installation completes.");
            }
        }

        return null;
    }

    private static ActionResult checkGatewayPrerequisites() {
        if (!isSchemeHandlerAvailable("jetbrains-gateway")) {
            return ActionResult.error(
                    "JetBrains Gateway does not appear to be installed.\n" +
                    "Install it from: https://www.jetbrains.com/remote-development/gateway/");
        }
        return null;
    }

    static String findVscodeCli() {
        if (isCommandAvailable("code")) {
            return "code";
        }
        if (dev.incusspawn.Environment.isMacOS()) {
            var bundled = "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code";
            if (new java.io.File(bundled).canExecute()) {
                return bundled;
            }
        }
        return null;
    }

    private static boolean isVscodeInstalled() {
        if (findVscodeCli() != null) return true;
        if (dev.incusspawn.Environment.isMacOS()) {
            return isMacAppInstalled("Visual Studio Code");
        }
        if (dev.incusspawn.Environment.isLinux()) {
            return isSchemeHandlerAvailable("vscode");
        }
        return false;
    }

    static boolean isCommandAvailable(String command) {
        try {
            var process = new ProcessBuilder("which", command)
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isVscodeExtensionInstalled(String codePath, String extensionId) {
        try {
            var process = new ProcessBuilder(codePath, "--list-extensions")
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return true;
            }
            return output.lines().anyMatch(line -> line.trim().equalsIgnoreCase(extensionId));
        } catch (Exception e) {
            return true;
        }
    }

    static boolean isSchemeHandlerAvailable(String scheme) {
        if (dev.incusspawn.Environment.isLinux()) {
            try {
                var process = new ProcessBuilder("xdg-mime", "query", "default",
                        "x-scheme-handler/" + scheme)
                        .redirectErrorStream(true).start();
                var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return true;
                }
                return !output.trim().isEmpty();
            } catch (Exception e) {
                return true;
            }
        }
        if (dev.incusspawn.Environment.isMacOS()) {
            return isMacAppInstalled("JetBrains Gateway");
        }
        return true;
    }

    private static boolean isMacAppInstalled(String appName) {
        try {
            var process = new ProcessBuilder("open", "-Ra", appName)
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return true;
        }
    }
}
