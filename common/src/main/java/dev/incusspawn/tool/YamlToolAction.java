package dev.incusspawn.tool;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostPath;
import java.io.IOException;
import java.util.function.UnaryOperator;

/**
 * Adapts a YAML-declared action entry into a ToolAction.
 * Handles template variable interpolation and execution for url, command, and copy-to-clipboard types.
 */
public class YamlToolAction implements ToolAction {

    public static final String TYPE_URL = "url";
    public static final String TYPE_COMMAND = "command";
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

    @Override
    public boolean isShellMenu() {
        return entry.isShellMenu();
    }

    @Override
    public java.util.Optional<String> shortcut() {
        var s = entry.getShortcut();
        return s == null || s.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(s);
    }

    @Override
    public java.util.Optional<String> type() {
        var t = entry.getType();
        return t == null || t.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(t);
    }

    @Override
    public Object expandedFrom() {
        return entry;
    }

    @Override
    public java.util.Optional<String> repoPath() {
        return repo != null ? java.util.Optional.of(repo.path()) : java.util.Optional.empty();
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
        return run(context, null);
    }

    @Override
    public ActionResult execute(ActionContext context, UnaryOperator<String> ask) {
        return run(context, ask);
    }

    @Override
    public ActionResult executeWithoutPrompting(ActionContext context) {
        // These take the terminal over; nothing that does not own it may run them.
        if (TYPE_COMMAND.equals(entry.getType()) || TYPE_SHELL.equals(entry.getType())) {
            return ActionResult.error("'" + entry.getLabel() + "' runs in the terminal; use isx run or the TUI");
        }
        return run(context, null);
    }

    /** {@code ask} shows a question and returns its answer, null at EOF; null itself when nothing may be asked. */
    private ActionResult run(ActionContext context, UnaryOperator<String> ask) {
        var type = entry.getType();
        if (type == null || type.isBlank()) {
            return ActionResult.error("Missing action type for tool: " + toolName);
        }

        return switch (type) {
            case TYPE_URL -> executeUrl(context, ask);
            case TYPE_COMMAND -> executeCommand(context);
            case TYPE_SHELL -> ActionResult.error("Missing command for shell action: " + entry.getLabel());
            case TYPE_COPY_TO_CLIPBOARD -> executeCopyToClipboard(context);
            default -> ActionResult.error("Unknown action type: " + type);
        };
    }

    private ActionResult executeUrl(ActionContext context, UnaryOperator<String> ask) {
        var url = interpolate(entry.getUrl(), context);
        if (url == null || url.isBlank()) {
            return ActionResult.error("Missing URL for action: " + entry.getLabel());
        }

        var prereq = checkUrlPrerequisites(url, ask);
        if (prereq != null) {
            return prereq;
        }

        var cliResult = tryOpenWithVscodeCli(url);
        if (cliResult != null) {
            return cliResult;
        }

        if (dev.incusspawn.Platform.openUrl(url)) {
            return ActionResult.ok("Opened " + url);
        } else {
            return ActionResult.error("Could not open URL (no handler found): " + url);
        }
    }

    /**
     * For vscode-remote SSH URLs, launch via the {@code code} CLI with
     * {@code --disable-workspace-trust} to suppress the trust prompt.
     * The remote-side machine setting alone does not suppress it because
     * the prompt is rendered by the local VS Code client.
     *
     * @return an ActionResult if handled (success or failure), null to fall back to Platform.openUrl
     */
    private static ActionResult tryOpenWithVscodeCli(String url) {
        var folderUri = toVscodeRemoteFolderUri(url);
        if (folderUri == null) {
            return null;
        }
        var codePath = findVscodeCli();
        if (codePath == null) {
            return null;
        }
        try {
            var pb = new ProcessBuilder(codePath, "--folder-uri", folderUri, "--disable-workspace-trust");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                if (p.exitValue() == 0) {
                    return ActionResult.ok("Opened " + folderUri);
                }
                return ActionResult.error("VS Code CLI exited with code " + p.exitValue());
            }
            return ActionResult.ok("Opened " + folderUri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.error("VS Code CLI execution interrupted");
        } catch (IOException e) {
            return null;
        }
    }

    static String toVscodeRemoteFolderUri(String vscodeUrl) {
        var prefix = "vscode://vscode-remote/";
        if (!vscodeUrl.startsWith(prefix)) {
            return null;
        }
        return "vscode-remote://" + vscodeUrl.substring(prefix.length());
    }

    private ActionResult executeCommand(ActionContext context) {
        var cmd = interpolate(entry.getCommand(), context);
        if (cmd == null || cmd.isBlank()) {
            return ActionResult.error("Missing command for action: " + entry.getLabel());
        }
        try {
            BuildOutput.releaseTerminal();
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
            // Host sources have no upstream URL, including when an action never uses this variable.
            result = result.replace("${repo_url}", repo.url() == null ? "" : repo.url());
        }

        // Instance variables (available when executing, not when generating label)
        if (context != null) {
            result = result.replace("${ip}", context.ipv4());
            result = result.replace("${name}", context.instanceName());
            result = result.replace("${parent}", context.parent());
        }

        return result;
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

    static ActionResult checkUrlPrerequisites(String url, UnaryOperator<String> ask) {
        var scheme = extractScheme(url);
        if (scheme == null) return null;

        return switch (scheme) {
            case "vscode" -> checkVscodePrerequisites(url, ask, VscodeProbe.HOST);
            case "jetbrains-gateway" -> checkGatewayPrerequisites();
            default -> null;
        };
    }

    static String extractScheme(String url) {
        int idx = url.indexOf("://");
        return idx > 0 ? url.substring(0, idx).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** What the VS Code checks ask the host, so a test can answer instead. */
    interface VscodeProbe {
        String cli();
        boolean installed();
        boolean hasExtension(String cli, String extensionId);
        boolean open(String url);

        VscodeProbe HOST = new VscodeProbe() {
            @Override public String cli() { return findVscodeCli(); }
            @Override public boolean installed() { return isVscodeInstalled(); }
            @Override public boolean hasExtension(String cli, String extensionId) {
                return isVscodeExtensionInstalled(cli, extensionId);
            }
            @Override public boolean open(String url) { return dev.incusspawn.Platform.openUrl(url); }
        };
    }

    static ActionResult checkVscodePrerequisites(String url, UnaryOperator<String> ask, VscodeProbe probe) {
        var codePath = probe.cli();
        if (codePath == null && !probe.installed()) {
            return ActionResult.error(
                    "VS Code does not appear to be installed.\n" +
                    "Install it from: https://code.visualstudio.com/");
        }

        if (codePath != null && url.contains("vscode-remote/ssh-remote")) {
            if (!probe.hasExtension(codePath, "ms-vscode-remote.remote-ssh")) {
                var notInstalled = ActionResult.error("VS Code's 'Remote - SSH' extension is not installed: "
                        + "install ms-vscode-remote.remote-ssh, then retry");
                if (ask == null) return notInstalled;
                BuildOutput.step(
                        "The VS Code 'Remote - SSH' extension is required for this action but is not installed.");
                // EOF is nobody confirming: open nothing (#1786)
                if (ask.apply("Press Enter to open VS Code and install it...") == null) return notInstalled;
                // Nothing opened is no installation to wait for
                if (!probe.open("vscode:extension/ms-vscode-remote.remote-ssh")) return notInstalled;
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
        if (dev.incusspawn.Platform.isMacOS()) {
            var bundled = "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code";
            if (new java.io.File(bundled).canExecute()) {
                return bundled;
            }
        }
        return null;
    }

    private static boolean isVscodeInstalled() {
        if (findVscodeCli() != null) return true;
        if (dev.incusspawn.Platform.isMacOS()) {
            return isMacAppInstalled("Visual Studio Code");
        }
        if (dev.incusspawn.Platform.isLinux()) {
            return isSchemeHandlerAvailable("vscode");
        }
        return false;
    }

    static boolean isCommandAvailable(String command) {
        return HostPath.isOnPath(command);
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
        if (dev.incusspawn.Platform.isLinux()) {
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
        if (dev.incusspawn.Platform.isMacOS()) {
            return isMacSchemeHandlerAvailable(scheme);
        }
        return true;
    }

    private static boolean isMacSchemeHandlerAvailable(String scheme) {
        try {
            var script = "ObjC.import('AppKit');" +
                    "var ws = $.NSWorkspace.sharedWorkspace;" +
                    "var url = $.NSURL.URLWithString('" + scheme + "://x');" +
                    "var r = '';" +
                    "try { r = ws.URLForApplicationToOpenURL(url).path.js; } catch(e) {}" +
                    "r;";
            var process = new ProcessBuilder("osascript", "-l", "JavaScript", "-e", script)
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return true;
            }
            if (process.exitValue() != 0) return true;
            return !output.trim().isEmpty();
        } catch (Exception e) {
            return true;
        }
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
